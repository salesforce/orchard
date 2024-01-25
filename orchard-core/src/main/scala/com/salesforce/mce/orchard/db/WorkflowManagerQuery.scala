/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.db

import java.time.LocalDateTime

import scala.jdk.DurationConverters._
import scala.concurrent.duration.FiniteDuration

import slick.jdbc.PostgresProfile.api._

import com.salesforce.mce.orchard.model.Status

class WorkflowManagerQuery(managerId: String) {

  def get(workflowId: String) =
    WorkflowManagerTable()
      .filter(r => r.managerId === managerId && r.workflowId === workflowId)
      .take(1)
      .result
      .headOption

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

  def delete(workflowId: String) = WorkflowManagerTable()
    .filter(r => r.workflowId === workflowId && r.managerId === managerId)
    .delete

}

object WorkflowManagerQuery {

  def getOrhpanWorkflows(orphanDuration: FiniteDuration, lookback: FiniteDuration) = {
    val currentTime = LocalDateTime.now()
    val cutoff = currentTime.minus(lookback.toJava)
    val latestCheckinBefore = currentTime.minus(orphanDuration.toJava)

    WorkflowTable()
      .joinLeft(WorkflowManagerTable())
      .on((wf, wm) => wf.id === wm.workflowId)
      .filter { case (wf, wmOpt) =>
        !wf.status.inSet(Status.TerminatedStatuses) &&
        wf.status =!= Status.Pending &&
        wf.status =!= Status.Deleted &&
        (wmOpt.isEmpty ||
          (wmOpt.map(_.lastCheckin) >= cutoff &&
            wmOpt.map(_.lastCheckin) < latestCheckinBefore))
      }
      .result
  }

  def all() = WorkflowManagerTable().sortBy(_.lastCheckin.desc).result

}
