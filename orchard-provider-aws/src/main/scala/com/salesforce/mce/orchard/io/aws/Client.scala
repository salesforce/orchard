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

object Client {

  def staticCredentialsOpt: Option[(StaticCredentialsProvider, Region)] = {
    if (ProviderSettings().staticCredEnabled.contains(true)) {
      for {
        awsAccessKeyId <- ProviderSettings().awsAccessKeyId
        awsSecretKey <- ProviderSettings().awsSecretKey
        awsRegion <- ProviderSettings().awsRegion
      } yield {
        val staticCred = StaticCredentialsProvider.create(
          AwsBasicCredentials.create(awsAccessKeyId, awsSecretKey)
        )
        (staticCred, Region.of(awsRegion))
      }
    } else None
  }

  def ec2(): Ec2Client = staticCredentialsOpt match {
    case Some((staticCred, region)) => Ec2Client.builder().region(region).credentialsProvider(staticCred).build()
    case None => Ec2Client.create()
  }

  def emr(): EmrClient = staticCredentialsOpt match {
    case Some((staticCred, region)) => EmrClient.builder().region(region).credentialsProvider(staticCred).build()
    case None => EmrClient.create()
  }

  def ssm(): SsmClient = staticCredentialsOpt match {
    case Some((staticCred, region)) => SsmClient.builder().region(region).credentialsProvider(staticCred).build()
    case None => SsmClient.create()
  }
}
