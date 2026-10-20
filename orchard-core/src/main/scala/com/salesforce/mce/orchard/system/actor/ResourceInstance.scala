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
    // Monotonic incarnation stamp from ResourceMgr. A crash-respawn reuses the same instanceId, so the
    // manager uses this epoch to tell this incarnation's Progressed apart from a stale one emitted by a
    // crashed predecessor. Echoed unchanged in every Progressed.
    epoch: Int,
    timers: TimerScheduler[ResourceInstance.Msg]
  )

  // When re-adopting a persisted Running row, report progress up front ONLY when the cluster is confirmed
  // still Running, so ResourceMgr's crash-respawn budget resets without waiting for an activity attempt to
  // poll this otherwise-passive instance. A non-Running status or a failed poll is not progress.
  private[actor] def readoptShouldReportProgress(status: Either[Throwable, Status.Value]): Boolean =
    status == Right(Status.Running)

  def apply(
    resourceMgr: ActorRef[ResourceMgr.Msg],
    database: OrchardDatabase,
    workflowId: String,
    resourceId: String,
    instanceId: Int,
    epoch: Int,
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
        epoch,
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
          // A fresh actor re-adopting a persisted Running row (a crash-respawn or a restart re-adoption).
          // Confirm the cluster is still Running and report it up front, instead of parking passively until
          // some activity attempt polls. Without this a respawned-but-unpolled instance never emits
          // ResourceInstanceProgressed, so the manager's consecutive-crash budget never resets and a later
          // unrelated crash can needlessly fail the resource. A non-Running/failed poll is left to the
          // on-demand poll + deadline paths.
          val startupStatus = resourceIO.getStatus(instSpec)
          if (readoptShouldReportProgress(startupStatus)) {
            resourceMgr ! ResourceMgr.ResourceInstanceProgressed(instanceId, epoch)
          } else {
            context.log.info(
              s"${context.self} re-adopted Running row; startup status poll returned $startupStatus, " +
                s"deferring to on-demand poll"
            )
          }
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
            terminate(ps, Status.Failed, Option(replyTo))
        }
      case Shutdown(status) =>
        ps.ctx.log.info(s"${ps.ctx.self} (pending) received Shutdown($status)")
        ps.database.sync(ps.query.setTerminated(status, ""))
        terminate(ps, status, None)
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
            // Confirmed cluster liveness -- let ResourceMgr clear its consecutive-crash respawn budget.
            ps.resourceMgr ! ResourceMgr.ResourceInstanceProgressed(ps.instanceId, ps.epoch)
            replyTo ! ResourceMgr.ResourceInstSpecRsp(Right(ps.instanceId -> instSpec))
            running(ps, instSpec)
          case Right(sts) =>
            ps.ctx.log.info(
              s"${ps.ctx.self} (activating) received resource status $sts, shutting down"
            )
            shuttingDown(ps, instSpec, sts, Option(replyTo))
          case Left(exp) =>
            ps.ctx.log.info(
              s"${ps.ctx.self} (activating) received resource exception, shutting down"
            )
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
            // Confirmed liveness -- and the only progress signal a respawned instance sends, since it
            // re-adopts a Running row and starts here (skipping activating). Resets the manager's budget.
            ps.resourceMgr ! ResourceMgr.ResourceInstanceProgressed(ps.instanceId, ps.epoch)
            replyTo ! ResourceMgr.ResourceInstSpecRsp(Right(ps.instanceId -> instSpec))
            Behaviors.same
          case Right(sts) =>
            ps.ctx.log.info(
              s"${ps.ctx.self} (running) received resource status $sts, shutting down"
            )
            shuttingDown(ps, instSpec, sts, Option(replyTo))
          case Left(exp) =>
            // A failed status poll (e.g. transient provider throttling) does NOT mean the cluster is gone.
            // Route through shuttingDown so we actually call resourceIO.terminate -- and, on a clean Left
            // there, hand teardown to the manager's backstop. Marking the row Failed and parking inactive
            // without terminating would orphan a live cluster.
            ps.ctx.log.info(
              s"${ps.ctx.self} (running) received resource exception, shutting down",
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
    // a) Proper resource termination msg after finished status should come from ResourceMgr that does not expect reply.
    // b) Getting Finished during running as we shortcut resource Terminating to Finished status, which
    // can lead to Terminated or Terminated with errors (Failed).
    // c) resource could terminate normally by external users, and should be marked as failed.
    val failureStatus = if (replyTo.nonEmpty && status == Status.Finished) Status.Failed else status
    ps.resourceIO.terminate(instSpec) match {
      case Right(_) =>
        ps.database.sync(ps.query.setTerminated(failureStatus, ""))
        terminate(ps, failureStatus, replyTo)
      case Left(exp) =>
        // A clean Left (not a crash): the DB row will read terminal, but the provider resource may still
        // be alive. Reporting a normal finish here would silently leak the cluster (DeathWatch only retries
        // teardown when it *throws*). Record the error, then hand the teardown back to ResourceMgr, which
        // drives a direct terminate backstop and still answers the waiting activity attempt via replyTo.
        ps.ctx.log.error(
          s"Actor ${ps.ctx.self} unable to terminate resource instance; provider may still be alive: $exp"
        )
        ps.database.sync(ps.query.setTerminated(failureStatus, exp.getMessage()))
        ps.resourceMgr ! ResourceMgr.ResourceInstanceTerminateFailed(
          ps.instanceId,
          failureStatus,
          replyTo
        )
        inactive(ps, failureStatus)
    }
  }

  // We should not terminate resource instance actor as it may need to respond to pending queries
  // from activity attempts in the mailbox
  private def inactive(ps: Params, status: Status.Value): Behavior[Msg] = {
    // The terminate-after deadline is moot once inactive (the cluster is already torn down). Cancel it so a
    // stale self-timer can't later fire Shutdown(Status.Timeout) here -- which, since inactive now replies
    // to Shutdown, would send a spurious ResourceInstanceFinished. After this cancel, the only Shutdown that
    // reaches `inactive` is a manager-initiated one to its current instance during running -> terminating.
    ps.timers.cancel(Shutdown(Status.Timeout))
    Behaviors.receiveMessage {
      case GetResourceInstSpec(replyTo) =>
        ps.ctx.log.info(s"${ps.ctx.self} (inactive) received GetResourceInstSpec($replyTo)")
        ps.resourceMgr ! ResourceMgr.InactiveResourceInstance(ps.instanceId, status, replyTo)
        Behaviors.same
      case Shutdown(sts) =>
        // The manager only sends Shutdown to its current instance (running -> terminating). If that
        // instance is already inactive, ignoring this would strand the manager in `terminating` awaiting a
        // completion signal that never comes. The cluster is already torn down, so report finished.
        ps.ctx.log.info(
          s"${ps.ctx.self} (inactive) received Shutdown($sts); reporting finished to unblock manager"
        )
        ps.resourceMgr ! ResourceMgr.ResourceInstanceFinished(status)
        Behaviors.same
    }
  }

  private def terminate(
    ps: Params,
    status: Status.Value,
    replyTo: Option[ActorRef[ResourceMgr.ResourceInstSpecRsp]]
  ): Behavior[Msg] = {
    replyTo match {
      case Some(r) =>
        ps.resourceMgr ! ResourceMgr.InactiveResourceInstance(ps.instanceId, status, r)
      case None =>
        ps.resourceMgr ! ResourceMgr.ResourceInstanceFinished(status)
    }
    inactive(ps, status)
  }

}
