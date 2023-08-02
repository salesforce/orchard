/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.io.aws.resource

import scala.jdk.CollectionConverters.CollectionHasAsScala

import org.slf4j.LoggerFactory
import play.api.libs.json._
import software.amazon.awssdk.services.ec2.model._
import software.amazon.awssdk.services.ssm.model.{
  DescribeInstanceInformationRequest,
  InstanceInformationStringFilter
}

import com.salesforce.mce.orchard.io.aws.Client
import com.salesforce.mce.orchard.io.ResourceIO
import com.salesforce.mce.orchard.model.Status
import com.salesforce.mce.orchard.util.RetryHelper._

case class Ec2Resource(name: String, spec: Ec2Resource.Spec) extends ResourceIO {
  private val logger = LoggerFactory.getLogger(getClass)

  override def create(): Either[Throwable, JsValue] = retryToEither {
    logger.debug(
      s"create: spec subnetId=${spec.subnetId} spec=$spec"
    )
    val client = Client.ec2()
    val builder = RunInstancesRequest
      .builder()
      .imageId(spec.amiImageId)
      .instanceType(spec.instanceType)
      .minCount(1)
      .maxCount(1)
      .subnetId(spec.subnetId)
      .iamInstanceProfile(
        IamInstanceProfileSpecification.builder().name(spec.instanceProfile).build()
      )

    spec.securityGroups
      .foldLeft(builder)(_.securityGroupIds(_: _*))

    if (spec.spotInstance) {
      logger.debug(s"create: spotInstance=true")
      builder.instanceMarketOptions(
        InstanceMarketOptionsRequest.builder().marketType(MarketType.SPOT).build()
      )
    }
    val tags = spec.tags match {
      case None =>
        logger.debug(s"no tags given")
        Seq.empty[Tag]
      case Some(ts) =>
        logger.debug(s"spec.tags=${spec.tags}")
        ts.map(tag => Tag.builder().key(tag.key).value(tag.value).build())
    }
    val fullTags = Tag.builder().key("name").value(name).build() +: tags

    builder.tagSpecifications(
      TagSpecification
        .builder()
        .resourceType(ResourceType.INSTANCE)
        .tags(fullTags: _*)
        .build()
    )

    val resp = client.runInstances(builder.build())
    client.close()
    if (logger.isDebugEnabled) {
      resp
        .instances()
        .forEach { i =>
          logger.debug(s"create: instanceId=${i.instanceId()}")
        }
    }
    Json.toJson(Ec2Resource.InstSpec(resp.instances().asScala.head.instanceId()))
  }

  /**
   * @param ec2InstanceId ec2InstanceId
   * @return Boolean SSM has visibility to the EC2 instance
   */
  private def isSsmVisible(ec2InstanceId: String): Boolean = {
    val ssmClient = Client.ssm()
    val infoReq = DescribeInstanceInformationRequest
      .builder()
      .filters(
        InstanceInformationStringFilter
          .builder()
          .key("InstanceIds")
          .values(ec2InstanceId)
          .build()
      )
      .build()
    val resultSet =
      ssmClient
        .describeInstanceInformation(infoReq)
        .instanceInformationList()
        .asScala
        .map(_.instanceId())
        .toSet
        .retry()
    ssmClient.close()
    resultSet.nonEmpty
  }

  final val GoodSummaryStatuses = Set(SummaryStatus.INITIALIZING, SummaryStatus.OK)

  /**
   * when ssm does not yet have visibility to ec2, it could be initializing phase of the ec2 instance,
   * or it could the ec2 has instanceStatus impaired.
   * getStatus() performs this check to fail early in case the initialized ec2 instance status has issues,
   * and the ResourceInstance logic can move on to the next attempt if available
   * @param ec2InstanceId  ec2 instance id
   * @return
   */
  def describeEc2Status(ec2InstanceId: String): Either[Throwable, Status.Value] = {
    val client = Client.ec2()
    val request = DescribeInstanceStatusRequest.builder().instanceIds(ec2InstanceId).build()
    val response = client.describeInstanceStatus(request)
    client.close()
    val status = response.instanceStatuses().asScala
    status.toList match {
      case sts :: _ =>
        val instanceStatus = sts.instanceStatus().status()
        val systemStatus = sts.systemStatus().status()
        if (!GoodSummaryStatuses.contains(instanceStatus)) {
          logger.info(s"Ec2 instance $ec2InstanceId instanceStatus=$instanceStatus}")
          Left(
            new Exception(
              s"Ec2 instance $ec2InstanceId instanceStatus=$instanceStatus."
            )
          )
        } else if (!GoodSummaryStatuses.contains(systemStatus)) {
          logger.info(s"Ec2 instance $ec2InstanceId systemStatus=$systemStatus")
          Left(
            new Exception(
              s"Ec2 instance $ec2InstanceId systemStatus=$systemStatus."
            )
          )
        } else {
          logger.debug(
            s"Ec2 $ec2InstanceId instanceStatus=${instanceStatus} systemStatus=$systemStatus"
          )
          Right(Status.Activating)
        }
      case _ => Left(new Exception(s"Ec2 instance $ec2InstanceId describeInstanceStatus failed."))
    }
  }

  override def getStatus(instSpec: JsValue): Either[Throwable, Status.Value] = {
    logger.debug(s"getStatus: instSpec=${instSpec.toString()}")
    val client = Client.ec2()
    val ec2InstanceId = (instSpec \ "ec2InstanceId").as[String]
    try {
      val response: DescribeInstancesResponse = client
        .describeInstances(
          DescribeInstancesRequest
            .builder()
            .instanceIds(ec2InstanceId)
            .build()
        )
        .retry()

      val state = response
        .reservations()
        .asScala
        .flatMap { r => r.instances().asScala.map { i => i.state().name() } }
        .toList
      client.close()
      val res = state.nonEmpty match {
        case true =>
          state.head match {
            case InstanceStateName.PENDING => Right(Status.Activating)
            case InstanceStateName.RUNNING =>
              isSsmVisible(ec2InstanceId) match {
                case true => Right(Status.Running)
                case _ =>
                  logger.info(s"ec2InstanceId $ec2InstanceId isSsmVisible=false")
                  describeEc2Status(ec2InstanceId)
              }
            case InstanceStateName.TERMINATED => Right(Status.Finished)
            case InstanceStateName.STOPPING => Right(Status.Finished)
            case InstanceStateName.STOPPED => Right(Status.Finished)
            case InstanceStateName.SHUTTING_DOWN => Right(Status.Finished)
            case InstanceStateName.UNKNOWN_TO_SDK_VERSION =>
              Left(new Exception("UNKNOWN_TO_SDK_VERSION"))
            case _ => Left(new Exception("UNKNOWN_TO_SDK_VERSION"))
          }
        case _ => Left(new Exception("UNKNOWN_TO_SDK_VERSION"))
      }
      logger.debug(s"getStatus: result = $res")
      res
    } catch {
      case e: Throwable => Left(e)
    }
  }

  override def terminate(instSpec: JsValue): Either[Throwable, Status.Value] = retryToEither {
    logger.debug(s"terminate: instSpec=${instSpec.toString()}")
    val client = Client.ec2()
    val id = (instSpec \ "ec2InstanceId").as[String]
    val describeResponse =
      client.describeInstances(DescribeInstancesRequest.builder().instanceIds(id).build())
    val instanceLifecycle = describeResponse
      .reservations()
      .asScala
      .flatMap { _.instances().asScala.map(_.instanceLifecycleAsString()) }
      .head
    logger.debug(s"terminate: instanceLifecycle=$instanceLifecycle")
    if (!"spot".equals(instanceLifecycle)) { // default setting spot instance can be terminated (not stopped)
      client.stopInstances(StopInstancesRequest.builder().instanceIds(id).build())
    }
    client.terminateInstances(TerminateInstancesRequest.builder().instanceIds(id).build())
    client.close()
    logger.debug(s"terminate: stopped and terminated instanceId=$id")
    Status.Finished
  }

}

object Ec2Resource {
  case class InstSpec(ec2InstanceId: String)

  implicit val instSpecWrites: Writes[InstSpec] = Json.writes[InstSpec]
  implicit val instSpecReads: Reads[InstSpec] = Json.reads[InstSpec]

  case class Spec(
    amiImageId: String,
    subnetId: String,
    instanceType: String,
    instanceProfile: String,
    securityGroups: Option[Seq[String]],
    tags: Option[Seq[AwsTag]],
    name: Option[String],
    spotInstance: Boolean
  )

  implicit val specReads: Reads[Spec] = Json.reads[Spec]

  def decode(conf: ResourceIO.Conf): JsResult[Ec2Resource] = conf.resourceSpec
    .validate[Spec]
    .map { spec =>
      val name = spec.name.getOrElse(s"${conf.workflowId}_rsc-${conf.resourceId}_${conf.instanceId}")
      Ec2Resource.apply(name, spec)
    }

}
