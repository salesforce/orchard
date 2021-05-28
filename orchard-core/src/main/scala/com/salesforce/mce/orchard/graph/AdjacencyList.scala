/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.mce.orchard.graph

class AdjacencyList[T] private (adjs: Map[T, Set[T]]) extends Graph[T] {

  def this() = this(Map.empty)

  override def addVertex(vertex: T): AdjacencyList[T] =
    if (adjs.contains(vertex)) this else new AdjacencyList(adjs + (vertex -> Set.empty[T]))

  override def removeVertex(vertex: T): AdjacencyList[T] =
    if (adjs.contains(vertex)) new AdjacencyList(adjs - vertex) else this

  override def addEdge(vertexA: T, vertexB: T): AdjacencyList[T] = new AdjacencyList({
    val newAdjs = adjs + (vertexA -> adjs.get(vertexA).map(_ + vertexB).getOrElse(Set(vertexB)))

    if (adjs.contains(vertexB))
      newAdjs
    else // Important! Also add empty B if it does not exist
      newAdjs + (vertexB -> Set.empty[T])
  })

  override def removeEdge(vertexA: T, vertexB: T): AdjacencyList[T] =
    if (adjs.contains(vertexA))
      new AdjacencyList({
        adjs + (vertexA -> (adjs.getOrElse(vertexA, Set.empty) - vertexB))
      })
    else
      this

  override def roots: Set[T] = adjs.keySet -- nonRoots

  override def nonRoots: Set[T] = adjs.values.flatten.toSet

  override def descendents(parent: T): Set[T] = {

    def descendentsRecursive(fringe: List[T], descendentsAccu: Set[T]): Set[T] = fringe match {
      case head :: tail =>
        val next = adjs(head) -- descendentsAccu
        descendentsRecursive(next ++: tail, descendentsAccu ++ next)
      case Nil =>
        descendentsAccu
    }

    descendentsRecursive(List(parent), Set.empty[T])
  }

  lazy val isAsynclic: Boolean = adjs.keys.forall(v => !descendents(v).contains(v))

  override def getChildren(parent: T): Option[Set[T]] = adjs.get(parent)

  override def children(parent: T): Set[T] = adjs(parent)

}
