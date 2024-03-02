/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard

import scala.jdk.DurationConverters._

import com.typesafe.config.{Config, ConfigFactory}

class OrchardSettings private (config: Config) {

  def slickDatabaseConf = config.getConfig("jdbc")

  def providerConfig(provider: String): Config = config.getConfig(s"io.$provider")

  val checkProgressDelayMin = config.getDuration("activity.checkProgressDelayMin").toScala

  val checkProgressDelayMax = config.getDuration("activity.checkProgressDelayMax").toScala

  val resourceReattemptDelay = config.getDuration("resource.reAttemptDelay").toScala

}

object OrchardSettings {

  val configPath = "com.salesforce.mce.orchard"

  def withRootConfig(rootConfig: Config): OrchardSettings = new OrchardSettings(
    rootConfig.getConfig(configPath)
  )

  def apply(): OrchardSettings = withRootConfig(ConfigFactory.load())

}
