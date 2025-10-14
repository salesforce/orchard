/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.io.aws.resource

import java.util.UUID

import scala.jdk.CollectionConverters._

import org.slf4j.LoggerFactory
import play.api.libs.json._
import software.amazon.awssdk.services.emr.model._

import com.salesforce.mce.orchard.io.ResourceIO
import com.salesforce.mce.orchard.io.aws.{Client, ProviderSettings}
import com.salesforce.mce.orchard.model.Status
import com.salesforce.mce.orchard.system.util.InvalidJsonException
import com.salesforce.mce.orchard.util.RetryHelper._

case class EmrResource(
  name: String,
  loggingPath: String,
  spec: EmrResource.Spec,
  lastAttempt: Boolean,
  useOnDemandOnLastAttempt: Boolean
) extends ResourceIO {
  private val logger = LoggerFactory.getLogger(getClass)

  private val releaseLabel = spec.releaseLabel
  private val instancesConfig = spec.instancesConfig
  private val applications = spec.applications.map(a => Application.builder().name(a).build())

  val loggingUriBase = ProviderSettings().loggingUri.map(p => s"$p$loggingPath/")

  override def create(): Either[Throwable, JsValue] = retryToEither {
    val awsTags = spec.tags match {
      case None =>
        logger.debug(s"no tags given")
        List.empty[Tag]
      case Some(ts) =>
        logger.debug(s"spec.tags=${spec.tags}")
        ts.map(tag => Tag.builder().key(tag.key).value(tag.value).build())
    }

    val unwrappedBootstrapActions = spec.bootstrapActions match {
      case None => Seq.empty
      case Some(bas) => bas
    }

    val response = Client
      .emr()
      .runJobFlow {
        val req = loggingUriBase
          .foldLeft(
            RunJobFlowRequest
              .builder()
              .name(name)
              .releaseLabel(releaseLabel)
              .applications(applications: _*)
              .serviceRole(spec.serviceRole)
              .jobFlowRole(spec.resourceRole)
              .bootstrapActions(
                unwrappedBootstrapActions.map { ba =>
                  BootstrapActionConfig
                    .builder()
                    .name(s"bootstrap-action.${UUID.randomUUID()}")
                    .scriptBootstrapAction(
                      ScriptBootstrapActionConfig
                        .builder()
                        .path(ba.path)
                        .args(ba.args: _*)
                        .build()
                    )
                    .build()
                }: _*
              )
              .tags(awsTags: _*)
              .configurations(EmrResource.asConfigurations(spec.configurations): _*)
              .instances {
                val builder = JobFlowInstancesConfig
                  .builder()
                  .keepJobFlowAliveWhenNoSteps(true)

                if (instancesConfig.instanceFleetConfigs.exists(_.nonEmpty)) {
                  instancesConfig.subnetIds.foldLeft(builder)(_.ec2SubnetIds(_: _*))
                  instancesConfig.instanceFleetConfigs
                    .foldLeft(builder) { case (b, instFleetConfigs) =>
                      b.instanceFleets(
                        instFleetConfigs.map { c =>
                          val builder = InstanceFleetConfig
                            .builder()
                            .name(s"orchard-instance-fleet-${c.instanceRoleType}".toLowerCase)
                            .instanceFleetType(c.instanceRoleType)
                            .instanceTypeConfigs(c.instanceConfigs.map { i =>
                              val builder = InstanceTypeConfig
                                .builder()
                                .instanceType(i.instanceType)
                              i.bidPrice.foldLeft(builder)(_.bidPrice(_))
                              i.weightedCapacity.foldLeft(builder)(_.weightedCapacity(_))
                              builder.build()
                            }: _*)
                          if (lastAttempt && useOnDemandOnLastAttempt || c.targetSpotCapacity.isEmpty) {
                            builder.targetOnDemandCapacity(c.targetOnDemandCapacity)
                          } else {
                            c.targetSpotCapacity.foldLeft(builder)(_.targetSpotCapacity(_))
                          }
                          builder.build()
                        }: _*
                      )
                    }
                } else {
                  builder.ec2SubnetId(instancesConfig.subnetIds.head)
                  instancesConfig.instanceGroupConfigs
                    .foldLeft(builder) { case (b, instGroupConfigs) =>
                      b.instanceGroups(
                        instGroupConfigs.map { c =>
                          val builder = InstanceGroupConfig
                            .builder()
                            .name(s"orchard-instance-group-${c.instanceRoleType}".toLowerCase)
                            .instanceRole(c.instanceRoleType)
                            .instanceCount(c.instanceCount)
                            .instanceType(c.instanceType)

                          c.instanceBidPrice
                            .fold(builder.market(MarketType.ON_DEMAND))(p =>
                              if (lastAttempt && useOnDemandOnLastAttempt) {
                                builder.market(MarketType.ON_DEMAND)
                              } else {
                                builder.bidPrice(p).market(MarketType.SPOT)
                              }
                            )

                          builder.build()
                        }: _*
                      )
                    }
                }

                instancesConfig.ec2KeyName
                  .foldLeft(builder)(_.ec2KeyName(_))
                instancesConfig.emrManagedMasterSecurityGroup
                  .foldLeft(builder)(_.emrManagedMasterSecurityGroup(_))
                instancesConfig.emrManagedSlaveSecurityGroup
                  .foldLeft(builder)(_.emrManagedSlaveSecurityGroup(_))
                instancesConfig.additionalMasterSecurityGroups
                  .foldLeft(builder)(_.additionalMasterSecurityGroups(_: _*))
                instancesConfig.additionalSlaveSecurityGroups
                  .foldLeft(builder)(_.additionalSlaveSecurityGroups(_: _*))
                instancesConfig.serviceAccessSecurityGroup
                  .foldLeft(builder)(_.serviceAccessSecurityGroup(_))

                builder.build()
              }
          )((r, uri) => r.logUri(uri))

        spec.customAmiId.foldLeft(req)(_.customAmiId(_))
        req.build()
      }
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

  case class InstanceGroupConfig(
    instanceRoleType: String,
    instanceCount: Int,
    instanceType: String,
    instanceBidPrice: Option[String]
  )
  implicit val instanceGroupConfigReads: Reads[InstanceGroupConfig] =
    Json.reads[InstanceGroupConfig]

  case class InstanceTypeConfig(
    instanceType: String,
    bidPrice: Option[String],
    weightedCapacity: Option[Int]
  )
  implicit val instanceTypeConfigReads: Reads[InstanceTypeConfig] =
    Json.reads[InstanceTypeConfig]

  case class InstanceFleetConfig(
    instanceRoleType: String,
    targetOnDemandCapacity: Int,
    targetSpotCapacity: Option[Int],
    instanceConfigs: Seq[InstanceTypeConfig]
  )
  implicit val instanceFleetConfigReads: Reads[InstanceFleetConfig] =
    Json.reads[InstanceFleetConfig]

  case class InstancesConfig(
    subnetIds: Seq[String],
    ec2KeyName: Option[String],
    instanceGroupConfigs: Option[Seq[InstanceGroupConfig]],
    instanceFleetConfigs: Option[Seq[InstanceFleetConfig]],
    emrManagedMasterSecurityGroup: Option[String],
    emrManagedSlaveSecurityGroup: Option[String],
    additionalMasterSecurityGroups: Option[Seq[String]],
    additionalSlaveSecurityGroups: Option[Seq[String]],
    serviceAccessSecurityGroup: Option[String]
  )
  implicit val instancesConfigReads: Reads[InstancesConfig] = Json.reads[InstancesConfig]

  case class ConfigurationSpec(
    classification: String,
    properties: Option[Map[String, String]],
    configurations: Option[Seq[ConfigurationSpec]]
  )
  implicit val configurationSpecReads: Reads[ConfigurationSpec] = Json.reads[ConfigurationSpec]

  def asConfigurations(configurations: Option[Seq[ConfigurationSpec]]): Seq[Configuration] = {

    def parse(configSpec: ConfigurationSpec): Configuration = {
      val builder = configSpec.properties.foldLeft(
        Configuration.builder().classification(configSpec.classification)
      )((b, ps) => b.properties(ps.asJava))

      for { moreConfigs <- configSpec.configurations } {
        val childConfigs = moreConfigs.map(parse)
        builder.configurations(childConfigs: _*)
      }

      builder.build()
    }

    configurations match {
      case None => List.empty[Configuration]
      case Some(conf) => conf.map(parse)
    }
  }

  case class BootstrapAction(path: String, args: Seq[String])
  implicit val bootstrapActionReads: Reads[BootstrapAction] =
    Json.reads[BootstrapAction]

  case class Spec(
    releaseLabel: String,
    customAmiId: Option[String],
    applications: Seq[String],
    serviceRole: String,
    resourceRole: String,
    tags: Option[Seq[AwsTag]],
    bootstrapActions: Option[Seq[BootstrapAction]],
    configurations: Option[Seq[ConfigurationSpec]],
    instancesConfig: InstancesConfig,
    useOnDemandOnLastAttempt: Option[Boolean]
  )
  implicit val specReads: Reads[Spec] = Json.reads[Spec]

  def decode(conf: ResourceIO.Conf): JsResult[EmrResource] = conf.resourceSpec
    .validate[Spec]
    .map { spec =>
      val loggingPath = s"${conf.workflowId}_rsc-${conf.resourceId}_${conf.instanceId}"
      EmrResource.apply(
        conf.resourceName,
        loggingPath,
        spec,
        conf.instanceId >= conf.maxAttempt,
        spec.useOnDemandOnLastAttempt.getOrElse(false)
      )
    }

}
