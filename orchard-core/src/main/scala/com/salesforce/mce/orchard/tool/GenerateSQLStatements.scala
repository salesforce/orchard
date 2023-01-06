/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.tool

import slick.jdbc.PostgresProfile.api._

import com.salesforce.mce.orchard.db._

object GenerateSQLStatements extends App {

  val schema = WorkflowTable().schema ++
    ResourceTable().schema ++
    ResourceInstanceTable().schema ++
    ActivityTable().schema ++
    DependencyTable().schema ++
    ActivityAttemptTable().schema ++
    WorkflowManagerTable().schema

  schema.createStatements.foreach(println)

}
