/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package controllers

import javax.inject._

import scala.concurrent.{ExecutionContext, Future}

import akka.actor.typed.ActorRef
import play.api.Logging
import play.api.libs.json.{JsError, JsNull, JsString}
import play.api.mvc._

import com.salesforce.mce.orchard.db.WorkflowQuery
import com.salesforce.mce.orchard.model.{Activity, Resource, Workflow}
import com.salesforce.mce.orchard.system.OrchardSystem

import models.WorkflowRequest
import services.DatabaseService
import utils.{AuthTransformAction, InvalidApiRequest, ValidApiRequest}

@Singleton
class WorkflowController @Inject() (
  cc: ControllerComponents,
  db: DatabaseService,
  authAction: AuthTransformAction,
  orchardSystem: ActorRef[OrchardSystem.Msg]
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
              orchardSystem ! OrchardSystem.ActivateMsg(db.orchardDB, id)
              Ok(JsString(id))
          }
      case InvalidApiRequest(_) => Future.successful(Results.Unauthorized(JsNull))
    }
  }

}
