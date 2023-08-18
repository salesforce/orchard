package models

import java.time.LocalDateTime

import play.api.libs.json.{JsValue, Json, Reads, Writes}

case class ResourceResponse(
  workflowId: String,
  resourceId: String,
  name: String,
  resourceType: String,
  resourceSpec: JsValue,
  maxAttempt: Int,
  status: String,
  createdAt: LocalDateTime,
  activatedAt: Option[LocalDateTime],
  terminatedAt: Option[LocalDateTime],
  terminateAfter: Double
)

object ResourceResponse {
  implicit val reads: Reads[ResourceResponse] = Json.reads[ResourceResponse]
  implicit val writes: Writes[ResourceResponse] = Json.writes[ResourceResponse]
}
