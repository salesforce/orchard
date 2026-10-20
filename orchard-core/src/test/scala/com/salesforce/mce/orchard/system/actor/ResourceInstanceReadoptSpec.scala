/*
 * Copyright (c) 2026, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.system.actor

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.salesforce.mce.orchard.model.Status

class ResourceInstanceReadoptSpec extends AnyWordSpec with Matchers {

  // A crash-respawned instance re-adopts the Running row and parks passively, so without an up-front
  // liveness report ResourceMgr's crash-respawn budget would never reset across hours of healthy running.
  // It reports progress ONLY on a confirmed-Running startup poll; a non-Running status or a failed poll is
  // not progress and is deferred to the on-demand poll + terminate-deadline paths.
  "ResourceInstance.readoptShouldReportProgress" should {

    "report progress when the re-adopted cluster confirms it is still Running" in {
      ResourceInstance.readoptShouldReportProgress(Right(Status.Running)) shouldBe true
    }

    "not report progress for any non-Running status (would falsely reset the crash budget)" in {
      ResourceInstance.readoptShouldReportProgress(Right(Status.Activating)) shouldBe false
      ResourceInstance.readoptShouldReportProgress(Right(Status.Pending)) shouldBe false
      ResourceInstance.readoptShouldReportProgress(Right(Status.Finished)) shouldBe false
      ResourceInstance.readoptShouldReportProgress(Right(Status.Failed)) shouldBe false
      ResourceInstance.readoptShouldReportProgress(Right(Status.Timeout)) shouldBe false
    }

    "not report progress when the startup status poll fails (the cluster may be gone)" in {
      ResourceInstance.readoptShouldReportProgress(Left(new RuntimeException("boom"))) shouldBe false
    }
  }
}
