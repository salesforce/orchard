/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package tasks

import javax.inject.Inject

import scala.concurrent.ExecutionContext

import akka.actor.ActorSystem
import play.api.Logging

import utils.Authorization

class AuthSettingReloadTask @Inject() (actorSystem: ActorSystem, auth: Authorization)(implicit
  ec: ExecutionContext
) extends Logging {

  def scheduleRefresh(): Unit = auth.refreshDelay match {
    case Some(d) =>
      actorSystem.scheduler.scheduleOnce(d) {
        logger.info("reload authorization setting...")
        auth.reloadSettings()
        scheduleRefresh()
      }
    case None => {}
  }

  scheduleRefresh()

}
