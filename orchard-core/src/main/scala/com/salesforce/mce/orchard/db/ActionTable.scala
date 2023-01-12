/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.db

import play.api.libs.json.JsValue
import slick.jdbc.PostgresProfile.api._

class ActionTable(tag: Tag) extends Table[ActionTable.R](tag, "actions") {

  def workflowId = column[String]("workflow_id", O.SqlType(WorkflowTable.IdSqlType))

  def actionId = column[String]("action_id", O.SqlType(ActionTable.IdSqlType))

  def name = column[String]("name", O.SqlType("VARCHAR(256)"))

  def actionType = column[String]("action_type", O.SqlType("VARCHAR(64)"))

  def actionSpec = column[JsValue]("action_spec", O.SqlType("TEXT"))

  def pk = primaryKey("pk_actions", (workflowId, actionId))

  def workflow = foreignKey("fk_actions_workflows", workflowId, WorkflowTable())(
    _.id,
    onUpdate = ForeignKeyAction.Restrict,
    onDelete = ForeignKeyAction.Cascade
  )

  override def * = (workflowId, actionId, name, actionType, actionSpec).mapTo[ActionTable.R]

}

object ActionTable {

  final val IdSqlType = "VARCHAR(64)"

  def apply() = TableQuery[ActionTable]

  case class R(
    workflowId: String,
    actionId: String,
    name: String,
    actionType: String,
    actionSpec: JsValue
  )

}
