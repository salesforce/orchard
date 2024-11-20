/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.io.aws.activity

import java.time.{LocalDateTime, ZoneOffset}
import java.time.temporal.ChronoUnit

import scala.jdk.CollectionConverters.{MapHasAsJava, SeqHasAsJava}

import org.slf4j.LoggerFactory
import play.api.libs.json.{JsResult, JsValue, Json, Reads}
import software.amazon.awssdk.services.ssm.model.SendCommandRequest

import com.salesforce.mce.orchard.io.ActivityIO
import com.salesforce.mce.orchard.io.aws.util.S3Uri
import com.salesforce.mce.orchard.io.aws.{Client, ProviderSettings}
import com.salesforce.mce.orchard.util.RetryHelper._

case class ShellScriptActivity(
  name: String,
  scriptLocation: String,
  args: Seq[String],
  ec2InstanceId: String,
  outputUri: Option[String],
  executionTimeout: Option[Int],
  deliveryTimeout: Option[Int],
  region: Option[String],
  endPoint: Option[String]
) extends Ec2Activity(name, ec2InstanceId) {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * create activity via AWS SSM SendCommand to an ec2Instance
   * @return  SSM command-id in a single entry list
   */
  override def create(): Either[Throwable, JsValue] = retryToEither {
    logger.debug(
      s"create: name=$name ec2InstanceId=$ec2InstanceId scriptLocation=$scriptLocation args=$args"
    )

    lazy val ts = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES)
    val preRunInfo = s"cd ~ "
    val scriptPrefix = "aws s3 cp"
    val scriptWithRegion = region.fold(scriptPrefix)(r => s"$scriptPrefix --region=$r")
    val scriptWithEndpoint = endPoint.fold(scriptWithRegion)(r => s"$scriptWithRegion --endpoint-url=$r")
    val pullScript = s"$scriptWithEndpoint $scriptLocation . "
    val scriptFile = scriptLocation.split("/").last
    val chmodScript = s"chmod +x ./$scriptFile"
    val runScript = s" ./$scriptFile ${args.mkString(" ")}"
    val commands =
      List(
        preRunInfo,
        s"echo $pullScript",
        pullScript,
        chmodScript,
        s"echo $runScript",
        runScript
      )
    commands.foreach(c => logger.debug(s"create: command=$c"))

    val client = Client.ssm()
    val paraMap =
      Map("commands" -> commands.asJava) ++
        executionTimeout.map(v => "executionTimeout" -> List(v.toString).asJava).toMap
    val loggingUri = outputUri.orElse(ProviderSettings().loggingUri).map(S3Uri.apply)

    val builder = loggingUri
      .foldLeft(
        SendCommandRequest
          .builder()
          .documentName(RunShellScriptDocument)
          .instanceIds(ec2InstanceId)
          .comment(getComment(getClass))
          .parameters(paraMap.asJava)
      )((r, uri) =>
        r
          .outputS3BucketName(uri.bucket)
          .outputS3KeyPrefix(s"${uri.path.stripSuffix("/")}/${name}_$ts")
      )
    val request = deliveryTimeout
      .foldLeft(builder)(_.timeoutSeconds(_))
      .build()

    val response = client.sendCommand(request)
    client.close()
    val command = response.command()
    val commandId = command.commandId()
    logger.debug(s"create: sendCommand commandId=$commandId command=${command.toString}")

    Json.toJson(List(commandId))
  }

  override def toString: String = {
    s"ShellScriptActivity: $name scriptLocation=$scriptLocation args=$args outputUri=$outputUri " +
      s"executionTimeout=$executionTimeout deliveryTimeout=$deliveryTimeout.  " +
      s"${super.toString}"
  }

}

object ShellScriptActivity {

  case class Spec(
    scriptLocation: String,
    args: Seq[String],
    outputUri: Option[String],
    executionTimeout: Option[Int],
    deliveryTimeout: Option[Int],
    region: Option[String],
    endPoint: Option[String]
  )

  implicit val specReads: Reads[Spec] = Json.reads[Spec]

  def decode(conf: ActivityIO.Conf): JsResult[ShellScriptActivity] = {
    for {
      spec <- conf.activitySpec.validate[Spec]
      ec2InstanceId <- (conf.resourceInstSpec \ "ec2InstanceId").validate[String]
    } yield
      ShellScriptActivity(
        s"${conf.workflowId}_act-${conf.activityId}_${conf.attemptId}",
        spec.scriptLocation,
        spec.args.map(ActivityContext.replace(_, conf)),
        ec2InstanceId,
        spec.outputUri,
        spec.executionTimeout,
        spec.deliveryTimeout,
        spec.region,
        spec.endPoint
      )
  }

}
