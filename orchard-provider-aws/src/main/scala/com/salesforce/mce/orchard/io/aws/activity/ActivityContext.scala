package com.salesforce.mce.orchard.io.aws.activity

import com.salesforce.mce.orchard.io.ActivityIO

object ActivityContext {

  def replace(target: String, ctx: ActivityIO.Conf): String = target
    .replace("{{workflowId}}", ctx.workflowId)
    .replace("{{activityId}}", ctx.activityId)
    .replace("{{attemptId}}", ctx.attemptId.toString)

}
