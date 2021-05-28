/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.db

import slick.jdbc.PostgresProfile.api._

class DependencyTable(tag: Tag) extends Table[DependencyTable.R](tag, "dependencies") {

  def workflowId = column[String]("workflow_id", O.SqlType("VARCHAR(64)"))

  def activityId = column[String]("activity_id", O.SqlType("VARCHAR(64)"))

  def dependentId = column[String]("dependent_id", O.SqlType("VARCHAR(64)"))

  def pk = primaryKey("pk_depends_on", (workflowId, activityId, dependentId))

  def activity = foreignKey(
    "fk_dependency_activity_dependent",
    (workflowId, activityId),
    TableQuery[ActivityTable]
  )(
    a => (a.workflowId, a.activityId),
    onUpdate = ForeignKeyAction.Restrict,
    onDelete = ForeignKeyAction.Cascade
  )

  def dependent = foreignKey(
    "fk_dependency_activity_dependee",
    (workflowId, dependentId),
    TableQuery[ActivityTable]
  )(
    a => (a.workflowId, a.activityId),
    onUpdate = ForeignKeyAction.Restrict,
    onDelete = ForeignKeyAction.Cascade
  )

  override def * = (workflowId, activityId, dependentId).mapTo[DependencyTable.R]

}

object DependencyTable {

  def apply() = TableQuery[DependencyTable]

  case class R(workflowId: String, activityId: String, dependentId: String)

}
