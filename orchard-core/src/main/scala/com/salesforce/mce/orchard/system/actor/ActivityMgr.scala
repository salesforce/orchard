/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.system.actor

import akka.actor.typed.scaladsl._
import akka.actor.typed.{ActorRef, Behavior}
import play.api.libs.json.JsValue

import com.salesforce.mce.orchard.db.{ActivityQuery, OrchardDatabase}
import com.salesforce.mce.orchard.model.Status

object ActivityMgr {

  sealed trait Msg
  case object Start extends Msg
  case object CascadeFail extends Msg
  case object Cancel extends Msg
  case class AttemptFinished(status: Status.Value) extends Msg

  case class Params(
    ctx: ActorContext[Msg],
    workflowMgr: ActorRef[WorkflowMgr.Msg],
    database: OrchardDatabase,
    activityQuery: ActivityQuery,
    workflowId: String,
    activityId: String,
    activityType: String,
    activitySpec: JsValue,
    maxAttempt: Int,
    resourceId: String,
    resourceMgr: ActorRef[ResourceMgr.Msg]
  )

  def apply(
    workflowMgr: ActorRef[WorkflowMgr.Msg],
    database: OrchardDatabase,
    workflowId: String,
    activityId: String,
    resourceMgr: ActorRef[ResourceMgr.Msg]
  ): Behavior[Msg] = Behaviors.setup { ctx =>
    ctx.log.info(s"Starting ActivityMgr ${ctx.self}...")
    val activityQuery = new ActivityQuery(workflowId, activityId)
    val activityR = database.sync(activityQuery.get()).get
    val params = Params(
      ctx,
      workflowMgr,
      database,
      activityQuery,
      workflowId,
      activityId,
      activityR.activtyType,
      activityR.activitySpec,
      activityR.maxAttempt,
      activityR.resourceId,
      resourceMgr
    )

    database
      .sync(activityQuery.get())
      .map{ r =>
        params.ctx.log.info(s"${ctx.self} init with status ${r.status}")
        init(params, r.status)
      }
      .getOrElse(terminate(params, Status.Failed))

  }

  private def init(ps: Params, status: Status.Value): Behavior[Msg] = status match {

    // this is the state if everything follows the happy path
    case Status.Pending =>
      idle(ps)

    // activity started by some other actor, but for some reason, it no longer supervises the
    // activity
    case Status.Running =>
      val attmptOpt = ps.database
        .sync(ps.activityQuery.attempts())
        .sortBy(_.attempt)(Ordering[Int].reverse)
        .headOption

      val attmptEith = attmptOpt match {
        case Some(r) =>
          if (!Status.isAlive(r.status) && r.attempt < ps.maxAttempt) {
            Right(r.attempt + 1)
          } else if (Status.isAlive(r.status)) {
            Right(r.attempt)  // resume an already live attempt
          }
          else Left(r.status)
        case None =>
          Right(1)
      }

      attmptEith.fold(
        sts => {
          ps.database.sync(ps.activityQuery.setTerminated(sts))
          terminate(ps, sts)
        },
        attmptId => {
          val attmpt = ps.ctx.spawn(
            ActivityAttempt(
              ps.ctx.self,
              ps.resourceMgr,
              ps.database,
              ps.workflowId,
              ps.activityId,
              attmptId,
              ps.activityType,
              ps.activitySpec,
              ps.resourceId
            ),
            s"attempt-${attmptId}"
          )
          running(ps, attmptId, attmpt)
        }
      )
    case other =>
      terminate(ps, other)
  }

  private def terminate(ps: Params, status: Status.Value): Behavior[Msg] = {
    ps.ctx.log.info(s"ActivityMgr ${ps.ctx.self} actor stopped")
    ps.workflowMgr ! WorkflowMgr.ActivityCompleted(ps.activityId, status)
    Behaviors.stopped
  }

  private def idle(ps: Params): Behavior[Msg] = Behaviors.receiveMessage {
    case Start =>
      ps.ctx.log.info(s"${ps.ctx.self} (idle) received Start")
      ps.database.sync(ps.activityQuery.setRunning())
      val attmptId = 1
      val attempt = ps.ctx.spawn(
        ActivityAttempt(
          ps.ctx.self,
          ps.resourceMgr,
          ps.database,
          ps.workflowId,
          ps.activityId,
          attmptId,
          ps.activityType,
          ps.activitySpec,
          ps.resourceId
        ),
        s"attempt-${attmptId}"
      )
      running(ps, attmptId, attempt)
    case CascadeFail =>
      ps.ctx.log.info(s"${ps.ctx.self} (idle) received CascadeFail")
      val sts = Status.CascadeFailed
      ps.database.sync(ps.activityQuery.setTerminated(sts))
      terminate(ps, sts)
    case Cancel =>
      ps.ctx.log.info(s"${ps.ctx.self} (idle) received Cancel")
      val sts = Status.Canceled
      ps.database.sync(ps.activityQuery.setTerminated(sts))
      terminate(ps, sts)
    case AttemptFinished(sts) =>
      ps.ctx.log.error(s"${ps.ctx.self} (idle) received unexpected AttemptFinished($sts)")
      terminate(ps, sts)
  }

  private def running(
    ps: Params,
    attemptId: Int,
    attempt: ActorRef[ActivityAttempt.Msg]
  ): Behavior[Msg] = Behaviors.receiveMessage {
    case Start =>
      ps.ctx.log.info(s"${ps.ctx.self} (running) received Start")
      Behaviors.same
    case CascadeFail =>
      ps.ctx.log.error(s"${ps.ctx.self} (running) received unexpected CascadeFail")
      attempt ! ActivityAttempt.Cancel
      Behaviors.same
    case Cancel =>
      ps.ctx.log.info(s"${ps.ctx.self} (running) received Cancel")
      attempt ! ActivityAttempt.Cancel
      Behaviors.same
    case AttemptFinished(
          sts @ (Status.Finished | Status.Canceled | Status.Timeout | Status.CascadeFailed)
        ) =>
      ps.ctx.log.info(s"${ps.ctx.self} (running) received AttemptFinished($sts)")
      ps.database.sync(ps.activityQuery.setTerminated(sts))
      terminate(ps, sts)
    case AttemptFinished(sts @ Status.Failed) =>
      ps.ctx.log.info(s"${ps.ctx.self} (running) received AttemptFinished($sts)")
      if (attemptId < ps.maxAttempt) {
        val newAttmptId = attemptId + 1
        val newAttempt = ps.ctx.spawn(
          ActivityAttempt(
            ps.ctx.self,
            ps.resourceMgr,
            ps.database,
            ps.workflowId,
            ps.activityId,
            newAttmptId,
            ps.activityType,
            ps.activitySpec,
            ps.resourceId
          ),
          s"attempt-${newAttmptId}"
        )
        running(ps, newAttmptId, newAttempt)
      } else {
        ps.database.sync(ps.activityQuery.setTerminated(sts))
        terminate(ps, sts)
      }
    case AttemptFinished(sts) =>
      ps.ctx.log.error(s"${ps.ctx.self} (running) received unexpected AttemptFinished($sts)")
      terminate(ps, sts)
  }

}
