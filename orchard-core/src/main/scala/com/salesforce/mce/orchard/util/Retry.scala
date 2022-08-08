package com.salesforce.mce.orchard.util

import com.krux.stubborn.RetryDefaults
import com.krux.stubborn.policy.Policy

import scala.util.{Failure, Try}

object Retry extends RetryDefaults {

  @annotation.tailrec
  def apply[T](numTries: Int = defaultMaxRetry, policy: Policy = defaultPolicy)(
    fn: => T
  ): Try[T] = {
    Try { fn } match {
      case Failure(_) if numTries > 1 =>
        val delay = policy.retryDelay(defaultMaxRetry - numTries)
        Thread.sleep(delay)
        Retry(numTries - 1, policy)(fn)
      case fn => fn
    }
  }

}
