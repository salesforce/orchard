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

class ActivityTable(tag: Tag) extends Table[ActivityTable.R](tag, "activities") {

  def workflowId = column[String]("workflow_id", O.SqlType("VARCHAR(64)"))

  def activityId = column[String]("activity_id", O.SqlType("VARCHAR(64)"))

  def name = column[String]("name", O.SqlType("VARCHAR(256)"))

  def activityType = column[String]("activity_type", O.SqlType("VARCHAR(64)"))

  def activitySpec = column[JsValue]("activity_spec", O.SqlType("TEXT"))

  def resourceId = column[String]("resource_id", O.SqlType("VARCHAR(256)"))

  def maxAttempt = column[Int]("max_attempt")

  def status = column[Status.Value]("status", O.SqlType("VARCHAR(32)"))

  def createdAt = column[LocalDateTime]("created_at")

  def activatedAt = column[Option[LocalDateTime]]("activated_at")

  def terminatedAt = column[Option[LocalDateTime]]("terminated_at")

  def pk = primaryKey("pk_activities", (workflowId, activityId))

  def runsOn =
    foreignKey("fk_activities_resources", (workflowId, resourceId), TableQuery[ResourceTable])(
      r => (r.workflowId, r.resourceId),
      onUpdate = ForeignKeyAction.Restrict,
      onDelete = ForeignKeyAction.Cascade
    )

  def workflow = foreignKey("fk_activities_workflows", workflowId, TableQuery[WorkflowTable])(
    _.id,
    onUpdate = ForeignKeyAction.Restrict,
    onDelete = ForeignKeyAction.Cascade
  )

  override def * = (
    workflowId,
    activityId,
    name,
    activityType,
    activitySpec,
    resourceId,
    maxAttempt,
    status,
    createdAt,
    activatedAt,
    terminatedAt
  ).mapTo[ActivityTable.R]

}

object ActivityTable {

  def apply() = TableQuery[ActivityTable]

  case class R(
    workflowId: String,
    activityId: String,
    name: String,
    activtyType: String,
    activitySpec: JsValue,
    resourceId: String,
    maxAttempt: Int,
    status: Status.Value,
    createdAt: LocalDateTime,
    activatedAt: Option[LocalDateTime],
    terminatedAt: Option[LocalDateTime]
  )

}
