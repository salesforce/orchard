package com.salesforce.mce.orchard.util

import scala.util.{Failure, Try}

import com.krux.stubborn.policy.{ExponentialBackoff, Policy}

trait RetryDefaults {
  protected val defaultMaxRetries = 3
  protected val defaultPolicy: Policy = ExponentialBackoff(100, 10000)
}

object Retry extends RetryDefaults {
  @annotation.tailrec
  def apply[T](numTries: Int = defaultMaxRetries, policy: Policy = defaultPolicy)(
    fn: => T
  ): Try[T] = {
    Try { fn } match {
      case Failure(_) if numTries > 1 =>
        val delay = policy.retryDelay(defaultMaxRetries - numTries)
        Thread.sleep(delay)
        Retry(numTries - 1, policy)(fn)
      case fn => fn
    }
  }

}
