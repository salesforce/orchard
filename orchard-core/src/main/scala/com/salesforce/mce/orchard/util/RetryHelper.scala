package com.salesforce.mce.orchard.util

import scala.util.Try

import com.krux.stubborn.RetryDefaults
import com.krux.stubborn.Retryable.retry
import com.krux.stubborn.policy.Policy

object Retry extends RetryDefaults {
  def apply[A](numTries: Int = defaultMaxRetry, policy: Policy = defaultPolicy)(
    action: => A
  ): Try[A] = {
    Try { retry(numTries, policy)(action) }
  }
}
