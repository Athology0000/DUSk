package org.cobalt.api.pathfinder.pathing.heuristic

import org.cobalt.api.pathfinder.pathing.PathfindingProgress
import org.cobalt.api.pathfinder.wrapper.PathPosition

class HeuristicContext(
  val pathfindingProgress: PathfindingProgress,
  val heuristicWeights: HeuristicWeights,
) {

  constructor(
    position: PathPosition,
    startPosition: PathPosition,
    targetPosition: PathPosition,
    heuristicWeights: HeuristicWeights,
  ) : this(PathfindingProgress(startPosition, position, targetPosition), heuristicWeights)

  val position: PathPosition
    get() = pathfindingProgress.current

  val startPosition: PathPosition
    get() = pathfindingProgress.start

  val targetPosition: PathPosition
    get() = pathfindingProgress.target

}
