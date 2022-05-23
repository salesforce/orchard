/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package utils

import java.math.BigInteger
import java.security.MessageDigest

import scala.util.Try
import scala.concurrent.duration._

import play.api.mvc.Request
import com.typesafe.config.ConfigFactory

class Authorization(private var authorizationSettings: AuthorizationSettings) {

  import Authorization._

  def getRoles(request: Request[_]): List[String] = {
    authorizationSettings.authEnabled match {
      case true => getKeyRoles(request.headers.get(authorizationSettings.authHeader))
      case false => List(Admin)
    }
  }

  private def getKeyRoles(key: Option[String]) = {
    key match {
      case Some(x) => authorizationSettings.keyRoles.getOrElse(convertToSha256(x), List.empty)
      case None => List.empty
    }
  }

  def checkAuthorization(request: Request[_]): Boolean =
    request.headers
      .get(authorizationSettings.authHeader)
      .map(validateKey)
      .getOrElse(!authorizationSettings.authEnabled)

  def refreshDelay: Option[FiniteDuration] = authorizationSettings.ttl.map(_.second)

  private def validateKey(key: String): Boolean =
    authorizationSettings.keyRoles.contains(convertToSha256(key))

  def reloadSettings(): this.type = {
    ConfigFactory.invalidateCaches()
    authorizationSettings = AuthorizationSettings()
    this
  }

}

object Authorization {

  final val Admin = "admin"
  final val User = "user"

  def convertToSha256(key: String): String =
    Try(
      String.format(
        "%032x",
        new BigInteger(
          1,
          MessageDigest
            .getInstance("SHA-256")
            .digest(key.getBytes("UTF-8"))
        )
      )
    ).getOrElse(key)

}
