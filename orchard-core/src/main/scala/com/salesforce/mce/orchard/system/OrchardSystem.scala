/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.system

import java.net.InetAddress
import java.util.UUID

import scala.concurrent.duration._

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}

import com.salesforce.mce.orchard.db.{OrchardDatabase, WorkflowManagerQuery, WorkflowQuery}
import com.salesforce.mce.orchard.model.Status
import com.salesforce.mce.orchard.system.actor.WorkflowMgr

object OrchardSystem {

  val CancelingScanDelay = 10.seconds
  val HeartBeatDelay = 10.seconds
  val CheckAdoptionDelay = 1.minute

  sealed trait Msg
  case class ActivateMsg(workflowId: String) extends Msg
  private case object ScanCanceling extends Msg
  private case class WorkflowTerminated(workflowId: String) extends Msg
  private case object HeartBeat extends Msg
  private case object AdoptOrphanWorkflows extends Msg

  def apply(database: OrchardDatabase): Behavior[Msg] = Behaviors.setup { ctx =>
    Behaviors.withTimers { timers =>
      timers.startSingleTimer(ScanCanceling, CancelingScanDelay)
      timers.startSingleTimer(HeartBeat, HeartBeatDelay)
      timers.startSingleTimer(AdoptOrphanWorkflows, CheckAdoptionDelay)
      val managerId =
        s"os-${InetAddress.getLocalHost().getHostName()}-${UUID.randomUUID()}".take(64)
      apply(
        ctx,
        database,
        new WorkflowManagerQuery(managerId),
        timers,
        Map.empty[String, ActorRef[WorkflowMgr.Msg]]
      )
    }
  }

  private def apply(
    ctx: ActorContext[Msg],
    database: OrchardDatabase,
    query: WorkflowManagerQuery,
    timers: TimerScheduler[Msg],
    workflows: Map[String, ActorRef[WorkflowMgr.Msg]]
  ): Behavior[Msg] = Behaviors.receiveMessage[Msg] {

    case ActivateMsg(workflowId) =>
      ctx.log.info(s"${ctx.self} Received ActivateMsg($workflowId)")
      if (workflows.contains(workflowId)) {
        ctx.log.warn(s"${ctx.self} workflow $workflowId is already active")
        Behaviors.same
      } else {
        if (database.sync(query.get(workflowId)).isEmpty)
          database.sync(query.manage(workflowId))
        else
          database.sync(query.checkin(Set(workflowId)))
        val workflowMgr = ctx.spawn(WorkflowMgr.apply(database, workflowId), workflowId)
        ctx.watchWith(workflowMgr, WorkflowTerminated(workflowId))
        apply(ctx, database, query, timers, workflows + (workflowId -> workflowMgr))
      }

    case WorkflowTerminated(workflowId) =>
      ctx.log.info(s"${ctx.self} Received WorkflowTerminated($workflowId)")
      apply(ctx, database, query, timers, workflows - workflowId)

    case ScanCanceling =>
      ctx.log.debug(s"${ctx.self} Received ScanCanceling")
      val cancelings = database.sync(WorkflowQuery.filterByStatus(Status.Canceling))
      for {
        workflow <- cancelings
        workflowMgr <- workflows.get(workflow.id)
      } workflowMgr ! WorkflowMgr.CancelWorkflow
      timers.startSingleTimer(ScanCanceling, CancelingScanDelay)
      Behaviors.same

    case HeartBeat =>
      ctx.log.debug(s"${ctx.self} Received HeartBeat")
      database.sync(query.checkin(workflows.keySet))
      timers.startSingleTimer(HeartBeat, HeartBeatDelay)
      Behaviors.same

    case AdoptOrphanWorkflows =>
      ctx.log.info(s"${ctx.self} Received AdoptOrphanWorkflows")
      database
        .sync(query.getOrhpanWorkflows(5.minutes, 1.day))
        .flatMap {
          case (wf, Some(wm)) =>
            if (
              database.sync(new WorkflowManagerQuery(wm.managerId).delete(wm.workflowId)) > 0 &&
              database.sync(query.manage(wf.id)) > 0
            ) {

              Some(wf.id)
            } else {
              None
            }
          case (wf, None) =>
            if (database.sync(query.manage(wf.id)) > 0) {
              Some(wf.id)
            } else {
              None
            }
        }
        .foreach { wfId =>
          ctx.log.info(s"${ctx.self} Adopt workflow ${wfId}")
          ctx.self ! ActivateMsg(wfId)
        }

      timers.startSingleTimer(AdoptOrphanWorkflows, CheckAdoptionDelay)
      Behaviors.same

  }

}
