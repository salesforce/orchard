package com.salesforce.mce.orchard.io.aws.activity

import java.time.{LocalDateTime, ZoneOffset}
import java.time.temporal.ChronoUnit
import scala.jdk.CollectionConverters.{MapHasAsJava, SeqHasAsJava}
import scala.util.Try
import org.slf4j.LoggerFactory
import play.api.libs.json.{JsResult, JsValue, Json, Reads}
import software.amazon.awssdk.services.ssm.model.SendCommandRequest
import com.salesforce.mce.orchard.io.ActivityIO
import com.salesforce.mce.orchard.io.aws.Client

case class ShellCommandActivity(
  name: String,
  cmd: ShellCommandActivity.Command,
  ec2InstanceId: String
) extends Ec2Activity(name, ec2InstanceId) {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * create activity via AWS SSM SendCommand to an ec2Instance
   * @return  SSM command-id in a single entry list
   */
  override def create(): Either[Throwable, JsValue] = Try {

    logger.debug(
      s"create: name=$name ec2InstanceId=$ec2InstanceId cmd lines=${cmd.lines.mkString(" ")}."
    )
    cmd.lines.foreach(c => logger.debug(s"create command=$c"))

    lazy val ts = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES)
    val client = Client.ssm()
    val paraMap = Map(
      "commands" -> cmd.lines.asJava,
      "executionTimeout" -> List(cmd.executionTimeout.toString).asJava
    )
    val request =
      SendCommandRequest
        .builder()
        .documentName(RunShellScriptDocument)
        .instanceIds(ec2InstanceId)
        .comment(getComment(getClass))
        .parameters(paraMap.asJava)
        .timeoutSeconds(cmd.deliveryTimeout)
        .outputS3BucketName(cmd.outputS3BucketName)
        .outputS3KeyPrefix(s"${cmd.outputS3KeyPrefix.stripPrefix("/")}/${name}_$ts")
        .build()
    val response = client.sendCommand(request)
    client.close()
    val command = response.command()
    val commandId = command.commandId()
    logger.debug(s"create: sendCommand commandId=$commandId command=${command.toString}")

    Json.toJson(List(commandId))
  }.toEither

  override def toString: String = {
    s"ShellCommandActivity: $name command=$cmd.  ${super.toString}"
  }
}

object ShellCommandActivity {
  case class Command(
    lines: Seq[String],
    outputS3BucketName: String,
    outputS3KeyPrefix: String,
    executionTimeout: Int = 3600,
    deliveryTimeout: Int = 600
  )

  implicit val cmdReads: Reads[Command] = Json.reads[Command]

  def decode(conf: ActivityIO.Conf): JsResult[ShellCommandActivity] = {
    for {
      cmd <- (conf.activitySpec \ "command").validate[ShellCommandActivity.Command]
      ec2InstanceId <- (conf.resourceInstSpec \ "ec2InstanceId").validate[String]
    } yield {
      ShellCommandActivity(
        s"${conf.workflowId}_act-${conf.activityId}_${conf.attemptId}",
        cmd,
        ec2InstanceId
      )
    }
  }
}
