/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard

import slick.jdbc.PostgresProfile.api._
import play.api.libs.json.{JsValue, Json}

import com.salesforce.mce.orchard.model.Status

package object db {

  implicit val StatusStringConvert = MappedColumnType.base[Status.Value, String](
    { status => status.toString() },
    { statusStr => Status.withName(statusStr) }
  )

  implicit val JsValueStringConvert = MappedColumnType.base[JsValue, String](
    { jsValue => Json.stringify(jsValue) },
    { jsStr => Json.parse(jsStr) }
  )

}
