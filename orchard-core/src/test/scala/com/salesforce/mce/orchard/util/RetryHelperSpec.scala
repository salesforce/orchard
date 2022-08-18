package com.salesforce.mce.orchard.util

import scala.util.control.Exception.catching

import com.krux.stubborn.Retryable
import com.krux.stubborn.policy.ExponentialBackoff
import org.scalatest.wordspec.AnyWordSpec

class RetryHelperSpec extends AnyWordSpec {
  private val testPolicy = ExponentialBackoff(300, 600)

  "Using Retry helper object idiom" when {
    "it runs" should {
      "Work with either" in {
        val expected = 1
        val maxRetries = 3
        val resultTry = Retry(maxRetries, testPolicy) {
          expected
        }
        assert(resultTry.toEither.isRight)
        assert(resultTry.get == expected)
      }
    }
  }

  "Using Retryable catching idiom" when {
    "it runs" should {
      "try to execute N times before failing using opt" in {
        val numRetries = 3
        var numRuns = -1
        catching(classOf[RuntimeException]).opt {
          Retryable.retry(maxRetry = numRetries, policy = testPolicy) {
            numRuns += 1
            throw new RuntimeException("Mock exception")
          }
        }
        assert(numRetries === numRuns)
      }

      "Return function's value in success case using either" in {
        val expected = 1
        val actual = catching(classOf[RuntimeException]) either {
          Retryable.retry(policy = testPolicy) {
            expected
          }
        }
        assert(actual.isRight)
        assert(actual.toTry.get == expected)
      }
    }
  }
}
