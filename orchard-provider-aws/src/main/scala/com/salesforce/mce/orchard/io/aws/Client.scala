/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.io.aws

import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.emr.EmrClient
import software.amazon.awssdk.services.ssm.SsmClient

object Client {

  def staticCredentialsOpt: Option[StaticCredentialsProvider] = {
    if (ProviderSettings().staticCredentials.contains(true)) {
      for {
        awsAccessKeyId <- ProviderSettings().awsAccessKeyId
        awsSecretKey <- ProviderSettings().awsSecretKey
      } yield {
        StaticCredentialsProvider.create(
          AwsBasicCredentials.create(awsAccessKeyId, awsSecretKey)
        )
      }
    } else None
  }

  def ec2(): Ec2Client = staticCredentialsOpt match {
    case Some(staticCred) => Ec2Client.builder().credentialsProvider(staticCred).build()
    case None => Ec2Client.create()
  }

  def emr(): EmrClient = staticCredentialsOpt match {
    case Some(staticCred) => EmrClient.builder().credentialsProvider(staticCred).build()
    case None => EmrClient.create()
  }

  def ssm(): SsmClient = staticCredentialsOpt match {
    case Some(staticCred) => SsmClient.builder().credentialsProvider(staticCred).build()
    case None => SsmClient.create()
  }
}
