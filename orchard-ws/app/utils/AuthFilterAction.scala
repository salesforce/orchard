/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package utils

import javax.inject.Inject

import scala.concurrent.{ExecutionContext, Future}

import play.api.mvc._
import play.api.libs.json.JsNull

class AuthFilterAction @Inject() (parser: BodyParsers.Default, auth: Authorization)(implicit
  ec: ExecutionContext
) extends ActionFilter[ApiRequest] {

  override protected def executionContext: ExecutionContext = ec

  override protected def filter[A](request: ApiRequest[A]): Future[Option[Result]] =
    Future.successful {
      request match {
        case InvalidApiRequest(_) =>
          Some(Results.Unauthorized(JsNull))
        case _ =>
          None
      }
    }

}
