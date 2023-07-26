/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package services

import javax.inject._

import scala.concurrent.duration._
import scala.util.{Success, Try}

import akka.actor.ActorSystem
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import com.typesafe.config.{Config, ConfigFactory}

import com.salesforce.mce.orchard.system.OrchardSystem

@Singleton
class OrchardSystemService @Inject() (actorSystem: ActorSystem, databaseService: DatabaseService) {

  private val config: Config = ConfigFactory.load()
  private val restartMaxNrOfRetries: Int = Try {
    config.getInt("orchard.system.restart-num-of-retries")
  } match {
    case Success(x) => x
    case _ => 10
  }
  private val restartTimeRange: FiniteDuration = Try {
    config.getInt("orchard.system.restart-time-range")
  } match {
    case Success(x) => x.seconds
    case _ => 60.seconds
  }

  private val supervisedOrchardSystem: Behavior[OrchardSystem.Msg] =
    Behaviors
      .supervise(OrchardSystem.apply(databaseService.orchardDB))
      .onFailure(
        SupervisorStrategy.restart
          .withLimit(maxNrOfRetries = restartMaxNrOfRetries, withinTimeRange = restartTimeRange)
      )

  val orchard: ActorRef[OrchardSystem.Msg] =
    actorSystem.spawn(supervisedOrchardSystem, "orchard-system")
}
