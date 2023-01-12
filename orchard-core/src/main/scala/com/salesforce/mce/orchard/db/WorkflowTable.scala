/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.db

import java.time.LocalDateTime

import slick.jdbc.PostgresProfile.api._

import com.salesforce.mce.orchard.model.Status

class WorkflowTable(tag: Tag) extends Table[WorkflowTable.R](tag, "workflows") {

  def id = column[String]("id", O.PrimaryKey, O.SqlType(WorkflowTable.IdSqlType))

  def name = column[String]("name", O.SqlType("VARCHAR(256)"))

  def status = column[Status.Value]("status", O.SqlType("VARCHAR(32)"))

  def createdAt = column[LocalDateTime]("created_at")

  def activatedAt = column[Option[LocalDateTime]]("activated_at")

  def terminatedAt = column[Option[LocalDateTime]]("terminated_at")

  override def * = (id, name, status, createdAt, activatedAt, terminatedAt).mapTo[WorkflowTable.R]

}

object WorkflowTable {

  final val IdSqlType = "VARCHAR(64)"

  def apply() = TableQuery[WorkflowTable]

  case class R(
    id: String,
    name: String,
    status: Status.Value,
    createdAt: LocalDateTime,
    activatedAt: Option[LocalDateTime],
    terminatedAt: Option[LocalDateTime]
  )

}
