/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.util

import scala.concurrent.duration
import scala.concurrent.duration.FiniteDuration

trait FixedDelay extends Policy {

  def fixedDelay: FiniteDuration = FixedDelay.defaultFixedDelay

  def delay(): FiniteDuration = FiniteDuration(fixedDelay.toSeconds, duration.SECONDS)

}

object FixedDelay {

  val defaultFixedDelay: FiniteDuration = FiniteDuration(3, duration.SECONDS)

  def apply(fixedDelayValue: FiniteDuration = defaultFixedDelay) = new FixedDelay {
    override def fixedDelay = fixedDelayValue

  }
}
