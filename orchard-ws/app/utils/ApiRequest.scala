/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package utils

import play.api.mvc.{Request, WrappedRequest}

sealed trait ApiRequest[A]

case class ValidApiRequest[A](apiRole: String, request: Request[A])
    extends WrappedRequest[A](request) with ApiRequest[A]

case class InvalidApiRequest[A](request: Request[A])
    extends WrappedRequest[A](request) with ApiRequest[A]
