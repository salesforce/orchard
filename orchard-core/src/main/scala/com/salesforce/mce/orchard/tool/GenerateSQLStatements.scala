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
