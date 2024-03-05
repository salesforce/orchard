/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.util

import scala.concurrent.duration.FiniteDuration

trait Policy {

  /**
   * @return the number of seconds to wait for the next attempt
   */
  def delay(): FiniteDuration

}
