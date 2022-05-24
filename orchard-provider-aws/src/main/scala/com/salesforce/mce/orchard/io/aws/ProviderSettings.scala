/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.io.aws

import scala.util.Try
import com.typesafe.config.{Config, ConfigFactory}

case class ProviderSettings(config: Config) {

  def getConfig[T](path: String): Option[T] = if (config.hasPath(path)) {
    Try(config.getValue(path).unwrapped().asInstanceOf[T]).toOption
  } else None

  lazy val loggingUri = getConfig[String]("aws.logging.uri")
  lazy val awsAccessKeyId = getConfig[String]("aws.static.credentials.accessKeyId")
  lazy val awsSecretKey = getConfig[String]("aws.static.credentials.secretKey")
  lazy val awsRegion = getConfig[String]("aws.static.credentials.region")
}

object ProviderSettings {

  def apply(): ProviderSettings =
    ProviderSettings(ConfigFactory.load().getConfig("com.salesforce.mce.orchard.io"))

}
