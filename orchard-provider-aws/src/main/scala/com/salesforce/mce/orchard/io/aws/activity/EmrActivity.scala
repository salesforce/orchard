/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.io.aws.activity

import scala.jdk.CollectionConverters._
import scala.util.Try

import play.api.libs.json.{JsResult, JsValue, Json}
import software.amazon.awssdk.services.emr.model._

import com.salesforce.mce.orchard.io.ActivityIO
import com.salesforce.mce.orchard.io.aws.Client
import com.salesforce.mce.orchard.model.Status
import com.salesforce.mce.orchard.system.util.InvalidJsonException

case class EmrActivity(name: String, steps: Seq[EmrActivity.Step], clusterId: String)
    extends ActivityIO {

  override def create(): Either[Throwable, JsValue] = Try {
    val response = Client
      .emr()
      .addJobFlowSteps(
        AddJobFlowStepsRequest
          .builder()
          .jobFlowId(clusterId)
          .steps(
            steps.map { step =>
              StepConfig
                .builder()
                .name(name)
                .actionOnFailure(ActionOnFailure.CONTINUE)
                .hadoopJarStep(
                  HadoopJarStepConfig
                    .builder()
                    .jar(step.jar)
                    .args(step.args: _*)
                    .build()
                )
                .build()
            }.asJava
          )
          .build()
      )

    val stepIds = response.stepIds().asScala
    Json.toJson(stepIds)
  }.toEither

  private def getProgress(steps: Seq[String]) = {
    val client = Client.emr()
    val statuses = steps.map { stepId =>
      client
        .describeStep(DescribeStepRequest.builder().clusterId(clusterId).stepId(stepId).build())
        .step()
        .status()
        .state()
    }

    if (statuses.forall(ss => ss == StepState.COMPLETED)) {
      Status.Finished
    } else if (statuses.exists(ss => ss == StepState.FAILED)) {
      Status.Failed
    } else if (statuses.exists(ss => ss == StepState.CANCELLED)) {
      Status.Canceled
    } else {
      Status.Running
    }

  }

  override def getProgress(spec: JsValue): Either[Throwable, Status.Value] = spec
    .validate[Seq[String]]
    .fold(
      invalid => Left(InvalidJsonException.raise(invalid)),
      valid => Right(getProgress(valid))
    )

  private def terminate(steps: Seq[String]) = {
    val client = Client.emr()
    client.cancelSteps(CancelStepsRequest.builder().clusterId(clusterId).stepIds(steps: _*).build())
    Status.Canceled
  }

  override def terminate(spec: JsValue): Either[Throwable, Status.Value] = spec
    .validate[Seq[String]]
    .fold(
      { invalid => Left(InvalidJsonException.raise(invalid)) },
      valid => Right(terminate(valid))
    )

}

object EmrActivity {

  case class Step(jar: String, args: Seq[String])

  implicit val stepReads = Json.reads[Step]

  def decode(conf: ActivityIO.Conf): JsResult[EmrActivity] = {
    for {
      steps <- (conf.activitySpec \ "steps").validate[Seq[EmrActivity.Step]]
      clusterId <- (conf.resourceInstSpec \ "clusterId").validate[String]
    } yield {
      EmrActivity(s"${conf.workflowId}_act-${conf.activityId}_${conf.attemptId}", steps, clusterId)
    }
  }

}
