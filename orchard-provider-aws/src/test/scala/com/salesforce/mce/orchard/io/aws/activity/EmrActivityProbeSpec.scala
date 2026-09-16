/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.io.aws.activity

import java.time.Instant

import scala.jdk.CollectionConverters._

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{JsError, JsSuccess, Json}
import software.amazon.awssdk.services.emr.model.{ActionOnFailure, StepState, StepStatus, StepTimeline}

import com.salesforce.mce.orchard.io.ActivityIO
import com.salesforce.mce.orchard.model.Status

class EmrActivityProbeSpec extends AnyWordSpec with Matchers {

  import EmrActivity._

  private def activityConf(activitySpec: play.api.libs.json.JsValue): ActivityIO.Conf =
    ActivityIO.Conf(
      workflowId = "workflow",
      activityId = "activity",
      activityName = "activity-name",
      attemptId = 1,
      activityType = "aws.activity.EmrActivity",
      activitySpec = activitySpec,
      resourceInstSpec = Json.obj("clusterId" -> "j-cluster")
    )

  private val stepsJson = Json.arr(
    Json.obj("jar" -> "command-runner.jar", "args" -> Json.arr("echo", "hello"))
  )

  "EMR failure health policy" should {
    "default an absent policy to disabled" in {
      val decoded = EmrActivity.decode(activityConf(Json.obj("steps" -> stepsJson)))

      decoded.map(_.failureHealthPolicy) shouldBe JsSuccess(FailureHealthPolicy.Disabled)
      encodeAttemptSpec(Seq("s-one"), FailureHealthPolicy.Disabled) shouldBe Json.arr("s-one")
    }

    "decode an explicit None policy as disabled" in {
      val decoded = EmrActivity.decode(
        activityConf(Json.obj("steps" -> stepsJson, "failureHealthPolicy" -> "None"))
      )

      decoded.map(_.failureHealthPolicy) shouldBe JsSuccess(FailureHealthPolicy.Disabled)
    }

    "enable versioned probe state only for HdfsReadWrite" in {
      val decoded = EmrActivity.decode(
        activityConf(Json.obj("steps" -> stepsJson, "failureHealthPolicy" -> "HdfsReadWrite"))
      )

      decoded.map(_.failureHealthPolicy) shouldBe JsSuccess(FailureHealthPolicy.HdfsReadWrite)
      encodeAttemptSpec(Seq("s-one"), FailureHealthPolicy.HdfsReadWrite) shouldBe
        Json.toJson(AttemptSpec(stepIds = Seq("s-one")))
    }

    "reject an unknown policy" in {
      val decoded = EmrActivity.decode(
        activityConf(Json.obj("steps" -> stepsJson, "failureHealthPolicy" -> "Unknown"))
      )

      decoded shouldBe a[JsError]
      decoded.toString should include("Invalid failureHealthPolicy 'Unknown'")
    }
  }

  "EMR attempt spec" should {
    "decode the legacy step ID array" in {
      decodeAttemptSpec(Json.arr("s-one", "s-two")) shouldBe JsSuccess(
        AttemptSpec(stepIds = Seq("s-one", "s-two"))
      )
    }

    "round-trip pending probe intent state" in {
      val spec = AttemptSpec(
        stepIds = Seq("s-source"),
        healthProbe = Some(
          HealthProbe(
            sourceStepId = "s-source",
            phase = ProbePending,
            probeStepId = None,
            submittedAt = Instant.parse("2026-09-14T12:00:00Z")
          )
        )
      )

      decodeAttemptSpec(Json.toJson(spec)) shouldBe JsSuccess(spec)
    }

    "round-trip a submitted probe step ID" in {
      val spec = AttemptSpec(
        stepIds = Seq("s-source"),
        healthProbe = Some(
          HealthProbe(
            sourceStepId = "s-source",
            phase = ProbeSubmitted,
            probeStepId = Some("s-probe"),
            submittedAt = Instant.parse("2026-09-14T12:00:00Z")
          )
        )
      )

      decodeAttemptSpec(Json.toJson(spec)) shouldBe JsSuccess(spec)
    }
  }

