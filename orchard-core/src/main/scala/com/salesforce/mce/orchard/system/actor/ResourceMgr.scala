/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.system.actor

import scala.concurrent.duration._

import org.apache.pekko.actor.typed._
import org.apache.pekko.actor.typed.scaladsl._
import play.api.libs.json.{JsError, JsSuccess, JsValue}

import com.salesforce.mce.orchard.OrchardSettings
import com.salesforce.mce.orchard.db.{OrchardDatabase, ResourceInstanceQuery, ResourceQuery}
import com.salesforce.mce.orchard.io.ResourceIO
import com.salesforce.mce.orchard.model.Status
import com.salesforce.mce.orchard.system.util.InvalidJsonException

object ResourceMgr {

  sealed trait Msg

  case class GetResourceInstSpec(replyTo: ActorRef[ResourceInstSpecRsp]) extends Msg
  case class CreateResourceInst(replyTo: ActorRef[ResourceInstSpecRsp], instId: Int) extends Msg
  case class ResourceInstSpecRsp(spec: Either[Status.Value, (Int, JsValue)])
  case class ResourceInstanceFinished(status: Status.Value) extends Msg
  case class InactiveResourceInstance(
    instanceId: Int,
    status: Status.Value,
    replyTo: ActorRef[ResourceInstSpecRsp]
  ) extends Msg
  // the shutdown call from WorkflowMgr, ResourceMgr should never shutdown unless told by WFMgr
  case class Shutdown(status: Status.Value) extends Msg
  // DeathWatch signal: the watched ResourceInstance child terminated. Because ResourceInstance never
  // stops itself on its happy path (it parks in `inactive` even after terminate), this only fires on
  // an uncaught crash, so it is an unambiguous "instance lost" indicator.
  case class ResourceInstanceDied(instId: Int) extends Msg

  case class Params(
    ctx: ActorContext[ResourceMgr.Msg],
    database: OrchardDatabase,
    resourceQuery: ResourceQuery,
    workflowMgr: ActorRef[WorkflowMgr.Msg],
    workflowId: String,
    resourceId: String,
    resourceName: String,
    maxAttempt: Int,
    rscType: String,
    rscSpec: JsValue,
    terminateAfter: FiniteDuration,
    timers: TimerScheduler[ResourceMgr.Msg]
  )

  // Bound on consecutive crash-respawns (running) and failed-termination retries (terminating) before
  // giving up, so a persistent crash loop fails the resource rather than spinning forever. In `running`
  // the counter resets whenever the instance successfully answers a spec query (a liveness signal).
  val MaxRespawnAttempts = 3

  // What to do when a watched ResourceInstance crashes, decided purely from its last persisted status.
  sealed trait DeathAction
  object DeathAction {
    // Either the spec is already persisted (Activating/Running → re-adopt the SAME cluster) or no row
    // exists yet (None → create() never ran, so a fresh start is duplicate-safe). No create() re-entry.
    case object Respawn extends DeathAction
    // Crashed inside the pending/create window (row persisted as Pending, spec not) → respawning risks
    // a duplicate cluster because create() is non-idempotent, so fail the resource instead (§7a).
    case object Fail extends DeathAction
    // Already terminal → nothing to recreate.
    case object Ignore extends DeathAction
  }

  private[actor] def deathActionFor(status: Option[Status.Value]): DeathAction = status match {
    case Some(Status.Activating) | Some(Status.Running) => DeathAction.Respawn
    // No persisted row means the child died before inserting it, i.e. before create() ran, so a
    // bounded fresh respawn cannot leak/duplicate a cluster.
    case None => DeathAction.Respawn
    case Some(Status.Pending) => DeathAction.Fail
    case _ => DeathAction.Ignore
  }

