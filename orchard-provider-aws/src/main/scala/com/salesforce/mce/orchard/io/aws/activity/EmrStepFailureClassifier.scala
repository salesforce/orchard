/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.io.aws.activity

private[activity] object EmrStepFailureClassifier {

  private val SparkStagingPatterns = Seq(
    ".sparkstaging",
    "spark staging",
    "staging directory"
  )

  private val DataNodePatterns = Seq(
    "datanode",
    "minreplication",
    "addblock",
    "data streamer",
    "could only be replicated"
  )

  private val TransportFailurePatterns = Seq(
    "noroutetohostexception",
    "connecttimeoutexception"
  )

  private val ZeroWritableReplicationPatterns = Seq(
    "could only be replicated to 0 nodes",
    "0 of the 1 minreplication nodes",
    "0 of 1 minreplication nodes"
  )

  def isClusterFatal(failureMetadata: String, stderr: String): Boolean = {
    val evidence = s"$failureMetadata\n$stderr"
      .toLowerCase(java.util.Locale.ROOT)
      .replaceAll("\\s+", " ")
    val hasSparkStagingContext = SparkStagingPatterns.exists(evidence.contains)
    val hasDataNodeContext = DataNodePatterns.exists(evidence.contains)
    val hasTransportFailure = TransportFailurePatterns.exists(evidence.contains)
    val hasZeroWritableReplication = ZeroWritableReplicationPatterns.exists(evidence.contains)
    val allDataNodesExcluded = evidence.contains("all datanodes") && evidence.contains("excluded")

    hasSparkStagingContext && hasDataNodeContext &&
      hasZeroWritableReplication && (hasTransportFailure || allDataNodesExcluded)
  }
}
