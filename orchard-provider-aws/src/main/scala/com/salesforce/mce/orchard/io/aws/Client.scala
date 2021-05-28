package com.salesforce.mce.orchard.io.aws

import software.amazon.awssdk.services.emr.EmrClient

object Client {

  def emr() = EmrClient.create()

}
