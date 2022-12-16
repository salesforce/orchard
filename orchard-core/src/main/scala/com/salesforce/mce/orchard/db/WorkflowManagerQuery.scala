package com.salesforce.mce.orchard.db

import java.time.LocalDateTime

import scala.compat.java8.DurationConverters._
import scala.concurrent.duration.FiniteDuration

import slick.jdbc.PostgresProfile.api._

import com.salesforce.mce.orchard.model.Status

class WorkflowManagerQuery(managerId: String) {

  def manage(workflowId: String) =
    WorkflowManagerTable() += WorkflowManagerTable.R(
      workflowId,
      managerId,
      LocalDateTime.now()
    )

  def checkin(workflowIds: Set[String]) =
    WorkflowManagerTable()
      .filter(r => r.managerId === managerId && r.workflowId.inSet(workflowIds))
      .map(_.lastCheckin)
      .update(LocalDateTime.now())

  def getOrhpanWorkflows(orphenDuration: FiniteDuration, lookback: FiniteDuration) = {
    val currentTime = LocalDateTime.now()
    val cutoff = currentTime.minus(lookback.toJava)
    val latestCheckinBefore = currentTime.minus(orphenDuration.toJava)

    WorkflowTable()
      .join(WorkflowManagerTable())
      .on((wf, wm) => wf.id === wm.workflowId)
      .filter { case (wf, wm) =>
        !wf.status.inSet(Status.TerminatedStatuses) &&
        wf.status =!= Status.Pending &&
        wf.status =!= Status.Deleted &&
        wm.lastCheckin >= cutoff &&
        wm.lastCheckin < latestCheckinBefore
      }
      .map { case (wf, _) => wf }
      .result
  }

  def delete(workflowId: String) = WorkflowManagerTable().filter(r => r.workflowId === workflowId && r.)

}
