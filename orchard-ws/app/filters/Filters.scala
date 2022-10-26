import utils.MetricFilter

import javax.inject.Inject

import play.api.http.DefaultHttpFilters

class Filters @Inject() (
  metricsFilter: MetricFilter
) extends DefaultHttpFilters(metricsFilter)
