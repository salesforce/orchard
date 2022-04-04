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

case class ShellScriptActivity(
  name: String,
  script: ShellScriptActivity.Script,
  ec2InstanceId: String
) extends Ec2Activity(name, ec2InstanceId) {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * create activity via AWS SSM SendCommand to an ec2Instance
   * @return  SSM command-id in a single entry list
   */
  override def create(): Either[Throwable, JsValue] = Try {

    logger.debug(s"create: name=$name ec2InstanceId=$ec2InstanceId script=$script")

    lazy val ts = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES)
    val preRunInfo = s"cd ~ "
    val pullScript = s"aws s3 cp ${script.scriptLocation} . "
    val scriptFile = script.scriptLocation.split("/").last
    val chmodScript = s"chmod +x ./$scriptFile"
    val runScript = s" ./$scriptFile ${script.args.mkString(" ")}"
    val commands =
      List(preRunInfo, s"echo $pullScript", pullScript, chmodScript, s"echo $runScript", runScript)
    commands.foreach(c => logger.debug(s"create: command=$c"))

    val client = Client.ssm()
    val paraMap = Map(
      "commands" -> commands.asJava,
      "executionTimeout" -> List(script.executionTimeout.toString).asJava
    )
    val request =
      SendCommandRequest
        .builder()
        .documentName(RunShellScriptDocument)
        .instanceIds(ec2InstanceId)
        .comment(getComment(getClass))
        .parameters(paraMap.asJava)
        .timeoutSeconds(script.deliveryTimeout)
        .outputS3BucketName(script.outputS3BucketName)
        .outputS3KeyPrefix(s"${script.outputS3KeyPrefix.stripPrefix("/")}/${name}_$ts")
        .build()
    val response = client.sendCommand(request)
    client.close()
    val command = response.command()
    val commandId = command.commandId()
    logger.debug(s"create: sendCommand commandId=$commandId command=${command.toString}")

    Json.toJson(List(commandId))
  }.toEither

  override def toString: String = {
    s"ShellScriptActivity: $name script=$script.  ${super.toString}"
  }

}

object ShellScriptActivity {
  case class Script(
    scriptLocation: String,
    args: Seq[String],
    outputS3BucketName: String,
    outputS3KeyPrefix: String,
    executionTimeout: Int = 3600,
    deliveryTimeout: Int = 600
  )

  implicit val scriptReads: Reads[Script] = Json.reads[Script]

  def decode(conf: ActivityIO.Conf): JsResult[ShellScriptActivity] = {
    for {
      script <- (conf.activitySpec \ "script").validate[ShellScriptActivity.Script]
      ec2InstanceId <- (conf.resourceInstSpec \ "ec2InstanceId").validate[String]
    } yield {
      ShellScriptActivity(
        s"${conf.workflowId}_act-${conf.activityId}_${conf.attemptId}",
        script,
        ec2InstanceId
      )
    }
  }

}
