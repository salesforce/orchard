/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.model

case class Workflow(
  name: String,
  activities: Seq[Activity],
  resources: Seq[Resource],
  dependencies: Map[String, Seq[String]],
  actions: Seq[Action]
)
