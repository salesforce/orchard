/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package workflow

import javax.inject._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

import play.api.Logging
import play.api.libs.json.{JsError, JsNull, JsString, JsValue, Json}
import play.api.mvc._

import com.salesforce.mce.orchard.db.{ActivityQuery, ResourceQuery, WorkflowQuery, WorkflowTable}
import com.salesforce.mce.orchard.model.{Action => WfAction, Activity, Resource, Status, Workflow}
import com.salesforce.mce.orchard.system.OrchardSystem

import models.{ActivityResponse, ResourceResponse, WorkflowRequest, WorkflowResponse}
import services.{DatabaseService, OrchardSystemService}
import utils.UserAction

@Singleton
class Controller @Inject() (
  cc: ControllerComponents,
  db: DatabaseService,
  userAction: UserAction,
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
            a.maxAttempt,
            a.onSuccess.getOrElse(Seq.empty),
            a.onFailure.getOrElse(Seq.empty)
          )
        },
        request.resources.map { r =>
          Resource(
            r.id,
            r.name,
            r.resourceType,
            r.resourceSpec,
            r.maxAttempt,
            r.terminateAfter.getOrElse(8)
          )
        },
        request.dependencies,
        request.actions.map { r =>
          WfAction(
            r.id,
            r.name,
            r.actionType,
            r.actionSpec
          )
        }
      )
    )
  }

  private def validatePayload(request: WorkflowRequest): Either[String, WorkflowRequest] = {

    val invalidTerminateAt =
      for {
        resource <- request.resources
        terminateAt <- resource.terminateAfter
        if terminateAt <= 0 || terminateAt > 24 * 30
      } yield terminateAt

    val invalidMaxAttempts =
      for {
        resource <- request.resources
        if resource.maxAttempt <= 0 || resource.maxAttempt > 100
      } yield resource.maxAttempt

    val definedResIds = request.resources.map(_.id)
    val invalidResourceDefined = for {
      referredId <- request.activities.map(_.resourceId)
      if !definedResIds.contains(referredId)
    } yield referredId

    val definedActIds = request.activities.map(_.id)
    val invalidActDependencies = for {
      (src, dest) <- request.dependencies
      if !(definedActIds.contains(src) && dest.toSet.subsetOf(definedActIds.toSet))
    } yield (src, dest)

    if (invalidTerminateAt.nonEmpty) {
      Left("Invalid terminateAt value, must be a value within (0, 24 * 30].")
    } else if (invalidMaxAttempts.nonEmpty) {
      Left("Invalid maxAttempt value, must be a value greater than 0.")
    } else if (invalidResourceDefined.nonEmpty) {
      Left("Workflow activities refers to undefined resource id.")
    } else if (invalidActDependencies.nonEmpty) {
      Left("Workflow dependencies refer to undefined activity.")
    } else {
      Right(request)
    }
  }

  def post() = userAction.async(parse.json) {
    _.body
      .validate[WorkflowRequest]
      .fold(
        e => {
          val errorJson = JsError.toJson(e)
          Future.successful(BadRequest(errorJson))
        },
        workflowReq => {
          validatePayload(workflowReq)
            .fold(
              e => Future.successful(BadRequest(JsString(e))),
              req => processWorkflowRequest(req).map(workflowId => Ok(JsString(workflowId)))
            )
        }
      )
  }

  def activities(id: String) = userAction.async {
    db.orchardDB.async(new WorkflowQuery(id).get()).flatMap {
      case Some(wf) =>
        for {
          activities <- db.orchardDB.async(new WorkflowQuery(wf.id).activities())
        } yield {
          Ok(
            Json.obj(
              "workflow" -> WorkflowResponse(
                wf.id,
                wf.name,
                wf.status.toString,
                wf.createdAt,
                wf.activatedAt,
                wf.terminatedAt
              ),
              "activities" -> activities.map { a =>
                ActivityResponse(
                  a.workflowId,
                  a.activityId,
                  a.name,
                  a.activtyType,
                  a.activitySpec,
                  a.resourceId,
                  a.maxAttempt,
                  a.status.toString(),
                  a.createdAt,
                  a.activatedAt,
                  a.terminatedAt
                )
              }
            )
          )
        }
      case None => Future.successful(NotFound(JsNull))
    }
  }

  def activityAttempts(id: String, actId: String) = userAction.async {
    val query = new ActivityQuery(id, actId)
    db.orchardDB.async(query.get()).flatMap {
      case Some(act) =>
        for {
          attempts <- db.orchardDB.async(query.attempts())
        } yield {
          Ok(
            Json.obj(
              "activity" -> ActivityResponse(
                act.workflowId,
                act.activityId,
                act.name,
                act.activtyType,
                act.activitySpec,
                act.resourceId,
                act.maxAttempt,
                act.status.toString(),
                act.createdAt,
                act.activatedAt,
                act.terminatedAt
              ),
              "attempts" -> attempts.map { at =>
                Json.obj(
                  "workflowId" -> at.workflowId,
                  "activityId" -> at.activityId,
                  "attempt" -> at.attempt,
                  "status" -> at.status.toString(),
                  "errorMessage" -> at.errorMessage,
                  "resourceId" -> at.resourceId,
                  "resourceInstanceAttempt" -> at.resourceInstanceAttempt,
                  "attemptSpec" -> at.attemptSpec,
                  "createdAt" -> at.createdAt,
                  "activatedAt" -> at.activatedAt,
                  "terminatedAt" -> at.terminatedAt
                )
              }
            )
          )
        }
      case None =>
        Future.successful(NotFound(JsNull))
    }
  }

  def resources(id: String) = userAction.async {
    val query = new WorkflowQuery(id)
    db.orchardDB.async(query.get()).flatMap {
      case Some(wf) =>
        for {
          resources <- db.orchardDB.async(query.resources())
        } yield {
          Ok(
            Json.obj(
              "workflow" -> WorkflowResponse(
                wf.id,
                wf.name,
                wf.status.toString,
                wf.createdAt,
                wf.activatedAt,
                wf.terminatedAt
              ),
              "resources" -> resources.map { r =>
                ResourceResponse(
                  r.workflowId,
                  r.resourceId,
                  r.name,
                  r.resourceType,
                  r.resourceSpec,
                  r.maxAttempt,
                  r.status.toString(),
                  r.createdAt,
                  r.activatedAt,
                  r.terminatedAt,
                  r.terminateAfter
                )
              }
            )
          )
        }
      case None =>
        Future.successful(NotFound(JsNull))
    }
  }

  def resourceInstances(id: String, rscId: String) = userAction.async {
    val query = new ResourceQuery(id, rscId)
    db.orchardDB.async(query.get()).flatMap {
      case Some(rsc) =>
        for {
          insts <- db.orchardDB.async(query.instances())
        } yield {
          Ok(
            Json.obj(
              "resource" -> ResourceResponse(
                rsc.workflowId,
                rsc.resourceId,
                rsc.name,
                rsc.resourceType,
                rsc.resourceSpec,
                rsc.maxAttempt,
                rsc.status.toString(),
                rsc.createdAt,
                rsc.activatedAt,
                rsc.terminatedAt,
                rsc.terminateAfter
              ),
              "instances" -> insts.map { inst =>
                Json.obj(
                  "workflowId" -> inst.workflowId,
                  "resourceId" -> inst.resourceId,
                  "instanceAttempt" -> inst.instanceAttempt,
                  "instanceSpec" -> inst.instanceSpec,
                  "status" -> inst.status.toString(),
                  "errorMessage" -> inst.errorMessage,
                  "createdAt" -> inst.createdAt,
                  "activatedAt" -> inst.activatedAt,
                  "terminatedAt" -> inst.terminatedAt
                )
              }
            )
          )
        }
      case None =>
        Future.successful(NotFound(JsNull))
    }
  }

  def activate(id: String) = userAction.async {
    db.orchardDB
      .async(new WorkflowQuery(id).activate())
      .map {
        case 0 =>
          NotFound(JsString("does not exists"))
        case _ =>
          orchardSystemService.orchard ! OrchardSystem.ActivateMsg(id)
          Ok(JsString(id))
      }
  }

  def workflowDetails(id: String) = userAction.async {
    db.orchardDB
      .async(new WorkflowQuery(id).get())
      .flatMap {
        case Some(wf) => Future.successful(Ok(toResponse(wf)))
        case _ => Future.successful(NotFound(Json.toJson(s"Workflow $id does not exist")))
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
    statuses: Option[String],
    orderBy: Option[String],
    order: Option[String],
    page: Option[Int],
    perPage: Option[Int]
  ) = userAction.async {
    val validated = for {
      vStatuses <- Controller.validateStatuses(statuses)
      vOrderBy <- Controller.validateOrderBy(orderBy)
      vOrder <- Controller.validateOrder(order)
      vPage <- Controller.validateOne(page.getOrElse(1), "page")
      limit <- Controller.validateOne(perPage.getOrElse(50), "per_page")
    } yield (vStatuses, vOrderBy, vOrder, limit, (vPage - 1) * limit)

    validated match {
      case Right((stses, by, ord, limit, offset)) =>
        db.orchardDB
          .async(WorkflowQuery.filter(like, stses, by, ord, limit, offset))
          .map(rs => Ok(Json.toJson(rs.map(toResponse))))
      case Left(msg) =>
        Future.successful(BadRequest(JsString(msg)))
    }
  }

  def delete(workflowId: String) = userAction.async {
    db.orchardDB
      .async(new WorkflowQuery(workflowId).deletePending())
      .map { r =>
        if (r >= 1) Ok(Json.toJson(r))
        else NotFound(Json.toJson(s"Pending workflow ${workflowId} does not exist"))
      }
  }

  def cancel(workflowId: String) = userAction.async {
    db.orchardDB
      .async(new WorkflowQuery(workflowId).setCanceling())
      .map { r =>
        if (r >= 1) Ok(Json.toJson(r))
        else NotFound(Json.toJson(s"Running workflow ${workflowId} does not exist"))
      }
  }

}

object Controller {

  private def validateStatuses(statuses: Option[String]): Either[String, Seq[Status.Value]] = {
    statuses.fold[Either[String, Seq[Status.Value]]](Right(Seq.empty)) { stses =>
      Right(stses.split(',').flatMap(s => Try(Status.withName(s)).toOption).toSeq)
    }
  }

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
