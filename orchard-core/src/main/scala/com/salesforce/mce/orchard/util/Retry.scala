package com.salesforce.mce.orchard.util

import com.krux.stubborn.policy.ExponentialBackoff

import scala.util.{Failure, Try}

object Retry {
  private val MAX_RETRIES = 3
  private val policy = ExponentialBackoff(100, 10000)

  @annotation.tailrec
  def apply[T](numTries: Int = MAX_RETRIES)(fn: => T): Try[T] = {
    Try { fn } match {
      case Failure(_) if numTries > 1 =>
        val delay = policy.retryDelay(MAX_RETRIES - numTries)
        Thread.sleep(delay)
        Retry(numTries - 1)(fn)
      case fn => fn
    }
  }

}
