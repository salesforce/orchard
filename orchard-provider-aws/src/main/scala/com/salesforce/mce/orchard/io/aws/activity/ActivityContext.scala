/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.io.aws.activity

import com.salesforce.mce.orchard.io.ActivityIO

object ActivityContext {

  def replace(target: String, ctx: ActivityIO.Conf): String = target
    .replace("{{workflowId}}", ctx.workflowId)
    .replace("{{activityId}}", ctx.activityId)
    .replace("{{attemptId}}", ctx.attemptId.toString)

}
