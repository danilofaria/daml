// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.participant.state.index.v2

import java.nio.charset.StandardCharsets
import java.util.Base64

import com.daml.ledger.api.domain.{User, UserRight}
import com.daml.lf.data.Ref

import scala.concurrent.{ExecutionContext, Future}

trait UserManagementStore {

  import UserManagementStore._

  // read access

  def getUserInfo(id: Ref.UserId): Future[Result[UserInfo]]

  def listUsers(fromExcl: Option[Ref.UserId], maxResults: Int): Future[Result[UsersPage]]

  // write access

  def createUser(user: User, rights: Set[UserRight]): Future[Result[Unit]]

  def deleteUser(id: Ref.UserId): Future[Result[Unit]]

  def grantRights(id: Ref.UserId, rights: Set[UserRight]): Future[Result[Set[UserRight]]]

  def revokeRights(id: Ref.UserId, rights: Set[UserRight]): Future[Result[Set[UserRight]]]

  // read helpers

  final def getUser(id: Ref.UserId): Future[Result[User]] = {
    getUserInfo(id).map(_.map(_.user))(ExecutionContext.parasitic)
  }

  final def listUserRights(id: Ref.UserId): Future[Result[Set[UserRight]]] = {
    getUserInfo(id).map(_.map(_.rights))(ExecutionContext.parasitic)
  }

  // TODO pbatko: test it
  protected def decodePageToken(pageToken: String): Option[Ref.UserId] = {
    if (pageToken.isEmpty) {
      None
    } else {
      val bytes = Base64.getUrlDecoder.decode(pageToken.getBytes(StandardCharsets.UTF_8))
      val str = new String(bytes, StandardCharsets.UTF_8)
      Some(Ref.UserId.assertFromString(str))
    }
  }
}

object UserManagementStore {
  type Result[T] = Either[Error, T]
  type Users = Seq[User]

  case class UsersPage(users: Seq[User]) {
    def lastUserIdOption: Option[Ref.UserId] = users.lastOption.map(_.id)
  }

  case class UserInfo(user: User, rights: Set[UserRight])

  sealed trait Error extends RuntimeException

  final case class UserNotFound(userId: Ref.UserId) extends Error

  final case class UserExists(userId: Ref.UserId) extends Error
  final case class TooManyUserRights(userId: Ref.UserId) extends Error

}
