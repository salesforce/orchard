package com.salesforce.mce.orchard.io.aws.util

import scala.util.{Failure, Try}

object Retry {
  @annotation.tailrec
  def apply[T](n: Int = 3)(fn: => T): Try[T] = {
    Try { fn } match {
      case Failure(_) if n > 1 => Retry(n - 1)(fn)
      case fn => fn
    }
  }
}
