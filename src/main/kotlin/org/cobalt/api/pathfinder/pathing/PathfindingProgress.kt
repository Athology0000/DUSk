package org.cobalt.api.pathfinder.pathing

import org.cobalt.api.pathfinder.wrapper.PathPosition

data class PathfindingProgress(
  val start: PathPosition,
  val current: PathPosition,
  val target: PathPosition,
)
