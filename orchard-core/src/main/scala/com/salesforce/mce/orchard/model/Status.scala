/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.model

/**
 * Use https://docs.aws.amazon.com/datapipeline/latest/DeveloperGuide/dp-interpret-status.html as a
 * guide
 */
object Status extends Enumeration {

  final val Pending = Value("pending")

  final val Activating = Value("activating")

  final val Running = Value("running")

  final val Finished = Value("finished")

  final val Failed = Value("failed")

  final val CascadeFailed = Value("cascade_failed")

  final val Canceling = Value("canceling")

  final val Canceled = Value("canceled")

  final val Deactivating = Value("deactivating")

  final val ShuttingDown = Value("shutting_down")

  final val Timeout = Value("timeout")

  val PrerunStatuses = Set(Pending)

  val TransitionToRunningStatuses = Set(Activating)

  val RunningStatuses = Set(Running)

  val TransitionToTerminateStatuses = Set(Canceling, Deactivating, ShuttingDown)

  val TerminatedStatuses = Set(Finished, Failed, CascadeFailed, Canceled, Timeout)

  def isAlive(status: Value): Boolean = !TerminatedStatuses.contains(status)

  object Order extends Ordering[Value] {

    val orderedStates = Seq(
      PrerunStatuses,
      TransitionToRunningStatuses,
      TransitionToTerminateStatuses,
      TerminatedStatuses
    ).zipWithIndex

    override def compare(x: Value, y: Value): Int = {
      val xIdx = orderedStates.find { case (xs, _) => xs.contains(x) }.get._2
      val yIdx = orderedStates.find { case (ys, _) => ys.contains(y) }.get._2

      xIdx - yIdx
    }

  }

}
