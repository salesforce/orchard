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

class ActivityAttemptQuery(workflowId: String, activityId: String, attempt: Int) {

  private def self = ActivityAttemptTable()
    .filter(r =>
      r.workflowId === workflowId && r.activityId === activityId && r.attempt === attempt
    )

  def get(): DBIO[Option[ActivityAttemptTable.R]] = self.result.headOption

  def setWaiting(): DBIO[Int] = self
    .map(r => r.status)
    .update(Status.Activating)

  def setRunning(resourceId: String, resourceInstance: Int, attemptSpec: JsValue): DBIO[Int] = self
    .map(r => (r.status, r.resourceId, r.resourceInstanceAttempt, r.attemptSpec, r.activatedAt))
    .update(
      (
        Status.Running,
        Option(resourceId),
        Option(resourceInstance),
        Option(attemptSpec),
        Option(LocalDateTime.now())
      )
    )

  def setTerminated(sts: Status.Value, errorMessage: String) = self
    .map(r => (r.status, r.errorMessage, r.terminatedAt))
    .update((sts, errorMessage, Option(LocalDateTime.now())))

  def create()(implicit ec: ExecutionContext): DBIO[ActivityAttemptTable.R] = {
    val r = ActivityAttemptTable.R(
      workflowId,
      activityId,
      attempt,
      Status.Pending,
      "",
      None,
      None,
      None,
      LocalDateTime.now(),
      None,
      None
    )

    (ActivityAttemptTable() += r).map(_ => r)
  }

}
