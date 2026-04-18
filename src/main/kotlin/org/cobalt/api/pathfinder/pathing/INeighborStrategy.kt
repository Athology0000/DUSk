package org.cobalt.api.pathfinder.pathing

import org.cobalt.api.pathfinder.wrapper.PathPosition
import org.cobalt.api.pathfinder.wrapper.PathVector

fun interface INeighborStrategy {

  fun getOffsets(): Iterable<PathVector>
  fun getOffsets(currentPosition: PathPosition): Iterable<PathVector> {
    return getOffsets()
  }

}
