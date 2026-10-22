/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.system.actor

import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import play.api.libs.json.Json
import slick.dbio.DBIO

import com.salesforce.mce.orchard.db.{OrchardDatabase, ResourceQuery}
import com.salesforce.mce.orchard.model.Status

/**
 * Actor-level tests for the resource-manager side of the lifecycle protocol. A ResourceMgr must
 * always complete to its WorkflowMgr with exactly one ResourceTerminated, including when a Shutdown
 * arrives while it is `waiting` between instance attempts -- a state in which no instance is
 * provisioned to drive teardown, so routing into `terminating` would hang forever.
 */
class ResourceMgrSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers {

  // The only DB call the tested path makes is a `setTerminated` write whose Int result is discarded.
  private class StubDatabase extends OrchardDatabase(ConfigFactory.empty()) {
    override def sync[T](dbio: DBIO[T]): T = 1.asInstanceOf[T]
  }

  private val WorkflowId = "wf-test"
  private val ResourceId = "rsc-test"

  // Start a ResourceMgr directly in `waiting`, bypassing `apply` (which would read the DB and spawn
  // real ResourceInstances). `waiting`'s Shutdown handler touches only the database, resourceQuery,
  // and workflowMgr, so a hand-built Params is enough to exercise it.
  private def spawnWaitingMgr(workflowMgr: org.apache.pekko.actor.typed.ActorRef[WorkflowMgr.Msg]) = {
    val behavior: Behavior[ResourceMgr.Msg] = Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        val ps = ResourceMgr.Params(
          ctx,
          new StubDatabase,
          new ResourceQuery(WorkflowId, ResourceId),
          workflowMgr,
          WorkflowId,
          ResourceId,
          "rsc-name",
          3,
          "rscType",
          Json.obj(),
          8.hours,
          timers
        )
        ResourceMgr.waiting(ps, 1.second)
      }
    }
    spawn(behavior)
  }

  "ResourceMgr in waiting" should {

    "complete with one ResourceTerminated on Shutdown instead of hanging" in {
      val wfMgr = createTestProbe[WorkflowMgr.Msg]()
      val mgr = spawnWaitingMgr(wfMgr.ref)

      mgr ! ResourceMgr.Shutdown(Status.Canceled)

      wfMgr.expectMessage(WorkflowMgr.ResourceTerminated(ResourceId, Status.Canceled))
      // the manager stops after completing, so no further messages follow
      wfMgr.expectNoMessage()
    }

  }

}
