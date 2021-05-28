/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.system.actor

import akka.actor.typed._
import akka.actor.typed.scaladsl._
import play.api.libs.json.{JsError, JsSuccess, JsValue}

import com.salesforce.mce.orchard.db.{OrchardDatabase, ResourceQuery}
import com.salesforce.mce.orchard.io.ResourceIO
import com.salesforce.mce.orchard.model.Status
import com.salesforce.mce.orchard.system.util.InvalidJsonException

object ResourceMgr {

  sealed trait Msg

  case class GetResourceInstSpec(replyTo: ActorRef[ResourceInstSpecRsp]) extends Msg
  case class ResourceInstSpecRsp(spec: Either[Status.Value, JsValue])
  case class ResourceInstanceFinished(status: Status.Value) extends Msg
  // the shutdown call from WorkflowMgr, ResourceMgr should never shutdown unless told by WFMgr
  case class Shutdown(status: Status.Value) extends Msg

  case class Params(
    ctx: ActorContext[ResourceMgr.Msg],
    database: OrchardDatabase,
    resourceQuery: ResourceQuery,
    workflowMgr: ActorRef[WorkflowMgr.Msg],
    workflowId: String,
    resourceId: String,
    maxAttempt: Int,
    rscType: String,
    rscSpec: JsValue
  )

  def apply(
    database: OrchardDatabase,
    workflowMgr: ActorRef[WorkflowMgr.Msg],
    workflowId: String,
    resourceId: String
  ): Behavior[Msg] = Behaviors.setup { ctx =>
    ctx.log.info(s"Starting ResourceMgr ${ctx.self}")

    val resourceQuery = new ResourceQuery(workflowId, resourceId)
    val resourceR = database.sync(resourceQuery.get()).get
    val ps = Params(
      ctx,
      database,
      resourceQuery,
      workflowMgr,
      workflowId,
      resourceId,
      resourceR.maxAttempt,
      resourceR.resourceType,
      resourceR.resourceSpec
    )

    resourceR.status match {
      case Status.Pending =>
        idle(ps, resourceR.resourceType, resourceR.resourceSpec)

      case Status.Running =>
        val resourceInsts = database.sync(resourceQuery.instances())
        val lastInstOpt = resourceInsts.sortBy(_.instanceAttempt)(Ordering[Int].reverse).headOption
        val instIdEith = lastInstOpt match {
          case Some(lastInst) =>
            if (
              !Status.isAlive(lastInst.status) && lastInst.instanceAttempt < resourceR.maxAttempt
            ) {
              Right(lastInst.instanceAttempt + 1)
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
        } yield running(ps, rscInst, instId)

        result.left.map { sts =>
          database.sync(resourceQuery.setTerminated(sts))
          finished(ps, sts)
        }.merge

      case sts => finished(ps, sts)
    }

  }

  def idle(
    ps: Params,
    resourceType: String,
    resourceSpec: JsValue
  ): Behavior[Msg] = Behaviors.receiveMessage {

    case GetResourceInstSpec(replyTo) =>
      ps.database.sync(ps.resourceQuery.setRun())
      val instId = 1
      spawnResourceInstance(ps.ctx, ps.database, ps, instId) match {
        case Left(sts) =>
          ps.database.sync(ps.resourceQuery.setTerminated(sts))
          replyTo ! ResourceInstSpecRsp(Left(sts))
          finished(ps, sts)
        case Right(rscInst) =>
          rscInst ! ResourceInstance.GetResourceInstSpec(replyTo)
          running(ps, rscInst, instId)
      }

    case ResourceInstanceFinished(status) =>
      ps.ctx.log.error(
        s"Unexpected message ResourceInstanceFinished($status) recieved in idle state"
      )
      Behaviors.stopped

    case Shutdown(status) =>
      ps.database.sync(ps.resourceQuery.setTerminated(status))
      terminate(ps, status)

  }

  def running(
    ps: Params,
    resourceInst: ActorRef[ResourceInstance.Msg],
    instId: Int
  ): Behavior[Msg] = Behaviors.receiveMessage {
    case GetResourceInstSpec(replyTo) =>
      resourceInst ! ResourceInstance.GetResourceInstSpec(replyTo)
      Behaviors.same
    case ResourceInstanceFinished(Status.Finished) =>
      ps.database.sync(ps.resourceQuery.setTerminated(Status.Finished))
      finished(ps, Status.Finished)
    case ResourceInstanceFinished(failureStatus) =>
      if (instId >= ps.maxAttempt) {
        ps.database.sync(ps.resourceQuery.setTerminated(failureStatus))
        finished(ps, failureStatus)
      } else {
        val newInstId = instId + 1
        spawnResourceInstance(ps.ctx, ps.database, ps, newInstId) match {
          case Left(sts) =>
            ps.database.sync(ps.resourceQuery.setTerminated(sts))
            finished(ps, sts)
          case Right(rscInst) =>
            running(ps, rscInst, newInstId)
        }
      }
    case Shutdown(status) =>
      resourceInst ! ResourceInstance.Shutdown(status)
      terminating(ps.ctx, ps, status)
  }

  def finished(
    ps: Params,
    status: Status.Value
  ): Behavior[Msg] = Behaviors.receiveMessage {
    case GetResourceInstSpec(replyTo) =>
      replyTo ! ResourceInstSpecRsp(Left(status))
      Behaviors.same
    case ResourceInstanceFinished(sts) =>
      ps.ctx.log.error(s"Getting instance finished status ($sts) in finished state")
      Behaviors.same
    case Shutdown(_) =>
      terminate(ps, status)
  }

  def terminating(
    ctx: ActorContext[ResourceMgr.Msg],
    ps: Params,
    status: Status.Value
  ): Behavior[Msg] = Behaviors.receiveMessage {
    case GetResourceInstSpec(replyTo) =>
      replyTo ! ResourceInstSpecRsp(Left(status))
      Behaviors.same
    case ResourceInstanceFinished(_) =>
      ps.database.sync(ps.resourceQuery.setTerminated(status))
      terminate(ps, status)
    case Shutdown(_) =>
      Behaviors.same
  }

  def terminate(
    ps: Params,
    status: Status.Value
  ): Behavior[Msg] = {
    ps.ctx.log.info(s"ResourceMgr ${ps.workflowId}-rsc-${ps.resourceId} stopped")
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
      ResourceIO.Conf(ps.workflowId, ps.resourceId, instId, ps.rscType, ps.rscSpec)
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
            resourceIO
          ),
          s"${ps.workflowId}-rsc-${ps.resourceId}-inst-$instId"
        )
        Right(rscInst)
      case JsError(errors) =>
        ctx.log.error("Invalid resource spec", InvalidJsonException.raise(errors))
        Left(Status.Failed)
    }
  }

}
