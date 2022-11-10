package services

import java.io.StringWriter
import javax.inject.{Inject, Singleton}

import scala.concurrent.{ExecutionContext, Future}

import com.typesafe.config.ConfigFactory
import play.api.Configuration
import play.api.mvc.RequestHeader
import io.prometheus.client._
import io.prometheus.client.exporter.common.TextFormat
import io.prometheus.client.hotspot.DefaultExports

trait Metric {
  private lazy val config = new Configuration(ConfigFactory.load())

  private val enableMetrics =
    config.getOptional[Boolean]("orchard.metrics.enable").getOrElse(false)

  private val bypassPaths = config.getOptional[Seq[String]]("orchard.metrics.bypass.paths") match {
    case Some(paths) => paths.toSet
    case _ => Set[String]()
  }

  private val staticPathMarkers = Seq("instance", "__metrics", "__status")

  def parseRequest(request: RequestHeader): Option[(String, String)] = {
    if (checkPathIsDisabled(request.path) || !checkMetricIsEnabled)
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

  def checkMetricIsEnabled: Boolean = { enableMetrics }

  def checkPathIsDisabled(path: String): Boolean = { bypassPaths.contains(path) }

  def incrementStatusCount(status: String): Unit

  def startApiTimer(labels: String*): () => Unit

  def collect: Future[String]

  def onCollect(): Unit

}

@Singleton
class PrometheusMetric @Inject() (implicit ec: ExecutionContext) extends Metric {

  DefaultExports.initialize()
  // clear the default registry uppon initialization to make it easier to work with play-plugin
  // auto recompile
  CollectorRegistry.defaultRegistry.clear()

  private val httpStatusCount: Counter = Counter.build
    .name("http_requests_total")
    .help("Total HTTP Requests Count")
    .labelNames("status")
    .register

  private val apiLatency: Summary = Summary
    .build()
    .name("apiLatencySummary")
    .labelNames("path", "arguments", "method")
    .help("Profile API response time summary")
    .quantile(0.5d, 0.001d)
    .quantile(0.95d, 0.001d)
    .quantile(0.99d, 0.001d)
    .register

  override def incrementStatusCount(status: String): Unit = {
    httpStatusCount.labels(status).inc()
  }

  override def startApiTimer(labels: String*): () => Unit = {
    val timer = apiLatency.labels(labels: _*).startTimer()
    val callback = () => timer.close
    callback
  }

  // Get metrics from the local prometheus collector default registry
  override def collect: Future[String] = Future {
    val writer = new StringWriter()
    TextFormat.write004(writer, CollectorRegistry.defaultRegistry.metricFamilySamples())
    writer.toString
  }

  override def onCollect(): Unit = {}

}
