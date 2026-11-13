/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.system.actor

import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.{ManualTime, ScalaTestWithActorTestKit}
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import play.api.libs.json.{JsValue, Json}
import slick.dbio.DBIO

import com.salesforce.mce.orchard.db.{ActivityAttemptQuery, OrchardDatabase}
import com.salesforce.mce.orchard.io.ActivityIO
import com.salesforce.mce.orchard.model.Status
import com.salesforce.mce.orchard.util.Policy

class ActivityAttemptSpec
    extends ScalaTestWithActorTestKit(ManualTime.config) with AnyWordSpecLike with Matchers {

  private val manualTime = ManualTime()

  private class StubDatabase extends OrchardDatabase(ConfigFactory.empty()) {
    override def sync[T](dbio: DBIO[T]): T = 1.asInstanceOf[T]
  }

  private def spawnRunning(
    io: ActivityIO,
    manager: ActorRef[ActivityMgr.Msg]
  ): ActorRef[ActivityAttempt.Msg] = {
    val ready = createTestProbe[Boolean]()
    val behavior = Behaviors.setup[ActivityAttempt.Msg] { ctx =>
      Behaviors.withTimers { timers =>
        val params = ActivityAttempt.Params(
          ctx,
          manager,
          new StubDatabase,
          new ActivityAttemptQuery("wf-test", "act-test", 1),
          "wf-test",
          "act-test",
          "test-activity",
          1,
          "rsc-test",
          ctx.messageAdapter[ResourceMgr.ResourceInstSpecRsp](r =>
            ActivityAttempt.ResourceInstSpec(r.spec)
          ),
          timers
        )
        val delay = new Policy {
          override def delay(): FiniteDuration = 5.seconds
        }
        val running = ActivityAttempt.running(params, 1, io, Json.arr("s-test"), delay)
        ready.ref ! true
        running
      }
    }
    val actor = spawn(behavior)
    ready.expectMessage(true)
    actor
  }

  "An activity waiting for its next progress observation" should {
    "use the polling delay before checking again and accept a later completion" in {
      val manager = createTestProbe[ActivityMgr.Msg]()
      val polls = createTestProbe[Int]()
      val io = new ActivityIO {
        private var count = 0
        override def create(): Either[Throwable, JsValue] = Right(Json.arr("s-test"))
        override def getProgress(spec: JsValue): Either[Throwable, Status.Value] = {
          count += 1
          polls.ref ! count
          Right(if (count == 1) Status.Running else Status.Finished)
        }
        override def terminate(spec: JsValue): Either[Throwable, Status.Value] =
          throw new IllegalStateException("Completed activity should not be canceled")
      }
      spawnRunning(io, manager.ref)

      manualTime.expectNoMessageFor(4.seconds, polls)
      manualTime.timePasses(1.second)
      polls.expectMessage(1)
      manager.expectNoMessage(10.millis)

      manualTime.expectNoMessageFor(4.seconds, polls)
      manualTime.timePasses(1.second)
      polls.expectMessage(2)
      manager.expectMessage(ActivityMgr.AttemptFinished(Status.Finished))
    }

    "honor cancellation while progress remains deferred" in {
      val manager = createTestProbe[ActivityMgr.Msg]()
      val polls = createTestProbe[Boolean]()
      val cancellations = createTestProbe[JsValue]()
      val io = new ActivityIO {
        override def create(): Either[Throwable, JsValue] = Right(Json.arr("s-test"))
        override def getProgress(spec: JsValue): Either[Throwable, Status.Value] = {
          polls.ref ! true
          Right(Status.Running)
        }
        override def terminate(spec: JsValue): Either[Throwable, Status.Value] = {
          cancellations.ref ! spec
          Right(Status.Canceled)
        }
      }
      val actor = spawnRunning(io, manager.ref)
      manualTime.timePasses(5.seconds)
      polls.expectMessage(true)

      actor ! ActivityAttempt.Cancel

      cancellations.expectMessage(Json.arr("s-test"))
      manager.expectMessage(ActivityMgr.AttemptFinished(Status.Canceled))
      manualTime.expectNoMessageFor(20.seconds, polls)
    }
  }
}
