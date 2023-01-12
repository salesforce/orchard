/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package models

import play.api.libs.json._

case class WorkflowRequest(
  name: String,
  activities: Seq[WorkflowRequest.Activity],
  resources: Seq[WorkflowRequest.Resource],
  // key depends on values
  dependencies: Map[String, Seq[String]],
  actions: Seq[WorkflowRequest.Action]
)

object WorkflowRequest {

  case class Resource(
    id: String,
    name: String,
    resourceType: String,
    resourceSpec: JsValue,
    maxAttempt: Int,
    terminateAfter: Option[Double]
  )

  case class Activity(
    id: String,
    name: String,
    activityType: String,
    activitySpec: JsValue,
    resourceId: String,
    maxAttempt: Int,
    onSuccess: Option[Seq[String]],
    onFailure: Option[Seq[String]]
  )

  case class Action(
    id: String,
    name: String,
    actionType: String,
    actionSpec: JsValue
  )

  implicit val activityReads = Json.reads[Activity]

  implicit val resourceReads = Json.reads[Resource]

  implicit val actionReads = Json.reads[Action]

  implicit val workflowRequestReads = Json.reads[WorkflowRequest]

}
