/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.io.aws.activity

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.Json
import software.amazon.awssdk.awscore.exception.{AwsErrorDetails, AwsServiceException}
import software.amazon.awssdk.services.emr.EmrClient
import software.amazon.awssdk.services.emr.model.{
  DescribeStepRequest,
  DescribeStepResponse,
  EmrException,
  Step,
  StepState,
  StepStatus
}

import com.salesforce.mce.orchard.model.Status

class EmrActivitySpec extends AnyWordSpec with Matchers {

  private class StubEmrClient(state: String => StepState) extends EmrClient {
    override def describeStep(request: DescribeStepRequest): DescribeStepResponse =
      DescribeStepResponse
        .builder()
        .step(
          Step.builder().status(StepStatus.builder().state(state(request.stepId())).build()).build()
        )
        .build()

    override def serviceName(): String = EmrClient.SERVICE_NAME
    override def close(): Unit = ()
  }

  private val activity = EmrActivity("test-activity", Seq.empty, "j-test")

  private def serviceError(code: String, status: Int): AwsServiceException = {
    val builder = EmrException.builder()
    builder.message("Rate exceeded")
    builder.statusCode(status)
    builder.awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build())
    builder.build()
  }

  "EMR progress polling" should {
    "defer an exhausted throttled query and observe completion on a later poll" in {
      val throttled = serviceError("ThrottlingException", 400)
      var available = false
      val client = new StubEmrClient(_ => if (available) StepState.COMPLETED else throw throttled)

      activity.getProgress(Seq("s-test"), client) shouldBe Right(Status.Running)

      available = true
      activity.getProgress(Seq("s-test"), client) shouldBe Right(Status.Finished)
    }

    "defer HTTP 429 even when the response has no AWS error details" in {
      val throttled = EmrException.builder().statusCode(429).build()
      val client = new StubEmrClient(_ => throw throttled)

      activity.getProgress(Seq("s-test"), client) shouldBe Right(Status.Running)
    }

    "keep non-throttling service errors as failures rather than matching their message" in {
      val denied = serviceError("AccessDeniedException", 403)
      val client = new StubEmrClient(_ => throw denied)

      activity.getProgress(Seq("s-test"), client) shouldBe Left(denied)
    }

    "keep other provider errors as failures" in {
      val failure = new IllegalStateException("Rate exceeded")
      val client = new StubEmrClient(_ => throw failure)

      activity.getProgress(Seq("s-test"), client) shouldBe Left(failure)
    }

    "preserve terminal EMR step states" in {
      Seq(StepState.FAILED, StepState.CANCELLED).foreach { state =>
        activity.getProgress(Seq("s-test"), new StubEmrClient(_ => state)) shouldBe
          Right(Status.Failed)
      }
      activity.getProgress(Seq("s-test"), new StubEmrClient(_ => StepState.COMPLETED)) shouldBe
        Right(Status.Finished)
    }

    "require every step to complete before reporting success" in {
      val client =
        new StubEmrClient(id => if (id == "s-done") StepState.COMPLETED else StepState.RUNNING)

      activity.getProgress(Seq("s-done", "s-running"), client) shouldBe Right(Status.Running)
    }

    "keep invalid attempt specs as errors before creating an AWS client" in {
      activity.getProgress(Json.obj("not" -> "a step list")).isLeft shouldBe true
    }
  }
}
