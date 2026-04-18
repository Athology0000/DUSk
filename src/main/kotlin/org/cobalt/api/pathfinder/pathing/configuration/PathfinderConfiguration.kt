package org.cobalt.api.pathfinder.pathing.configuration

import org.cobalt.api.pathfinder.pathing.INeighborStrategy
import org.cobalt.api.pathfinder.pathing.NeighborStrategies
import org.cobalt.api.pathfinder.pathing.context.EnvironmentContext
import org.cobalt.api.pathfinder.pathing.heuristic.HeuristicWeights
import org.cobalt.api.pathfinder.pathing.heuristic.IHeuristicStrategy
import org.cobalt.api.pathfinder.pathing.heuristic.LinearHeuristicStrategy
import org.cobalt.api.pathfinder.pathing.processing.NodeProcessor
import org.cobalt.api.pathfinder.provider.NavigationPoint
import org.cobalt.api.pathfinder.provider.NavigationPointProvider
import org.cobalt.api.pathfinder.wrapper.PathPosition

data class PathfinderConfiguration(
  val maxIterations: Int = 5000,
  val maxLength: Int = 0,
  val async: Boolean = false,
  val fallback: Boolean = true,
  val provider: NavigationPointProvider = DefaultNavigationPointProvider,
  val heuristicWeights: HeuristicWeights = HeuristicWeights.DEFAULT_WEIGHTS,
  val processors: List<NodeProcessor> = emptyList(),
  val neighborStrategy: INeighborStrategy = NeighborStrategies.VERTICAL_AND_HORIZONTAL,
  val heuristicStrategy: IHeuristicStrategy = LinearHeuristicStrategy(),
) {

  companion object {
    val DEFAULT: PathfinderConfiguration = PathfinderConfiguration()

    fun deepCopy(pathfinderConfiguration: PathfinderConfiguration): PathfinderConfiguration {
      return pathfinderConfiguration.copy(processors = pathfinderConfiguration.processors.toList())
    }
  }

}

private object DefaultNavigationPointProvider : NavigationPointProvider {

  override fun getNavigationPoint(
    position: PathPosition,
    environmentContext: EnvironmentContext?,
  ): NavigationPoint {
    return object : NavigationPoint {
      override fun isTraversable(): Boolean = true
      override fun hasFloor(): Boolean = true
      override fun getFloorLevel(): Double = 0.0
      override fun isClimbable(): Boolean = false
      override fun isLiquid(): Boolean = false
    }
  }

}
