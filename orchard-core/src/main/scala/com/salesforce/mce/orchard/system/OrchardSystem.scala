/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.system

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}

import com.salesforce.mce.orchard.db.OrchardDatabase
import com.salesforce.mce.orchard.system.actor.WorkflowMgr

object OrchardSystem {

  sealed trait Msg
  case class ActivateMsg(workflowId: String) extends Msg

  def apply(database: OrchardDatabase): Behavior[Msg] = Behaviors.setup { ctx =>
    apply(ctx, database, Map.empty[String, ActorRef[WorkflowMgr.Msg]])
  }

  private def apply(
    ctx: ActorContext[Msg],
    database: OrchardDatabase,
    workflows: Map[String, ActorRef[WorkflowMgr.Msg]]
  ): Behavior[Msg] = Behaviors.receiveMessage { case ActivateMsg(workflowId) =>
    ctx.log.info(s"${ctx.self} Received ActivateMsg($workflowId)")
    if (workflows.contains(workflowId)) {
      ctx.log.warn(s"${ctx.self} workflow $workflowId is already active")
      Behaviors.same
    } else {
      val workflowMgr = ctx.spawn(WorkflowMgr.apply(database, workflowId), workflowId)
      apply(ctx, database, workflows + (workflowId -> workflowMgr))
    }
  }

}
