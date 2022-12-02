/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.io.aws.resource

import play.api.libs.json.{Json, Reads, Writes}

/**
 * Used in Ec2Resource and EmrResource Spec, leverages Play Json validate for error checking
 * @param key  field cannot be null, cannot be empty String
 * @param value field cannot be null, though can be empty String
 */
case class AwsTag(
  key: String,
  value: String
)

object AwsTag {
  implicit val tagWrites: Writes[AwsTag] = Json.writes[AwsTag]
  implicit val tagReads: Reads[AwsTag] = Json.reads[AwsTag]
}
