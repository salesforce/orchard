/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.system.actor

import scala.util.{Failure, Success}

import org.apache.pekko.actor.typed.scaladsl._
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import play.api.libs.json.JsValue

import com.salesforce.mce.orchard.io.ActionIO
import com.salesforce.mce.orchard.model.{ActionCondition, ActionStatus}
import com.salesforce.mce.orchard.system.util.InvalidJsonException

object ActionRunner {

  sealed trait Msg
  private case class Completed(status: ActionStatus, errMsg: Option[String]) extends Msg

  case class Params(
    ctx: ActorContext[Msg],
    actionMgr: ActorRef[ActionMgr.Msg],
    workflowId: String,
    condition: ActionCondition,
    activityId: String,
    actionId: String
  )

  def apply(
    actionMgr: ActorRef[ActionMgr.Msg],
    workflowId: String,
    activityId: String,
    condition: ActionCondition,
    actionId: String,
    actionType: String,
    actionSpec: JsValue
  ): Behavior[Msg] = Behaviors.setup { ctx =>
    ctx.log.info(s"Starting ActionRunner ${ctx.self}...")

    val ps = Params(
      ctx,
      actionMgr,
      workflowId,
      condition,
      activityId,
      actionId
    )
    ActionIO(ActionIO.Conf(workflowId, activityId, actionId, actionType, actionSpec))
      .fold(
        invalid => {
          val exp = InvalidJsonException.raise(invalid)
          ctx.log.error(s"${ctx.self} invalid actionIO: $exp")
          terminate(ps, ActionStatus.Failed, exp.getMessage())
        },
        actionIO => {
          ctx.log.info(s"${ctx.self} become running")
          running(ps, actionIO)
        }
      )
  }

  def running(ps: Params, actionIO: ActionIO): Behavior[Msg] = {
    val stsTry = actionIO.run()
    stsTry match {
      case Success(sts) => terminate(ps, sts, "")
      case Failure(exp) => terminate(ps, ActionStatus.Failed, exp.getMessage())
    }
  }

  def terminate(ps: Params, status: ActionStatus, errorMsg: String): Behavior[Msg] = {
    ps.actionMgr ! ActionMgr.ActionFinished(
      ps.activityId,
      ps.condition,
      ps.actionId,
      status,
      errorMsg
    )
    Behaviors.stopped
  }

}
