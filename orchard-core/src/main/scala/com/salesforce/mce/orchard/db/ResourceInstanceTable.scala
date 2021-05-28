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

class ResourceInstanceTable(tag: Tag)
    extends Table[ResourceInstanceTable.R](tag, "resource_instances") {

  def workflowId = column[String]("workflow_id", O.SqlType("VARCHAR(64)"))

  def resourceId = column[String]("resource_id", O.SqlType("VARCHAR(64)"))

  def instanceAttempt = column[Int]("instance_attempt")

  def instanceSpec = column[Option[JsValue]]("instance_spec")

  def status = column[Status.Value]("status", O.SqlType("VARCHAR(32)"))

  def errorMessage = column[String]("error_message", O.SqlType("TEXT"))

  def createdAt = column[LocalDateTime]("created_at")

  def activatedAt = column[Option[LocalDateTime]]("activated_at")

  def terminatedAt = column[Option[LocalDateTime]]("terminated_at")

  def pk = primaryKey("pk_resource_instances", (workflowId, resourceId, instanceAttempt))

  def resource = foreignKey(
    "fk_resource_instances_resources",
    (workflowId, resourceId),
    TableQuery[ResourceTable]
  )(
    r => (r.workflowId, r.resourceId),
    onUpdate = ForeignKeyAction.Restrict,
    onDelete = ForeignKeyAction.Cascade
  )

  override def * = (
    workflowId,
    resourceId,
    instanceAttempt,
    instanceSpec,
    status,
    errorMessage,
    createdAt,
    activatedAt,
    terminatedAt
  ).mapTo[ResourceInstanceTable.R]

}

object ResourceInstanceTable {

  def apply() = TableQuery[ResourceInstanceTable]

  case class R(
    workflowId: String,
    resourceId: String,
    instanceAttempt: Int,
    instanceSpec: Option[JsValue],
    status: Status.Value,
    errorMessage: String,
    createdAt: LocalDateTime,
    activatedAt: Option[LocalDateTime],
    terminatedAt: Option[LocalDateTime]
  )

}
