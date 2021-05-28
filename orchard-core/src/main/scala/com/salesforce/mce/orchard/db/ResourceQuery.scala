/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.db

import java.time.LocalDateTime

import slick.jdbc.PostgresProfile.api._

import com.salesforce.mce.orchard.model.Status

class ResourceQuery(workflowId: String, resourceId: String) {

  private def self = ResourceTable()
    .filter(r => r.workflowId === workflowId && r.resourceId === resourceId)

  def get(): DBIO[Option[ResourceTable.R]] = self.result.headOption

  def instances(): DBIO[Seq[ResourceInstanceTable.R]] = ResourceInstanceTable()
    .filter(r => r.workflowId === workflowId && r.resourceId === resourceId)
    .result

  def setRun(): DBIO[Int] =
    self.map(r => (r.status, r.activatedAt)).update((Status.Running, Option(LocalDateTime.now())))

  def setShuttingDown(): DBIO[Int] = self.map(r => r.status).update(Status.ShuttingDown)

  def setTerminated(status: Status.Value): DBIO[Int] =
    self.map(r => (r.status, r.terminatedAt)).update((status, Option(LocalDateTime.now())))

}
