/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.system.actor

import org.apache.pekko.actor.typed.scaladsl._
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import com.salesforce.mce.orchard.db.{ActionQuery, ActionTable, OrchardDatabase}
import com.salesforce.mce.orchard.model.{ActionCondition, ActionStatus, Status}

object ActionMgr {

  sealed trait Msg
  case class RunActions(activityId: String, status: Status.Value) extends Msg
  case class ActionFinished(
    activityId: String,
    condition: ActionCondition,
    actionId: String,
    status: ActionStatus,
    errorMsg: String
  ) extends Msg

  case class Params(
    ctx: ActorContext[Msg],
    database: OrchardDatabase,
    query: ActionQuery,
    workflowId: String,
    actions: Map[String, ActionTable.R],
    pendingActions: Set[(String, ActionCondition, String)]
  )

  def apply(
    database: OrchardDatabase,
    workflowMgr: ActorRef[WorkflowMgr.Msg],
    workflowId: String
  ): Behavior[Msg] = Behaviors.setup { ctx =>
    val query = new ActionQuery(workflowId)
    val pendingActions =
      database.sync(query.activityActions()).map(r => (r.activityId, r.condition, r.actionId))

    if (pendingActions.isEmpty) {
      Behaviors.stopped
    } else {
      val actions = database
        .sync(query.allActions())
        .map { r => r.actionId -> r }
        .toMap

      val ps = Params(ctx, database, query, workflowId, actions, pendingActions.toSet)

      active(ps)
    }
  }

  private def active(ps: Params): Behavior[Msg] = {
    if (ps.pendingActions.isEmpty)
      Behaviors.stopped
    else
      withPendingActions(ps)
  }

  private def withPendingActions(ps: Params): Behavior[Msg] = Behaviors.receiveMessage {
    case RunActions(activityId, sts) =>
      val (conditionToSkip, conditionToUse) =
        if (sts == Status.Finished) (ActionCondition.OnFailure, ActionCondition.OnSuccess)
        else (ActionCondition.OnSuccess, ActionCondition.OnFailure)
      ps.database.sync(ps.query.skipByCondition(activityId, conditionToSkip))
      val newPendings = ps.pendingActions.filterNot { case (actId, condition, _) =>
        actId == activityId && condition == conditionToSkip
      }
      newPendings.foreach { case (actId, condition, actionId) =>
        if (actId == activityId && condition == conditionToUse) {
          val acnR = ps.actions(actionId)
          ps.ctx.spawn(
            ActionRunner(
              ps.ctx.self,
              ps.workflowId,
              actId,
              condition,
              actionId,
              acnR.actionType,
              acnR.actionSpec
            ),
            s"acn-${activityId}-${actionId}"
          )
        }
      }
      active(ps.copy(pendingActions = newPendings))
    case ActionFinished(activityId, condition, actionId, status, errMsg) =>
      ps.database.sync(ps.query.completeAction(activityId, condition, actionId, status, errMsg))
      active(ps.copy(pendingActions = ps.pendingActions - ((activityId, condition, actionId))))
  }
}
