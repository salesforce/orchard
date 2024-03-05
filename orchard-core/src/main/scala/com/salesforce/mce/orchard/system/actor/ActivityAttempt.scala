/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.system.actor

import scala.concurrent.ExecutionContext

import org.apache.pekko.actor.typed._
import org.apache.pekko.actor.typed.scaladsl._
import play.api.libs.json.JsValue

import com.salesforce.mce.orchard.db.{ActivityAttemptQuery, OrchardDatabase, ResourceInstanceQuery}
import com.salesforce.mce.orchard.io.ActivityIO
import com.salesforce.mce.orchard.model.Status
import com.salesforce.mce.orchard.system.util.InvalidJsonException
import com.salesforce.mce.orchard.util.Policy

object ActivityAttempt {

  sealed trait Msg
  case object Cancel extends Msg
  case class ResourceInstSpec(spec: Either[Status.Value, (Int, JsValue)]) extends Msg
  private case object CheckProgress extends Msg

  case class Params(
    ctx: ActorContext[Msg],
    activityMgr: ActorRef[ActivityMgr.Msg],
    database: OrchardDatabase,
    query: ActivityAttemptQuery,
    workflowId: String,
    activityId: String,
    activityName: String,
    attemptId: Int,
    resourceId: String,
    rscInstSpecAdapter: ActorRef[ResourceMgr.ResourceInstSpecRsp],
    timers: TimerScheduler[Msg]
  )

  def apply(
    activityMgr: ActorRef[ActivityMgr.Msg],
    resourceMgr: ActorRef[ResourceMgr.Msg],
    database: OrchardDatabase,
    workflowId: String,
    activityId: String,
    activityName: String,
    attemptId: Int,
    activityType: String,
    activitySpec: JsValue,
    resourceId: String,
    checkProgressDelay: Policy
  ): Behavior[Msg] = Behaviors.supervise {
    Behaviors.setup { ctx: ActorContext[Msg] =>
      Behaviors.withTimers { timers: TimerScheduler[Msg] =>
        ctx.log.info(s"Starting ActivityAttempt ${ctx.self}...")
        val query = new ActivityAttemptQuery(workflowId, activityId, attemptId)

        val attemptR = database
          .sync(query.get())
          .getOrElse(database.sync(query.create()(ExecutionContext.global)))

        val rscInstSpecAdapter =
          ctx.messageAdapter[ResourceMgr.ResourceInstSpecRsp](r => ResourceInstSpec(r.spec))

        val ps = Params(
          ctx,
          activityMgr,
          database,
          query,
          workflowId,
          activityId,
          activityName,
          attemptId,
          resourceId,
          rscInstSpecAdapter,
          timers
        )

        attemptR.status match {
          case Status.Pending | Status.Activating =>
            if (attemptR.status == Status.Pending) database.sync(query.setWaiting())
            resourceMgr ! ResourceMgr.GetResourceInstSpec(rscInstSpecAdapter)
            ctx.log.info(s"${ctx.self} became waiting")
            waiting(ps, resourceMgr, activityType, activitySpec, checkProgressDelay)
          case Status.Running =>
            val resourceInstInfo = for {
              resourceInstAttempt <- attemptR.resourceInstanceAttempt
              resourceInst <- database
                .sync(
                  new ResourceInstanceQuery(
                    workflowId,
                    resourceId,
                    resourceInstAttempt
                  ).get()
                )
              instSpec <- resourceInst.instanceSpec
            } yield (resourceInst, instSpec)

            resourceInstInfo match {
              case Some((resourceInst, instSpec)) =>
                ActivityIO(
                  ActivityIO.Conf(
                    workflowId,
                    activityId,
                    activityName,
                    attemptId,
                    activityType,
                    activitySpec,
                    instSpec
                  )
                ).fold(
                  invalid => {
                    ctx.log.error(
                      s"${ctx.self} invalid activityIO: ${InvalidJsonException.raise(invalid)}"
                    )
                    terminate(ps, Status.Failed)
                  },
                  activityIO => {
                    ctx.log.info(s"${ctx.self} became running")
                    running(
                      ps,
                      resourceInst.instanceAttempt,
                      activityIO,
                      attemptR.attemptSpec.get,
                      checkProgressDelay
                    )
                  }
                )
              case None =>
                ctx.log.error(s"${ctx.self} Running activity attemt missing resource instance info")
                terminate(ps, Status.Failed)
            }

          case sts =>
            terminate(ps, sts)
        }
      }
    }
  }.onFailure(SupervisorStrategy.restart)

