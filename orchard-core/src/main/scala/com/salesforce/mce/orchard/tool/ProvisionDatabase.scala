/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.tool

import scala.concurrent.Await
import scala.concurrent.duration._

import slick.jdbc.PostgresProfile.api._

import com.salesforce.mce.orchard.db._

object ProvisionDatabase extends App {

  val schema = WorkflowTable().schema ++
    ResourceTable().schema ++
    ResourceInstanceTable().schema ++
    ActivityTable().schema ++
    DependencyTable().schema ++
    ActivityAttemptTable().schema

  val db = OrchardDatabase()

  println("executing the following statements...")
  schema.dropIfExistsStatements.foreach(println)
  Await.result(
    db.connection.run(DBIO.seq(schema.dropIfExists)),
    2.minutes
  )

  println("executing the following statements...")
  schema.createIfNotExistsStatements.foreach(println)
  Await.result(
    db.connection.run(DBIO.seq(schema.createIfNotExists)),
    2.minutes
  )

}
