/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.io.aws.activity

import java.time.{Duration, Instant}

import scala.annotation.tailrec
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import org.slf4j.LoggerFactory
import play.api.libs.json._
import software.amazon.awssdk.services.emr.EmrClient
import software.amazon.awssdk.services.emr.model._

import com.salesforce.mce.orchard.io.ActivityIO
import com.salesforce.mce.orchard.io.aws.Client
import com.salesforce.mce.orchard.model.Status
import com.salesforce.mce.orchard.system.util.InvalidJsonException
import com.salesforce.mce.orchard.util.RetryHelper._

case class EmrActivity(
  name: String,
  steps: Seq[EmrActivity.Step],
  clusterId: String,
  failureHealthPolicy: EmrActivity.FailureHealthPolicy
) extends ActivityIO {

  import EmrActivity._

  private val logger = LoggerFactory.getLogger(getClass)

  override def create(): Either[Throwable, JsValue] = retryToEither {
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

    encodeAttemptSpec(response.stepIds().asScala.toSeq, failureHealthPolicy)
  }

  override def getProgress(spec: JsValue): Either[Throwable, Status.Value] =
    decodeAttemptSpec(spec)
      .fold(
        invalid => Left(InvalidJsonException.raise(invalid)),
        valid => originalProgress(valid.stepIds)
      )

  override def getProgressWithDetails(spec: JsValue): Either[Throwable, ActivityIO.Progress] =
    decodeAttemptSpec(spec)
      .fold(
        invalid => Left(InvalidJsonException.raise(invalid)),
        valid => detailedProgress(valid)
      )

  private def originalProgress(stepIds: Seq[String]): Either[Throwable, Status.Value] = {
    val client = Client.emr()
    describeSteps(client, stepIds).map(statuses => aggregateOriginalStatus(statuses.map(_._2.state())))
  }

  private def detailedProgress(spec: AttemptSpec): Either[Throwable, ActivityIO.Progress] = {
    val client = Client.emr()
    describeSteps(client, spec.stepIds).flatMap { originalSteps =>
      val originalStatus = aggregateOriginalStatus(originalSteps.map(_._2.state()))
      if (originalStatus != Status.Failed || failureHealthPolicy != FailureHealthPolicy.HdfsReadWrite) {
        Right(ActivityIO.Progress(originalStatus))
      } else {
        val failedSourceStep = firstFailedStepId(originalSteps)
        (failedSourceStep, spec.healthProbe) match {
          case (None, _) => Right(ActivityIO.Progress(Status.Failed))
          case (Some(sourceStepId), None) =>
            val pending = HealthProbe(
              sourceStepId = sourceStepId,
              phase = ProbePending,
              probeStepId = None,
              submittedAt = Instant.now()
            )
            Right(
              ActivityIO.Progress(
                status = Status.Running,
                detail = s"Preparing HDFS health probe for failed EMR step $sourceStepId",
                updatedAttemptSpec = Some(Json.toJson(spec.copy(healthProbe = Some(pending))))
              )
            )
          case (_, Some(probe)) if probe.phase == ProbePending =>
            if (probe.submittedAt.plus(ProbePendingTimeout).isBefore(Instant.now())) {
              try {
                val orphanIds = findStepsByName(client, healthProbeName(probe.sourceStepId)).map(_.id())
                cancelProbesBestEffort(client, orphanIds)
              } catch {
                case NonFatal(exp) =>
                  logger.warn(s"Unable to clean up pending HDFS health probe ${probe.sourceStepId}", exp)
              }
              Right(
                ActivityIO.Progress(
                  Status.Failed,
                  detail = s"EMR step ${probe.sourceStepId} failed; HDFS health probe could not be submitted"
                )
              )
            } else {
              try {
                val submitted = recoverOrCreateProbe(client, probe)
                Right(
                  ActivityIO.Progress(
                    status = Status.Running,
                    detail = s"Submitted HDFS health probe ${submitted.probeStepId.get}",
                    updatedAttemptSpec = Some(Json.toJson(spec.copy(healthProbe = Some(submitted))))
                  )
                )
              } catch {
                case NonFatal(exp) =>
                  logger.warn(
                    s"Unable to submit or recover HDFS health probe for ${probe.sourceStepId}; will retry discovery",
                    exp
                  )
                  Right(ActivityIO.Progress(Status.Running, detail = "Recovering HDFS health probe"))
              }
            }
          case (_, Some(probe)) if probe.phase == ProbeSubmitted => pollProbe(client, probe)
          case (_, Some(_)) => Left(new IllegalArgumentException("Invalid EMR health probe phase"))
        }
      }
    }
  }

  private def describeSteps(
    client: EmrClient,
    stepIds: Seq[String]
  ): Either[Throwable, Seq[(String, StepStatus)]] = retryToEither {
    stepIds.map { stepId =>
      val status = client
        .describeStep(DescribeStepRequest.builder().clusterId(clusterId).stepId(stepId).build())
        .step()
        .status()
      stepId -> status
    }
  }

  private def recoverOrCreateProbe(client: EmrClient, pending: HealthProbe): HealthProbe = {
    val probeName = healthProbeName(pending.sourceStepId)
    val matches = findStepsByName(client, probeName)
    matches.headOption match {
      case Some(existing) =>
        cancelProbesBestEffort(client, matches.drop(1).map(_.id()))
        pending.copy(
          phase = ProbeSubmitted,
          probeStepId = Some(existing.id()),
          submittedAt = Option(existing.status())
            .flatMap(status => Option(status.timeline()))
            .flatMap(timeline => Option(timeline.creationDateTime()))
            .getOrElse(pending.submittedAt)
        )
      case None =>
        val response = client.addJobFlowSteps(
          AddJobFlowStepsRequest
            .builder()
            .jobFlowId(clusterId)
            .steps(healthProbeStep(clusterId, pending.sourceStepId))
            .build()
        )
        pending.copy(
          phase = ProbeSubmitted,
          probeStepId = Some(response.stepIds().get(0)),
          submittedAt = Instant.now()
        )
    }
  }

  private def pollProbe(
    client: EmrClient,
    probe: HealthProbe
  ): Either[Throwable, ActivityIO.Progress] = retryToEither {
    val stepId = probe.probeStepId.getOrElse(
      throw new IllegalArgumentException("Submitted EMR health probe has no step ID")
    )
    val status = client
      .describeStep(DescribeStepRequest.builder().clusterId(clusterId).stepId(stepId).build())
      .step()
      .status()
    val now = Instant.now()
    val decision = evaluateProbe(probe, status, now)
    if (decision.cancel) cancelProbesBestEffort(client, Seq(stepId))
    reconcileProbeDuplicates(client, probe, stepId)

    if (decision.status == Status.Failed && decision.resourceFailure) {
      probeResourceFailure(probe, stepId, decision.reason)
    } else {
      decision.status match {
        case Status.Failed =>
          ActivityIO.Progress(
            Status.Failed,
            detail = s"EMR step ${probe.sourceStepId} failed; ${decision.reason}"
          )
        case Status.Running =>
          ActivityIO.Progress(Status.Running, detail = s"Waiting for HDFS health probe $stepId")
        case _ =>
          throw new IllegalStateException(s"Unexpected HDFS health probe status ${decision.status}")
      }
    }
  }

  private def cancelProbesBestEffort(client: EmrClient, stepIds: Seq[String]): scala.Unit = {
    if (stepIds.nonEmpty) {
      try {
        client.cancelSteps(
          CancelStepsRequest.builder().clusterId(clusterId).stepIds(stepIds: _*).build()
        )
      } catch {
        case NonFatal(exp) =>
          logger.warn(s"Unable to cancel HDFS health probe steps ${stepIds.mkString(",")}", exp)
      }
    }
    ()
  }

  private def reconcileProbeDuplicates(
    client: EmrClient,
    probe: HealthProbe,
    retainedStepId: String
  ): scala.Unit = try {
    val duplicateIds = findStepsByName(client, healthProbeName(probe.sourceStepId))
      .map(_.id())
      .filterNot(_ == retainedStepId)
    cancelProbesBestEffort(client, duplicateIds)
  } catch {
    case NonFatal(exp) =>
      logger.warn(s"Unable to reconcile duplicate HDFS health probes for $retainedStepId", exp)
  }

  private def probeResourceFailure(
    probe: HealthProbe,
    probeStepId: String,
    reason: String
  ): ActivityIO.Progress = ActivityIO.Progress(
    status = Status.Failed,
    resourceFailure = true,
    detail =
      s"EMR step ${probe.sourceStepId} failed and HDFS $reason in probe $probeStepId"
  )

  private def findStepsByName(client: EmrClient, expectedName: String): Seq[StepSummary] = {
    @tailrec
    def find(
      marker: Option[String],
      seenMarkers: Set[String],
      matches: Vector[StepSummary]
    ): Seq[StepSummary] = {
      val builder = ListStepsRequest.builder().clusterId(clusterId)
      marker.foreach(builder.marker)
      val response = client.listSteps(builder.build())
      val nextMatches = matches ++ response.steps().asScala.filter(_.name() == expectedName)
      Option(response.marker()).filterNot(seenMarkers).filter(_.nonEmpty) match {
        case Some(nextMarker) => find(Some(nextMarker), seenMarkers + nextMarker, nextMatches)
        case None => nextMatches
      }
    }

    find(None, Set.empty, Vector.empty)
  }

  private def terminate(spec: AttemptSpec): Status.Value = {
    val client = Client.emr()
    val probeStepIds = spec.healthProbe.flatMap(_.probeStepId).toSeq
    val discoveredProbeStepIds = spec.healthProbe.toSeq.flatMap { probe =>
      try findStepsByName(client, healthProbeName(probe.sourceStepId)).map(_.id())
      catch { case NonFatal(_) => Seq.empty }
    }
    client.cancelSteps(
      CancelStepsRequest
        .builder()
        .clusterId(clusterId)
        .stepIds((spec.stepIds ++ probeStepIds ++ discoveredProbeStepIds).distinct: _*)
        .build()
    )
    Status.Canceled
  }

  override def terminate(spec: JsValue): Either[Throwable, Status.Value] =
    decodeAttemptSpec(spec)
      .fold(
        invalid => Left(InvalidJsonException.raise(invalid)),
        valid => Right(terminate(valid))
      )
}

