/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.io.aws

import com.typesafe.config.{Config, ConfigFactory}

case class ProviderSettings(config: Config) {

  private def getConfigString(path: String): Option[String] = {
    if (config.hasPath(path)) Option(config.getString(path)) else None
  }

  lazy val loggingUri = getConfigString("aws.logging.uri")
  lazy val awsAccessKeyId = getConfigString("aws.static.credentials.accessKeyId")
  lazy val awsSecretKey = getConfigString("aws.static.credentials.secretKey")
  lazy val awsClientRegion = getConfigString("aws.client.region")
  lazy val awsAssumeRoleArn = getConfigString("aws.assume.role.arn")
}

object ProviderSettings {

  def apply(): ProviderSettings =
    ProviderSettings(ConfigFactory.load().getConfig("com.salesforce.mce.orchard.io"))

}
