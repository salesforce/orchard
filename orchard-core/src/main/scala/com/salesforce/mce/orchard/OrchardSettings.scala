/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard

import scala.jdk.DurationConverters._

import com.typesafe.config.{Config, ConfigFactory}

import com.salesforce.mce.orchard.util.{DelayType, FixedDelay, JitteredDelay, Policy}

class OrchardSettings private (config: Config) {

  def slickDatabaseConf = config.getConfig("jdbc")

  def providerConfig(provider: String): Config = config.getConfig(s"io.$provider")

  private def delayPolicy(config: Config, path: String): Policy = {
    DelayType.withName(config.getString(s"$path.type")) match {
      case DelayType.JitteredDelay =>
        val minDelay = config.getDuration(s"$path.minDelay").toScala
        val maxDelay = config.getDuration(s"$path.maxDelay").toScala
        JitteredDelay(minDelay, maxDelay)
      case _ =>
        val fixedDelay = config.getDuration(s"$path.fixedDelay").toScala
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
