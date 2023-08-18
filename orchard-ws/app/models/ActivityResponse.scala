package models

import java.time.LocalDateTime

import play.api.libs.json.{JsValue, Json, Reads, Writes}

case class ActivityResponse(
  workflowId: String,
  activityId: String,
  name: String,
  activityType: String,
  activitySpec: JsValue,
  resourceId: String,
  maxAttempt: Int,
  status: String,
  createdAt: LocalDateTime,
  activatedAt: Option[LocalDateTime],
  terminatedAt: Option[LocalDateTime]
)

object ActivityResponse {
  implicit val reads: Reads[ActivityResponse] = Json.reads[ActivityResponse]
  implicit val writes: Writes[ActivityResponse] = Json.writes[ActivityResponse]
}
