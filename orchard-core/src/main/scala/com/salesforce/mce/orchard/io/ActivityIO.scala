/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.io

import play.api.libs.json.{JsResult, JsValue}

import com.salesforce.mce.orchard.model.Status

trait ActivityIO {

  def create(): Either[Throwable, JsValue]

  def getProgress(spec: JsValue): Either[Throwable, Status.Value]

  def terminate(spec: JsValue): Either[Throwable, Status.Value]

}

object ActivityIO {

  case class Conf(
    workflowId: String,
    activityId: String,
    attemptId: Int,
    activityType: String,
    activitySpec: JsValue,
    resourceInstSpec: JsValue
  )

  def apply(conf: Conf): JsResult[ActivityIO] = {
    val clz = Class
      .forName(s"com.salesforce.mce.orchard.io.${conf.activityType}$$")

    clz
      .getDeclaredMethod("decode", classOf[Conf])
      .invoke(clz.getField("MODULE$").get(null), conf)
      .asInstanceOf[JsResult[ActivityIO]]
  }

}
