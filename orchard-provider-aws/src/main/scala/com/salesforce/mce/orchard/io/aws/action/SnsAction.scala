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

  def decode(conf: ActionIO.Conf): JsResult[SnsAction] = conf.actionSpec
    .validate[Spec]
    .map(spec => SnsAction(spec))

}
