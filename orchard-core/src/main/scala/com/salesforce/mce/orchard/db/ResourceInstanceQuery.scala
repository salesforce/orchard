/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.db

import java.time.LocalDateTime

import scala.concurrent.ExecutionContext

import play.api.libs.json.JsValue
import slick.jdbc.PostgresProfile.api._

import com.salesforce.mce.orchard.model.Status

class ResourceInstanceQuery(workflowId: String, resourceId: String, instanceId: Int) {

  private def self = ResourceInstanceTable()
    .filter(r =>
      r.workflowId === workflowId && r.resourceId === resourceId && r.instanceAttempt === instanceId
    )

  def get(): DBIO[Option[ResourceInstanceTable.R]] = self.result.headOption

  def insert()(implicit ec: ExecutionContext): DBIO[ResourceInstanceTable.R] = {
    val record = ResourceInstanceTable.R(
      workflowId,
      resourceId,
      instanceId,
      None,
      Status.Pending,
      "",
      LocalDateTime.now(),
      None,
      None
    )
    (ResourceInstanceTable() += record).map(_ => record)
  }

  def setActivated(): DBIO[Int] = self
    .map(r => (r.status, r.activatedAt))
    .update((Status.Activating, Option(LocalDateTime.now())))

  def setRunning(): DBIO[Int] = self
    .map(r => r.status)
    .update(Status.Running)

  def setSpec(spec: JsValue): DBIO[Int] = self.map(_.instanceSpec).update(Option(spec))

  def setTerminated(status: Status.Value, errorMessage: String) = self
    .map(r => (r.status, r.errorMessage, r.terminatedAt))
    .update((status, errorMessage, Option(LocalDateTime.now())))

}