  def waiting(
    ps: Params,
    resourceMgr: ActorRef[ResourceMgr.Msg],
    activityType: String,
    activitySpec: JsValue,
    checkProgressDelay: Policy
  ): Behavior[Msg] =
    Behaviors
      .receiveMessage[Msg] {
        case Cancel =>
          ps.ctx.log.info(s"${ps.ctx.self} (waiting) received Cancel")
          val sts = Status.Canceled
          ps.database.sync(ps.query.setTerminated(sts, ""))
          terminate(ps, sts)
        case ResourceInstSpec(specEither) =>
          ps.ctx.log.info(s"${ps.ctx.self} (waiting) received ResourceInstSpec($specEither)")
          specEither match {
            // resource is up, with valid instance spec, start the activity
            case Right((resourceInst, spec)) =>
              val result = for {
                activityIO <- ActivityIO(
                  ActivityIO.Conf(
                    ps.workflowId,
                    ps.activityId,
                    ps.activityName,
                    ps.attemptId,
                    activityType,
                    activitySpec,
                    spec
                  )
                ).asEither.left
                  .map(InvalidJsonException.raise(_))
                attemptSpec <- activityIO.create()
              } yield (activityIO, attemptSpec)

              result match {
                case Left(exp) =>
                  ps.ctx.log.error(s"ActivityAttempt ${ps.ctx.self} exception in creating task", exp)
                  ps.database.sync(ps.query.setTerminated(Status.Failed, exp.getMessage()))
                  terminate(ps, Status.Failed)
                case Right((activityIO, attemptSpec)) =>
                  ps.database.sync(ps.query.setRunning(ps.resourceId, resourceInst, attemptSpec))
                  running(ps, resourceInst, activityIO, attemptSpec, checkProgressDelay)
              }

            // Resource not ready yet
            case Left(Status.Pending) =>
              ps.timers.startSingleTimer(CheckProgress, checkProgressDelay.delay())
              Behaviors.same

            // Resource down for unknown reason, cancel the activity?
            case Left(_) =>
              val sts = Status.Canceled
              ps.database.sync(ps.query.setTerminated(sts, ""))
              terminate(ps, sts)

          }
        case CheckProgress =>
          ps.ctx.log.info(s"${ps.ctx.self} (waiting) received CheckProgress")
          resourceMgr ! ResourceMgr.GetResourceInstSpec(ps.rscInstSpecAdapter)
          Behaviors.same
      }
      .receiveSignal { case (actorContext, signal) =>
        ps.ctx.log.info(s"${actorContext.self} (waiting) received signal $signal")
        Behaviors.same
      }

  def running(
    ps: Params,
    resourceInstance: Int,
    activityIO: ActivityIO,
    attemptSpec: JsValue,
    checkProgressDelay: Policy
  ): Behavior[Msg] = {
    ps.timers.startSingleTimer(CheckProgress, checkProgressDelay.delay())
    Behaviors
      .receiveMessage[Msg] {
        case Cancel =>
          ps.ctx.log.info(s"${ps.ctx.self} (running) received Cancel")
          val sts = activityIO.terminate(attemptSpec).getOrElse(Status.Failed)
          ps.database.sync(ps.query.setTerminated(sts, ""))
          terminate(ps, sts)
        case ResourceInstSpec(spec) =>
          ps.ctx.log.error(
            s"${ps.ctx.self} (running) received unexpected ResourceInstSpec($spec) during running state"
          )
          ps.database.sync(ps.query.setTerminated(Status.Failed, "Internal Error"))
          terminate(ps, Status.Failed)
        case CheckProgress =>
          ps.ctx.log.info(s"${ps.ctx.self} (running) received CheckProgress")
          activityIO.getProgress(attemptSpec) match {
            case Left(exp) =>
              val errorMessage = s"Failed getting attempt progress $exp"
              ps.ctx.log.error(errorMessage)
              ps.database.sync(ps.query.setTerminated(Status.Failed, errorMessage))
              terminate(ps, Status.Failed)
            case Right(status) if Status.TerminatedStatuses.contains(status) =>
              ps.database.sync(ps.query.setTerminated(status, ""))
              terminate(ps, status)
            case Right(status) =>
              ps.timers.startSingleTimer(CheckProgress, checkProgressDelay.delay())
              Behaviors.same
          }
      }
      .receiveSignal { case (actorContext, signal) =>
        ps.ctx.log.info(s"${actorContext.self} (running) received signal $signal")
        Behaviors.same
      }
  }

  def terminate(ps: Params, status: Status.Value): Behavior[Msg] = {
    ps.ctx.log.info(s"Stopping ActivityAttempt ${ps.ctx.self} actor...")
    ps.activityMgr ! ActivityMgr.AttemptFinished(status)
    Behaviors.stopped
  }

}
