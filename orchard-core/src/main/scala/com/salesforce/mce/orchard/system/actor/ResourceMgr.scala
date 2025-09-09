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
import com.salesforce.mce.orchard.db.{OrchardDatabase, ResourceQuery}
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
    currentInstId: Int
  ): Behavior[Msg] = Behaviors
    .receiveMessage[Msg] {
      case GetResourceInstSpec(replyTo) =>
        ps.ctx.log.info(s"${ps.ctx.self} (running) received GetResourceInstSpec($replyTo)")
        resourceInst ! ResourceInstance.GetResourceInstSpec(replyTo)
        Behaviors.same
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
    status: Status.Value
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
        Right(rscInst)
      case JsError(errors) =>
        ctx.log.error("Invalid resource spec", InvalidJsonException.raise(errors))
        Left(Status.Failed)
    }
  }

}
