/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package admin

import javax.inject._

import scala.concurrent.ExecutionContext

import play.api.Logging
import play.api.libs.json.{JsArray, Json}
import play.api.mvc._

import com.salesforce.mce.orchard.db.WorkflowManagerQuery

import services.DatabaseService
import utils.AdminAction

@Singleton
class Controller @Inject() (
  cc: ControllerComponents,
  adminAction: AdminAction,
  db: DatabaseService
)(implicit ec: ExecutionContext)
    extends AbstractController(cc) with Logging {

  def workflowManagers() = adminAction.async {
    db.orchardDB.async(WorkflowManagerQuery.all()).map { rs =>
      Results.Ok(
        JsArray(
          rs.map { r =>
            Json.obj(
              "workflowId" -> r.workflowId,
              "managerId" -> r.managerId,
              "lastCheckin" -> r.lastCheckin.toString()
            )
          }
        )
      )
    }
  }

}
