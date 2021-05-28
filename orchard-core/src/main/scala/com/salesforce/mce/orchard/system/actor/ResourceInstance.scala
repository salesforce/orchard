/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.system.actor

import scala.concurrent.duration._

import akka.actor.typed._
import akka.actor.typed.scaladsl._
import play.api.libs.json.JsValue

import com.salesforce.mce.orchard.db.{OrchardDatabase, ResourceInstanceQuery}
import com.salesforce.mce.orchard.io.ResourceIO
import com.salesforce.mce.orchard.model.Status

object ResourceInstance {

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
    timers: TimerScheduler[ResourceInstance.Msg]
  )

  def apply(
    resourceMgr: ActorRef[ResourceMgr.Msg],
    database: OrchardDatabase,
    workflowId: String,
    resourceId: String,
    instanceId: Int,
    resourceIO: ResourceIO
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
        timers
      )

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

  private def pending(ps: Params): Behavior[Msg] = Behaviors.receiveMessage {
    case GetResourceInstSpec(replyTo) =>
      ps.database.sync(ps.query.setActivated())
      ps.resourceIO.create() match {
        case Right(instSpec) =>
          ps.database.sync(ps.query.setSpec(instSpec))
          ps.timers.startSingleTimer(GetResourceInstSpec(replyTo), ResourceCheckDelay)
          activating(ps, instSpec)
        case Left(exp) =>
          ps.ctx.log.error(s"Actor ${ps.ctx} Exception when creating resource", exp)
          ps.database.sync(ps.query.setTerminated(Status.Failed, exp.getMessage()))
          replyTo ! ResourceMgr.ResourceInstSpecRsp(Left(Status.Pending))
          terminate(ps.resourceMgr, Status.Failed)
      }
    case Shutdown(status) =>
      ps.database.sync(ps.query.setTerminated(status, ""))
      terminate(ps.resourceMgr, status)
  }

  private def activating(ps: Params, instSpec: JsValue): Behavior[Msg] = Behaviors.receiveMessage {
    case GetResourceInstSpec(replyTo) =>
      ps.resourceIO.getStatus(instSpec) match {
        case Right(Status.Activating) =>
          ps.ctx.log.info(s"${ps.ctx} Resource status Activating...")
          ps.timers.startSingleTimer(GetResourceInstSpec(replyTo), ResourceCheckDelay)
          Behaviors.same
        case Right(Status.Running) =>
          ps.database.sync(ps.query.setRunning())
          replyTo ! ResourceMgr.ResourceInstSpecRsp(Right(instSpec))
          running(ps, instSpec)
        case Right(sts) =>
          ps.ctx.log.info(s"${ps.ctx} Received resource status $sts, shutting down")
          replyTo ! ResourceMgr.ResourceInstSpecRsp(Left(sts))
          shuttindDown(ps, instSpec, sts)
        case Left(exp) =>
          ps.ctx.log.info(s"${ps.ctx} Received resource exception, shutting down")
          replyTo ! ResourceMgr.ResourceInstSpecRsp(Left(Status.Failed))
          shuttindDown(ps, instSpec, Status.Failed)
      }
    case Shutdown(status) =>
      shuttindDown(ps, instSpec, Status.Failed)
  }

  private def running(ps: Params, instSpec: JsValue): Behavior[Msg] = Behaviors.receiveMessage {
    case GetResourceInstSpec(replyTo) =>
      ps.resourceIO.getStatus(instSpec) match {
        case Right(Status.Running) =>
          replyTo ! ResourceMgr.ResourceInstSpecRsp(Right(instSpec))
          Behaviors.same
        case sts =>
          ps.ctx.log.error(s"Unexpected resource status $sts")
          ps.database.sync(ps.query.setTerminated(Status.Failed, ""))
          replyTo ! ResourceMgr.ResourceInstSpecRsp(Left(Status.Failed))
          terminate(ps.resourceMgr, Status.Failed)
      }
    case Shutdown(status) =>
      shuttindDown(ps, instSpec, status)
  }

  private def shuttindDown(ps: Params, instSpec: JsValue, status: Status.Value): Behavior[Msg] = {
    val errorMsg = ps.resourceIO.terminate(instSpec) match {
      case Right(sts) =>
        ""
      case Left(exp) =>
        ps.ctx.log.error(
          s"Actor ${ps.ctx.self} Unable to terminate resource instance with exception ${exp}"
        )
        exp.getMessage()
    }
    ps.database.sync(ps.query.setTerminated(status, errorMsg))
    terminate(ps.resourceMgr, status)
  }

  private def terminate(
    resourceMgr: ActorRef[ResourceMgr.Msg],
    status: Status.Value
  ): Behavior[Msg] = {
    resourceMgr ! ResourceMgr.ResourceInstanceFinished(status)
    Behaviors.stopped
  }

}
