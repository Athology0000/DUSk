package org.cobalt.api.pathfinder.pathing.processing.context

import org.cobalt.api.pathfinder.pathing.configuration.PathfinderConfiguration
import org.cobalt.api.pathfinder.pathing.context.EnvironmentContext
import org.cobalt.api.pathfinder.provider.NavigationPointProvider
import org.cobalt.api.pathfinder.wrapper.PathPosition

interface EvaluationContext {

  val currentPathPosition: PathPosition
  val previousPathPosition: PathPosition?
  val currentNodeDepth: Int
  val currentNodeHeuristicValue: Double
  val pathCostToPreviousPosition: Double
  val baseTransitionCost: Double
  val searchContext: SearchContext
  val grandparentPathPosition: PathPosition?

  val pathfinderConfiguration: PathfinderConfiguration
    get() = searchContext.pathfinderConfiguration

  val navigationPointProvider: NavigationPointProvider
    get() = searchContext.navigationPointProvider

  val sharedData: MutableMap<String, Any>
    get() = searchContext.sharedData

  val startPathPosition: PathPosition
    get() = searchContext.startPathPosition

  val targetPathPosition: PathPosition
    get() = searchContext.targetPathPosition

  val environmentContext: EnvironmentContext?
    get() = searchContext.environmentContext

}
