/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package services

import javax.inject._

import akka.actor.ActorSystem
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._

import com.salesforce.mce.orchard.system.OrchardSystem

@Singleton
class OrchardSystemService @Inject() (actorSystem: ActorSystem, databaseService: DatabaseService) {
  private val supervisedOrchardSystem: Behavior[OrchardSystem.Msg] =
    Behaviors
      .supervise(OrchardSystem.apply(databaseService.orchardDB))
      .onFailure[Exception](SupervisorStrategy.restart)
  val orchard: ActorRef[OrchardSystem.Msg] =
    actorSystem.spawn(supervisedOrchardSystem, "orchard-system")
}
