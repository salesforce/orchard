/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package admin

import javax.inject.Inject

import play.api.routing.{Router => PlayRouter, SimpleRouter}
import play.api.routing.sird._

class Router @Inject() (ctrl: Controller) extends SimpleRouter {

  override def routes: PlayRouter.Routes = {
    case GET(p"/workflow-managers") => ctrl.workflowManagers()
  }

}
