/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard

import scala.jdk.DurationConverters._

import com.typesafe.config.{Config, ConfigFactory}

import com.salesforce.mce.orchard.util.{FixedDelay, JitteredDelay, Policy}

class OrchardSettings private (config: Config) {

  val jitteredDelayKey = "jitteredDelay"

  def slickDatabaseConf = config.getConfig("jdbc")

  def providerConfig(provider: String): Config = config.getConfig(s"io.$provider")

  private def delayPolicy(config: Config, path: String): Policy = {
    val delayObj = config.getObject(path)
    if (delayObj.containsKey(jitteredDelayKey)) {
      val minDelay = config.getDuration(s"$path.$jitteredDelayKey.minDelay").toScala
      val maxDelay = config.getDuration(s"$path.$jitteredDelayKey.maxDelay").toScala
      JitteredDelay(minDelay, maxDelay)
    } else {
      val fixedDelay = config.getDuration(path).toScala
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
