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
import play.api.libs.json.{JsResult, JsValue, Json}
import software.amazon.awssdk.services.ssm.model.SendCommandRequest

import com.salesforce.mce.orchard.io.ActivityIO
import com.salesforce.mce.orchard.io.aws.Client
import com.salesforce.mce.orchard.io.aws.util.Retry

case class ShellScriptActivity(
  name: String,
  scriptLocation: String,
  args: Seq[String],
  outputS3BucketName: String,
  outputS3KeyPrefix: String,
  executionTimeout: Int,
  deliveryTimeout: Int,
  ec2InstanceId: String
) extends Ec2Activity(name, ec2InstanceId) {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * create activity via AWS SSM SendCommand to an ec2Instance
   * @return  SSM command-id in a single entry list
   */
  override def create(): Either[Throwable, JsValue] = Retry() {

    logger.debug(
      s"create: name=$name ec2InstanceId=$ec2InstanceId scriptLocation=$scriptLocation args=$args"
    )

    lazy val ts = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES)
    val preRunInfo = s"cd ~ "
    val pullScript = s"aws s3 cp $scriptLocation . "
    val scriptFile = scriptLocation.split("/").last
    val chmodScript = s"chmod +x ./$scriptFile"
    val runScript = s" ./$scriptFile ${args.mkString(" ")}"
    val commands =
      List(preRunInfo, s"echo $pullScript", pullScript, chmodScript, s"echo $runScript", runScript)
    commands.foreach(c => logger.debug(s"create: command=$c"))

    val client = Client.ssm()
    val paraMap = Map(
      "commands" -> commands.asJava,
      "executionTimeout" -> List(executionTimeout.toString).asJava
    )
    val request =
      SendCommandRequest
        .builder()
        .documentName(RunShellScriptDocument)
        .instanceIds(ec2InstanceId)
        .comment(getComment(getClass))
        .parameters(paraMap.asJava)
        .timeoutSeconds(deliveryTimeout)
        .outputS3BucketName(outputS3BucketName)
        .outputS3KeyPrefix(s"${outputS3KeyPrefix.stripPrefix("/")}/${name}_$ts")
        .build()
    val response = client.sendCommand(request)
    client.close()
    val command = response.command()
    val commandId = command.commandId()
    logger.debug(s"create: sendCommand commandId=$commandId command=${command.toString}")

    Json.toJson(List(commandId))
  }.toEither

  override def toString: String = {
    s"ShellScriptActivity: $name scriptLocation=$scriptLocation args=$args outputS3BucketName=$outputS3BucketName " +
      s"outputS3KeyPrefix=$outputS3KeyPrefix executionTimeout=$executionTimeout deliveryTimeout=$deliveryTimeout.  " +
      s"${super.toString}"
  }

}

object ShellScriptActivity {

  def decode(conf: ActivityIO.Conf): JsResult[ShellScriptActivity] = {
    for {
      scriptLocation <- (conf.activitySpec \ "scriptLocation").validate[String]
      args <- (conf.activitySpec \ "args").validate[Seq[String]]
      outputS3BucketName <- (conf.activitySpec \ "outputS3BucketName").validate[String]
      outputS3KeyPrefix <- (conf.activitySpec \ "outputS3KeyPrefix").validate[String]
      executionTimeout <- (conf.activitySpec \ "executionTimeout").validate[Int]
      deliveryTimeout <- (conf.activitySpec \ "deliveryTimeout").validate[Int]
      ec2InstanceId <- (conf.resourceInstSpec \ "ec2InstanceId").validate[String]
    } yield {
      ShellScriptActivity(
        s"${conf.workflowId}_act-${conf.activityId}_${conf.attemptId}",
        scriptLocation,
        args,
        outputS3BucketName,
        outputS3KeyPrefix,
        executionTimeout,
        deliveryTimeout,
        ec2InstanceId
      )
    }
  }

}