object EmrActivity {

  sealed trait FailureHealthPolicy

  private[activity] object FailureHealthPolicy {
    case object Disabled extends FailureHealthPolicy
    case object HdfsReadWrite extends FailureHealthPolicy

    implicit val reads: Reads[FailureHealthPolicy] = Reads {
      case JsString("None") => JsSuccess(Disabled)
      case JsString("HdfsReadWrite") => JsSuccess(HdfsReadWrite)
      case JsString(value) =>
        JsError(s"Invalid failureHealthPolicy '$value'; expected 'None' or 'HdfsReadWrite'")
      case _ => JsError("failureHealthPolicy must be a string")
    }
  }

  private[activity] val ProbePending = "pending"
  private[activity] val ProbeSubmitted = "submitted"
  private[activity] val ProbePendingTimeout = Duration.ofMinutes(10)
  private[activity] val ProbeRunningTimeout = Duration.ofMinutes(3)

  private[activity] val ProbeScript =
    """set -euo pipefail
      |probe_dir=/tmp/orchard-health-probe
      |probe_path="$probe_dir/$1-$2-$$"
      |payload=orchard-hdfs-health-probe
      |cleanup() { hdfs dfs -rm -f "$probe_path" >/dev/null 2>&1 || true; }
      |trap cleanup EXIT
      |hdfs dfs -mkdir -p "$probe_dir"
      |printf '%s' "$payload" | hdfs dfs -put -f - "$probe_path"
      |actual="$(hdfs dfs -cat "$probe_path")"
      |test "$actual" = "$payload""".stripMargin

