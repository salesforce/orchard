/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.system

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}

import com.salesforce.mce.orchard.db.OrchardDatabase
import com.salesforce.mce.orchard.system.actor.WorkflowMgr

object OrchardSystem {

  sealed trait Msg
  case class ActivateMsg(database: OrchardDatabase, workflowId: String) extends Msg

  def apply(): Behavior[Msg] = Behaviors.setup(context => new OrchardSystem(context))

}

class OrchardSystem(context: ActorContext[OrchardSystem.Msg]) extends AbstractBehavior(context) {

  import OrchardSystem._

  override def onMessage(msg: Msg): Behavior[Msg] = msg match {
    case ActivateMsg(database, workflowId) =>
      context.log.info(s"activating... $workflowId")
      context.spawn(WorkflowMgr.apply(database, workflowId), s"$workflowId")
      this
  }

}
