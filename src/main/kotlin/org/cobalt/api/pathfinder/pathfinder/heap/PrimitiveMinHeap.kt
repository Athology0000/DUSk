package org.cobalt.api.pathfinder.pathfinder.heap

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap

class PrimitiveMinHeap(initialCapacity: Int) {

  private val nodeToIndexMap: Long2IntOpenHashMap =
    Long2IntOpenHashMap(initialCapacity).apply { defaultReturnValue(-1) }

  private var nodes: LongArray = LongArray(initialCapacity + 1)
  private var costs: DoubleArray = DoubleArray(initialCapacity + 1)
  private var size = 0

  fun isEmpty(): Boolean = size == 0

  fun size(): Int = size

  fun clear() {
    size = 0
    nodeToIndexMap.clear()
  }

  fun peekMin(): Long {
    if (size == 0) throw NoSuchElementException()
    return nodes[1]
  }

  fun peekMinCost(): Double {
    if (size == 0) throw NoSuchElementException()
    return costs[1]
  }

  fun contains(packedNode: Long): Boolean = nodeToIndexMap.containsKey(packedNode)

  fun getCost(packedNode: Long): Double {
    val index = nodeToIndexMap.get(packedNode)
    return if (index == -1) Double.MAX_VALUE else costs[index]
  }

  fun insertOrUpdate(packedNode: Long, cost: Double) {
    val existingIndex = nodeToIndexMap.get(packedNode)

    if (existingIndex != -1) {
      if (cost < costs[existingIndex]) {
        costs[existingIndex] = cost
        siftUp(existingIndex)
      }
    } else {
      ensureCapacity()
      size++
      nodes[size] = packedNode
      costs[size] = cost
      nodeToIndexMap.put(packedNode, size)
      siftUp(size)
    }
  }

  fun extractMin(): Long {
    if (size == 0) throw NoSuchElementException()

    val minNode = nodes[1]
    nodeToIndexMap.remove(minNode)

    val lastNode = nodes[size]
    val lastCost = costs[size]
    nodes[1] = lastNode
    costs[1] = lastCost
    size--

    if (size > 0) {
      nodeToIndexMap.put(lastNode, 1)
      siftDown(1)
    }

    return minNode
  }

  private fun ensureCapacity() {
    if (size >= nodes.size - 1) {
      val newCap = nodes.size * 2
      nodes = nodes.copyOf(newCap)
      costs = costs.copyOf(newCap)
    }
  }

  private fun siftUp(index: Int) {
    var current = index
    val nodeToMove = nodes[current]
    val costToMove = costs[current]

    while (current > 1) {
      val parentIndex = current shr 1
      val parentCost = costs[parentIndex]

      if (costToMove < parentCost) {
        nodes[current] = nodes[parentIndex]
        costs[current] = parentCost
        nodeToIndexMap.put(nodes[current], current)
        current = parentIndex
      } else {
        break
      }
    }

    nodes[current] = nodeToMove
    costs[current] = costToMove
    nodeToIndexMap.put(nodeToMove, current)
  }

  private fun siftDown(index: Int) {
    var current = index
    val nodeToMove = nodes[current]
    val costToMove = costs[current]
    val half = size shr 1

    while (current <= half) {
      var childIndex = current shl 1
      var childCost = costs[childIndex]
      val rightIndex = childIndex + 1

      if (rightIndex <= size && costs[rightIndex] < childCost) {
        childIndex = rightIndex
        childCost = costs[rightIndex]
      }

      if (costToMove > childCost) {
        nodes[current] = nodes[childIndex]
        costs[current] = childCost
        nodeToIndexMap.put(nodes[current], current)
        current = childIndex
      } else {
        break
      }
    }

    nodes[current] = nodeToMove
    costs[current] = costToMove
    nodeToIndexMap.put(nodeToMove, current)
  }

}
