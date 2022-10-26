import io.prometheus.client.CollectorRegistry
import org.scalatest.BeforeAndAfterEach
import org.scalatestplus.play._
import org.scalatestplus.play.guice.GuiceOneServerPerTest

/**
 * Runs a browser test using Fluentium against a play application on a server port.
 */
class BrowserSpec
    extends PlaySpec with OneBrowserPerTest with GuiceOneServerPerTest with HtmlUnitFactory
    with ServerProvider with BeforeAndAfterEach {
  override def afterEach(): Unit = {
    // prevent prometheus illegal argument / duplicate collector errors
    CollectorRegistry.defaultRegistry.clear()
  }

  "Application" should {

    "work from within a browser" in {

      go to ("http://localhost:" + port)

      pageSource must include("Your new application is ready.")
    }
  }
}
