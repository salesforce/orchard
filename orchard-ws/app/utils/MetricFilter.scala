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
import play.api.Configuration

class MetricFilter @Inject() (
  metric: Metric,
  conf: Configuration
)(implicit
  val mat: Materializer,
  ec: ExecutionContext
) extends Filter {

  private def checkPathIsDisabled(path: String): Boolean = bypassPaths.contains(path)

  private val bypassPaths = conf.getOptional[Seq[String]]("orchard.metrics.bypass.paths") match {
    case Some(paths) => paths.toSet
    case _ => Set[String]()
  }

  private val enableMetrics = conf.getOptional[Boolean]("orchard.metrics.enable").getOrElse(false)

  private val staticPathMarkers = Seq("instance", "__metrics", "__status")

  private def parseRequest(request: RequestHeader): Option[(String, String)] = {
    if (checkPathIsDisabled(request.path) || !enableMetrics)
      None
    else {
      val (staticPath, args) = request.path
        .split("/")
        .filter(_.nonEmpty)
        .foldLeft((List[String](), List[String]())) {
          case ((Nil, l2), token) => (List(token), l2)
          case ((h :: t, l2), token) =>
            if (staticPathMarkers.contains(h)) (h :: t, token :: l2)
            else (token :: h :: t, l2)
        }
      Some((staticPath.reverse.mkString("-"), args.reverse.mkString("-")))
    }
  }

  def apply(
    nextFilter: RequestHeader => Future[Result]
  )(requestHeader: RequestHeader): Future[Result] = {
    parseRequest(requestHeader) match {
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
