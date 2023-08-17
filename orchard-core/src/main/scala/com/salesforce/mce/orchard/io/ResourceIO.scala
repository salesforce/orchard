/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.io

import play.api.libs.json.{JsResult, JsValue}

import com.salesforce.mce.orchard.model.Status

trait ResourceIO {

  def create(): Either[Throwable, JsValue]

  def getStatus(instSpec: JsValue): Either[Throwable, Status.Value]

  def terminate(instSpec: JsValue): Either[Throwable, Status.Value]

}

object ResourceIO {

  case class Conf(
    workflowId: String,
    resourceId: String,
    resourceName: String,
    instanceId: Int,
    resourceType: String,
    resourceSpec: JsValue,
    maxAttempt: Int
  )

  def apply(conf: Conf): JsResult[ResourceIO] = {
    val clz = Class
      .forName(s"com.salesforce.mce.orchard.io.${conf.resourceType}$$")

    clz
      .getDeclaredMethod("decode", classOf[Conf])
      .invoke(clz.getField("MODULE$").get(null), conf)
      .asInstanceOf[JsResult[ResourceIO]]
  }

}
