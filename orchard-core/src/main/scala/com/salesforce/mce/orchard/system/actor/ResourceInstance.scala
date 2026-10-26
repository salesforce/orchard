/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.system.actor

import java.time.{Duration => JDuration, LocalDateTime}

import scala.concurrent.duration._
import scala.jdk.DurationConverters._

import org.apache.pekko.actor.typed._
import org.apache.pekko.actor.typed.scaladsl._
import play.api.libs.json.JsValue

import com.salesforce.mce.orchard.db.{OrchardDatabase, ResourceInstanceQuery}
import com.salesforce.mce.orchard.io.ResourceIO
import com.salesforce.mce.orchard.model.Status

object ResourceInstance {

  // the delay to check resource instance status during activating, running relys on activity
  // attempts, or new activity
  val ResourceCheckDelay = 1.minute

  sealed trait Msg
  case class GetResourceInstSpec(replyTo: ActorRef[ResourceMgr.ResourceInstSpecRsp]) extends Msg
  case class Shutdown(status: Status.Value) extends Msg

  case class Params(
    ctx: ActorContext[Msg],
    resourceMgr: ActorRef[ResourceMgr.Msg],
    database: OrchardDatabase,
    query: ResourceInstanceQuery,
    resourceIO: ResourceIO,
    instanceId: Int,
    timers: TimerScheduler[ResourceInstance.Msg]
  )

  def apply(
    resourceMgr: ActorRef[ResourceMgr.Msg],
    database: OrchardDatabase,
    workflowId: String,
    resourceId: String,
    instanceId: Int,
    resourceIO: ResourceIO,
    terminateAfter: FiniteDuration
  ): Behavior[Msg] = Behaviors.setup { context =>
    Behaviors.withTimers { timers =>
      context.log.info(s"Starting ResourceInstance ${context.self}...")

      val query = new ResourceInstanceQuery(workflowId, resourceId, instanceId)
      implicit val ec = scala.concurrent.ExecutionContext.global

      val instR = database.sync(query.get()).getOrElse(database.sync(query.insert()))

      val ps = Params(
        context,
        resourceMgr,
        database,
        query,
        resourceIO,
        instanceId,
        timers
      )

      val estimatedTerminatedAfter =
        terminateAfter - JDuration.between(instR.createdAt, LocalDateTime.now()).toScala

      val terminateAfterTimer = if (estimatedTerminatedAfter < 5.minutes) {
        5.minutes
      } else estimatedTerminatedAfter

      timers.startSingleTimer(Shutdown(Status.Timeout), terminateAfterTimer)

      (instR.status, instR.instanceSpec) match {
        case (Status.Pending, _) =>
          pending(ps)
        case (Status.Activating, Some(instSpec)) =>
          activating(ps, instSpec)
        case (Status.Running, Some(instSpec)) =>
          running(ps, instSpec)
        case other =>
          context.log.error(s"Unexpected instance status $other")
          Behaviors.unhandled
      }
    }
  }

  // pending is the initial state after RI is created
  private def pending(ps: Params): Behavior[Msg] = Behaviors
    .receiveMessage[Msg] {
      case GetResourceInstSpec(replyTo) =>
        ps.ctx.log.info(s"${ps.ctx.self} (pending) received GetResourceInstSpec($replyTo)")
        ps.resourceIO.create() match {
          case Right(instSpec) =>
            ps.ctx.log.info(s"${ps.ctx.self} (pending) created resource instSpec=$instSpec")
            ps.database.sync(ps.query.setActivated(instSpec))
            ps.timers.startSingleTimer(GetResourceInstSpec(replyTo), ResourceCheckDelay)
            activating(ps, instSpec)
          case Left(exp) =>
            ps.ctx.log.error(s"${ps.ctx.self} (pending) Exception when creating resource", exp)
            ps.database.sync(ps.query.setTerminated(Status.Failed, exp.getMessage()))
            // nothing was provisioned, so there is nothing to tear down
            terminate(ps, Status.Failed, Option(replyTo), ResourceMgr.Teardown.Terminated)
        }
      case Shutdown(status) =>
        ps.ctx.log.info(s"${ps.ctx.self} (pending) received Shutdown($status)")
        ps.database.sync(ps.query.setTerminated(status, ""))
        // nothing was provisioned, so there is nothing to tear down
        terminate(ps, status, None, ResourceMgr.Teardown.Terminated)
    }
    .receiveSignal { case (actorContext, signal) =>
      ps.ctx.log.info(s"${actorContext.self} (pending) received signal $signal")
      Behaviors.same
    }

  // activating status is the status where resource instance is up but awating for ready status
  private def activating(ps: Params, instSpec: JsValue): Behavior[Msg] = Behaviors
    .receiveMessage[Msg] {
      case GetResourceInstSpec(replyTo) =>
        ps.ctx.log.info(s"${ps.ctx.self} (activating) received GetResourceInstSpec($replyTo)")
        ps.resourceIO.getStatus(instSpec) match {
          case Right(Status.Activating) =>
            ps.ctx.log.info(s"${ps.ctx.self} (activating) received resource status Activating")
            ps.timers.startSingleTimer(GetResourceInstSpec(replyTo), ResourceCheckDelay)
            Behaviors.same
          case Right(Status.Running) =>
            ps.ctx.log.info(s"${ps.ctx.self} (activating) received resource status Running")
            ps.database.sync(ps.query.setRunning())
            replyTo ! ResourceMgr.ResourceInstSpecRsp(Right(ps.instanceId -> instSpec))
            running(ps, instSpec)
          case Right(sts) =>
            ps.ctx.log.info(
              s"${ps.ctx.self} (activating) received resource status $sts, shutting down"
            )
            shuttingDown(ps, instSpec, sts, Option(replyTo))
          case Left(exp) =>
            ps.ctx.log.info(s"${ps.ctx.self} (activating) received resource exception, shutting down")
            shuttingDown(ps, instSpec, Status.Failed, Option(replyTo))
        }
      case Shutdown(status) =>
        ps.ctx.log.info(s"${ps.ctx.self} (activating) received Shutdown($status)")
        shuttingDown(ps, instSpec, if (status == Status.Timeout) status else Status.Failed, None)
    }
    .receiveSignal { case (actorContext, signal) =>
      ps.ctx.log.info(s"${actorContext.self} (activating) received signal $signal")
      Behaviors.same
    }

