/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package utils

import java.net.URL

import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

import com.typesafe.config.ConfigException
import com.typesafe.config.{Config, ConfigFactory, ConfigList}

class AuthorizationSettings private (config: Config) {

  def authHeader: String = config.getString(s"header-name")

  def authEnabled: Boolean = config.getBoolean(s"enabled")

  def keyRoles: Map[String, List[String]] = {
    val userRoles = for {
      (r, us) <- config.getConfig("hashed-keys").root().asScala.toList
      u <- us.asInstanceOf[ConfigList].unwrapped().asScala
    } yield u.toString() -> r

    userRoles.groupBy(_._1).view.mapValues(_.map(_._2)).toMap
  }


  def ttl: Option[Long] = Try(config.getInt(s"ttl")) match {
    case Success(d) => Some(d)
    case Failure(e: ConfigException.Missing) => None
    case Failure(e) => throw e
  }

}

object AuthorizationSettings {

  val configPath = "orchard.auth"

  def withRootConfig(rootConfig: Config): AuthorizationSettings = new AuthorizationSettings(
    rootConfig.getConfig(configPath)
  )

  private def loadConfig(): Config = Option(System.getProperty("orchard.auth.config"))
    .orElse(Option(System.getenv("ORCHARD_AUTH_CONFIG_URL")))
    .map(url => ConfigFactory.load(ConfigFactory.parseURL(new URL(url))))
    .getOrElse(ConfigFactory.load())

  def apply(): AuthorizationSettings = withRootConfig(loadConfig())

}
