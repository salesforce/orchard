/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package services

import javax.inject._

import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.adapter._

import com.salesforce.mce.orchard.system.OrchardSystem

@Singleton
class OrchardSystemService @Inject() (actorSystem: ActorSystem, databaseService: DatabaseService) {
  val orchard =
    actorSystem.spawn(OrchardSystem.apply(databaseService.orchardDB), "orchard-system")
}
