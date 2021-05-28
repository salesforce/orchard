/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.db

import slick.jdbc.PostgresProfile.api._
import com.salesforce.mce.orchard.model.Status
import java.time.LocalDateTime

class WorkflowQuery(workflowId: String) {

  private def self = WorkflowTable().filter(_.id === workflowId)

  def get(): DBIO[Option[WorkflowTable.R]] = self.result.headOption

  def activate(): DBIO[Int] = WorkflowTable()
    // Note can only set activate if workflow is pending (probably should move this to the app
    // layer
    .filter(r => r.id === workflowId && r.status === Status.Pending)
    .map(r => (r.status, r.activatedAt))
    .update((Status.Running, Option(LocalDateTime.now())))

  def activities(): DBIO[Seq[ActivityTable.R]] = ActivityTable()
    .filter(_.workflowId === workflowId)
    .result

  def dependencies(): DBIO[Seq[DependencyTable.R]] = DependencyTable()
    .filter(_.workflowId === workflowId)
    .result

  def setTerminated(status: Status.Value): DBIO[Int] =
    self.map(r => (r.status, r.terminatedAt)).update((status, Option(LocalDateTime.now())))

}
