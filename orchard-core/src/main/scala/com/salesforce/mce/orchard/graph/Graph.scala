/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.graph

trait Graph[T] {

  def addVertex(vertex: T): Graph[T]

  def removeVertex(vertex: T): Graph[T]

  def addEdge(vertexA: T, vertexB: T): Graph[T]

  def removeEdge(vertexA: T, vertexB: T): Graph[T]

  def roots: Set[T]

  def nonRoots: Set[T]

  def descendents(parent: T): Set[T]

  def getChildren(parent: T): Option[Set[T]]

  def children(parent: T): Set[T]

}

object Graph {

  def apply[T]() = new AdjacencyList[T]()

}
