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

  val numRetriesAndTimeRange = for {
    n <- conf.getOptional[Int]("orchard.system.restart-num-of-retries")
    t <- conf.getOptional[Long]("orchard.system.restart-time-range")
  } yield (n, t)

  private val supervisedOrchardSystem: Behavior[OrchardSystem.Msg] =
    if (numRetriesAndTimeRange.isEmpty) {
      Behaviors
        .supervise(OrchardSystem.apply(databaseService.orchardDB))
        .onFailure(
          SupervisorStrategy.restart
        )
    } else {
      val (restartMaxNrOfRetries, restartTimeRange) = numRetriesAndTimeRange.get
      Behaviors
        .supervise(OrchardSystem.apply(databaseService.orchardDB))
        .onFailure(
          SupervisorStrategy.restart
            .withLimit(
              maxNrOfRetries = restartMaxNrOfRetries,
              withinTimeRange = restartTimeRange.seconds
            )
        )
    }

  val orchard: ActorRef[OrchardSystem.Msg] =
    actorSystem.spawn(supervisedOrchardSystem, "orchard-system")
}
