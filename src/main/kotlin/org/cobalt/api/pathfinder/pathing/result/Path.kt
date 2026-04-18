package org.cobalt.api.pathfinder.pathing.result

import org.cobalt.api.pathfinder.wrapper.PathPosition

interface Path : Iterable<PathPosition> {

  fun length(): Int
  fun getStart(): PathPosition
  fun getEnd(): PathPosition
  fun collect(): Collection<PathPosition>

}
