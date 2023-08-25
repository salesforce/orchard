/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.util

import scala.util.Try

import com.krux.stubborn._
import com.krux.stubborn.policy.ExponentialBackoffAndJitter

object RetryHelper extends Retryable with ExponentialBackoffAndJitter {

  def retryToEither[A](action: => A) = Try(action.retry()).toEither

  override def base: Int = 30000

  override def cap: Int = 300000

}
