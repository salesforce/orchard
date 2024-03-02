package com.salesforce.mce.orchard.util

import scala.concurrent.duration
import scala.concurrent.duration.FiniteDuration
import scala.util.Random

case class ProgressDelay(minDelay: FiniteDuration, maxDelay: FiniteDuration) {
  def jitteredDelay(): FiniteDuration = {
    val rand = new Random()
    FiniteDuration(rand.between(minDelay.toSeconds, maxDelay.toSeconds + 1L), duration.SECONDS)
  }
}
