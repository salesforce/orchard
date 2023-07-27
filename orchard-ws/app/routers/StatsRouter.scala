/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package routers

import javax.inject._

import controllers.StatsController
import play.api.routing.{Router, SimpleRouter}
import play.api.routing.sird._

class StatsRouter @Inject() (ctrl: StatsController) extends SimpleRouter {

  override def routes: Router.Routes = {
    case GET(p"/counts") => ctrl.count()
  }

}
