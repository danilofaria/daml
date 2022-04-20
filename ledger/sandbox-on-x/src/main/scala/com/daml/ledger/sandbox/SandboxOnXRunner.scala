// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.sandbox

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import com.codahale.metrics.InstrumentedExecutorService
import com.daml.api.util.TimeProvider
import com.daml.buildinfo.BuildInfo
import com.daml.ledger.api.auth.{
  AuthServiceJWT,
  AuthServiceNone,
  AuthServiceStatic,
  AuthServiceWildcard,
}
import com.daml.ledger.api.health.HealthChecks
import com.daml.ledger.api.v1.experimental_features.{
  CommandDeduplicationFeatures,
  CommandDeduplicationPeriodSupport,
  CommandDeduplicationType,
  ExperimentalContractIds,
}
import com.daml.ledger.offset.Offset
import com.daml.ledger.participant.state.index.v2.IndexService
import com.daml.ledger.participant.state.v2.metrics.{TimedReadService, TimedWriteService}
import com.daml.ledger.participant.state.v2.{ReadService, Update, WriteService}
import com.daml.ledger.resources.{Resource, ResourceContext, ResourceOwner}
import com.daml.ledger.runner.common._
import com.daml.ledger.sandbox.bridge.{BridgeMetrics, LedgerBridge}
import com.daml.lf.data.Ref
import com.daml.lf.engine.Engine
import com.daml.logging.LoggingContext.{newLoggingContext, newLoggingContextWith}
import com.daml.logging.{ContextualizedLogger, LoggingContext}
import com.daml.metrics.{JvmMetricSet, Metrics}
import com.daml.platform.apiserver._
import com.daml.platform.configuration.ServerRole
import com.daml.platform.indexer.StandaloneIndexerServer
import com.daml.platform.store.{DbSupport, DbType, LfValueTranslationCache}
import com.daml.platform.usermanagement.{PersistentUserManagementStore, UserManagementConfig}

import java.util.concurrent.{Executors, TimeUnit}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService}
import scala.util.chaining._

object SandboxOnXRunner {
  val RunnerName = "sandbox-on-x"
  private val logger = ContextualizedLogger.get(getClass)

  val configProvider: ConfigProvider[BridgeConfig] = BridgeConfigProvider

  def owner(
      args: collection.Seq[String],
      manipulateConfig: CliConfig[BridgeConfig] => CliConfig[BridgeConfig] = identity,
  ): ResourceOwner[Unit] =
    CliConfig
      .owner(
        RunnerName,
        configProvider.extraConfigParser,
        configProvider.defaultExtraConfig,
        args,
      )
      .map(manipulateConfig)
      .flatMap(owner)

  def owner(originalConfig: CliConfig[BridgeConfig]): ResourceOwner[Unit] =
    new ResourceOwner[Unit] {
      override def acquire()(implicit context: ResourceContext): Resource[Unit] = {
        originalConfig.mode match {
          case Mode.DumpIndexMetadata(jdbcUrls) =>
            DumpIndexMetadata(jdbcUrls, RunnerName)
            sys.exit(0)
          case Mode.Run =>
            val config = configProvider.toInternalConfig(originalConfig)
            run(config, originalConfig.extra)
        }
      }
    }

  private def run(
      config: Config,
      extra: BridgeConfig,
  )(implicit resourceContext: ResourceContext): Resource[Unit] = {
    implicit val actorSystem: ActorSystem = ActorSystem(RunnerName)
    implicit val materializer: Materializer = Materializer(actorSystem)

    for {
      // Take ownership of the actor system and materializer so they're cleaned up properly.
      // This is necessary because we can't declare them as implicits in a `for` comprehension.
      _ <- ResourceOwner.forActorSystem(() => actorSystem).acquire()
      _ <- ResourceOwner.forMaterializer(() => materializer).acquire()

      // Start the ledger
      participantConfig <- validateCombinedParticipantMode(config)
      _ <- buildLedger(
        config,
        participantConfig,
        extra,
        materializer,
        actorSystem,
      ).acquire()
    } yield logInitializationHeader(config, participantConfig, extra)
  }

  def validateCombinedParticipantMode(
      config: Config
  ): Resource[ParticipantConfig] =
    config.participants.toList match {
      case participantConfig :: Nil if participantConfig.mode == ParticipantRunMode.Combined =>
        Resource.successful(participantConfig)
      case _ =>
        Resource.failed {
          val loggingMessage = "Sandbox-on-X can only be run in a single COMBINED participant mode."
          newLoggingContext(logger.info(loggingMessage)(_))
          new IllegalArgumentException(loggingMessage)
        }
    }