  def apply(
    orchardSettings: OrchardSettings,
    database: OrchardDatabase,
    workflowMgr: ActorRef[WorkflowMgr.Msg],
    workflowId: String,
    resourceId: String
  ): Behavior[Msg] = Behaviors.setup { ctx =>
    Behaviors.withTimers { timers =>
      ctx.log.info(s"Starting ResourceMgr ${ctx.self}")

      val resourceQuery = new ResourceQuery(workflowId, resourceId)
      val resourceR = database.sync(resourceQuery.get()).get

      // here we make all invalid input to default 8 hours, the input should do validation before
      // saving them to DB
      val terminateAfterDuration: FiniteDuration =
        try {
          (resourceR.terminateAfter * 1.hour).asInstanceOf[FiniteDuration]
        } catch {
          case e: Exception => 8.hour
        }
      val ps = Params(
        ctx,
        database,
        resourceQuery,
        workflowMgr,
        workflowId,
        resourceId,
        resourceR.name,
        resourceR.maxAttempt,
        resourceR.resourceType,
        resourceR.resourceSpec,
        terminateAfterDuration,
        timers
      )

      resourceR.status match {
        case Status.Pending =>
          idle(ps, orchardSettings.resourceReattemptDelayPolicy.delay())

        case Status.Running =>
          val resourceInsts = database.sync(resourceQuery.instances())
          val lastInstOpt =
            resourceInsts.sortBy(_.instanceAttempt)(Ordering[Int].reverse).headOption
          val instIdEith = lastInstOpt match {
            case Some(lastInst) =>
              if (
                !Status.isAlive(lastInst.status) && lastInst.instanceAttempt < resourceR.maxAttempt
              ) {
                Right(lastInst.instanceAttempt + 1)
              } else if (
                lastInst.status == Status.Activating || lastInst.status == Status.Running || lastInst.status == Status.Pending
              ) {
                Right(lastInst.instanceAttempt)
              } else {
                Left(lastInst.status)
              }
            case None =>
              Right(1)
          }

          val result = for {
            instId <- instIdEith
            rscInst <- spawnResourceInstance(
              ctx,
              database,
              ps,
              instId
            )
          } yield running(ps, orchardSettings.resourceReattemptDelayPolicy.delay(), rscInst, instId)

          result.left.map { sts =>
            database.sync(resourceQuery.setTerminated(sts))
            finished(ps, sts)
          }.merge

        case sts => finished(ps, sts)
      }
    }
  }

  def idle(
    ps: Params,
    resourceAttemptDelay: FiniteDuration
  ): Behavior[Msg] = Behaviors
    .receiveMessage[Msg] {

      case GetResourceInstSpec(replyTo) =>
        ps.ctx.log.info(s"${ps.ctx.self} (idle) received GetResourceInstSpec($replyTo)")
        ps.database.sync(ps.resourceQuery.setRun())
        val instId = 1
        spawnResourceInstance(ps.ctx, ps.database, ps, instId) match {
          case Left(sts) =>
            ps.database.sync(ps.resourceQuery.setTerminated(sts))
            replyTo ! ResourceInstSpecRsp(Left(sts))
            finished(ps, sts)
          case Right(rscInst) =>
            rscInst ! ResourceInstance.GetResourceInstSpec(replyTo)
            running(ps, resourceAttemptDelay, rscInst, instId)
        }

      case CreateResourceInst(replyTo, instId) =>
        ps.ctx.log.error(
          s"${ps.ctx.self} (idle) received UNEXPECTED CreateResourceInst($replyTo, $instId)"
        )
        Behaviors.unhandled

      // no resource instance should exist yet, this is unexpected
      case msg: ResourceInstanceFinished =>
        ps.ctx.log.error(s"${ps.ctx.self} (idle) received UNEXPECTED $msg")
        Behaviors.unhandled

      // no resource instance should exist yet, this is unexpected
      case msg: InactiveResourceInstance =>
        ps.ctx.log.error(s"${ps.ctx.self} (idle) received UNEXPECTED $msg")
        Behaviors.unhandled

      // no instance has been spawned/watched yet, so this is unexpected
      case msg: ResourceInstanceDied =>
        ps.ctx.log.error(s"${ps.ctx.self} (idle) received UNEXPECTED $msg")
        Behaviors.unhandled

      case Shutdown(status) =>
        ps.ctx.log.info(s"${ps.ctx.self} (idle) received Shutdown($status)")
        ps.database.sync(ps.resourceQuery.setTerminated(status))
        terminate(ps, status)

    }
    .receiveSignal { case (actorContext, signal) =>
      ps.ctx.log.info(s"${actorContext.self} (idle) received signal $signal")
      Behaviors.same
    }

