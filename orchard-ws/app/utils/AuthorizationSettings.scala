/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package utils

import scala.jdk.CollectionConverters._

import com.typesafe.config.{Config, ConfigFactory, ConfigList}

class AuthorizationSettings private (config: Config) {

  def authHeader: String = config.getString(s"header-name")

  def authEnabled: Boolean = config.getBoolean(s"enabled")

  def keyRoles: Map[String, List[String]] = {
    val userRoles = for {
      (r, _) <- config.getConfig("hashed-keys").root().asScala.toList
      u <- asInstanceOf[ConfigList].unwrapped().asScala
    } yield u.toString() -> r

    userRoles.groupBy(_._1).view.mapValues(_.map(_._2)).toMap
  }

}

object AuthorizationSettings {

  val configPath = "orchard.auth"

  def withRootConfig(rootConfig: Config): AuthorizationSettings = new AuthorizationSettings(
    rootConfig.getConfig(configPath)
  )

  def apply(): AuthorizationSettings = withRootConfig(ConfigFactory.load())

}
