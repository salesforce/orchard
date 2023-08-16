/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package controllers

import javax.inject._

import scala.concurrent.ExecutionContext

import play.api.Logging
import play.api.libs.json.{JsNumber, JsObject}
import play.api.mvc._

import com.salesforce.mce.orchard.db.WorkflowQuery

import services.DatabaseService
import utils.UserAction

@Singleton
class StatsController @Inject() (
  cc: ControllerComponents,
  db: DatabaseService,
  userAction: UserAction
)(implicit ec: ExecutionContext)
    extends AbstractController(cc) with Logging {

  def count(days: Option[Int]) = userAction.async {
    db.orchardDB
      .async(WorkflowQuery.countByStatus(days.getOrElse(365)))
      .map { rs =>
        Results.Ok(JsObject(rs.map { case (k, v) => k.toString() -> JsNumber(v) }))
      }
  }

}