  def running(
    ps: Params,
    resourceAttemptDelay: FiniteDuration,
    resourceInst: ActorRef[ResourceInstance.Msg],
    currentInstId: Int,
    respawnAttempts: Int = 0
  ): Behavior[Msg] = Behaviors
    .receiveMessage[Msg] {
      case GetResourceInstSpec(replyTo) =>
        ps.ctx.log.info(s"${ps.ctx.self} (running) received GetResourceInstSpec($replyTo)")
        resourceInst ! ResourceInstance.GetResourceInstSpec(replyTo)
        // A responsive instance clears the consecutive-crash budget: only back-to-back deaths with no
        // successful spec query in between count towards MaxRespawnAttempts.
        if (respawnAttempts == 0) Behaviors.same
        else running(ps, resourceAttemptDelay, resourceInst, currentInstId, 0)
      case CreateResourceInst(replyTo, instId) =>
        ps.ctx.log.error(
          s"${ps.ctx.self} (running) received UNEXPECTED CreateResourceInst($replyTo, $instId)"
        )
        Behaviors.unhandled
      case ResourceInstanceFinished(Status.Timeout) =>
        ps.ctx.log.info(s"${ps.ctx.self} (running) received ResourceInstanceFinished(Timeout)")
        ps.database.sync(ps.resourceQuery.setTerminated(Status.Timeout))
        finished(ps, Status.Timeout)
      // resource should not receive finished status during "running" state other than Timeout
      case ResourceInstanceFinished(status) =>
        ps.ctx.log.error(
          s"${ps.ctx.self} (running) received UNEXPECTED ResourceInstanceFinished($status, None)"
        )
        Behaviors.unhandled
      case InactiveResourceInstance(instId, status, replyTo) =>
        ps.ctx.log.info(
          s"${ps.ctx.self} (running) received InactiveResourceInstance($instId, $status, $replyTo)"
        )
        // maybe the current instance is already a new one
        if (instId < currentInstId) {
          ps.ctx.self ! GetResourceInstSpec(replyTo)
          Behaviors.same
        } else if (currentInstId >= ps.maxAttempt) {
          ps.database.sync(ps.resourceQuery.setTerminated(status))
          replyTo ! ResourceInstSpecRsp(Left(status))
          finished(ps, status)
        } else {
          val newInstId = currentInstId + 1
          // create a new instance upon failure and delegate the response to the new instance
          ps.timers.startSingleTimer(CreateResourceInst(replyTo, newInstId), resourceAttemptDelay)
          waiting(ps, resourceAttemptDelay)
        }
      case ResourceInstanceDied(instId) if instId == currentInstId =>
        ps.ctx.log.warn(
          s"${ps.ctx.self} (running) ResourceInstance inst-$instId died unexpectedly (crash); " +
            s"re-reading persisted status to decide on respawn"
        )
        val instStatus = ps.database
          .sync(new ResourceInstanceQuery(ps.workflowId, ps.resourceId, instId).get())
          .map(_.status)
        deathActionFor(instStatus) match {
          case DeathAction.Respawn if respawnAttempts >= MaxRespawnAttempts =>
            ps.ctx.log.error(
              s"${ps.ctx.self} (running) inst-$instId crashed $respawnAttempts times without recovering; " +
                s"giving up and failing resource"
            )
            ps.database.sync(ps.resourceQuery.setTerminated(Status.Failed))
            finished(ps, Status.Failed)
          case DeathAction.Respawn =>
            // Activating/Running → a fresh incarnation re-reads the persisted spec in
            // ResourceInstance.apply, re-attaches to the SAME cluster, and re-arms the terminate-after
            // timers (no create() re-entry). None → no row was persisted, so create() never ran and a
            // fresh Pending start is duplicate-safe. Bounded by MaxRespawnAttempts (reset on liveness).
            //
            // FOLLOW-UP: the replacement actor is passive until the next GetResourceInstSpec (driven by
            // an activity attempt) arrives — it does not resume status polling on its own, so a resource
            // whose activities have all completed could sit re-adopted-but-idle until the deadline. The
            // CheckTimeout backstop still bounds it, but making ResourceInstance self-poll on (re)start
            // would close the gap.
            spawnResourceInstance(ps.ctx, ps.database, ps, instId) match {
              case Right(rscInst) =>
                running(ps, resourceAttemptDelay, rscInst, instId, respawnAttempts + 1)
              case Left(sts) =>
                ps.database.sync(ps.resourceQuery.setTerminated(sts))
                finished(ps, sts)
            }
          case DeathAction.Fail =>
            // Pending = crashed inside the non-idempotent create() window. Because provider create()
            // has no client token and the spec (cluster id) is persisted only AFTER the cluster exists,
            // we cannot tell here whether a cluster was actually provisioned, so we fail rather than
            // respawn (a respawn could provision a second one). Residual: a cluster created in that
            // window but never recorded is orphaned. Recovering it durably needs create-intent to be
            // persisted BEFORE the call plus reconcile-by-tag adoption on restart — out of scope for
            // this in-process watch fix; tracked separately as the durable remedy.
            ps.ctx.log.error(
              s"${ps.ctx.self} (running) inst-$instId died before its spec was persisted; " +
                s"failing resource rather than risk a duplicate cluster"
            )
            ps.database.sync(ps.resourceQuery.setTerminated(Status.Failed))
            finished(ps, Status.Failed)
          case DeathAction.Ignore =>
            Behaviors.same
        }
      case ResourceInstanceDied(instId) =>
        ps.ctx.log.info(
          s"${ps.ctx.self} (running) ignoring stale ResourceInstanceDied($instId); current=$currentInstId"
        )
        Behaviors.same
      case Shutdown(status) =>
        ps.ctx.log.info(s"${ps.ctx.self} (running) received Shutdown($status)")
        resourceInst ! ResourceInstance.Shutdown(status)
        terminating(ps.ctx, ps, status)
    }
    .receiveSignal { case (actorContext, signal) =>
      ps.ctx.log.info(s"${actorContext.self} (running) received signal $signal")
      Behaviors.same
    }

