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

class ResourceTable(tag: Tag) extends Table[ResourceTable.R](tag, "resources") {

  def workflowId = column[String]("workflow_id", O.SqlType("VARCHAR(64)"))

  def resourceId = column[String]("resource_id", O.SqlType("VARCHAR(64)"))

  def name = column[String]("name", O.SqlType("VARCHAR(256)"))

  def resourceType = column[String]("resource_type", O.SqlType("VARCHAR(64)"))

  def resourceSpec = column[JsValue]("resource_spec", O.SqlType("TEXT"))

  def maxAttempt = column[Int]("max_attempt")

  def status = column[Status.Value]("status", O.SqlType("VARCHAR(32)"))

  def createdAt = column[LocalDateTime]("created_at")

  def activatedAt = column[Option[LocalDateTime]]("activated_at")

  def terminatedAt = column[Option[LocalDateTime]]("terminated_at")

  // number of hours
  def terminateAfter = column[Double]("terminate_after")

  def pk = primaryKey("pk_resources", (workflowId, resourceId))

  def workflow = foreignKey("fk_resources_workflows", workflowId, TableQuery[WorkflowTable])(
    _.id,
    onUpdate = ForeignKeyAction.Restrict,
    onDelete = ForeignKeyAction.Cascade
  )

  def * = (
    workflowId,
    resourceId,
    name,
    resourceType,
    resourceSpec,
    maxAttempt,
    status,
    createdAt,
    activatedAt,
    terminatedAt,
    terminateAfter
  ).mapTo[ResourceTable.R]

}

object ResourceTable {

  def apply() = TableQuery[ResourceTable]

  case class R(
    workflowId: String,
    resourceId: String,
    name: String,
    resourceType: String,
    resourceSpec: JsValue,
    maxAttempt: Int,
    status: Status.Value,
    createdAt: LocalDateTime,
    activatedAt: Option[LocalDateTime],
    terminatedAt: Option[LocalDateTime],
    terminateAfter: Double
  )

}
