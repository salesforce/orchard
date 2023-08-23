/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.io.aws.activity

import scala.jdk.CollectionConverters.CollectionHasAsScala

import org.slf4j.LoggerFactory
import play.api.libs.json.JsValue
import software.amazon.awssdk.services.ssm.model._

import com.salesforce.mce.orchard.io.ActivityIO
import com.salesforce.mce.orchard.io.aws.Client
import com.salesforce.mce.orchard.model.Status
import com.salesforce.mce.orchard.system.util.InvalidJsonException
import com.salesforce.mce.orchard.util.RetryHelper._

abstract class Ec2Activity(
  name: String,
  ec2IstanceId: String
) extends ActivityIO {

  private val logger = LoggerFactory.getLogger(getClass)
  protected val RunShellScriptDocument = "AWS-RunShellScript"

  protected def getComment(clazz: Class[_ <: Ec2Activity]): String = {
    // ssm runCommand comment length must be <= 100
    val comment = s"$name ${clazz.getName.split("\\.").last}"
    comment.substring(0, math.min(comment.length, 100))
  }

  /**
   * Ec2 related activities check progress the same way, thus this abstract class method to abstract the logic.
   * Example extensions of this class include ShellCommandActivity and ShellScriptActivity
   * @param commands  command-id wrt AWS SSM
   * @return
   */
  private def getProgress(commands: Seq[String]): Either[Throwable, Status.Value] = {

    logger.debug(s"getProgress: commands=$commands")
    val client = Client.ssm()
    val respEither = retryToEither {
      client
        .listCommands(
          ListCommandsRequest.builder().commandId(commands.head).instanceId(ec2IstanceId).build()
        )
    }
    client.close()
    respEither.map { resp =>
      val statuses = resp
        .commands()
        .asScala
        .map { c =>
          logger.debug(s"getProgress: command status=${c.status()}")
          c.status() match {
            case CommandStatus.IN_PROGRESS => Status.Running
            case CommandStatus.PENDING => Status.Pending
            case CommandStatus.FAILED => Status.Failed
            case CommandStatus.SUCCESS => Status.Finished
            case CommandStatus.CANCELLED => Status.Canceled
            case CommandStatus.CANCELLING => Status.Canceling
            case CommandStatus.TIMED_OUT => Status.Failed
            case CommandStatus.UNKNOWN_TO_SDK_VERSION => Status.Failed
            case _ => Status.Failed
          }
        }
        .toSet

      if (statuses.isEmpty || statuses.contains(Status.Failed))
        Status.Failed
      else if (statuses.contains(Status.Canceling))
        Status.Canceling
      else if (statuses.contains(Status.Canceled))
        Status.Canceled
      else if (statuses.contains(Status.Running))
        Status.Running
      else if (statuses.size == 1 && statuses.contains(Status.Finished))
        Status.Finished
      else
        Status.Pending
    }
  }

  override def getProgress(spec: JsValue): Either[Throwable, Status.Value] = spec
    .validate[Seq[String]]
    .fold(
      invalid => Left(InvalidJsonException.raise(invalid)),
      valid => getProgress(valid)
    )

  /**
   * Ec2 related activities terminate the same way, thus this abstract class method to abstract the logic.
   * Example extensions of this class include ShellCommandActivity and ShellScriptActivity
   * @param commands  command-id wrt AWS SSM
   * @return
   */
  private def terminate(commands: Seq[String]) = {
    logger.debug(s"terminate: commands=$commands")
    val client = Client.ssm()
    client
      .cancelCommand(
        CancelCommandRequest.builder().commandId(commands.head).instanceIds(ec2IstanceId).build()
      )
      .retry()
    Status.Canceled
  }

  override def terminate(spec: JsValue): Either[Throwable, Status.Value] = spec
    .validate[Seq[String]]
    .fold(
      { invalid => Left(InvalidJsonException.raise(invalid)) },
      valid => Right(terminate(valid))
    )

  override def toString: String = {
    s"Ec2Activity: name=$name ec2IstanceId=$ec2IstanceId.  ${super.toString}"
  }

}
