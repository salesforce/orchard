package com.salesforce.mce.orchard.io.aws

import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.emr.EmrClient
import software.amazon.awssdk.services.ssm.SsmClient

object Client {

  def ec2(): Ec2Client = Ec2Client.create()
  def emr(): EmrClient = EmrClient.create()
  def ssm(): SsmClient = SsmClient.create()
}
