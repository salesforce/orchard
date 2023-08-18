/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package workflow

import javax.inject.Inject

import play.api.routing.SimpleRouter
import play.api.routing.sird._

class Router @Inject() (ctrl: Controller) extends SimpleRouter {

  override def routes = {

    case POST(p"") => ctrl.post()
    case GET(
          p"" ?
          q"like=$like" &
          q_o"statuses=$statuses" &
          q_o"order_by=$orderBy" &
          q_o"order=$order" &
          q_o"page=${int(page)}" &
          q_o"per_page=${int(perPage)}"
        ) =>
      ctrl.filter(like, statuses, orderBy, order, page, perPage)


    case GET(p"/$id/activities") => ctrl.activities(id)
    case GET(p"/$id/activity/$actId") => ctrl.activityAttempts(id, actId)

    case GET(p"/$id/resources") => ctrl.resources(id)
    case GET(p"/$id/resource/$rscId") => ctrl.resourceInstances(id, rscId)

    case PUT(p"/$id/cancel") => ctrl.cancel(id)
    case PUT(p"/$id/activate") => ctrl.activate(id)

    case DELETE(p"/$id") => ctrl.delete(id)

  }
}
