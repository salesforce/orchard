/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.system.util

import scala.collection.Seq

import play.api.libs.json.JsPath
import play.api.libs.json.JsonValidationError

case class InvalidJsonException(msg: String) extends RuntimeException(msg)

object InvalidJsonException {

  def raise(invalidFields: Seq[(JsPath, Seq[JsonValidationError])]): InvalidJsonException =
    new InvalidJsonException(
      invalidFields.map { case (path, error) => s"field: ${path}, error: ${error}" }.mkString("\n")
    )

}