  "HDFS health probe step" should {
    "perform a bounded non-empty HDFS write, read, and cleanup" in {
      val step = healthProbeStep("j-cluster", "s-source")
      val args = step.hadoopJarStep().args().asScala.toSeq

      step.name() shouldBe "orchard-hdfs-health-probe-s-source"
      step.actionOnFailure() shouldBe ActionOnFailure.CONTINUE
      step.hadoopJarStep().jar() shouldBe "command-runner.jar"
      args.take(7) shouldBe Seq(
        "timeout",
        "--signal=TERM",
        "--kill-after=15s",
        "90s",
        "bash",
        "-c",
        ProbeScript
      )
      args.takeRight(3) shouldBe Seq("_", "j-cluster", "s-source")
      ProbeScript should include("printf '%s' \"$payload\" | hdfs dfs -put -f -")
      ProbeScript should include("hdfs dfs -cat")
      ProbeScript should include("hdfs dfs -rm -f")
    }

    "keep AWS identifiers out of the shell program" in {
      val step = healthProbeStep("j-cluster; touch /tmp/bad", "s-source; exit 0")
      val args = step.hadoopJarStep().args().asScala.toSeq

      ProbeScript should not include "j-cluster"
      ProbeScript should not include "s-source"
      args.takeRight(2) shouldBe Seq("j-cluster; touch /tmp/bad", "s-source; exit 0")
    }
  }

  "original EMR status aggregation" should {
    "retain the previous terminal and running behavior" in {
      aggregateOriginalStatus(Seq(StepState.COMPLETED)) shouldBe Status.Finished
      aggregateOriginalStatus(Seq(StepState.COMPLETED, StepState.FAILED)) shouldBe Status.Failed
      aggregateOriginalStatus(Seq(StepState.CANCELLED)) shouldBe Status.Failed
      aggregateOriginalStatus(Seq(StepState.RUNNING)) shouldBe Status.Running
    }

    "not select a cancelled step for an HDFS probe" in {
      val cancelled = StepStatus.builder().state(StepState.CANCELLED).build()

      firstFailedStepId(Seq("s-cancelled" -> cancelled)) shouldBe None
    }
  }

  "HDFS health probe decisions" should {
    val submittedAt = Instant.parse("2026-09-14T12:00:00Z")
    val probe = HealthProbe(
      sourceStepId = "s-source",
      phase = ProbeSubmitted,
      probeStepId = Some("s-probe"),
      submittedAt = submittedAt
    )

    "preserve a same-cluster retry when the probe succeeds" in {
      val status = StepStatus.builder().state(StepState.COMPLETED).build()

      evaluateProbe(probe, status, submittedAt.plusSeconds(30)) shouldBe ProbeDecision(
        Status.Failed,
        reason = "HDFS health probe succeeded"
      )
    }

    "replace the resource when the probe fails" in {
      val status = StepStatus.builder().state(StepState.FAILED).build()
      val decision = evaluateProbe(probe, status, submittedAt.plusSeconds(30))

      decision.status shouldBe Status.Failed
      decision.resourceFailure shouldBe true
      decision.cancel shouldBe false
    }

    "replace the resource and cancel a probe that exceeds its running deadline" in {
      val status = StepStatus
        .builder()
        .state(StepState.RUNNING)
        .timeline(StepTimeline.builder().startDateTime(submittedAt).build())
        .build()
      val decision = evaluateProbe(probe, status, submittedAt.plus(ProbeRunningTimeout).plusSeconds(1))

      decision.status shouldBe Status.Failed
      decision.resourceFailure shouldBe true
      decision.cancel shouldBe true
    }

    "fail open without replacing the resource when the probe remains queued" in {
      val status = StepStatus.builder().state(StepState.PENDING).build()
      val decision = evaluateProbe(probe, status, submittedAt.plus(ProbePendingTimeout).plusSeconds(1))

      decision.status shouldBe Status.Failed
      decision.resourceFailure shouldBe false
      decision.cancel shouldBe true
    }

    "continue polling within both deadlines" in {
      val pending = StepStatus.builder().state(StepState.PENDING).build()
      val running = StepStatus
        .builder()
        .state(StepState.RUNNING)
        .timeline(StepTimeline.builder().startDateTime(submittedAt).build())
        .build()

      evaluateProbe(probe, pending, submittedAt.plusSeconds(30)).status shouldBe Status.Running
      evaluateProbe(probe, running, submittedAt.plusSeconds(30)).status shouldBe Status.Running
    }
  }
}
