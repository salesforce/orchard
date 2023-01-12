package com.salesforce.mce.orchard.db

import slick.jdbc.PostgresProfile.api._

import com.salesforce.mce.orchard.model.{ActionCondition, ActionStatus}

class ActionQuery(workflowId: String) {

  def allActions(): DBIO[Seq[ActionTable.R]] =
    ActionTable().filter(r => r.workflowId === workflowId).result

  def activityActions(): DBIO[Seq[ActivityActionTable.R]] =
    ActivityActionTable().filter(r => r.workflowId === workflowId).result

  def skipByCondition(activityId: String, condition: ActionCondition) = ActivityActionTable()
    .filter(r =>
      r.workflowId === workflowId && r.activityId === activityId && r.condition === condition
    )
    .map(_.status)
    .update(ActionStatus.Skipped)

  def completeAction(
    activityId: String,
    condition: ActionCondition,
    actionId: String,
    sts: ActionStatus,
    errMsg: String
  ) = ActivityActionTable()
    .filter(r =>
      r.workflowId === workflowId && r.activityId === r.activityId &&
        r.condition === condition && r.actionId === actionId
    )
    .map(r => (r.status, r.errorMessage))
    .update((sts, errMsg))

}
