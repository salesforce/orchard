/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

import utils.MetricFilter

import javax.inject.Inject

import play.api.http.DefaultHttpFilters

class Filters @Inject() (
  metricsFilter: MetricFilter
) extends DefaultHttpFilters(metricsFilter)