  def buildLedger(implicit
      config: Config,
      participantConfig: ParticipantConfig,
      extra: BridgeConfig,
      materializer: Materializer,
      actorSystem: ActorSystem,
      metrics: Option[Metrics] = None,
  ): ResourceOwner[(ApiServer, WriteService, IndexService)] = {
    val apiServerConfig: ApiServerConfig = participantConfig.apiServerConfig
    val sharedEngine = new Engine(config.engineConfig)

    newLoggingContextWith("participantId" -> participantConfig.participantId) {
      implicit loggingContext =>
        for {
          metrics <- metrics.map(ResourceOwner.successful).getOrElse(buildMetrics)
          translationCache = LfValueTranslationCache.Cache.newInstrumentedInstance(
            config = participantConfig.lfValueTranslationCacheConfig,
            metrics = metrics,
          )

          (stateUpdatesFeedSink, stateUpdatesSource) <- AkkaSubmissionsBridge()

          servicesThreadPoolSize = Runtime.getRuntime.availableProcessors()
          servicesExecutionContext <- buildServicesExecutionContext(
            metrics,
            servicesThreadPoolSize,
          )

          readServiceWithSubscriber = new BridgeReadService(
            ledgerId = config.ledgerId,
            maximumDeduplicationDuration = participantConfig.maxDeduplicationDuration.getOrElse(
              BridgeConfigProvider.DefaultMaximumDeduplicationDuration
            ),
            stateUpdatesSource,
          )

          indexerHealthChecks <- buildIndexerServer(
            metrics,
            new TimedReadService(readServiceWithSubscriber, metrics),
            translationCache,
            participantConfig,
          )

          dbSupport <- DbSupport
            .owner(
              serverRole = ServerRole.ApiServer,
              metrics = metrics,
              dataSourceConfig = apiServerConfig.dataSourceConfig,
            )

          indexService <- StandaloneIndexService(
            ledgerId = config.ledgerId,
            config = participantConfig.indexConfiguration,
            metrics = metrics,
            engine = sharedEngine,
            servicesExecutionContext = servicesExecutionContext,
            lfValueTranslationCache = translationCache,
            dbSupport = dbSupport,
            participantId = participantConfig.participantId,
          )

          timeServiceBackend = configProvider.timeServiceBackend(apiServerConfig)

          writeService <- buildWriteService(
            stateUpdatesFeedSink,
            indexService,
            metrics,
            servicesExecutionContext,
            servicesThreadPoolSize,
            timeServiceBackend,
            participantConfig,
            extra,
          )

          apiServer <- buildStandaloneApiServer(
            sharedEngine,
            indexService,
            metrics,
            servicesExecutionContext,
            new TimedWriteService(writeService, metrics),
            indexerHealthChecks,
            timeServiceBackend,
            dbSupport,
            config,
            apiServerConfig,
            participantConfig.participantId,
          )
        } yield (apiServer, writeService, indexService)
    }
  }

  private def buildStandaloneApiServer(
      sharedEngine: Engine,
      indexService: IndexService,
      metrics: Metrics,
      servicesExecutionContext: ExecutionContextExecutorService,
      writeService: WriteService,
      healthChecksWithIndexer: HealthChecks,
      timeServiceBackend: Option[TimeServiceBackend],
      dbSupport: DbSupport,
      config: Config,
      apiServerConfig: ApiServerConfig,
      participantId: Ref.ParticipantId,
  )(implicit
      actorSystem: ActorSystem,
      loggingContext: LoggingContext,
  ): ResourceOwner[ApiServer] =
    StandaloneApiServer(
      indexService = indexService,
      ledgerId = config.ledgerId,
      config = apiServerConfig,
      optWriteService = Some(writeService),
      healthChecks = healthChecksWithIndexer + ("write" -> writeService),
      metrics = metrics,
      timeServiceBackend = timeServiceBackend,
      otherInterceptors = List.empty,
      engine = sharedEngine,
      servicesExecutionContext = servicesExecutionContext,
      userManagementStore = PersistentUserManagementStore.cached(
        dbSupport = dbSupport,
        metrics = metrics,
        cacheExpiryAfterWriteInSeconds =
          apiServerConfig.userManagementConfig.cacheExpiryAfterWriteInSeconds,
        maxCacheSize = apiServerConfig.userManagementConfig.maxCacheSize,
        maxRightsPerUser = UserManagementConfig.MaxRightsPerUser,
        timeProvider = TimeProvider.UTC,
      )(servicesExecutionContext, loggingContext),
      ledgerFeatures = LedgerFeatures(
        staticTime = timeServiceBackend.isDefined,
        commandDeduplicationFeatures = CommandDeduplicationFeatures.of(
          deduplicationPeriodSupport = Some(
            CommandDeduplicationPeriodSupport.of(
              CommandDeduplicationPeriodSupport.OffsetSupport.OFFSET_NOT_SUPPORTED,
              CommandDeduplicationPeriodSupport.DurationSupport.DURATION_NATIVE_SUPPORT,
            )
          ),
          deduplicationType = CommandDeduplicationType.ASYNC_ONLY,
          maxDeduplicationDurationEnforced = true,
        ),
        contractIdFeatures = ExperimentalContractIds.of(
          v1 = ExperimentalContractIds.ContractIdV1Support.NON_SUFFIXED
        ),
      ),
      participantId = participantId,
    )

