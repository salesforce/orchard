/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.io.aws.resource

import scala.jdk.CollectionConverters.MapHasAsJava

import org.slf4j.LoggerFactory
import play.api.libs.json._
import software.amazon.awssdk.services.emr.model._

import com.salesforce.mce.orchard.io.aws.{Client, ProviderSettings}
import com.salesforce.mce.orchard.io.ResourceIO
import com.salesforce.mce.orchard.model.Status
import com.salesforce.mce.orchard.system.util.InvalidJsonException
import com.salesforce.mce.orchard.util.RetryHelper._

case class EmrResource(name: String, spec: EmrResource.Spec) extends ResourceIO {
  private val logger = LoggerFactory.getLogger(getClass)

  private val releaseLabel = spec.releaseLabel
  private val instancesConfig = spec.instancesConfig
  private val applications = spec.applications.map(a => Application.builder().name(a).build())

  val loggingUriBase = ProviderSettings().loggingUri.map(p => s"$p$name/")

  override def create(): Either[Throwable, JsValue] = retryToEither {
    val awsTags = spec.tags match {
      case None =>
        logger.debug(s"no tags given")
        List.empty[Tag]
      case Some(ts) =>
        logger.debug(s"spec.tags=${spec.tags}")
        ts.map(tag => Tag.builder().key(tag.key).value(tag.value).build())
    }
    val response = Client
      .emr()
      .runJobFlow(
        loggingUriBase
          .foldLeft(
            RunJobFlowRequest
              .builder()
              .name(name)
              .releaseLabel(releaseLabel)
              .applications(applications: _*)
              .serviceRole(spec.serviceRole)
              .jobFlowRole(spec.resourceRole)
              .tags(awsTags: _*)
              .configurations(EmrResource.parseConfig(spec.sparkConfigs): _*)
              .instances {
                val builder = JobFlowInstancesConfig
                  .builder()
                  .ec2SubnetId(instancesConfig.subnetId)
                  .ec2KeyName(instancesConfig.ec2KeyName)
                  .instanceCount(instancesConfig.instanceCount)
                  .masterInstanceType(instancesConfig.masterInstanceType)
                  .slaveInstanceType(instancesConfig.slaveInstanceType)
                  .keepJobFlowAliveWhenNoSteps(true)

                instancesConfig.additionalMasterSecurityGroups
                  .foldLeft(builder)(_.additionalMasterSecurityGroups(_: _*))
                instancesConfig.additionalSlaveSecurityGroups
                  .foldLeft(builder)(_.additionalSlaveSecurityGroups(_: _*))

                builder.build()
              }
          )((r, uri) => r.logUri(uri))
          .build()
      )
    logger.debug(s"create: name=$name jobFlowId=${response.jobFlowId()}")
    Json.toJson(EmrResource.InstSpec(response.jobFlowId()))
  }

  private def getStatus(spec: EmrResource.InstSpec) = {
    val response = Client
      .emr()
      .describeCluster(DescribeClusterRequest.builder().clusterId(spec.clusterId).build())
      .retry()

    response.cluster().status().state() match {
      case ClusterState.BOOTSTRAPPING =>
        Right(Status.Activating)
      case ClusterState.RUNNING =>
        Right(Status.Running)
      case ClusterState.STARTING =>
        Right(Status.Activating)
      case ClusterState.TERMINATED =>
        Right(Status.Finished)
      case ClusterState.TERMINATED_WITH_ERRORS =>
        Right(Status.Failed)
      case ClusterState.TERMINATING =>
        Right(Status.Finished)
      case ClusterState.WAITING =>
        Right(Status.Running)
      case ClusterState.UNKNOWN_TO_SDK_VERSION =>
        Left(new Exception("UNKNOWN_TO_SDK_VERSION"))
    }

  }

  override def getStatus(instSpec: JsValue): Either[Throwable, Status.Value] = instSpec
    .validate[EmrResource.InstSpec]
    .fold(
      invalid => Left(InvalidJsonException.raise(invalid)),
      valid => getStatus(valid)
    )

  private def terminate(spec: EmrResource.InstSpec) = {
    Client
      .emr()
      .terminateJobFlows(TerminateJobFlowsRequest.builder().jobFlowIds(spec.clusterId).build())
      .retry()

    Status.Finished
  }

  override def terminate(instSpec: JsValue): Either[Throwable, Status.Value] = instSpec
    .validate[EmrResource.InstSpec]
    .fold(
      invalid => Left(InvalidJsonException.raise(invalid)),
      valid => Right(terminate(valid))
    )

}

object EmrResource {

  case class InstSpec(clusterId: String)

  implicit val instSpecWrites: Writes[InstSpec] = Json.writes[InstSpec]
  implicit val instSpecReads: Reads[InstSpec] = Json.reads[InstSpec]

  case class InstancesConfig(
    subnetId: String,
    ec2KeyName: String,
    instanceCount: Int,
    masterInstanceType: String,
    slaveInstanceType: String,
    additionalMasterSecurityGroups: Option[Seq[String]],
    additionalSlaveSecurityGroups: Option[Seq[String]]
  )
  implicit val instancesConfigReads: Reads[InstancesConfig] = Json.reads[InstancesConfig]

  case class SparkConfig(
    classification: String,
    properties: Option[Map[String, String]],
    configurations: Option[Seq[SparkConfig]]
  )
  implicit val sparkConfigReads: Reads[SparkConfig] = Json.reads[SparkConfig]

  case class Spec(
    releaseLabel: String,
    applications: Seq[String],
    serviceRole: String,
    resourceRole: String,
    tags: Option[Seq[AwsTag]],
    instancesConfig: InstancesConfig,
    sparkConfigs: Option[Seq[SparkConfig]]
  )
  implicit val specReads: Reads[Spec] = Json.reads[Spec]

  def parseConfig(sparkConfigs: Option[Seq[SparkConfig]]): Seq[Configuration] = {
    def parseConfig2(sparkConfig: SparkConfig): Configuration = {
      val builder = Configuration.builder().classification(sparkConfig.classification)
      for { props <- sparkConfig.properties } yield {
        builder.properties(props.asJava)
      }
      for { moreConfigs <- sparkConfig.configurations } yield {
        val childConfigs = moreConfigs.map { childConfig =>
          val childBuilder = Configuration.builder().classification(childConfig.classification)
          for { childProps <- childConfig.properties } yield {
            childBuilder.properties(childProps.asJava)
          }
          for { grandChild <- childConfig.configurations } yield {
            childBuilder.configurations(grandChild.map(parseConfig2(_)): _*)
          }
          childBuilder.build()
        }
        builder.configurations(childConfigs: _*)
      }
      builder.build()
    }

    sparkConfigs match {
      case None =>
        logger.debug(s"no spark configs given")
        List.empty[Configuration]
      case Some(sparkConfigs) =>
        logger.debug(s"spec.sparkConfigs=$sparkConfigs")
        sparkConfigs.map(parseConfig2(_))
    }
  }

  def decode(conf: ResourceIO.Conf): JsResult[EmrResource] = conf.resourceSpec
    .validate[Spec]
    .map(spec =>
      EmrResource.apply(s"${conf.workflowId}_rsc-${conf.resourceId}_${conf.instanceId}", spec)
    )

}
