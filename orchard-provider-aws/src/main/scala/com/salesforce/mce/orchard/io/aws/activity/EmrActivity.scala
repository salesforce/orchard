/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.io.aws.activity

import java.io.{ByteArrayOutputStream, IOException, InputStream}
import java.time.{Duration, Instant}
import java.util.zip.GZIPInputStream

import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import org.slf4j.LoggerFactory
import play.api.libs.json.{JsResult, JsValue, Json, Reads}
import software.amazon.awssdk.services.emr.model._
import software.amazon.awssdk.services.s3.model.{GetObjectRequest, NoSuchKeyException, S3Exception}

import com.salesforce.mce.orchard.io.ActivityIO
import com.salesforce.mce.orchard.io.aws.Client
import com.salesforce.mce.orchard.io.aws.util.S3Uri
import com.salesforce.mce.orchard.model.Status
import com.salesforce.mce.orchard.system.util.InvalidJsonException
import com.salesforce.mce.orchard.util.RetryHelper._

case class EmrActivity(name: String, steps: Seq[EmrActivity.Step], clusterId: String)
    extends ActivityIO {

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

    val stepIds = response.stepIds().asScala
    Json.toJson(stepIds)
  }

  private def getProgress(steps: Seq[String]): Either[Throwable, ActivityIO.Progress] = {
    val client = Client.emr()
    val statuses = retryToEither {
      steps
        .map { stepId =>
          val status = client
            .describeStep(DescribeStepRequest.builder().clusterId(clusterId).stepId(stepId).build())
            .step()
            .status()
          stepId -> status
        }
    }

    statuses.map { stepStatuses =>
      val states = stepStatuses.map(_._2.state())
      if (states.forall(_ == StepState.COMPLETED)) {
        ActivityIO.Progress(Status.Finished)
      } else if (states.contains(StepState.FAILED)) {
        classifyFailedSteps(stepStatuses.filter(_._2.state() == StepState.FAILED), client)
      } else if (states.contains(StepState.CANCELLED)) {
        // when EMR cluster terminate with error, the StepState is CANCELLED, return Fail so new attempts could be made
        ActivityIO.Progress(Status.Failed)
      } else {
        ActivityIO.Progress(Status.Running)
      }
    }
  }

  override def getProgressWithDetails(spec: JsValue): Either[Throwable, ActivityIO.Progress] = spec
    .validate[Seq[String]]
    .fold(
      invalid => Left(InvalidJsonException.raise(invalid)),
      valid => getProgress(valid)
    )

  override def getProgress(spec: JsValue): Either[Throwable, Status.Value] =
    getProgressWithDetails(spec).map(_.status)

  private def classifyFailedSteps(
    failedSteps: Seq[(String, StepStatus)],
    client: software.amazon.awssdk.services.emr.EmrClient
  ): ActivityIO.Progress = {
    val clusterLogUri = try {
      Option(
        client
          .describeCluster(DescribeClusterRequest.builder().clusterId(clusterId).build())
          .cluster()
          .logUri()
      )
    } catch {
      case NonFatal(_) => None
    }

    var waitingForLog = false
    val stepIterator = failedSteps.iterator
    while (stepIterator.hasNext) {
      val (stepId, status) = stepIterator.next()
      val details = Option(status.failureDetails())
      val metadata = details
        .toSeq
        .flatMap(d => Seq(Option(d.reason()), Option(d.message())).flatten)
        .mkString("\n")
      if (EmrStepFailureClassifier.isClusterFatal(metadata, "")) {
        return resourceFailure(stepId)
      }
      val log = (for {
        base <- clusterLogUri
        failure <- details
        logFile <- Option(failure.logFile())
      } yield readFailureLog(base, logFile)).getOrElse(FailureLogUnavailable)

      log match {
        case FailureLogAvailable(stderr)
            if EmrStepFailureClassifier.isClusterFatal(metadata, stderr) =>
          return resourceFailure(stepId)
        case FailureLogAvailable(_) =>
        case FailureLogPending if isWithinLogAvailabilityGrace(status) => waitingForLog = true
        case FailureLogPending =>
          logger.warn(s"EMR failure log did not become available for step $stepId")
        case FailureLogUnavailable =>
          logger.warn(s"Unable to read EMR failure log for step $stepId; preserving normal retry")
      }
    }

    if (waitingForLog) {
      ActivityIO.Progress(Status.Running, detail = "Waiting for EMR failure log")
    } else {
      ActivityIO.Progress(Status.Failed)
    }
  }

  private def resourceFailure(stepId: String): ActivityIO.Progress = ActivityIO.Progress(
    status = Status.Failed,
    resourceFailure = true,
    detail = s"EMR step $stepId failed while Spark staging could not reach writable HDFS DataNodes"
  )

  private def isWithinLogAvailabilityGrace(status: StepStatus): Boolean =
    Option(status.timeline())
      .flatMap(timeline => Option(timeline.endDateTime()))
      .exists(_.plus(LogAvailabilityGrace).isAfter(Instant.now()))

  /**
    * Reads only a log object that EMR reports beneath this cluster's configured log prefix.
    * Missing objects remain pending only for the bounded availability grace. Invalid paths,
    * authorization failures, and other permanent errors preserve the normal activity retry.
    */
  private def readFailureLog(clusterLogUri: String, failureLogUri: String): FailureLogRead = {
    val locations = try {
      val base = S3Uri(clusterLogUri)
      val failure = S3Uri(failureLogUri)
      if (base.bucket != failure.bucket || !isKeyUnderPrefix(failure.path, base.path)) {
        Seq.empty
      } else {
        candidateLogKeys(failure.path)
          .filter(isKeyUnderPrefix(_, base.path))
          .map(key => base.bucket -> key)
      }
    } catch {
      case NonFatal(_) => Seq.empty
    }

    if (locations.isEmpty) {
      FailureLogUnavailable
    } else {
      try {
        val s3 = Client.s3()
        try {
          val reads = locations.map { case (bucket, key) => readS3Object(s3, bucket, key) }
          reads.collectFirst { case available: FailureLogAvailable => available }
            .getOrElse {
              if (reads.contains(FailureLogPending)) FailureLogPending else FailureLogUnavailable
            }
        } finally {
          s3.close()
        }
      } catch {
        case NonFatal(_) => FailureLogUnavailable
      }
    }
  }

  private def readS3Object(
    client: software.amazon.awssdk.services.s3.S3Client,
    bucket: String,
    key: String
  ): FailureLogRead = {
    var response: InputStream = null
    try {
      val objectResponse = client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())
      response = objectResponse
      if (objectResponse.response().contentLength() > MaxCompressedLogBytes) {
        FailureLogUnavailable
      } else {
        val bounded = new BoundedInputStream(response, MaxCompressedLogBytes)
        val decoded = if (key.endsWith(".gz")) new GZIPInputStream(bounded) else bounded
        FailureLogAvailable(readUtf8(decoded, MaxDecodedLogBytes))
      }
    } catch {
      case _: NoSuchKeyException => FailureLogPending
      case exp: S3Exception if exp.statusCode() == 404 => FailureLogPending
      case NonFatal(_) => FailureLogUnavailable
    } finally {
      if (response != null) {
        try response.close()
        catch { case NonFatal(_) => }
      }
    }
  }

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

  private sealed trait FailureLogRead
  private case class FailureLogAvailable(contents: String) extends FailureLogRead
  private case object FailureLogPending extends FailureLogRead
  private case object FailureLogUnavailable extends FailureLogRead

  private val LogAvailabilityGrace = Duration.ofMinutes(6)
  private val MaxCompressedLogBytes = 8L * 1024 * 1024
  private val MaxDecodedLogBytes = 8L * 1024 * 1024

  private class BoundedInputStream(delegate: InputStream, limit: Long) extends InputStream {
    private var readCount = 0L

    override def read(): Int = {
      if (readCount >= limit) {
        throw new IOException(s"input exceeds $limit bytes")
      }
      val value = delegate.read()
      if (value >= 0) readCount += 1
      value
    }

    override def read(buffer: Array[Byte], offset: Int, length: Int): Int = {
      if (readCount >= limit) {
        throw new IOException(s"input exceeds $limit bytes")
      }
      val allowed = math.min(length.toLong, limit - readCount).toInt
      val count = delegate.read(buffer, offset, allowed)
      if (count > 0) readCount += count
      count
    }
  }

  private def readUtf8(input: InputStream, limit: Long): String = {
    val output = new ByteArrayOutputStream()
    val buffer = new Array[Byte](8192)
    var total = 0L
    var count = input.read(buffer)
    while (count >= 0) {
      total += count
      if (total > limit) {
        throw new IOException(s"decoded log exceeds $limit bytes")
      }
      output.write(buffer, 0, count)
      count = input.read(buffer)
    }
    output.toString(java.nio.charset.StandardCharsets.UTF_8.name())
  }

  private def candidateLogKeys(path: String): Seq[String] = {
    if (path.endsWith("/stderr.gz")) Seq(path, path.stripSuffix(".gz"))
    else if (path.endsWith("/stderr")) Seq(s"$path.gz", path)
    else Seq(s"${path.stripSuffix("/")}/stderr.gz", s"${path.stripSuffix("/")}/stderr")
  }

  private def isKeyUnderPrefix(key: String, rawPrefix: String): Boolean = {
    val prefix = rawPrefix.stripSuffix("/")
    val safeSegments = key.split("/", -1).forall(segment => segment != "." && segment != "..")
    safeSegments && key.nonEmpty &&
      (prefix.isEmpty || key.startsWith(s"$prefix/"))
  }

  case class Step(jar: String, args: Seq[String])

  private def applyContext(step: Step, ctx: ActivityIO.Conf) = step.copy(
    args = step.args.map(ActivityContext.replace(_, ctx))
  )

  implicit val stepReads: Reads[Step] = Json.reads[Step]

  def decode(conf: ActivityIO.Conf): JsResult[EmrActivity] = {
    for {
      steps <- (conf.activitySpec \ "steps")
        .validate[Seq[EmrActivity.Step]]
        .map(_.map(applyContext(_, conf)))
      clusterId <- (conf.resourceInstSpec \ "clusterId").validate[String]
    } yield {
      EmrActivity(conf.activityName, steps, clusterId)
    }
  }

}
