package org.cobalt.api.pathfinder.pathfinder.processing

import kotlin.math.max
import org.cobalt.api.pathfinder.Node
import org.cobalt.api.pathfinder.pathing.heuristic.IHeuristicStrategy
import org.cobalt.api.pathfinder.pathing.processing.context.EvaluationContext
import org.cobalt.api.pathfinder.pathing.processing.context.SearchContext
import org.cobalt.api.pathfinder.wrapper.PathPosition

class EvaluationContextImpl(
  override val searchContext: SearchContext,
  private val engineNode: Node,
  private val parentEngineNode: Node?,
  private val heuristicStrategy: IHeuristicStrategy,
) : EvaluationContext {

  override val currentPathPosition: PathPosition
    get() = engineNode.position

  override val previousPathPosition: PathPosition?
    get() = parentEngineNode?.position

  override val currentNodeDepth: Int
    get() = engineNode.depth

  override val currentNodeHeuristicValue: Double
    get() = engineNode.heuristic

  override val pathCostToPreviousPosition: Double
    get() = parentEngineNode?.gCost ?: 0.0

  override val baseTransitionCost: Double
    get() {
    if (parentEngineNode == null) return 0.0

    val from = parentEngineNode.position
    val to = engineNode.position
    val baseCost = heuristicStrategy.calculateTransitionCost(from, to)

    if (baseCost.isNaN() || baseCost.isInfinite()) {
      throw IllegalStateException(
        "Heuristic transition cost produced an invalid numeric value: $baseCost"
      )
    }

    return max(baseCost, 0.0)
  }

  override val grandparentPathPosition: PathPosition?
    get() = parentEngineNode?.parent?.position

}
