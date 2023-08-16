package admin

import javax.inject.Inject

import play.api.routing.{Router => PlayRouter, SimpleRouter}
import play.api.routing.sird._

class Router @Inject() (ctrl: Controller) extends SimpleRouter {

  override def routes: PlayRouter.Routes = {
    case GET(p"/workflow-managers") => ctrl.workflowManagers()
  }

}
