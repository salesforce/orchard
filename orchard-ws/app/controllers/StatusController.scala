/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package controllers

import javax.inject._

import play.api.libs.json._
import play.api.mvc._

import com.salesforce.mce.orchard.ws.BuildInfo

@Singleton
class StatusController @Inject() (cc: ControllerComponents) extends AbstractController(cc) {

  def status() = Action {
    Ok(Json.obj("status" -> "ok", "version" -> BuildInfo.version))
  }

}
