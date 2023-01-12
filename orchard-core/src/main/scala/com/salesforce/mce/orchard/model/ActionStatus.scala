/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.model

sealed trait ActionStatus {
  val Serialized: String
}

object ActionStatus {

  def apply(serialized: String) = serialized match {
    case Pending.Serialized => Pending
    case Skipped.Serialized => Skipped
    case Finished.Serialized => Finished
    case Failed.Serialized => Failed
    case other => throw new MatchError(s"unmatched $other")
  }

  case object Pending extends ActionStatus {
    final val Serialized = "pending"
  }

  case object Skipped extends ActionStatus {
    final val Serialized = "skipped"
  }

  case object Finished extends ActionStatus {
    final val Serialized = "finished"
  }
  case object Failed extends ActionStatus {
    final val Serialized = "failed"
  }

}
