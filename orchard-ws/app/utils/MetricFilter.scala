/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package utils

import javax.inject.Inject

import scala.concurrent.{ExecutionContext, Future}
import akka.stream.Materializer

import play.api.mvc.{Filter, RequestHeader, Result}

import services.Metric

class MetricFilter @Inject() (
  metric: Metric
)(implicit
  val mat: Materializer,
  ec: ExecutionContext
) extends Filter {

  def apply(
    nextFilter: RequestHeader => Future[Result]
  )(requestHeader: RequestHeader): Future[Result] = {
    metric.parseRequest(requestHeader) match {
      case Some((staticPath, argument)) =>
        val stopTimerCallback = metric.startApiTimer(staticPath, argument, requestHeader.method)

        nextFilter(requestHeader)
          .transform(
            result => {
              metric.incrementStatusCount(result.header.status.toString)
              stopTimerCallback()
              result
            },
            exception => {
              metric.incrementStatusCount("500")
              stopTimerCallback()
              exception
            }
          )
      case _ =>
        nextFilter(requestHeader)
    }
  }
}
