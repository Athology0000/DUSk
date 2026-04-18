package org.cobalt.api.pathfinder.pathfinder

import it.unimi.dsi.fastutil.longs.Long2ObjectMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import it.unimi.dsi.fastutil.longs.LongSet
import kotlin.math.abs
import kotlin.math.max
import org.cobalt.api.pathfinder.Node
import org.cobalt.api.pathfinder.pathfinder.heap.PrimitiveMinHeap
import org.cobalt.api.pathfinder.pathfinder.processing.EvaluationContextImpl
import org.cobalt.api.pathfinder.pathing.configuration.PathfinderConfiguration
import org.cobalt.api.pathfinder.pathing.processing.context.EvaluationContext
import org.cobalt.api.pathfinder.pathing.processing.context.SearchContext
import org.cobalt.api.pathfinder.util.RegionKey
import org.cobalt.api.pathfinder.wrapper.PathPosition

class AStarPathfinder(configuration: PathfinderConfiguration) : AbstractPathfinder(configuration) {

  private val currentSession = ThreadLocal<PathfindingSession>()

  override fun insertStartNode(node: Node, fCost: Double, openSet: PrimitiveMinHeap) {
    val session = getSessionOrThrow()
    val packedPos = RegionKey.pack(node.position)
    openSet.insertOrUpdate(packedPos, fCost)
    session.openSetNodes[packedPos] = node
  }

  override fun extractBestNode(openSet: PrimitiveMinHeap): Node {
    val session = getSessionOrThrow()
    val packedPos = openSet.extractMin()
    val node = session.openSetNodes[packedPos]!!
    session.openSetNodes.remove(packedPos)
    return node
  }

  override fun initializeSearch() {
    currentSession.set(PathfindingSession())
  }

  override fun processSuccessors(
    requestStart: PathPosition,
    requestTarget: PathPosition,
    currentNode: Node,
    openSet: PrimitiveMinHeap,
    searchContext: SearchContext,
  ) {
    val session = getSessionOrThrow()
    val offsets = neighborStrategy.getOffsets(currentNode.position)

    for (offset in offsets) {
      val neighborPos = currentNode.position.add(offset)
      val packedPos = RegionKey.pack(neighborPos)

      if (openSet.contains(packedPos)) {
        val existing = session.openSetNodes[packedPos]!!
        updateExistingNode(existing, packedPos, currentNode, searchContext, openSet)
        continue
      }

      if (session.closedSet.contains(packedPos)) {
        continue
      }

      val neighbor = createNeighborNode(neighborPos, requestStart, requestTarget, currentNode)
      neighbor.parent = currentNode

      val context =
        EvaluationContextImpl(
          searchContext,
          neighbor,
          currentNode,
          pathfinderConfiguration.heuristicStrategy
        )

      if (!isValidByCustomProcessors(context)) {
        continue
      }

      val gCost = calculateGCost(context)
      neighbor.gCost = gCost
      val fCost = neighbor.fCost
      val heapKey = calculateHeapKey(neighbor, fCost)

      openSet.insertOrUpdate(packedPos, heapKey)
      session.openSetNodes[packedPos] = neighbor
    }
  }

  private fun updateExistingNode(
    existing: Node,
    packedPos: Long,
    currentNode: Node,
    searchContext: SearchContext,
    openSet: PrimitiveMinHeap,
  ) {
    val context =
      EvaluationContextImpl(
        searchContext,
        existing,
        currentNode,
        pathfinderConfiguration.heuristicStrategy
      )

    val newG = calculateGCost(context)
    val tol = Math.ulp(max(abs(newG), abs(existing.gCost)))

    if (newG + tol >= existing.gCost) return

    if (!isValidByCustomProcessors(context)) {
      return
    }

    existing.parent = currentNode
    existing.gCost = newG
    val newF = existing.fCost
    val newKey = calculateHeapKey(existing, newF)
    val oldKey = openSet.getCost(packedPos)

    if (newKey + Math.ulp(newKey) < oldKey) {
      openSet.insertOrUpdate(packedPos, newKey)
    } else if (abs(newKey - oldKey) <= Math.ulp(newKey)) {
      openSet.insertOrUpdate(packedPos, oldKey - Math.ulp(oldKey))
    }
  }

  private fun createNeighborNode(
    position: PathPosition,
    start: PathPosition,
    target: PathPosition,
    parent: Node,
  ): Node {
    return Node(
      position,
      start,
      target,
      pathfinderConfiguration.heuristicWeights,
      pathfinderConfiguration.heuristicStrategy,
      parent.depth + 1
    )
  }

  private fun isValidByCustomProcessors(context: EvaluationContext): Boolean {
    return processors.all { it.isValid(context) }
  }

  private fun calculateGCost(context: EvaluationContext): Double {
    val baseCost = context.baseTransitionCost
    val additionalCost = processors.sumOf { it.calculateCostContribution(context).value }

    val transitionCost = max(0.0, baseCost + additionalCost)
    return context.pathCostToPreviousPosition + transitionCost
  }

  override fun markNodeAsExpanded(node: Node) {
    val session = getSessionOrThrow()
    val packedPos = RegionKey.pack(node.position)

    session.closedSet.add(packedPos)
  }

  override fun performAlgorithmCleanup() {
    currentSession.remove()
  }

  private fun getSessionOrThrow(): PathfindingSession {
    return currentSession.get()
      ?: throw IllegalStateException(
        "Pathfinding session not initialized. Call initializeSearch() first."
      )
  }

  private class PathfindingSession {
    val openSetNodes: Long2ObjectMap<Node> = Long2ObjectOpenHashMap()
    val closedSet: LongSet = LongOpenHashSet()
  }

}
