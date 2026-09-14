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

  /**
    * Returns activity progress together with whether a terminal failure makes the backing
    * resource unsafe to reuse. Providers that cannot make that distinction retain the existing
    * behavior and treat failures as activity-local.
    */
  def getProgressWithDetails(spec: JsValue): Either[Throwable, ActivityIO.Progress] =
    getProgress(spec).map(ActivityIO.Progress(_))

  def terminate(spec: JsValue): Either[Throwable, Status.Value]

}

object ActivityIO {

  case class Progress(
    status: Status.Value,
    resourceFailure: Boolean = false,
    detail: String = ""
  )

  case class Conf(
    workflowId: String,
    activityId: String,
    activityName: String,
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
