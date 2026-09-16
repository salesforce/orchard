/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.system.actor

import org.scalatest.wordspec.AnyWordSpec

import com.salesforce.mce.orchard.model.Status

class ActivityMgrSpec extends AnyWordSpec {

  "ActivityMgr.shouldRetry" should {
    "allow another activity attempt while its resource can serve work" in {
      assert(
        ActivityMgr.shouldRetry(
          attemptId = 1,
          maxAttempt = 3,
          resourceStatus = Status.Running
        )
      )
    }

    "stop retrying after resource replacement is exhausted" in {
      assert(
        !ActivityMgr.shouldRetry(
          attemptId = 1,
          maxAttempt = 3,
          resourceStatus = Status.Failed
        )
      )
    }

    "honor the activity attempt limit" in {
      assert(
        !ActivityMgr.shouldRetry(
          attemptId = 3,
          maxAttempt = 3,
          resourceStatus = Status.Running
        )
      )
    }
  }
}
