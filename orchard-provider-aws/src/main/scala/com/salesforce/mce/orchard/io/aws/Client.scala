/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.io.aws

import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.emr.EmrClient
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider

object Client {

  def staticCredentialsOpt: Option[StaticCredentialsProvider] = for {
    awsAccessKeyId <- ProviderSettings().awsAccessKeyId
    awsSecretKey <- ProviderSettings().awsSecretKey
  } yield StaticCredentialsProvider.create(
    AwsBasicCredentials.create(awsAccessKeyId, awsSecretKey)
  )

  def clientRegionOpt: Option[Region] = for {
    clientRegion <- ProviderSettings().awsClientRegion
  } yield Region.of(clientRegion)

  def assumeRoleCredentialsOpt(clientType: String): Option[StsAssumeRoleCredentialsProvider] = {
    for {
      awsRoleToAssume <- ProviderSettings().awsRoleToAssume
    } yield {

      val stsClient = staticCredentialsOpt.map { staticCredentials =>
        StsClient.builder().credentialsProvider(staticCredentials).build()
      }.getOrElse(StsClient.builder().build())

      val assumeRoleRequest = AssumeRoleRequest
        .builder()
        .roleArn(awsRoleToAssume)
        .roleSessionName(s"${clientType}Session")
        .build()

      StsAssumeRoleCredentialsProvider.builder()
        .refreshRequest(assumeRoleRequest)
        .stsClient(stsClient)
        .build()
    }
  }

  def ec2(): Ec2Client = {

    val clientBuilder = clientRegionOpt
      .map(Ec2Client.builder().region)
      .getOrElse(Ec2Client.builder())

    assumeRoleCredentialsOpt(Ec2Client.SERVICE_NAME) match {
      case Some(stsAssumeRoleCredentials) =>
        clientBuilder.credentialsProvider(stsAssumeRoleCredentials).build()
      case None =>
        staticCredentialsOpt
          .map(clientBuilder.credentialsProvider(_).build())
          .getOrElse(clientBuilder.build())
    }
  }

  def emr(): EmrClient = {

    val clientBuilder = clientRegionOpt
      .map(EmrClient.builder().region)
      .getOrElse(EmrClient.builder())

    assumeRoleCredentialsOpt(EmrClient.SERVICE_NAME) match {
      case Some(stsAssumeRoleCredentials) =>
        clientBuilder.credentialsProvider(stsAssumeRoleCredentials).build()
      case None =>
        staticCredentialsOpt
          .map(clientBuilder.credentialsProvider(_).build())
          .getOrElse(clientBuilder.build())
    }
  }

  def ssm(): SsmClient = {

    val clientBuilder = clientRegionOpt
      .map(SsmClient.builder().region)
      .getOrElse(SsmClient.builder())

    assumeRoleCredentialsOpt(SsmClient.SERVICE_NAME) match {
      case Some(stsAssumeRoleCredentials) =>
        clientBuilder.credentialsProvider(stsAssumeRoleCredentials).build()
      case None =>
        staticCredentialsOpt
          .map(clientBuilder.credentialsProvider(_).build())
          .getOrElse(clientBuilder.build())
    }
  }
}
