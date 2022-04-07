/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.io.aws

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

case class ProviderSettings(config: Config) {

  def loggingUri: Option[String] = {
    val path = "logging.uri"
    if (config.hasPath(path)) Option(config.getString(path))
    else None
  }

}

object ProviderSettings {

  def apply(): ProviderSettings =
    ProviderSettings(ConfigFactory.load().getConfig("com.salesforce.mce.orchard.io.aws"))

}
