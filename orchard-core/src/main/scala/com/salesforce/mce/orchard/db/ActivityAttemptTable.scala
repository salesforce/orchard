/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.db

import java.time.LocalDateTime

import play.api.libs.json.JsValue
import slick.jdbc.PostgresProfile.api._

import com.salesforce.mce.orchard.model.Status

class ActivityAttemptTable(tag: Tag)
    extends Table[ActivityAttemptTable.R](tag, "activity_attempts") {

  def workflowId = column[String]("workflow_id", O.SqlType("VARCHAR(64)"))

  def activityId = column[String]("activity_id", O.SqlType("VARCHAR(64)"))

  def attempt = column[Int]("attempt")

  def resourceId = column[Option[String]]("resource_id", O.SqlType("VARCHAR(64)"))

  def resourceInstanceAttempt = column[Option[Int]]("resource_instance_attempt")

  def status = column[Status.Value]("status", O.SqlType("VARCHAR(32)"))

  def errorMessage = column[String]("error_message", O.SqlType("TEXT"))

  def attemptSpec = column[Option[JsValue]]("attempt_spec", O.SqlType("TEXT"))

  def createdAt = column[LocalDateTime]("created_at")

  def activatedAt = column[Option[LocalDateTime]]("activated_at")

  def terminatedAt = column[Option[LocalDateTime]]("terminated_at")

  def pk = primaryKey("pk_attempts", (workflowId, activityId, attempt))

  def activity =
    foreignKey("fk_attempts_activities", (workflowId, activityId), TableQuery[ActivityTable])(
      r => (r.workflowId, r.activityId),
      onUpdate = ForeignKeyAction.Restrict,
      onDelete = ForeignKeyAction.Cascade
    )

  def resourceInstance = foreignKey(
    "fk_attempts_resource_instances",
    (workflowId, resourceId, resourceInstanceAttempt),
    TableQuery[ResourceInstanceTable]
  )(
    r => (r.workflowId, r.resourceId.?, r.instanceAttempt.?),
    onUpdate = ForeignKeyAction.Restrict,
    onDelete = ForeignKeyAction.Cascade
  )

  override def * = (
    workflowId,
    activityId,
    attempt,
    status,
    errorMessage,
    resourceId,
    resourceInstanceAttempt,
    attemptSpec,
    createdAt,
    activatedAt,
    terminatedAt
  ).mapTo[ActivityAttemptTable.R]

}

object ActivityAttemptTable {

  def apply() = TableQuery[ActivityAttemptTable]

  case class R(
    workflowId: String,
    activityId: String,
    attempt: Int,
    status: Status.Value,
    errorMessage: String,
    resourceId: Option[String],
    resourceInstanceAttempt: Option[Int],
    attemptSpec: Option[JsValue],
    createdAt: LocalDateTime,
    activatedAt: Option[LocalDateTime],
    terminatedAt: Option[LocalDateTime]
  )

}
