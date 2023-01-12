package com.salesforce.mce.orchard.model

sealed trait ActionCondition {
  val Serialized: String
}

object ActionCondition {

  def apply(serialized: String) = serialized match {
    case OnSuccess.Serialized => OnSuccess
    case OnFailure.Serialized => OnFailure
  }

  case object OnSuccess extends ActionCondition {
    final val Serialized = "on_success"
  }

  case object OnFailure extends ActionCondition {
    final val Serialized = "on_failure"
  }

}
