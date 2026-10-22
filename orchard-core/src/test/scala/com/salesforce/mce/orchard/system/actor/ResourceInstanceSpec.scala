/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.system.actor

import java.time.LocalDateTime

import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import play.api.libs.json.{JsValue, Json}
import slick.dbio.DBIO

import com.salesforce.mce.orchard.db.{OrchardDatabase, ResourceInstanceTable}
import com.salesforce.mce.orchard.io.ResourceIO
import com.salesforce.mce.orchard.model.Status

/**
 * Actor-level tests for the resource-lifecycle protocol changes: a ResourceInstance must attempt
 * provider teardown exactly once, report the real outcome, and always answer Shutdown so a manager
 * can complete. The instance is exercised in isolation -- its ResourceMgr is a TestProbe, the
 * provider a stubbed ResourceIO, and the database a stub that returns a seeded instance row (the
 * only DB read the instance branches on) and a dummy count for the writes it ignores.
 */
class ResourceInstanceSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers {

  // The instance reads its row once in setup and branches on it; every later sync is a status write
  // whose Int result the actor discards. Returning the seeded row first and a dummy afterward is
  // enough to drive behavior without a real database.
  private class StubDatabase(instanceRow: ResourceInstanceTable.R)
      extends OrchardDatabase(ConfigFactory.empty()) {
    // `sync` is overridden and `connection` is lazy, so no real database is ever opened
    private var callCount = 0
    override def sync[T](dbio: DBIO[T]): T = {
      val result: Any = if (callCount == 0) Option(instanceRow) else 1
      callCount += 1
      result.asInstanceOf[T]
    }
  }

  private class StubResourceIO(
    getStatusResult: Either[Throwable, Status.Value],
    terminateResult: Either[Throwable, Status.Value]
  ) extends ResourceIO {
    var terminateCount = 0
    var getStatusCount = 0
    override def create(): Either[Throwable, JsValue] = Right(Json.obj())
    override def getStatus(instSpec: JsValue): Either[Throwable, Status.Value] = {
      getStatusCount += 1
      getStatusResult
    }
    override def terminate(instSpec: JsValue): Either[Throwable, Status.Value] = {
      terminateCount += 1
      terminateResult
    }
  }

  private val InstId = 1
  private val Spec = Json.obj("cluster" -> "c-1")

  private def runningRow: ResourceInstanceTable.R = ResourceInstanceTable.R(
    "wf-test",
    "rsc-test",
    InstId,
    Option(Spec),
    Status.Running,
    "",
    LocalDateTime.now(),
    Option(LocalDateTime.now()),
    None
  )

  private def spawnRunningInstance(io: ResourceIO) = {
    val mgr = createTestProbe[ResourceMgr.Msg]()
    val ri = spawn(
      ResourceInstance(
        mgr.ref,
        new StubDatabase(runningRow),
        "wf-test",
        "rsc-test",
        InstId,
        io,
        8.hours
      )
    )
    (mgr, ri)
  }

  "ResourceInstance" should {

    "report a clean completion with the teardown outcome on a normal shutdown" in {
      val io = new StubResourceIO(Right(Status.Running), Right(Status.Finished))
      val (mgr, ri) = spawnRunningInstance(io)

      ri ! ResourceInstance.Shutdown(Status.Finished)

      mgr.expectMessage(
        ResourceMgr.ResourceInstanceFinished(
          InstId,
          Status.Finished,
          ResourceMgr.Teardown.Terminated
        )
      )
      io.terminateCount shouldBe 1
    }

    "surface a failed teardown but still complete the workflow" in {
      val io =
        new StubResourceIO(Right(Status.Running), Left(new RuntimeException("provider down")))
      val (mgr, ri) = spawnRunningInstance(io)

      ri ! ResourceInstance.Shutdown(Status.Finished)

      mgr.expectMessage(
        ResourceMgr.ResourceInstanceFinished(
          InstId,
          Status.Finished,
          ResourceMgr.Teardown.TerminateFailed
        )
      )
    }

    "re-report completion when Shutdown reaches an already-inactive instance" in {
      val io = new StubResourceIO(Right(Status.Running), Right(Status.Finished))
      val (mgr, ri) = spawnRunningInstance(io)

      ri ! ResourceInstance.Shutdown(Status.Finished)
      mgr.expectMessage(
        ResourceMgr.ResourceInstanceFinished(
          InstId,
          Status.Finished,
          ResourceMgr.Teardown.Terminated
        )
      )

      // a second Shutdown (e.g. a manager racing the self-timer) must unblock, not be ignored
      ri ! ResourceInstance.Shutdown(Status.Failed)
      mgr.expectMessage(
        ResourceMgr.ResourceInstanceFinished(
          InstId,
          Status.Finished,
          ResourceMgr.Teardown.Terminated
        )
      )
      // teardown is not re-run; the instance was already torn down
      io.terminateCount shouldBe 1
    }

    "attempt teardown when getStatus fails in running rather than orphaning the resource" in {
      val io = new StubResourceIO(Left(new RuntimeException("throttled")), Right(Status.Finished))
      val (mgr, ri) = spawnRunningInstance(io)
      val replyTo = createTestProbe[ResourceMgr.ResourceInstSpecRsp]()

      ri ! ResourceInstance.GetResourceInstSpec(replyTo.ref)

      mgr.expectMessage(ResourceMgr.InactiveResourceInstance(InstId, Status.Failed, replyTo.ref))
      io.getStatusCount shouldBe 1
      io.terminateCount shouldBe 1
    }

  }

}
