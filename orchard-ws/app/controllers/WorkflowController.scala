/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package controllers

import javax.inject._

import scala.concurrent.{ExecutionContext, Future}

import play.api.Logging
import play.api.libs.json.{JsError, JsNull, JsString, JsValue, Json}
import play.api.mvc._

import com.salesforce.mce.orchard.db.{WorkflowQuery, WorkflowTable}
import com.salesforce.mce.orchard.model.{Activity, Resource, Workflow}
import com.salesforce.mce.orchard.system.OrchardSystem

import models.{WorkflowRequest, WorkflowResponse}
import services.{DatabaseService, OrchardSystemService}
import utils.{AuthTransformAction, InvalidApiRequest, ValidApiRequest}

@Singleton
class WorkflowController @Inject() (
  cc: ControllerComponents,
  db: DatabaseService,
  authAction: AuthTransformAction,
  orchardSystemService: OrchardSystemService
)(implicit ec: ExecutionContext)
    extends AbstractController(cc) with Logging {

  private def processWorkflowRequest(request: WorkflowRequest): Future[String] = {
    // TODO make sure to validate the workflow request (i.e. all IDs exists and it's a DAG)

    db.orchardDB.createWorkflow(
      Workflow(
        request.name,
        request.activities.map { a =>
          Activity(
            a.id,
            a.name,
            a.activityType,
            a.activitySpec,
            a.resourceId,
            a.maxAttempt
          )
        },
        request.resources.map { r =>
          Resource(
            r.id,
            r.name,
            r.resourceType,
            r.resourceSpec,
            r.maxAttempt
          )
        },
        request.dependencies
      )
    )
  }

  def post() = authAction.async(parse.json) { request =>
    request match {
      case ValidApiRequest(apiRole, req) =>
        req.body
          .validate[WorkflowRequest]
          .fold(
            e => {
              val errorJson = JsError.toJson(e)
              Future.successful(BadRequest(errorJson))
            },
            workflowReq => {
              processWorkflowRequest(workflowReq).map(workflowId => Ok(JsString(workflowId)))
            }
          )
      case InvalidApiRequest(_) => Future.successful(Results.Unauthorized(JsNull))
    }
  }

  def activate(id: String) = authAction.async { request =>
    request match {
      case ValidApiRequest(apiRole, req) =>
        db.orchardDB
          .async(new WorkflowQuery(id).activate())
          .map {
            case 0 =>
              NotFound(JsString("does not exists"))
            case _ =>
              orchardSystemService.orchard ! OrchardSystem.ActivateMsg(id)
              Ok(JsString(id))
          }
      case InvalidApiRequest(_) => Future.successful(Results.Unauthorized(JsNull))
    }
  }

  private def toResponse(r: WorkflowTable.R): JsValue = Json.toJson(
    WorkflowResponse(
      r.id,
      r.name,
      r.status.toString,
      r.createdAt,
      r.activatedAt,
      r.terminatedAt
    )
  )

  def filter(
    like: String,
    orderBy: Option[String],
    order: Option[String],
    page: Option[Int],
    perPage: Option[Int]
  ) = authAction.async {
    case ValidApiRequest(apiRole, req) =>
      val validated = for {
        vOrderBy <- WorkflowController.validateOrderBy(orderBy)
        vOrder <- WorkflowController.validateOrder(order)
        vPage <- WorkflowController.validateOne(page.getOrElse(1), "page")
        limit <- WorkflowController.validateOne(perPage.getOrElse(50), "per_page")
      } yield (vOrderBy, vOrder, limit, (vPage - 1) * limit)

      validated match {
        case Right((by, ord, limit, offset)) =>
          db.orchardDB
            .async(WorkflowQuery.filter(like, by, ord, limit, offset))
            .map(rs => Ok(Json.toJson(rs.map(toResponse))))
        case Left(msg) =>
          Future.successful(BadRequest(JsString(msg)))
      }
    case InvalidApiRequest(_) =>
      Future.successful(Results.Unauthorized(JsNull))
  }

  def delete(workflowId: String) = authAction.async {
    case ValidApiRequest(apiRole, req) =>
      db.orchardDB
        .async(new WorkflowQuery(workflowId).deletePending())
        .map { r =>
          if (r >= 1) Ok(Json.toJson(r))
          else NotFound(Json.toJson(s"Pending workflow ${workflowId} does not exist"))
        }
    case InvalidApiRequest(_) =>
      Future.successful(Results.Unauthorized(JsNull))
  }

  def cancel(workflowId: String) = authAction.async {
    case ValidApiRequest(apiRole, req) =>
      db.orchardDB
        .async(new WorkflowQuery(workflowId).setCanceling())
        .map { r =>
          if (r >= 1) Ok(Json.toJson(r))
          else NotFound(Json.toJson(s"Running workflow ${workflowId} does not exist"))
        }
    case InvalidApiRequest(_) =>
      Future.successful(Results.Unauthorized(JsNull))
  }

}

object WorkflowController {

  private def validateOrderBy(orderBy: Option[String]): Either[String, WorkflowQuery.OrderBy] = {
    orderBy.fold[Either[String, WorkflowQuery.OrderBy]](Right(WorkflowQuery.OrderBy.CreatedAt)) {
      case "created_at" => Right(WorkflowQuery.OrderBy.CreatedAt)
      case "activated_at" => Right(WorkflowQuery.OrderBy.ActivatedAt)
      case "terminated_at" => Right(WorkflowQuery.OrderBy.TerminatedAt)
      case other => Left(s"Unknown order_by $other")
    }
  }

  private def validateOrder(order: Option[String]): Either[String, WorkflowQuery.Order] = {
    order.fold[Either[String, WorkflowQuery.Order]](Right(WorkflowQuery.Order.Desc)) {
      case "desc" => Right(WorkflowQuery.Order.Desc)
      case "asc" => Right(WorkflowQuery.Order.Asc)
      case other => Left(s"Unknown order $other")
    }
  }

  private def validateOne(num: Int, name: String): Either[String, Int] = {
    if (num < 1) Left(s"$name must be at least 1")
    else Right(num)
  }

}
