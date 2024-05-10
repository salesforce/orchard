/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.db

import java.time.{LocalDate, LocalDateTime}

import slick.jdbc.GetResult
import slick.jdbc.PostgresProfile.api._

import com.salesforce.mce.orchard.model.Status

class WorkflowQuery(workflowId: String) {

  private def self = WorkflowTable().filter(r => r.id === workflowId && r.status =!= Status.Deleted)

  def get(): DBIO[Option[WorkflowTable.R]] = self.result.headOption

  def activate(): DBIO[Int] = WorkflowTable()
    // Note can only set activate if workflow is pending (probably should move this to the app
    // layer
    .filter(r => r.id === workflowId && r.status === Status.Pending)
    .map(r => (r.status, r.activatedAt))
    .update((Status.Running, Option(LocalDateTime.now())))

  def activities(): DBIO[Seq[ActivityTable.R]] = ActivityTable()
    .filter(_.workflowId === workflowId)
    .result

  def resources(): DBIO[Seq[ResourceTable.R]] = ResourceTable()
    .filter(_.workflowId === workflowId)
    .result

  def dependencies(): DBIO[Seq[DependencyTable.R]] = DependencyTable()
    .filter(_.workflowId === workflowId)
    .result

  def setTerminated(status: Status.Value): DBIO[Int] = self
    .map(r => (r.status, r.terminatedAt))
    .update((status, Option(LocalDateTime.now())))

  def setCanceling(): DBIO[Int] = WorkflowTable()
    .filter(r => r.id === workflowId && r.status === Status.Running)
    .map(_.status)
    .update(Status.Canceling)

  def deletePending(): DBIO[Int] = self
    .filter(_.status === Status.Pending)
    .map(r => (r.status, r.terminatedAt))
    .update((Status.Deleted, Option(LocalDateTime.now())))

}

object WorkflowQuery {

  sealed trait OrderBy
  object OrderBy {
    case object CreatedAt extends OrderBy
    case object ActivatedAt extends OrderBy
    case object TerminatedAt extends OrderBy
  }

  sealed trait Order
  object Order {
    case object Asc extends Order
    case object Desc extends Order
  }

  def filter(
    like: String,
    statuses: Seq[Status.Value],
    orderBy: OrderBy,
    order: Order,
    limit: Int,
    offset: Int
  ): DBIO[Seq[WorkflowTable.R]] = {

    val sortByColumn = (orderBy, order) match {
      case (OrderBy.CreatedAt, Order.Desc) =>
        t: WorkflowTable => t.createdAt.desc
      case (OrderBy.CreatedAt, Order.Asc) =>
        t: WorkflowTable => t.createdAt.asc
      case (OrderBy.ActivatedAt, Order.Desc) =>
        t: WorkflowTable => t.activatedAt.desc
      case (OrderBy.ActivatedAt, Order.Asc) =>
        t: WorkflowTable => t.activatedAt.asc
      case (OrderBy.TerminatedAt, Order.Desc) =>
        t: WorkflowTable => t.terminatedAt.desc
      case (OrderBy.TerminatedAt, Order.Asc) =>
        t: WorkflowTable => t.terminatedAt.asc
    }

    val base = WorkflowTable().filter(r => (r.id.like(like) || r.name.like(like)) && r.status =!= Status.Deleted)

    val query = if (statuses.isEmpty) base else base.filter(r => r.status.inSet(statuses))

    query
      .sortBy(sortByColumn)
      .drop(offset)
      .take(limit)
      .result
  }

  def filterByStatus(status: Status.Value): DBIO[Seq[WorkflowTable.R]] =
    WorkflowTable().filter(r => r.status === status).result

  def countByStatus(window: Int): DBIO[Seq[(Status.Value, Int)]] = {

    val fromDate = LocalDateTime.now().minusDays(window)

    WorkflowTable()
      .filter(_.createdAt >= fromDate)
      .groupBy(_.status)
      .map { case (sts, rs) => sts -> rs.length }
      .result
  }

  implicit val countsConverter: GetResult[(LocalDate, Status.Value, Int)] = GetResult(r => (
    r.nextTimestamp().toLocalDateTime().toLocalDate(),
    Status.withName(r.nextString()),
    r.nextInt()
  ))

  def dailyCounts(window: Int): DBIO[Seq[(LocalDate, Status.Value, Int)]] = {
    val fromDate = LocalDateTime.now().minusDays(window)

    sql"""
      SELECT DATE(activated_at) "date", status, count(*)
      FROM workflows
      WHERE activated_at > '#${fromDate.toString()}'::timestamp without time zone
      GROUP BY DATE(activated_at), status
      ORDER BY "date", status
    """.as[(LocalDate, Status.Value, Int)]
  }

  def hourlyPattern(window: Int): DBIO[Seq[(Int, Int, Int)]] = {
    val fromDate = LocalDateTime.now().minusDays(window)

    sql"""
      SELECT EXTRACT(DOW FROM activated_at) "dow", EXTRACT(HOUR FROM activated_at) "hour", count(*)
      FROM workflows
      WHERE activated_at > '#${fromDate.toString()}'::timestamp without time zone
      GROUP BY EXTRACT(DOW FROM activated_at), EXTRACT(HOUR FROM activated_at) 
      ORDER BY "dow", "hour"
    """.as[(Int, Int, Int)]
  }

}
