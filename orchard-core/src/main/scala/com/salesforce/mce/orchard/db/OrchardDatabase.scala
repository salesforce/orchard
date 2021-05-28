/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.db

import java.time.LocalDateTime
import java.util.UUID

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

import com.typesafe.config.Config
import slick.jdbc.PostgresProfile.api._

import com.salesforce.mce.orchard.model.{Status, Workflow}
import com.salesforce.mce.orchard.OrchardSettings

class OrchardDatabase(conf: Config) {

  val connection: Database = Database.forConfig("slick", conf)

  // TODO need to also validate if the workflow ID already exists within a transaction
  def createWorkflow(workflow: Workflow)(implicit ec: ExecutionContext): Future[String] = {
    val workflowId = s"wf-${UUID.randomUUID().toString()}"
    val currentTime = LocalDateTime.now()
    val initialStatus = Status.Pending

    val addWorkflow =
      WorkflowTable() += WorkflowTable.R(
        workflowId,
        workflow.name,
        initialStatus,
        currentTime,
        None,
        None
      )

    val addResources = ResourceTable() ++= workflow.resources.map { r =>
      ResourceTable.R(
        workflowId,
        r.id,
        r.name,
        r.resourceType,
        r.resourceSpec,
        r.maxInstanceCount,
        initialStatus,
        currentTime,
        None,
        None
      )
    }

    val addActivities = ActivityTable() ++= workflow.activities.map { a =>
      ActivityTable.R(
        workflowId,
        a.id,
        a.name,
        a.activityType,
        a.activitySpec,
        a.resourceId,
        a.maxAttempt,
        initialStatus,
        currentTime,
        None,
        None
      )
    }

    val addDependencies = DependencyTable() ++= (
      for {
        (dependent, acts) <- workflow.dependencies.toSeq
        act <- acts
      } yield DependencyTable.R(workflowId, act, dependent)
    )

    connection
      .run(DBIO.seq(addWorkflow, addResources, addActivities, addDependencies))
      .map(_ => workflowId)
  }

  def async[T](dbio: DBIO[T]): Future[T] = connection.run(dbio)

  def sync[T](dbio: DBIO[T]): T = Await.result(async(dbio), 1.minute)

  def sync[T](dbio: DBIO[T], duration: Duration): T = Await.result(async(dbio), duration)

}

object OrchardDatabase {

  def apply(): OrchardDatabase = apply(OrchardSettings())

  def apply(settings: OrchardSettings): OrchardDatabase =
    new OrchardDatabase(settings.slickDatabaseConf)

}