  private def buildIndexerServer(
      metrics: Metrics,
      readService: ReadService,
      translationCache: LfValueTranslationCache.Cache,
      participantConfig: ParticipantConfig,
  )(implicit
      loggingContext: LoggingContext,
      materializer: Materializer,
  ): ResourceOwner[HealthChecks] =
    for {
      indexerHealth <- new StandaloneIndexerServer(
        readService = readService,
        config = participantConfig.indexerConfig,
        metrics = metrics,
        lfValueTranslationCache = translationCache,
      )
    } yield new HealthChecks(
      "read" -> readService,
      "indexer" -> indexerHealth,
    )

  private def buildServicesExecutionContext(
      metrics: Metrics,
      servicesThreadPoolSize: Int,
  ): ResourceOwner[ExecutionContextExecutorService] =
    ResourceOwner
      .forExecutorService(() =>
        new InstrumentedExecutorService(
          Executors.newWorkStealingPool(servicesThreadPoolSize),
          metrics.registry,
          metrics.daml.lapi.threadpool.apiServices.toString,
        )
      )
      .map(ExecutionContext.fromExecutorService)

  private def buildMetrics(implicit
      participantConfig: ParticipantConfig,
      config: Config,
  ): ResourceOwner[Metrics] =
    Metrics
      .fromSharedMetricRegistries(participantConfig.metricsRegistryName)
      .tap(_.registry.registerAll(new JvmMetricSet))
      .pipe { metrics =>
        config.metricsConfig.reporter
          .fold(ResourceOwner.unit)(reporter =>
            ResourceOwner
              .forCloseable(() => reporter.register(metrics.registry))
              .map(_.start(config.metricsConfig.reportingInterval.toMillis, TimeUnit.MILLISECONDS))
          )
          .map(_ => metrics)
      }

  // Builds the write service and uploads the initialization DARs
  private def buildWriteService(
      feedSink: Sink[(Offset, Update), NotUsed],
      indexService: IndexService,
      metrics: Metrics,
      servicesExecutionContext: ExecutionContext,
      servicesThreadPoolSize: Int,
      timeServiceBackend: Option[TimeServiceBackend],
      participantConfig: ParticipantConfig,
      extra: BridgeConfig,
  )(implicit
      materializer: Materializer,
      loggingContext: LoggingContext,
  ): ResourceOwner[WriteService] = {
    implicit val ec: ExecutionContext = servicesExecutionContext
    val bridgeMetrics = new BridgeMetrics(metrics)
    for {
      ledgerBridge <- LedgerBridge.owner(
        participantConfig,
        extra,
        indexService,
        bridgeMetrics,
        servicesThreadPoolSize,
        timeServiceBackend.getOrElse(TimeProvider.UTC),
      )
      writeService <- ResourceOwner.forCloseable(() =>
        new BridgeWriteService(
          feedSink = feedSink,
          submissionBufferSize = extra.submissionBufferSize,
          ledgerBridge = ledgerBridge,
          bridgeMetrics = bridgeMetrics,
        )
      )
    } yield writeService
  }

  private def logInitializationHeader(
      config: Config,
      participantConfig: ParticipantConfig,
      extra: BridgeConfig,
  ): Unit = {
    val authentication = participantConfig.apiServerConfig.authService match {
      case _: AuthServiceJWT => "JWT-based authentication"
      case AuthServiceNone => "none authenticated"
      case _: AuthServiceStatic => "static authentication"
      case AuthServiceWildcard => "all unauthenticated allowed"
      case other => other.getClass.getSimpleName
    }

    val ledgerDetails =
      Seq[(String, String)](
        "run-mode" -> s"${participantConfig.mode} participant",
        "index DB backend" -> DbType
          .jdbcType(participantConfig.apiServerConfig.dataSourceConfig.jdbcUrl)
          .name,
        "participant-id" -> participantConfig.participantId,
        "ledger-id" -> config.ledgerId,
        "port" -> participantConfig.apiServerConfig.port.toString,
        "time mode" -> participantConfig.apiServerConfig.timeProviderType.description,
        "allowed language versions" -> s"[min = ${config.engineConfig.allowedLanguageVersions.min}, max = ${config.engineConfig.allowedLanguageVersions.max}]",
        "authentication" -> authentication,
        "contract ids seeding" -> participantConfig.apiServerConfig.seeding.toString,
      ).map { case (key, value) =>
        s"$key = $value"
      }.mkString(", ")

    logger.withoutContext.info(
      s"Initialized {} with {}, version {}, {}",
      RunnerName,
      if (extra.conflictCheckingEnabled) "conflict checking ledger bridge"
      else "pass-through ledger bridge (no conflict checking)",
      BuildInfo.Version,
      ledgerDetails,
    )
  }
}
