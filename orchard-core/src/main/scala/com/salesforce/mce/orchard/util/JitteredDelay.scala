/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.util

import scala.concurrent.duration
import scala.concurrent.duration.FiniteDuration
import scala.util.Random

trait JitteredDelay extends Policy {

  def minDelay: FiniteDuration = JitteredDelay.defaultMinDelay

  def maxDelay: FiniteDuration = JitteredDelay.defaultMaxDelay

  def delay(): FiniteDuration = {
    val rand = new Random()
    FiniteDuration(rand.between(minDelay.toSeconds, maxDelay.toSeconds + 1L), duration.SECONDS)
  }
}

object JitteredDelay {

  val defaultMinDelay: FiniteDuration = FiniteDuration(10, duration.SECONDS)

  val defaultMaxDelay: FiniteDuration = FiniteDuration(20, duration.SECONDS)

  def apply(
    minDelayValue: FiniteDuration = defaultMinDelay,
    maxDelayValue: FiniteDuration = defaultMaxDelay
  ) =
    new JitteredDelay {
      override def minDelay: FiniteDuration = minDelayValue

      override def maxDelay: FiniteDuration = maxDelayValue
    }
}
