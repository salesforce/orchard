/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.io.aws.util

import play.api.libs.json.{Json, Reads, Writes}

object MarketTypeStrategy extends Enumeration{

  /**
   * AlwaysUseSpot: always use spot instances, regardless of the node type or attempt number.
   * UseOnDemandOnLastAttempt: use on-demand instances for the last attempt, regardless of the node type.
   * UseOnDemandOnMasterNode: use on-demand instances for the master node, regardless of the attempt number.
   * AlwaysUseOnDemand: always use on-demand instances, regardless of the node type or attempt number.
   */

  type MarketTypeStrategy = Value
  final val AlwaysUseSpot, UseOnDemandOnLastAttempt, UseOnDemandOnMasterNode, AlwaysUseOnDemand = Value

  implicit val marketTypeStrategyWrites: Writes[MarketTypeStrategy.Value] = Json.writes[MarketTypeStrategy.Value]
  implicit val marketTypeStrategyReads: Reads[MarketTypeStrategy.Value] = Json.reads[MarketTypeStrategy.Value]

}

