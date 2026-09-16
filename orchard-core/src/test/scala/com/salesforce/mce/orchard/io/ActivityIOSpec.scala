/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.io

import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{JsValue, Json}

import com.salesforce.mce.orchard.model.Status

class ActivityIOSpec extends AnyWordSpec {

  "ActivityIO.getProgressWithDetails" should {
    "preserve existing provider behavior as an activity-local result" in {
      val activity = new ActivityIO {
        override def create(): Either[Throwable, JsValue] = Right(Json.obj())
        override def getProgress(spec: JsValue): Either[Throwable, Status.Value] =
          Right(Status.Failed)
        override def terminate(spec: JsValue): Either[Throwable, Status.Value] =
          Right(Status.Canceled)
      }

      val progress = activity.getProgressWithDetails(Json.obj())

      assertResult(Right(ActivityIO.Progress(Status.Failed)))(progress)
      assert(progress.toOption.flatMap(_.updatedAttemptSpec).isEmpty)
    }
  }
}