  case class Step(jar: String, args: Seq[String])

  private[activity] case class HealthProbe(
    sourceStepId: String,
    phase: String,
    probeStepId: Option[String],
    submittedAt: Instant
  )

  private[activity] case class AttemptSpec(
    version: Int = 1,
    stepIds: Seq[String],
    healthProbe: Option[HealthProbe] = None
  )

  private[activity] case class ProbeDecision(
    status: Status.Value,
    resourceFailure: Boolean = false,
    cancel: Boolean = false,
    reason: String = ""
  )

  private implicit val instantFormat: Format[Instant] = Format(
    Reads.StringReads.flatMapResult(value =>
      try JsSuccess(Instant.parse(value))
      catch { case exp: Exception => JsError(s"Invalid instant: ${exp.getMessage}") }
    ),
    Writes(value => JsString(value.toString))
  )
  private[activity] implicit val healthProbeFormat: OFormat[HealthProbe] = Json.format[HealthProbe]
  private[activity] implicit val attemptSpecFormat: OFormat[AttemptSpec] = Json.format[AttemptSpec]

  private[activity] def decodeAttemptSpec(value: JsValue): JsResult[AttemptSpec] =
    value.validate[AttemptSpec].fold(
      _ => value.validate[Seq[String]].map(stepIds => AttemptSpec(stepIds = stepIds)),
      valid => JsSuccess(valid)
    )

