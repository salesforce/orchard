/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.model

import play.api.libs.json.JsValue

case class Resource(
  id: String,
  name: String,
  resourceType: String,
  resourceSpec: JsValue,
  maxInstanceCount: Int
)