  def waiting(
    ps: Params,
    resourceAttemptDelay: FiniteDuration
  ): Behavior[Msg] = Behaviors
    .receiveMessage[Msg] {
      case GetResourceInstSpec(replyTo) =>
        ps.ctx.log.info(s"${ps.ctx.self} (waiting) received GetResourceInstSpec($replyTo)")
        ps.timers.startSingleTimer(GetResourceInstSpec(replyTo), 10.seconds)
        Behaviors.same
      case CreateResourceInst(replyTo, instId) =>
        ps.ctx.log.info(s"${ps.ctx.self} (waiting) received CreateResourceInst($replyTo, $instId)")
        spawnResourceInstance(ps.ctx, ps.database, ps, instId) match {
          case Left(sts) =>
            ps.database.sync(ps.resourceQuery.setTerminated(sts))
            replyTo ! ResourceInstSpecRsp(Left(sts))
            finished(ps, sts)
          case Right(rscInst) =>
            ps.ctx.self ! GetResourceInstSpec(replyTo)
            running(ps, resourceAttemptDelay, rscInst, instId)
        }
      case ResourceInstanceFinished(status) =>
        ps.ctx.log.error(
          s"${ps.ctx.self} (waiting) received UNEXPECTED ResourceInstanceFinished($status)"
        )
        Behaviors.unhandled
      case InactiveResourceInstance(instId, status, replyTo) =>
        ps.ctx.log.info(
          s"${ps.ctx.self} (waiting) received InactiveResourceInstance($instId, $status, $replyTo)"
        )
        ps.timers.startSingleTimer(GetResourceInstSpec(replyTo), 10.seconds)
        Behaviors.same
      case ResourceInstanceDied(instId) =>
        // A superseded instance died between attempts; the new attempt is already scheduled.
        ps.ctx.log.info(s"${ps.ctx.self} (waiting) ignoring ResourceInstanceDied($instId)")
        Behaviors.same
      case Shutdown(status) =>
        ps.ctx.log.info(s"${ps.ctx.self} (waiting) received Shutdown($status)")
        terminating(ps.ctx, ps, status)
    }
    .receiveSignal { case (actorContext, signal) =>
      ps.ctx.log.info(s"${actorContext.self} (waiting) received signal $signal")
      Behaviors.same
    }

