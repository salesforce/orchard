/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package services

import javax.inject._

import scala.concurrent.duration._

import akka.actor.ActorSystem
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import play.api.Configuration

import com.salesforce.mce.orchard.system.OrchardSystem

@Singleton
class OrchardSystemService @Inject() (
  actorSystem: ActorSystem,
  databaseService: DatabaseService,
  conf: Configuration
) {

  val restartBackoffParams = for {
    a <- conf.getOptional[Int]("orchard.system.restart-min-backoff-seconds")
    b <- conf.getOptional[Int]("orchard.system.restart-max-backoff-seconds")
    j <- conf.getOptional[Double]("orchard.system.restart-jitter-probability")
  } yield (a, b, j)

  private val supervisedOrchardSystem: Behavior[OrchardSystem.Msg] =
    if (restartBackoffParams.isEmpty) {
      Behaviors
        .supervise(OrchardSystem.apply(databaseService.orchardDB))
        .onFailure(
          SupervisorStrategy.restart
        )
    } else {
      val (minBackoff, maxBackoff, jitter) = restartBackoffParams.get
      Behaviors
        .supervise(OrchardSystem.apply(databaseService.orchardDB))
        .onFailure(
          SupervisorStrategy.restartWithBackoff(minBackoff.seconds, maxBackoff.seconds, jitter)
        )
    }

  val orchard: ActorRef[OrchardSystem.Msg] =
    actorSystem.spawn(supervisedOrchardSystem, "orchard-system")
}
