/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package models

import java.time.LocalDateTime

import play.api.libs.json.{Json, Writes, Reads}

case class WorkflowResponse(
  id: String,
  name: String,
  status: String,
  createdAt: LocalDateTime,
  activatedAt: Option[LocalDateTime],
  terminatedAt: Option[LocalDateTime]
)

object WorkflowResponse {
  implicit val reads: Reads[WorkflowResponse] = Json.reads[WorkflowResponse]
  implicit val writes: Writes[WorkflowResponse] = Json.writes[WorkflowResponse]
}
