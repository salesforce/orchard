/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.tool

import scala.concurrent.Await
import scala.concurrent.duration._

import slick.jdbc.PostgresProfile.SchemaDescription
import slick.jdbc.PostgresProfile.api._

import com.salesforce.mce.orchard.db._

object ProvisionDatabase extends App {

  val schema = WorkflowTable().schema ++
    ResourceTable().schema ++
    ResourceInstanceTable().schema ++
    ActivityTable().schema ++
    DependencyTable().schema ++
    ActivityAttemptTable().schema ++
    WorkflowManagerTable().schema

   val db = OrchardDatabase()

  checkFirstTimeProvision(db)

  println("executing the following statements...")
  schema.dropIfExistsStatements.foreach(println)
  Await.result(
    db.connection.run(DBIO.seq(schema.dropIfExists)),
    2.minutes
  )

  println("executing the following statements...")
  schema.createStatements.foreach(println)
  Await.result(
    db.connection.run(DBIO.seq(schema.createIfNotExists)),
    2.minutes
  )

  /**
   * corner case for 1st time creation of schema, when the drop would give
   * an error in a step before drop table to alter (non-existing) table
   * drop constraint for the primary key
   * @param db  OrchardDatabase
   */
  private def checkFirstTimeProvision(db: OrchardDatabase): Unit = {
    Seq(
      ResourceTable(),
      ResourceInstanceTable(),
      ActivityTable(),
      DependencyTable(),
      ActivityAttemptTable()
    ).foreach { tb =>
      checkTableFirstTime(db, tb.baseTableRow.tableName, tb.schema)
    }
  }

  private def checkTableFirstTime(
    db: OrchardDatabase,
    tableName: String,
    schema: SchemaDescription
  ): Unit = {

    def tableOption(tableName: String) = {
      lazy val public = "public"
      sql"select tablename from pg_tables where schemaname = $public and tablename = $tableName"
        .as[String]
        .headOption
    }

    Await.result(
      db.connection.run(tableOption(tableName)),
      2.minutes
    ) match {
      case None =>
        println(s"First time to provision table $tableName...")
        // create table to avoid error in the regular idempotent steps (schema dropIfExists, createIfNotExists)
        schema.createIfNotExistsStatements.foreach(println)
        Await.result(
          db.connection.run(DBIO.seq(schema.createIfNotExists)),
          2.minutes
        )
      case Some(_) =>
        // no action necessary
        println(s"Provision table $tableName re-run...")
    }
  }


}