  private[activity] def encodeAttemptSpec(
    stepIds: Seq[String],
    failureHealthPolicy: FailureHealthPolicy
  ): JsValue = failureHealthPolicy match {
    case FailureHealthPolicy.Disabled => Json.toJson(stepIds)
    case FailureHealthPolicy.HdfsReadWrite => Json.toJson(AttemptSpec(stepIds = stepIds))
  }

  private[activity] def aggregateOriginalStatus(states: Seq[StepState]): Status.Value = {
    if (states.forall(_ == StepState.COMPLETED)) Status.Finished
    else if (states.contains(StepState.FAILED) || states.contains(StepState.CANCELLED)) Status.Failed
    else Status.Running
  }

  private[activity] def firstFailedStepId(
    steps: Seq[(String, StepStatus)]
  ): Option[String] = steps.collectFirst {
    case (stepId, status) if status.state() == StepState.FAILED => stepId
  }

  private[activity] def evaluateProbe(
    probe: HealthProbe,
    status: StepStatus,
    now: Instant
  ): ProbeDecision = {
    val timeline = Option(status.timeline())
    status.state() match {
      case StepState.COMPLETED =>
        ProbeDecision(Status.Failed, reason = "HDFS health probe succeeded")
      case StepState.FAILED | StepState.CANCELLED | StepState.INTERRUPTED =>
        ProbeDecision(
          Status.Failed,
          resourceFailure = true,
          reason = s"health probe ended ${status.stateAsString()}"
        )
      case StepState.RUNNING
          if timeline
            .flatMap(value => Option(value.startDateTime()))
            .exists(_.plus(ProbeRunningTimeout).isBefore(now)) =>
        ProbeDecision(
          Status.Failed,
          resourceFailure = true,
          cancel = true,
          reason = "health probe exceeded its running deadline"
        )
      case StepState.PENDING | StepState.CANCEL_PENDING
          if probe.submittedAt.plus(ProbePendingTimeout).isBefore(now) =>
        ProbeDecision(
          Status.Failed,
          cancel = true,
          reason = "HDFS health probe remained queued"
        )
      case _ => ProbeDecision(Status.Running)
    }
  }

  private[activity] def healthProbeName(sourceStepId: String): String =
    s"orchard-hdfs-health-probe-$sourceStepId"

  private[activity] def healthProbeStep(clusterId: String, sourceStepId: String): StepConfig =
    StepConfig
      .builder()
      .name(healthProbeName(sourceStepId))
      .actionOnFailure(ActionOnFailure.CONTINUE)
      .hadoopJarStep(
        HadoopJarStepConfig
          .builder()
          .jar("command-runner.jar")
          .args(
            "timeout",
            "--signal=TERM",
            "--kill-after=15s",
            "90s",
            "bash",
            "-c",
            ProbeScript,
            "_",
            clusterId,
            sourceStepId
          )
          .build()
      )
      .build()

  private def applyContext(step: Step, ctx: ActivityIO.Conf) = step.copy(
    args = step.args.map(ActivityContext.replace(_, ctx))
  )

  implicit val stepReads: Reads[Step] = Json.reads[Step]

  def decode(conf: ActivityIO.Conf): JsResult[EmrActivity] = {
    for {
      steps <- (conf.activitySpec \ "steps")
        .validate[Seq[EmrActivity.Step]]
        .map(_.map(applyContext(_, conf)))
      failureHealthPolicy <- (conf.activitySpec \ "failureHealthPolicy")
        .validateOpt[FailureHealthPolicy]
        .map(_.getOrElse(FailureHealthPolicy.Disabled))
      clusterId <- (conf.resourceInstSpec \ "clusterId").validate[String]
    } yield {
      EmrActivity(conf.activityName, steps, clusterId, failureHealthPolicy)
    }
  }
}
