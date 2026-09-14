/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.io.aws.activity

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class EmrStepFailureClassifierSpec extends AnyWordSpec with Matchers {

  "EmrStepFailureClassifier" should {
    "classify a Spark staging failure that cannot route to HDFS DataNodes" in {
      val stderr =
        """org.apache.hadoop.ipc.RemoteException(java.io.IOException): File
          |/user/hadoop/.sparkStaging/application_123/__spark_libs__.zip could only be replicated
          |to 0 nodes instead of minReplication (=1). There are 4 datanode(s) running.
          |Caused by: java.net.NoRouteToHostException: No route to host
          |""".stripMargin

      EmrStepFailureClassifier.isClusterFatal("", stderr) shouldBe true
    }

    "classify a correlated DataNode connection timeout" in {
      val stderr =
        """Uploading Spark staging files to hdfs://cluster/user/hadoop/.sparkStaging/application_456
          |DataStreamer Exception
          |Caused by: org.apache.hadoop.net.ConnectTimeoutException: 10000 millis timeout
          |connecting to DataNode 10.0.0.12:9866
          |File could only be replicated to 0 nodes instead of minReplication (=1).
          |""".stripMargin

      EmrStepFailureClassifier.isClusterFatal("Step failed", stderr) shouldBe true
    }

    "classify zero writable replication without a transport exception" in {
      val stderr =
        """Error creating /user/hadoop/.sparkStaging/application_789/resources.zip.
          |File could only be replicated to 0 nodes instead of minReplication (=1).
          |All DataNodes are excluded in this operation.
          |""".stripMargin

      EmrStepFailureClassifier.isClusterFatal("", stderr) shouldBe true
    }

    "not classify an ordinary Spark application failure" in {
      val stderr =
        """Spark application failed after initialization
          |Caused by: java.lang.IllegalArgumentException: invalid input column
          |""".stripMargin

      EmrStepFailureClassifier.isClusterFatal("", stderr) shouldBe false
    }

    "not classify an unrelated connection timeout" in {
      val stderr =
        """Caused by: org.apache.hadoop.net.ConnectTimeoutException: timeout
          |connecting to an external REST endpoint
          |""".stripMargin

      EmrStepFailureClassifier.isClusterFatal("", stderr) shouldBe false
    }

    "not classify one DataNode timeout while HDFS remains writable" in {
      val stderr =
        "Spark staging saw ConnectTimeoutException to DataNode 10.0.0.12:9866 and continued"

      EmrStepFailureClassifier.isClusterFatal("", stderr) shouldBe false
    }

    "not classify HDFS staging context without cluster-fatal evidence" in {
      val stderr =
        """Created /user/hadoop/.sparkStaging/application_abc on HDFS.
          |DataNode pipeline established successfully.
          |""".stripMargin

      EmrStepFailureClassifier.isClusterFatal("", stderr) shouldBe false
    }

    "combine FailureDetails metadata with stderr evidence" in {
      val metadata = "Spark staging failed with NoRouteToHostException"
      val stderr =
        "DataStreamer could not connect to a DataNode; file could only be replicated to 0 nodes"

      EmrStepFailureClassifier.isClusterFatal(metadata, stderr) shouldBe true
    }

    "classify a complete cluster-fatal signature from FailureDetails metadata" in {
      val metadata =
        "Spark staging to .sparkStaging failed: NoRouteToHostException contacting DataNode; 0 of 1 minReplication nodes"

      EmrStepFailureClassifier.isClusterFatal(metadata, "") shouldBe true
    }
  }
}
