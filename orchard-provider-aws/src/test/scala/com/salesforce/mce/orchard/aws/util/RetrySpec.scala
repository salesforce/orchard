package com.salesforce.mce.orchard.aws.util

import org.scalatest.wordspec.AnyWordSpec

import com.salesforce.mce.orchard.io.aws.util.Retry

class RetrySpec extends AnyWordSpec {
  "Retry" when {
    "it runs" should {
      "try to execute N times before failing" in {
        val numRetries = 2
        var numRuns = 0
        Retry(numRetries) {
          numRuns += 1
          throw new RuntimeException("Throw")
        }
        assert(numRuns === numRetries)
      }

      "Return function's value in success case" in {
        val expected = 1
        val actual = Retry() {
          expected
        }.get
        assert(actual == expected)
      }
    }
  }
}
