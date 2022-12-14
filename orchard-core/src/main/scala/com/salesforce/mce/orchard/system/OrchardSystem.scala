/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.system

import scala.concurrent.duration._

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}

import com.salesforce.mce.orchard.db.{OrchardDatabase, WorkflowQuery}
import com.salesforce.mce.orchard.model.Status
import com.salesforce.mce.orchard.system.actor.WorkflowMgr
import akka.actor.typed.scaladsl.TimerScheduler

object OrchardSystem {

  val CancelingScanDelay = 10.seconds

  sealed trait Msg
  case class ActivateMsg(workflowId: String) extends Msg
  private case object ScanCanceling extends Msg
  private case class WorkflowTerminated(workflowId: String) extends Msg

  def apply(database: OrchardDatabase): Behavior[Msg] = Behaviors.setup { ctx =>
    Behaviors.withTimers { timers =>
      timers.startSingleTimer(ScanCanceling, CancelingScanDelay)
      apply(ctx, database, timers, Map.empty[String, ActorRef[WorkflowMgr.Msg]])
    }
  }

  private def apply(
    ctx: ActorContext[Msg],
    database: OrchardDatabase,
    timers: TimerScheduler[Msg],
    workflows: Map[String, ActorRef[WorkflowMgr.Msg]]
  ): Behavior[Msg] = Behaviors.receiveMessage[Msg] {
    case ActivateMsg(workflowId) =>
      ctx.log.info(s"${ctx.self} Received ActivateMsg($workflowId)")
      if (workflows.contains(workflowId)) {
        ctx.log.warn(s"${ctx.self} workflow $workflowId is already active")
        Behaviors.same
      } else {
        val workflowMgr = ctx.spawn(WorkflowMgr.apply(database, workflowId), workflowId)
        ctx.watchWith(workflowMgr, WorkflowTerminated(workflowId))
        apply(ctx, database, timers, workflows + (workflowId -> workflowMgr))
      }

    case WorkflowTerminated(workflowId) =>
      ctx.log.info(s"${ctx.self} Received WorkflowTerminated($workflowId)")
      apply(ctx, database, timers, workflows - workflowId)

    case ScanCanceling =>
      ctx.log.info(s"${ctx.self} Received ScanCanceling")
      val cancelings = database.sync(WorkflowQuery.filterByStatus(Status.Canceling))
      for {
        workflow <- cancelings
        workflowMgr <- workflows.get(workflow.id)
      } workflowMgr ! WorkflowMgr.CancelWorkflow
      timers.startSingleTimer(ScanCanceling, CancelingScanDelay)
      Behaviors.same
  }

}
