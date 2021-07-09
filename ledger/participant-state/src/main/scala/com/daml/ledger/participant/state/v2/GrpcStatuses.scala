// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.participant.state.v2

import com.google.rpc.error_details.ErrorInfo

import scala.util.Try

object GrpcStatuses {
  val DefiniteAnswerKey = "definite_answer"

  def isDefiniteAnswer(status: com.google.rpc.status.Status): Boolean =
    status.details.exists { any =>
      if (any.is(ErrorInfo.messageCompanion)) {
        Try(any.unpack(ErrorInfo.messageCompanion)).toOption
          .exists(isDefiniteAnswer)
      } else {
        false
      }
    }

  def isDefiniteAnswer(errorInfo: ErrorInfo): Boolean =
    errorInfo.metadata.get(DefiniteAnswerKey).exists(value => java.lang.Boolean.valueOf(value))

  def completeWithOffset(
      incompleteStatus: com.google.rpc.status.Status,
      completionKey: String,
      completionOffset: Offset,
  ): com.google.rpc.status.Status = {
    val (errorInfo, errorInfoIndex) =
      incompleteStatus.details.zipWithIndex
        .collectFirst {
          case (errorDetail, index) if errorDetail.is(ErrorInfo.messageCompanion) =>
            errorDetail.unpack(ErrorInfo.messageCompanion) -> index
        }
        .getOrElse(
          throw new IllegalArgumentException(
            s"No com.google.rpc.error_details.ErrorInfo found in details for $incompleteStatus"
          )
        )

    val newErrorInfo =
      errorInfo
        .copy()
        .addMetadata(completionKey -> completionOffset.toHexString)
    val newDetails = incompleteStatus.details.updated(
      errorInfoIndex,
      com.google.protobuf.any.Any.pack(newErrorInfo),
    )
    incompleteStatus.withDetails(newDetails)
  }
}