  // finished status is when resource instance creation failed and cannot or should not be retried,
  // we set resource manager in a state that it can still handle incoming calls, but won't create
  // new instance anymore
  def finished(
    ps: Params,
    status: Status.Value
  ): Behavior[Msg] = Behaviors
    .receiveMessage[Msg] {
      case GetResourceInstSpec(replyTo) =>
        ps.ctx.log.info(s"${ps.ctx.self} (finished) received GetResourceInstSpec($replyTo)")
        replyTo ! ResourceInstSpecRsp(Left(status))
        Behaviors.same
      case CreateResourceInst(replyTo, instId) =>
        ps.ctx.log.error(
          s"${ps.ctx.self} (finished) received UNEXPECTED CreateResourceInst($replyTo, $instId)"
        )
        Behaviors.same
      case ResourceInstanceFinished(sts) =>
        ps.ctx.log.error(
          s"${ps.ctx.self} (finished) received UNEXPECTED ResourceInstanceFinished($sts)"
        )
        Behaviors.same
      case InactiveResourceInstance(instanceId, sts, replyTo) =>
        ps.ctx.log.info(
          s"${ps.ctx.self} (finished) received InactiveResourceInstance($instanceId, $sts, $replyTo)"
        )
        ps.ctx.self ! GetResourceInstSpec(replyTo)
        Behaviors.same
      case ResourceInstanceDied(instId) =>
        // Resource already finished; an inactive instance dying afterwards is inconsequential.
        ps.ctx.log.info(s"${ps.ctx.self} (finished) ignoring ResourceInstanceDied($instId)")
        Behaviors.same
      case Shutdown(_) =>
        ps.ctx.log.info(s"${ps.ctx.self} (finished) received Shutdown(_)")
        terminate(ps, status)
    }
    .receiveSignal { case (actorContext, signal) =>
      ps.ctx.log.info(s"${actorContext.self} (finished) received signal $signal")
      Behaviors.same
    }

