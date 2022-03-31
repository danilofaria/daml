// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.platform.index

import akka.NotUsed
import akka.stream.scaladsl.{Flow, Source}
import com.daml.ledger.api.DeduplicationPeriod.{DeduplicationDuration, DeduplicationOffset}
import com.daml.ledger.offset.Offset
import com.daml.ledger.participant.state.v2.Update.{CommandRejected, TransactionAccepted}
import com.daml.ledger.participant.state.v2.{CompletionInfo, Update}
import com.daml.lf.engine.Blinding
import com.daml.lf.ledger.EventId
import com.daml.lf.transaction.Transaction.ChildrenRecursion
import com.daml.platform.store.CompletionFromTransaction
import com.daml.platform.store.appendonlydao.events._
import com.daml.platform.store.cache.MutableCacheBackedContractStore.EventSequentialId
import com.daml.platform.store.interfaces.TransactionLogUpdate
import com.daml.platform.store.interfaces.TransactionLogUpdate.{
  CompletionDetails,
  LedgerEndMarker,
  SubmissionRejected,
}

import java.util.concurrent.Executors
import scala.concurrent.{ExecutionContext, Future}

object LedgerBuffersUpdater {
  private type UpdateToTransactionLogUpdate = ((Offset, Update)) => TransactionLogUpdate
  private val prepareUpdatesParallelism = 2
  private val ec: ExecutionContext =
    ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(prepareUpdatesParallelism))

  def flow(
      initSeqId: Long
  ): Flow[(Offset, Update), ((Offset, Long), TransactionLogUpdate), NotUsed] =
    Flow[(Offset, Update)]
      .mapAsync(prepareUpdatesParallelism)(o => Future(transform(o))(ec))
      .async
      .scan[TransactionLogUpdate](LedgerEndMarker(Offset.beforeBegin, initSeqId))(sequence)
      .drop(1)
      .flatMapConcat(withLedgerEndMarkers)

  private def transform: UpdateToTransactionLogUpdate = {
    case (offset, u: TransactionAccepted) => updateToTransactionAccepted(offset, u)
    case (offset, u: CommandRejected) => updateToSubmissionRejected(offset, u)
    case (offset, _) => LedgerEndMarker(offset, 0L)
  }

  private def sequence(
      prev: TransactionLogUpdate,
      current: TransactionLogUpdate,
  ): TransactionLogUpdate = {
    var currSeqId = prev match {
      case TransactionLogUpdate.TransactionAccepted(_, _, _, _, events, _) =>
        events.last.eventSequentialId
      case TransactionLogUpdate.LedgerEndMarker(_, lastEventSeqId) => lastEventSeqId
      case rejection: SubmissionRejected => rejection.lastEventSeqId
    }
    current match {
      case transaction: TransactionLogUpdate.TransactionAccepted =>
        transaction.copy(events = transaction.events.map {
          case divulgence: TransactionLogUpdate.DivulgenceEvent =>
            currSeqId += 1
            divulgence.copy(eventSequentialId = currSeqId)
          case create: TransactionLogUpdate.CreatedEvent =>
            currSeqId += 1
            create.copy(eventSequentialId = currSeqId)
          case exercise: TransactionLogUpdate.ExercisedEvent =>
            currSeqId += 1
            exercise.copy(eventSequentialId = currSeqId)
        })
      case ledgerEndMarker: LedgerEndMarker =>
        ledgerEndMarker.copy(lastEventSeqId = currSeqId)
      case rejection: SubmissionRejected =>
        rejection.copy(lastEventSeqId = currSeqId)
    }
  }

  private def withLedgerEndMarkers(
      transactionLogUpdate: TransactionLogUpdate
  ): Source[((Offset, EventSequentialId), TransactionLogUpdate), NotUsed] =
    transactionLogUpdate match {
      case transaction: TransactionLogUpdate.TransactionAccepted =>
        Source.fromIterator(() => {
          val eventSequentialId = transaction.events.last.eventSequentialId
          val offset = transaction.offset
          val ledgerEndMarker = TransactionLogUpdate.LedgerEndMarker(offset, eventSequentialId)
          val woDivulgence = transaction.copy(events =
            transaction.events.filterNot(_.isInstanceOf[TransactionLogUpdate.DivulgenceEvent])
          )
          if (woDivulgence.events.nonEmpty) // Divulgence-only transactions should not be forwarded
            Iterator(
              (offset -> eventSequentialId, woDivulgence),
              (offset -> eventSequentialId, ledgerEndMarker),
            )
          else Iterator((offset -> eventSequentialId, ledgerEndMarker))
        })
      case ledgerEndMarker: TransactionLogUpdate.LedgerEndMarker =>
        Source.fromIterator(() =>
          Iterator((ledgerEndMarker.offset -> ledgerEndMarker.lastEventSeqId, ledgerEndMarker))
        )
      case rejection: SubmissionRejected =>
        Source.fromIterator(() =>
          Iterator((rejection.offset -> rejection.lastEventSeqId, rejection))
        )
    }

  private def updateToSubmissionRejected(offset: Offset, u: CommandRejected) = {
    val (deduplicationOffset, deduplicationDurationSeconds, deduplicationDurationNanos) =
      deduplicationInfo(u.completionInfo)

    TransactionLogUpdate.SubmissionRejected(
      offset = offset,
      lastEventSeqId = 0L,
      completionDetails = CompletionDetails(
        CompletionFromTransaction.rejectedCompletion(
          recordTime = u.recordTime,
          offset = offset,
          commandId = u.completionInfo.commandId,
          status = u.reasonTemplate.status,
          applicationId = u.completionInfo.applicationId,
          optSubmissionId = u.completionInfo.submissionId,
          optDeduplicationOffset = deduplicationOffset,
          optDeduplicationDurationSeconds = deduplicationDurationSeconds,
          optDeduplicationDurationNanos = deduplicationDurationNanos,
        ),
        submitters = u.completionInfo.actAs.toSet,
      ),
    )
  }

  private def updateToTransactionAccepted(offset: Offset, u: TransactionAccepted) = {
    val rawEvents = u.transaction.transaction
      .foldInExecutionOrder(List.empty[(NodeId, Node)])(
        exerciseBegin = (acc, nid, node) => ((nid -> node) :: acc, ChildrenRecursion.DoRecurse),
        // Rollback nodes are not indexed
        rollbackBegin = (acc, _, _) => (acc, ChildrenRecursion.DoNotRecurse),
        leaf = (acc, nid, node) => (nid -> node) :: acc,
        exerciseEnd = (acc, _, _) => acc,
        rollbackEnd = (acc, _, _) => acc,
      )
      .reverseIterator

    val blinding = u.blindingInfo.getOrElse(Blinding.blind(u.transaction))

    val logUpdates = rawEvents.collect {
      case (nodeId, create: Create) =>
        TransactionLogUpdate.CreatedEvent(
          eventOffset = offset,
          transactionId = u.transactionId,
          nodeIndex = nodeId.index,
          eventSequentialId = 0L,
          eventId = EventId(u.transactionId, nodeId),
          contractId = create.coid,
          ledgerEffectiveTime = u.transactionMeta.ledgerEffectiveTime,
          templateId = create.templateId,
          commandId = u.optCompletionInfo.map(_.commandId).getOrElse(""),
          workflowId = u.transactionMeta.workflowId.map(_.toString).getOrElse(""),
          contractKey =
            create.key.map(k => com.daml.lf.transaction.Versioned(create.version, k.key)),
          treeEventWitnesses = blinding.disclosure.getOrElse(nodeId, Set.empty),
          flatEventWitnesses = create.stakeholders,
          submitters = u.optCompletionInfo
            .map(_.actAs.toSet)
            .getOrElse(Set.empty),
          createArgument = com.daml.lf.transaction.Versioned(create.version, create.arg),
          createSignatories = create.signatories,
          createObservers = create.stakeholders.diff(create.signatories),
          createAgreementText = Some(create.agreementText).filter(_.nonEmpty),
        )
      case (nodeId, exercise: Exercise) =>
        TransactionLogUpdate.ExercisedEvent(
          eventOffset = offset,
          transactionId = u.transactionId,
          nodeIndex = nodeId.index,
          eventSequentialId = 0L,
          eventId = EventId(u.transactionId, nodeId),
          contractId = exercise.targetCoid,
          ledgerEffectiveTime = u.transactionMeta.ledgerEffectiveTime,
          templateId = exercise.templateId,
          commandId = u.optCompletionInfo.map(_.commandId).getOrElse(""),
          workflowId = u.transactionMeta.workflowId.map(_.toString).getOrElse(""),
          contractKey =
            exercise.key.map(k => com.daml.lf.transaction.Versioned(exercise.version, k.key)),
          treeEventWitnesses = blinding.disclosure.getOrElse(nodeId, Set.empty),
          flatEventWitnesses = if (exercise.consuming) exercise.stakeholders else Set.empty,
          submitters = u.optCompletionInfo
            .map(_.actAs.toSet)
            .getOrElse(Set.empty),
          choice = exercise.choiceId,
          actingParties = exercise.actingParties,
          children = exercise.children.iterator
            .map(EventId(u.transactionId, _).toLedgerString.toString)
            .toSeq,
          exerciseArgument = exercise.versionedChosenValue,
          exerciseResult = exercise.versionedExerciseResult,
          consuming = exercise.consuming,
        )
    }

    val events = logUpdates ++ blinding.divulgence.iterator.collect {
      // only store divulgence events, which are divulging to parties
      case (_, visibleToParties) if visibleToParties.nonEmpty =>
        // This DTO is never used in Ledger API buffers
        // Only for created for the purpose of `sequence` phase ^
        TransactionLogUpdate.DivulgenceEvent(
          eventOffset = offset,
          eventSequentialId = 0L,
          transactionId = null,
          eventId = null,
          commandId = null,
          workflowId = null,
          ledgerEffectiveTime = null,
          treeEventWitnesses = null,
          flatEventWitnesses = null,
          submitters = null,
          templateId = null,
          contractId = null,
        )
    }

    TransactionLogUpdate.TransactionAccepted(
      transactionId = u.transactionId,
      workflowId = u.transactionMeta.workflowId.getOrElse(""), // TODO check
      effectiveAt = u.transactionMeta.ledgerEffectiveTime,
      offset = offset,
      events = events.toVector,
      completionDetails = u.optCompletionInfo.map(completionInfo => {
        val (deduplicationOffset, deduplicationDurationSeconds, deduplicationDurationNanos) =
          deduplicationInfo(completionInfo)

        CompletionDetails(
          CompletionFromTransaction.acceptedCompletion(
            recordTime = u.recordTime,
            offset = offset,
            commandId = completionInfo.commandId,
            transactionId = u.transactionId,
            applicationId = completionInfo.applicationId,
            optSubmissionId = completionInfo.submissionId,
            optDeduplicationOffset = deduplicationOffset,
            optDeduplicationDurationSeconds = deduplicationDurationSeconds,
            optDeduplicationDurationNanos = deduplicationDurationNanos,
          ),
          submitters = completionInfo.actAs.toSet,
        )
      }),
    )
  }

  private def deduplicationInfo(completionInfo: CompletionInfo) =
    completionInfo.optDeduplicationPeriod
      .map {
        case DeduplicationOffset(offset) =>
          (Some(offset.toHexString), None, None)
        case DeduplicationDuration(duration) =>
          (None, Some(duration.getSeconds), Some(duration.getNano))
      }
      .getOrElse((None, None, None))
}
