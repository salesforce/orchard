package com.salesforce.mce.orchard.util

import scala.util.{Failure, Try}

import com.krux.stubborn.policy.{ExponentialBackoff, Policy}

object Retry {
  val maxRetries = 3
  val policy: Policy = ExponentialBackoff(100, 10000)

  @annotation.tailrec
  def apply[T](numTries: Int = maxRetries)(fn: => T): Try[T] = {
    Try { fn } match {
      case Failure(_) if numTries > 1 =>
        val delay = policy.retryDelay(maxRetries - numTries)
        Thread.sleep(delay)
        Retry(numTries - 1)(fn)
      case fn => fn
    }
  }

}
