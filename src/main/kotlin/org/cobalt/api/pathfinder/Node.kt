package org.cobalt.api.pathfinder

import org.cobalt.api.pathfinder.pathing.heuristic.HeuristicContext
import org.cobalt.api.pathfinder.pathing.heuristic.HeuristicWeights
import org.cobalt.api.pathfinder.pathing.heuristic.IHeuristicStrategy
import org.cobalt.api.pathfinder.wrapper.PathPosition

class Node(
  val position: PathPosition,
  start: PathPosition,
  target: PathPosition,
  heuristicWeights: HeuristicWeights,
  heuristicStrategy: IHeuristicStrategy,
  val depth: Int,
) : Comparable<Node> {

  val heuristic: Double =
    heuristicStrategy.calculate(HeuristicContext(position, start, target, heuristicWeights))

  var gCost: Double = 0.0
  var parent: Node? = null

  val fCost: Double
    get() = gCost + heuristic

  fun isTarget(target: PathPosition): Boolean = position == target

  override fun equals(other: Any?): Boolean {
    if (other == null || this::class != other::class) return false
    other as Node
    return position == other.position
  }

  override fun hashCode(): Int = position.hashCode()

  override fun compareTo(other: Node): Int {
    val fCostComparison = fCost.compareTo(other.fCost)
    if (fCostComparison != 0) {
      return fCostComparison
    }

    val heuristicComparison = heuristic.compareTo(other.heuristic)
    if (heuristicComparison != 0) {
      return heuristicComparison
    }

    return this.depth.compareTo(other.depth)
  }

}
