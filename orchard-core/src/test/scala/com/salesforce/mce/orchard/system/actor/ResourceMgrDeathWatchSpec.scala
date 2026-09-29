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

class ResourceMgrDeathWatchSpec extends AnyWordSpec with Matchers {

  "ResourceMgr.deathActionFor" should {

    // Spec is only persisted once the instance reaches Activating/Running, so a respawn there can
    // re-adopt the same cluster (ResourceInstance.apply re-reads it) without re-entering create().
    "respawn when the crashed instance had its spec persisted" in {
      ResourceMgr.deathActionFor(Some(Status.Activating)) shouldBe ResourceMgr.DeathAction.Respawn
      ResourceMgr.deathActionFor(Some(Status.Running)) shouldBe ResourceMgr.DeathAction.Respawn
    }

    // No persisted row means the child crashed before inserting it, i.e. before create() ran, so a
    // bounded fresh respawn cannot leak/duplicate a cluster.
    "respawn when the crashed instance had no persisted row (create() never ran)" in {
      ResourceMgr.deathActionFor(None) shouldBe ResourceMgr.DeathAction.Respawn
    }

    // Pending = crashed inside the non-idempotent create() window. Respawning could provision a
    // second cluster, so we fail the resource instead.
    "fail when the crashed instance was still Pending (no spec persisted)" in {
      ResourceMgr.deathActionFor(Some(Status.Pending)) shouldBe ResourceMgr.DeathAction.Fail
    }

    "ignore when the instance is already terminal" in {
      ResourceMgr.deathActionFor(Some(Status.Finished)) shouldBe ResourceMgr.DeathAction.Ignore
      ResourceMgr.deathActionFor(Some(Status.Failed)) shouldBe ResourceMgr.DeathAction.Ignore
      ResourceMgr.deathActionFor(Some(Status.Timeout)) shouldBe ResourceMgr.DeathAction.Ignore
    }
  }
}
