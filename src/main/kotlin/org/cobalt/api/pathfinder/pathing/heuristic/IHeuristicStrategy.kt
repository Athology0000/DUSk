package org.cobalt.api.pathfinder.pathing.heuristic

import org.cobalt.api.pathfinder.wrapper.PathPosition

interface IHeuristicStrategy {

  fun calculate(context: HeuristicContext): Double
  fun calculateTransitionCost(from: PathPosition, to: PathPosition): Double

}
