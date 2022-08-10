/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.io.aws.resource

import scala.jdk.CollectionConverters.CollectionHasAsScala
import scala.util.control.Exception.catching

import org.slf4j.LoggerFactory
import play.api.libs.json.{JsResult, JsValue, Json, Reads, Writes}
import software.amazon.awssdk.services.ec2.model._
import software.amazon.awssdk.core.exception.SdkException

import com.krux.stubborn.Retryable
import com.krux.stubborn.policy.ExponentialBackoff
import com.salesforce.mce.orchard.io.ResourceIO
import com.salesforce.mce.orchard.io.aws.Client
import com.salesforce.mce.orchard.model.Status

case class Ec2Resource(name: String, spec: Ec2Resource.Spec) extends ResourceIO {
  private val logger = LoggerFactory.getLogger(getClass)

  override def create(): Either[Throwable, JsValue] =
    catching(classOf[SdkException]) either
      Retryable
        .retry(policy = ExponentialBackoff()) {
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
          if (spec.spotInstance) {
            logger.debug(s"create: spotInstance=true")
            builder.instanceMarketOptions(
              InstanceMarketOptionsRequest.builder().marketType(MarketType.SPOT).build()
            )
          }
          spec.tags match {
            case None =>
              logger.debug(s"no tags given")
            case Some(ts) =>
              logger.debug(s"spec.tags=${spec.tags}")
              val tags2 = ts.map(tag => Tag.builder().key(tag.key).value(tag.value).build())
              builder.tagSpecifications(
                TagSpecification
                  .builder()
                  .resourceType(ResourceType.INSTANCE)
                  .tags(tags2: _*)
                  .build()
              )
          }
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
   * @param instSpec
   * @return Boolean SSM has visibility to the EC2 instance
   */
  private def isSsmVisible(instSpec: JsValue): Boolean = {
    val ssmClient = Client.ssm()
    val isVisible = catching(classOf[SdkException]).opt {
      Retryable
        .retry(policy = ExponentialBackoff()) {
          ssmClient
            .describeInstanceInformation()
            .instanceInformationList()
            .asScala
            .map(_.instanceId())
            .toSet
            .contains((instSpec \ "ec2InstanceId").as[String])
        }
    }.get
    ssmClient.close()
    isVisible
  }

  override def getStatus(instSpec: JsValue): Either[Throwable, Status.Value] = {
    logger.debug(s"getStatus: instSpec=${instSpec.toString()}")
    val client = Client.ec2()
    val ec2InstanceId = (instSpec \ "ec2InstanceId").as[String]
    try {
      val response: DescribeInstancesResponse = catching(classOf[SdkException]).opt {
        Retryable
          .retry(policy = ExponentialBackoff()) {
            client.describeInstances(
              DescribeInstancesRequest
                .builder()
                .instanceIds(ec2InstanceId)
                .build()
            )
          }
      }.get

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
              isSsmVisible(Json.toJson(Ec2Resource.InstSpec(ec2InstanceId))) match {
                case true => Right(Status.Running)
                case _ => Right(Status.Activating)
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

  override def terminate(instSpec: JsValue): Either[Throwable, Status.Value] =
    catching(classOf[SdkException]) either {
      Retryable
        .retry(policy = ExponentialBackoff()) {
          logger.debug(s"terminate: instSpec=${instSpec.toString()}")
          val client = Client.ec2()
          val id = (instSpec \ "ec2InstanceId").as[String]
          val describeResponse =
            client.describeInstances(DescribeInstancesRequest.builder().instanceIds(id).build())
          val instanceLifecycle = describeResponse
            .reservations()
            .asScala
            .flatMap {
              _.instances().asScala.map(_.instanceLifecycleAsString())
            }
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
    tags: Option[Seq[AwsTag]],
    spotInstance: Boolean
  )

  implicit val specReads: Reads[Spec] = Json.reads[Spec]

  def decode(conf: ResourceIO.Conf): JsResult[Ec2Resource] = conf.resourceSpec
    .validate[Spec]
    .map { spec =>
      Ec2Resource.apply(s"${conf.workflowId}_rsc-${conf.resourceId}_${conf.instanceId}", spec)
    }

}