  private def running(ps: Params, instSpec: JsValue): Behavior[Msg] = Behaviors
    .receiveMessage[Msg] {
      case GetResourceInstSpec(replyTo) =>
        ps.ctx.log.info(s"${ps.ctx.self} (running) received GetResourceInstSpec(${replyTo})")
        ps.resourceIO.getStatus(instSpec) match {
          case Right(Status.Running) =>
            replyTo ! ResourceMgr.ResourceInstSpecRsp(Right(ps.instanceId -> instSpec))
            Behaviors.same
          case Right(sts) =>
            ps.ctx.log.info(
              s"${ps.ctx.self} (running) received resource status $sts, shutting down"
            )
            shuttingDown(ps, instSpec, sts, Option(replyTo))
          case Left(exp) =>
            // getStatus failed (e.g. a transient provider error): shut down so teardown is still
            // attempted and reported, rather than parking a possibly-live resource as inactive.
            ps.ctx.log.info(
              s"${ps.ctx.self} (running) getStatus failed, shutting down to attempt teardown",
              exp
            )
            shuttingDown(ps, instSpec, Status.Failed, Option(replyTo))
        }
      case Shutdown(status) =>
        ps.ctx.log.info(s"${ps.ctx.self} (running) received Shutdown($status)")
        shuttingDown(ps, instSpec, status, None)
    }
    .receiveSignal { case (actorContext, signal) =>
      ps.ctx.log.info(s"${actorContext.self} (running) received signal $signal")
      Behaviors.same
    }

  private def shuttingDown(
    ps: Params,
    instSpec: JsValue,
    status: Status.Value,
    replyTo: Option[ActorRef[ResourceMgr.ResourceInstSpecRsp]]
  ): Behavior[Msg] = {
    // Record the real teardown outcome instead of always reporting a clean finish: on a Left the
    // resource may still be alive, and the completion carries that so the manager can surface it.
    val (teardown, errorMsg) = ps.resourceIO.terminate(instSpec) match {
      case Right(_) =>
        (ResourceMgr.Teardown.Terminated, "")
      case Left(exp) =>
        ps.ctx.log.error(
          s"Actor ${ps.ctx.self} unable to terminate resource instance; resource may still be alive",
          exp
        )
        (ResourceMgr.Teardown.TerminateFailed, exp.getMessage())
    }

    // a) Proper resource termination msg after finished status should come from ResourceMgr that does not expect reply.
    // b) Getting Finished during running as we shortcut resource Terminating to Finished status, which
    // can lead to Terminated or Terminated with errors (Failed).
    // c) resource could terminate normally by external users, and should be marked as failed.
    val failureStatus = if (replyTo.nonEmpty && status == Status.Finished) Status.Failed else status
    ps.database.sync(ps.query.setTerminated(failureStatus, errorMsg))
    terminate(ps, failureStatus, replyTo, teardown)
  }

  // We should not terminate resource instance actor as it may need to respond to pending queries
  // from activity attempts in the mailbox
  private def inactive(
    ps: Params,
    status: Status.Value,
    teardown: ResourceMgr.Teardown
  ): Behavior[Msg] = {
    // Teardown is done, so the terminate-after deadline is moot; cancel it to keep a stale
    // Shutdown(Timeout) from firing here later.
    ps.timers.cancel(Shutdown(Status.Timeout))
    Behaviors.receiveMessage {
      case GetResourceInstSpec(replyTo) =>
        ps.ctx.log.info(s"${ps.ctx.self} (inactive) received GetResourceInstSpec($replyTo)")
        ps.resourceMgr ! ResourceMgr.InactiveResourceInstance(ps.instanceId, status, teardown, replyTo)
        Behaviors.same
      case Shutdown(sts) =>
        // Teardown already happened; re-report completion so a manager parked in `terminating`
        // unblocks instead of waiting for a finish that would otherwise never arrive.
        ps.ctx.log.info(s"${ps.ctx.self} (inactive) received Shutdown($sts); re-reporting finished")
        ps.resourceMgr ! ResourceMgr.ResourceInstanceFinished(ps.instanceId, status, teardown)
        Behaviors.same
    }
  }

  private def terminate(
    ps: Params,
    status: Status.Value,
    replyTo: Option[ActorRef[ResourceMgr.ResourceInstSpecRsp]],
    teardown: ResourceMgr.Teardown
  ): Behavior[Msg] = {
    replyTo match {
      case Some(r) =>
        ps.resourceMgr ! ResourceMgr.InactiveResourceInstance(ps.instanceId, status, teardown, r)
      case None =>
        ps.resourceMgr ! ResourceMgr.ResourceInstanceFinished(ps.instanceId, status, teardown)
    }
    inactive(ps, status, teardown)
  }

}
