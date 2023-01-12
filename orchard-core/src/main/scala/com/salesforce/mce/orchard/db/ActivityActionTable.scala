/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.db

import slick.jdbc.PostgresProfile.api._

import com.salesforce.mce.orchard.model.{ActionCondition, ActionStatus}

class ActivityActionTable(tag: Tag) extends Table[ActivityActionTable.R](tag, "activity_actions") {

  def workflowId = column[String]("workflow_id", O.SqlType(WorkflowTable.IdSqlType))

  def activityId = column[String]("activity_id", O.SqlType(ActivityTable.IdSqlType))

  def condition = column[ActionCondition]("condition", O.SqlType("VARCHAR(32)"))

  def actionId = column[String]("action_id", O.SqlType(ActionTable.IdSqlType))

  def status = column[ActionStatus]("status", O.SqlType("VARCHAR(32)"))

  def errorMessage = column[String]("error_message", O.SqlType("TEXT"))

  def pk = primaryKey("pk_activity_actions", (workflowId, activityId, condition, actionId))

  def activity =
    foreignKey("fk_activity_actions_activities", (workflowId, activityId), ActivityTable())(
      r => (r.workflowId, r.activityId),
      onUpdate = ForeignKeyAction.Restrict,
      onDelete = ForeignKeyAction.Cascade
    )

  def action =
    foreignKey("fk_activity_actions_actions", (workflowId, actionId), ActionTable())(
      r => (r.workflowId, r.actionId),
      onUpdate = ForeignKeyAction.Restrict,
      onDelete = ForeignKeyAction.Cascade
    )

  override def * =
    (workflowId, activityId, condition, actionId, status, errorMessage).mapTo[ActivityActionTable.R]

}

object ActivityActionTable {

  def apply() = TableQuery[ActivityActionTable]

  case class R(
    workflowId: String,
    activityId: String,
    condition: ActionCondition,
    actionId: String,
    status: ActionStatus,
    errorMessage: String
  )

}
