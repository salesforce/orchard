package com.salesforce.mce.orchard

import org.scalatest.wordspec.AnyWordSpec

class HelloWorldSpec extends AnyWordSpec {
  "hello world" when {
    "it runs" should {
      "do stuff" in {
        assert(1 + 1 === 2)
      }
    }
  }
}
