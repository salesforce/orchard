/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package routers

import javax.inject._

import play.api.routing.sird._
import play.api.routing.{Router, SimpleRouter}

import workflow.Controller

class WorkflowsRouter @Inject() (ctlr: Controller) extends SimpleRouter {

  override def routes: Router.Routes = {
    // TODO to be deprecated, use /v1/workflow instead of workflows
    case GET(
          p"" ?
          q"like=$like" &
          q_o"statuses=$statuses" &
          q_o"order_by=$orderBy" &
          q_o"order=$order" &
          q_o"page=${int(page)}" &
          q_o"per_page=${int(perPage)}"
        ) =>
      ctlr.filter(like, statuses, orderBy, order, page, perPage)
  }

}
