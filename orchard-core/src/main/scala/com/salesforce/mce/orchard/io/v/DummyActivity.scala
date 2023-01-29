package com.salesforce.mce.orchard.io.v

import java.time.LocalDateTime

import scala.util.Try

import play.api.libs.json.{JsResult, JsValue, Json, Reads}

import com.salesforce.mce.orchard.io.ActivityIO
import com.salesforce.mce.orchard.model.Status
import com.salesforce.mce.orchard.system.util.InvalidJsonException

case class DummyActivity(spec: DummyActivity.Spec) extends ActivityIO {

  override def create(): Either[Throwable, JsValue] = Right(
    Json.toJson(LocalDateTime.now().toString())
  )

  override def getProgress(attemptSpec: JsValue): Either[Throwable, Status.Value] = attemptSpec
    .validate[String]
    .asEither
    .left
    .map(InvalidJsonException.raise(_))
    .toTry
    .flatMap(str => Try(LocalDateTime.parse(str)))
    .map { startTime =>
      if (LocalDateTime.now().isBefore(startTime.plusSeconds(spec.sleepSeconds)))
        Status.Running
      else
        Status.Finished
    }
    .toEither

  override def terminate(spec: JsValue): Either[Throwable, Status.Value] = Right(Status.Canceled)

}

object DummyActivity {

  case class Spec(
    sleepSeconds: Int
  )
  implicit val specRead: Reads[Spec] = Json.reads[Spec]

  def decode(conf: ActivityIO.Conf): JsResult[DummyActivity] =
    conf.activitySpec.validate[Spec].map(DummyActivity(_))

}
