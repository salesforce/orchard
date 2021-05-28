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

class ActivityQuery(workflowId: String, activityId: String) {

  private def self = ActivityTable()
    .filter(r => r.workflowId === workflowId && r.activityId === activityId)

  def get(): DBIO[Option[ActivityTable.R]] = self.result.headOption

  def attempts(): DBIO[Seq[ActivityAttemptTable.R]] = ActivityAttemptTable()
    .filter(r => r.workflowId === workflowId && r.activityId === activityId)
    .result

  def setRunning(): DBIO[Int] = self
    .map(r => (r.status, r.activatedAt))
    .update((Status.Running, Option(LocalDateTime.now())))

  def setTerminated(sts: Status.Value): DBIO[Int] = self
    .map(r => (r.status, r.activatedAt))
    .update((sts, Option(LocalDateTime.now())))

}
