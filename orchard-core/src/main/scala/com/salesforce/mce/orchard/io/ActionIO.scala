/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.io

import scala.util.Try

import play.api.libs.json.{JsResult, JsValue}

import com.salesforce.mce.orchard.model.ActionStatus

trait ActionIO {

  def run(): Try[ActionStatus]

}

object ActionIO {

  case class Conf(
    workflowId: String,
    activityId: String,
    actionId: String,
    actionType: String,
    actionSpec: JsValue
  )

  def apply(conf: Conf): JsResult[ActionIO] = {
    val clz = Class.forName(s"com.salesforce.mce.orchard.io.${conf.actionType}$$")

    clz.getDeclaredMethod("decode", classOf[Conf])
      .invoke(clz.getField("MODULE$").get(null), conf)
      .asInstanceOf[JsResult[ActionIO]]
  }

}
