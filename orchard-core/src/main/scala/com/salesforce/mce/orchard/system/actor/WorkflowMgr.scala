/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.system.actor

import akka.actor.typed._
import akka.actor.typed.scaladsl._
import com.salesforce.mce.orchard.db.{OrchardDatabase, WorkflowQuery}
import com.salesforce.mce.orchard.graph.{AdjacencyList, Graph}
import com.salesforce.mce.orchard.model.Status

import scala.concurrent.duration.FiniteDuration

object WorkflowMgr {

  sealed trait Msg

  case class ActivityCompleted(activityId: String, status: Status.Value) extends Msg
  case class ResourceTerminated(resourceId: String, status: Status.Value) extends Msg
  case object ActionCompleted extends Msg
  case object CancelWorkflow extends Msg

  case class Params(
    ctx: ActorContext[Msg],
    database: OrchardDatabase,
    query: WorkflowQuery,
    workflowId: String,
    status: Status.Value,
    // for keeping track of activity dependencies
    activityGraph: Graph[String],
    // which activity depends on which resource
    activityResources: Map[String, String],
    // keeping track of resource and their associated manager actors
    resourceMgrs: Map[String, ActorRef[ResourceMgr.Msg]],
    // keeping track of activity and their associated manager actors
    activityMgrs: Map[String, ActorRef[ActivityMgr.Msg]],
    actionMgr: Option[ActorRef[ActionMgr.Msg]]
  )

  def apply(database: OrchardDatabase, workflowId: String, checkProgressDelay: FiniteDuration): Behavior[Msg] = Behaviors.setup { ctx =>
    val query = new WorkflowQuery(workflowId)

    val workflow = database.sync(query.get()).get
    val activities = database.sync(query.activities())
    val dependencies = database.sync(query.dependencies())

    val initialVertices = activities.foldLeft(new AdjacencyList[String]()) { (g, v) =>
      g.addVertex(v.activityId)
    }

    val graph = dependencies.foldLeft(initialVertices) { (g, d) =>
      g.addEdge(d.activityId, d.dependentId)
    }

    val activityResources = activities.map(a => a.activityId -> a.resourceId).toMap

    val resourceMgrs = activityResources.values.toSet.map { rscId: String =>
      rscId -> ctx.spawn(
        ResourceMgr(database, ctx.self, workflowId, rscId),
        s"rsc-$rscId"
      )
    }.toMap

    val actionMgr = ctx.spawn(ActionMgr(database, ctx.self, workflowId), "acn-mgr")
    ctx.watchWith(actionMgr, ActionCompleted)

    val ps = Params(
      ctx,
      database,
      query,
      workflowId,
      workflow.status,
      graph,
      activityResources,
      resourceMgrs,
      Map.empty,
      Option(actionMgr)
    )
    val newState = scheduleNextActivities(ctx, database, ps, Status.Finished, checkProgressDelay)

    ctx.log.info(s"WorkflowMgr ${ctx.self} actor $workflowId started")
    active(ctx, database, checkProgressDelay, newState)
  }

  def active(
    ctx: ActorContext[Msg],
    database: OrchardDatabase,
    checkProgressDelay: FiniteDuration,
    ps: Params
  ): Behavior[Msg] = Behaviors.receiveMessage[Msg] {

    case ActivityCompleted(activityId, actStatus) =>
      ctx.log.info(s"${ctx.self} (active) recieved ActivityCompleted($activityId, $actStatus)")

      // trigger the necessary action
      ps.actionMgr.foreach(acnMgr => acnMgr ! ActionMgr.RunActions(activityId, actStatus))

      val nextStatus = actStatus match {
        case Status.Canceled => Status.Canceled
        case Status.Finished => Status.Finished
        case _ => Status.Failed
      }

      val newGraph = ps.activityGraph.removeVertex(activityId)
      val activityMgrs = ps.activityMgrs - activityId
      val newActivityResources = ps.activityResources - activityId

      val rscId = ps.activityResources(activityId)
      // if no other activities depend on the resource, we can shut it down
      if (!newActivityResources.values.toSet.contains(rscId)) {
        ps.resourceMgrs(rscId) ! ResourceMgr.Shutdown(Status.Finished)
      }

      val newState = scheduleNextActivities(
        ctx,
        database,
        ps.copy(
          activityGraph = newGraph,
          status = nextStatus,
          activityMgrs = activityMgrs,
          activityResources = newActivityResources
        ),
        actStatus,
        checkProgressDelay
      )

      if (terminateWhenComplete(ctx, database, newState)) Behaviors.stopped
      else active(ctx, database, checkProgressDelay, newState)

    case ResourceTerminated(resourceId, rscStatus) =>
      ctx.log.info(s"${ctx.self} (active) recieved ResourceTerminated($resourceId, $rscStatus)")
      val newResourceMgrs = ps.resourceMgrs - resourceId
      for {
        (actId, rscId) <- ps.activityResources
        if rscId == resourceId
        actMgr <- ps.activityMgrs.get(actId)
      } actMgr ! ActivityMgr.Cancel

      val newState = ps.copy(resourceMgrs = newResourceMgrs)

      if (terminateWhenComplete(ctx, database, newState)) Behaviors.stopped
      else active(ctx, database, checkProgressDelay, newState)

    case ActionCompleted =>
      ctx.log.info(s"${ctx.self} (active) received ActionCompleted")
      val newState = ps.copy(actionMgr = None)
      if (terminateWhenComplete(ctx, database, newState)) Behaviors.stopped
      else active(ctx, database, checkProgressDelay, newState)

    case CancelWorkflow =>
      ctx.log.info(s"${ctx.self} (active) received CancelWorkflow")
      ps.activityMgrs.values.foreach(_ ! ActivityMgr.Cancel)
      Behaviors.same

  } receiveSignal { case (c, PostStop) =>
    c.log.info(s"${c.self} stopped")
    Behaviors.same
  }

  def scheduleNextActivities(
    ctx: ActorContext[Msg],
    database: OrchardDatabase,
    ps: Params,
    lastActStatus: Status.Value,
    checkProgressDelay: FiniteDuration
  ): Params = {

    // get a list of activities where the dependencies are met and are not scheduled, spawn new
    // activity managers and update the state
    val activityMgrs = (ps.activityGraph.roots -- ps.activityMgrs.keySet).map { actId =>
      val rscMgr = ps.resourceMgrs(ps.activityResources(actId))
      val actMgr = ctx.spawn(
        ActivityMgr(ctx.self, database, ps.workflowId, actId, rscMgr, checkProgressDelay),
        s"act-$actId"
      )
      val activityMsg = lastActStatus match {
        case Status.Canceled => ActivityMgr.Cancel
        case Status.CascadeFailed => ActivityMgr.CascadeFail
        case Status.Failed => ActivityMgr.CascadeFail
        case _ => ActivityMgr.Start
      }
      actMgr ! activityMsg
      actId -> actMgr
    }
    val newActivityMgrs = ps.activityMgrs ++ activityMgrs

    ps.copy(activityMgrs = newActivityMgrs)

  }

  def terminateWhenComplete(
    ctx: ActorContext[Msg],
    database: OrchardDatabase,
    ps: Params
  ): Boolean =
    if (ps.activityMgrs.isEmpty && ps.resourceMgrs.isEmpty && ps.actionMgr.isEmpty) {
      val newStatus =
        if (!Status.TerminatedStatuses.contains(ps.status)) Status.Finished else ps.status
      database.sync(ps.query.setTerminated(newStatus))
      true
    } else {
      false
    }

}
