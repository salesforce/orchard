package com.salesforce.mce.orchard.db

import slick.jdbc.PostgresProfile.api._
import java.time.LocalDateTime

class WorkflowManagerTable(tag: Tag)
    extends Table[WorkflowManagerTable.R](tag, "workflow_managers") {

  def workflowId = column[String]("workflow_id", O.SqlType("VARCHAR(64)"), O.PrimaryKey)

  def managerId = column[String]("manager_id", O.SqlType("VARCHAR(64)"))

  def lastCheckin = column[LocalDateTime]("last_checkin")

  def workflow =
    foreignKey("fk_workflow_managers_workflows", workflowId, TableQuery[WorkflowTable])(
      _.id,
      onUpdate = ForeignKeyAction.Restrict,
      onDelete = ForeignKeyAction.Cascade
    )

  override def * = (workflowId, managerId, lastCheckin).mapTo[WorkflowManagerTable.R]

}

object WorkflowManagerTable {

  def apply() = TableQuery[WorkflowManagerTable]

  case class R(
    workflowId: String,
    managerId: String,
    lastCheckin: LocalDateTime
  )

}
