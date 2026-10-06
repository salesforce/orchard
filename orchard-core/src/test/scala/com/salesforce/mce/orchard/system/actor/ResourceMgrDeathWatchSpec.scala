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

  // The epoch guard disambiguates reused instIds: a crash-respawn reuses the same instId, so a stale
  // Progressed from a crashed predecessor shares the live successor's instId; only the epoch tells them
  // apart. These pin the "both must match" contract so a regression that drops the epoch clause fails.
  "ResourceMgr.isCurrentIncarnation" should {

    "accept a Progressed whose instId AND epoch both match the current incarnation" in {
      ResourceMgr.isCurrentIncarnation(msgInstId = 2, msgEpoch = 3, currentInstId = 2, currentEpoch = 3) shouldBe true
    }

    // Same reused instId, but the stale predecessor carries an older epoch.
    "reject a stale Progressed with the same instId but an older epoch" in {
      ResourceMgr.isCurrentIncarnation(msgInstId = 2, msgEpoch = 2, currentInstId = 2, currentEpoch = 3) shouldBe false
    }

    "reject a Progressed from a superseded attempt (different instId), even if the epoch coincides" in {
      ResourceMgr.isCurrentIncarnation(msgInstId = 1, msgEpoch = 3, currentInstId = 2, currentEpoch = 3) shouldBe false
    }

    "reject when neither instId nor epoch match" in {
      ResourceMgr.isCurrentIncarnation(msgInstId = 1, msgEpoch = 1, currentInstId = 2, currentEpoch = 3) shouldBe false
    }
  }

  // The consecutive-crash budget bounds a crash loop. These pin the >= boundary (an off-by-one here would
  // either fail a resource one crash too early or let a loop run one crash too long) relative to the
  // configured MaxRespawnAttempts, so the test tracks the constant rather than hard-coding 3.
  "ResourceMgr.respawnBudgetExhausted" should {

    "keep respawning while consecutive crashes are below MaxRespawnAttempts" in {
      ResourceMgr.respawnBudgetExhausted(0) shouldBe false
      ResourceMgr.respawnBudgetExhausted(ResourceMgr.MaxRespawnAttempts - 1) shouldBe false
    }

    "give up once consecutive crashes reach MaxRespawnAttempts" in {
      ResourceMgr.respawnBudgetExhausted(ResourceMgr.MaxRespawnAttempts) shouldBe true
      ResourceMgr.respawnBudgetExhausted(ResourceMgr.MaxRespawnAttempts + 1) shouldBe true
    }
  }
}
