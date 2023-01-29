package com.salesforce.mce.orchard.io.v

import java.time.LocalDateTime

import scala.util.Try

import play.api.libs.json.{JsResult, JsValue, Json, Reads}

import com.salesforce.mce.orchard.io.ResourceIO
import com.salesforce.mce.orchard.model.Status
import com.salesforce.mce.orchard.system.util.InvalidJsonException

case class DummyResource(spec: DummyResource.Spec) extends ResourceIO {

  override def create(): Either[Throwable, JsValue] = Right(
    Json.toJson(LocalDateTime.now.toString())
  )

  override def getStatus(instSpec: JsValue): Either[Throwable, Status.Value] = instSpec
    .validate[String]
    .asEither
    .left
    .map(InvalidJsonException.raise(_))
    .toTry
    .flatMap(str => Try(LocalDateTime.parse(str)))
    .map { startTime =>
      if (LocalDateTime.now().isBefore(startTime.plusSeconds(spec.initSeconds)))
        Status.Activating
      else
        Status.Running
    }
    .toEither

  override def terminate(instSpec: JsValue): Either[Throwable, Status.Value] =
    Right(Status.Finished)

}

object DummyResource {

  case class Spec(
    initSeconds: Int
  )
  implicit val specReads: Reads[Spec] = Json.reads[Spec]

  def decode(conf: ResourceIO.Conf): JsResult[DummyResource] = conf.resourceSpec
    .validate[Spec]
    .map(DummyResource(_))

}
