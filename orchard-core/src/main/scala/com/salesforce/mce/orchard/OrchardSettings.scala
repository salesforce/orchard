/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard

import com.salesforce.mce.orchard.util.{FixedDelay, JitteredDelay, Policy}

import scala.jdk.DurationConverters._
import com.typesafe.config.{Config, ConfigFactory}

class OrchardSettings private (config: Config) {

  def slickDatabaseConf = config.getConfig("jdbc")

  def providerConfig(provider: String): Config = config.getConfig(s"io.$provider")

  private def delayPolicy(config: Config, path: String): Policy = {
    config.getString(s"$path.type") match {
      case "JitteredDelay" =>
        val minDelay = config.getDuration(s"$path.params.minDelay").toScala
        val maxDelay = config.getDuration(s"$path.params.maxDelay").toScala
        JitteredDelay(minDelay, maxDelay)
      case _ =>
        val fixedDelay = config.getDuration(s"$path.params.fixedDelay").toScala
        FixedDelay(fixedDelay)
    }
  }

  val checkProgressDelayPolicy = delayPolicy(config, "activity.checkProgressDelay")

  val resourceReattemptDelayPolicy = delayPolicy(config, "resource.reAttemptDelay")

}

object OrchardSettings {

  val configPath = "com.salesforce.mce.orchard"

  def withRootConfig(rootConfig: Config): OrchardSettings = new OrchardSettings(
    rootConfig.getConfig(configPath)
  )

  def apply(): OrchardSettings = withRootConfig(ConfigFactory.load())

}
