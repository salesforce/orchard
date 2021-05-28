/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package utils

import javax.inject.Inject

import scala.concurrent.{ExecutionContext, Future}

import play.api.Logging
import play.api.mvc.{ActionBuilder, ActionTransformer, AnyContent, BodyParsers, Request}

class AuthTransformAction @Inject() (val parser: BodyParsers.Default)(implicit
  val executionContext: ExecutionContext
) extends ActionBuilder[ApiRequest, AnyContent] with ActionTransformer[Request, ApiRequest]
    with Logging {
  def transform[A](request: Request[A]) =
    Future.successful {
      Authorization.getRoles(request) match {
        case Nil =>
          logger.debug(s"transform not found x-api-key")
          InvalidApiRequest(request)
        case x =>
          logger.debug(s"transform found x-api-key $x")
          if (x.contains(Authorization.Admin)) {
            ValidApiRequest(Authorization.Admin, request)
          } else if (x.contains(Authorization.User)) {
            ValidApiRequest(Authorization.User, request)
          } else {
            InvalidApiRequest(request)
          }
      }
    }

}
