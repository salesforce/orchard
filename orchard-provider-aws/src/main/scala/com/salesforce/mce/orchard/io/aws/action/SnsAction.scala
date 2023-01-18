/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.io.aws.action

import scala.util.Try

import play.api.libs.json.{JsResult, Json, Reads}
import software.amazon.awssdk.services.sns.model.PublishRequest

import com.salesforce.mce.orchard.io.ActionIO
import com.salesforce.mce.orchard.io.aws.Client
import com.salesforce.mce.orchard.model.ActionStatus

case class SnsAction(spec: SnsAction.Spec) extends ActionIO {

  override def run(): Try[ActionStatus] = Try {
    val request = PublishRequest
      .builder()
      .topicArn(spec.topicArn)
      .subject(spec.subject.take(100))
      .message(spec.message)
      .build()

    Client.sns().publish(request)
    ActionStatus.Finished
  }

}

object SnsAction {

  case class Spec(
    topicArn: String,
    subject: String,
    message: String
  )
  implicit val specReads: Reads[Spec] = Json.reads[Spec]

  private def fillContext(content: String, conf: ActionIO.Conf): String = content
    .replace("{{workflowId}}", conf.workflowId)
    .replace("{{activityId}}", conf.activityId)
    .replace("{{actionId}}", conf.actionId)

  def compileSpec(spec: Spec, conf: ActionIO.Conf): Spec = spec.copy(
    subject = fillContext(spec.subject, conf),
    message = fillContext(spec.message, conf)
  )

  def decode(conf: ActionIO.Conf): JsResult[SnsAction] = conf.actionSpec
    .validate[Spec]
    .map(spec => SnsAction(compileSpec(spec, conf)))

}
