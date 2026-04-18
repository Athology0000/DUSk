package org.cobalt.api.pathfinder.pathing.heuristic

data class HeuristicWeights(
  val manhattanWeight: Double,
  val octileWeight: Double,
  val perpendicularWeight: Double,
  val heightWeight: Double,
) {

  companion object {
    val DEFAULT_WEIGHTS = HeuristicWeights(0.0, 1.0, 0.0, 0.0)
  }

}