  def terminating(
    ctx: ActorContext[ResourceMgr.Msg],
    ps: Params,
    status: Status.Value,
    attempts: Int = 0
  ): Behavior[Msg] = Behaviors
    .receiveMessage[Msg] {
      case GetResourceInstSpec(replyTo) =>
        ps.ctx.log.info(s"${ps.ctx.self} (terminating) received GetResourceInstSpec(${replyTo})")
        replyTo ! ResourceInstSpecRsp(Left(status))
        Behaviors.same
      case CreateResourceInst(replyTo, instId) =>
        ps.ctx.log.info(
          s"${ps.ctx.self} (terminating) received CreateResourceInst($replyTo, $instId)"
        )
        Behaviors.same
      case ResourceInstanceFinished(sts) =>
        ps.ctx.log.info(s"${ps.ctx.self} (terminating) received ResourceInstanceFinished($sts)")
        ps.database.sync(ps.resourceQuery.setTerminated(status))
        terminate(ps, status)
      case InactiveResourceInstance(instId, sts, replyTo) =>
        ps.ctx.log.info(
          s"${ps.ctx.self} (terminating) received InactiveResourceInstance($instId, $sts, $replyTo)"
        )
        ctx.self ! GetResourceInstSpec(replyTo)
        Behaviors.same
      case ResourceInstanceDied(instId) =>
        // ResourceInstance never stops on its happy path (it parks in `inactive` after terminate), so a
        // death here means shutdown itself threw (e.g. resourceIO.terminate exhausted its retries) and
        // no ResourceInstanceFinished will ever arrive. Ignoring it would hang this manager — and, via
        // WorkflowMgr, the whole workflow — forever with a possibly-live cluster. Instead decide from
        // the persisted status: re-spawn and re-issue Shutdown to retry termination while the row is
        // still non-terminal (bounded), otherwise notify WorkflowMgr so the workflow unblocks.
        val instStatus = ps.database
          .sync(new ResourceInstanceQuery(ps.workflowId, ps.resourceId, instId).get())
          .map(_.status)
        deathActionFor(instStatus) match {
          case DeathAction.Respawn if attempts < MaxRespawnAttempts =>
            ps.ctx.log.warn(
              s"${ps.ctx.self} (terminating) inst-$instId died before termination completed; " +
                s"re-spawning to retry teardown (attempt ${attempts + 1})"
            )
            spawnResourceInstance(ps.ctx, ps.database, ps, instId) match {
              case Right(rscInst) =>
                rscInst ! ResourceInstance.Shutdown(status)
                terminating(ps.ctx, ps, status, attempts + 1)
              case Left(_) =>
                // Can't even re-spawn (bad spec); stop hanging and unblock the workflow.
                ps.database.sync(ps.resourceQuery.setTerminated(status))
                terminate(ps, status)
            }
          case _ =>
            // Retries exhausted, the row already reached a terminal status (teardown effectively done),
            // or it never got a spec (Pending/None → nothing safe to re-drive here). Record the status
            // and notify WorkflowMgr so the workflow unblocks. A still-live cluster may leak — accepted,
            // the same residual class as §7a; the durable reconcile remedy (a) is what closes it.
            ps.ctx.log.warn(
              s"${ps.ctx.self} (terminating) inst-$instId died during shutdown (status=$instStatus, " +
                s"attempts=$attempts); notifying WorkflowMgr without a clean terminate"
            )
            ps.database.sync(ps.resourceQuery.setTerminated(status))
            terminate(ps, status)
        }
      case Shutdown(_) =>
        Behaviors.same
    }
    .receiveSignal { case (actorContext, signal) =>
      ps.ctx.log.info(s"${actorContext.self} (terminating) received signal $signal")
      Behaviors.same
    }

  def terminate(
    ps: Params,
    status: Status.Value
  ): Behavior[Msg] = {
    ps.ctx.log.info(s"ResourceMgr ${ps.ctx.self} stopped")
    ps.workflowMgr ! WorkflowMgr.ResourceTerminated(ps.resourceId, status)
    Behaviors.stopped
  }

  def spawnResourceInstance(
    ctx: ActorContext[ResourceMgr.Msg],
    database: OrchardDatabase,
    ps: Params,
    instId: Int
  ): Either[Status.Value, ActorRef[ResourceInstance.Msg]] = {
    val rscIOResult = ResourceIO(
      ResourceIO.Conf(
        ps.workflowId,
        ps.resourceId,
        ps.resourceName,
        instId,
        ps.maxAttempt,
        ps.rscType,
        ps.rscSpec
      )
    )

    rscIOResult match {
      case JsSuccess(resourceIO, _) =>
        val rscInst = ctx.spawn(
          ResourceInstance(
            ctx.self,
            database,
            ps.workflowId,
            ps.resourceId,
            instId,
            resourceIO,
            ps.terminateAfter
          ),
          s"inst-$instId"
        )
        // Watch the child so an uncaught exception (pekko-typed's silent default is to stop it) is
        // surfaced as ResourceInstanceDied instead of leaving this manager holding a stale ActorRef.
        // Mirrors WorkflowMgr's ctx.watchWith(actionMgr, ActionCompleted).
        ctx.watchWith(rscInst, ResourceInstanceDied(instId))
        Right(rscInst)
      case JsError(errors) =>
        ctx.log.error("Invalid resource spec", InvalidJsonException.raise(errors))
        Left(Status.Failed)
    }
  }

}
