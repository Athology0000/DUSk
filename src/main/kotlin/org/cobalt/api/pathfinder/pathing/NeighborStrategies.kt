package org.cobalt.api.pathfinder.pathing

import org.cobalt.api.pathfinder.wrapper.PathVector

object NeighborStrategies {

  private val VERTICAL_AND_HORIZONTAL_OFFSETS =
    listOf(
      PathVector(1.0, 0.0, 0.0),
      PathVector(-1.0, 0.0, 0.0),
      PathVector(0.0, 0.0, 1.0),
      PathVector(0.0, 0.0, -1.0),
      PathVector(0.0, 1.0, 0.0),
      PathVector(0.0, -1.0, 0.0)
    )

  private val DIAGONAL_3D_OFFSETS = buildList {
    for (x in -1..1) {
      for (y in -1..1) {
        for (z in -1..1) {
          if (x == 0 && y == 0 && z == 0) continue
          add(PathVector(x.toDouble(), y.toDouble(), z.toDouble()))
        }
      }
    }
  }

  private val HORIZONTAL_DIAGONAL_AND_VERTICAL_OFFSETS =
    listOf(
      PathVector(1.0, 0.0, 0.0),
      PathVector(-1.0, 0.0, 0.0),
      PathVector(0.0, 0.0, 1.0),
      PathVector(0.0, 0.0, -1.0),
      PathVector(0.0, 1.0, 0.0),
      PathVector(0.0, -1.0, 0.0),
      PathVector(1.0, 0.0, 1.0),
      PathVector(1.0, 0.0, -1.0),
      PathVector(-1.0, 0.0, 1.0),
      PathVector(-1.0, 0.0, -1.0)
    )

  val VERTICAL_AND_HORIZONTAL = INeighborStrategy { VERTICAL_AND_HORIZONTAL_OFFSETS }
  val DIAGONAL_3D = INeighborStrategy { DIAGONAL_3D_OFFSETS }

  val HORIZONTAL_DIAGONAL_AND_VERTICAL = INeighborStrategy {
    HORIZONTAL_DIAGONAL_AND_VERTICAL_OFFSETS
  }

}
