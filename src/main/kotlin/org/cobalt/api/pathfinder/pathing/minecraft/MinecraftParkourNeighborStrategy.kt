package org.cobalt.api.pathfinder.pathing.minecraft

import org.cobalt.api.pathfinder.minecraft.MinecraftPathingRules
import org.cobalt.api.pathfinder.pathing.INeighborStrategy
import org.cobalt.api.pathfinder.wrapper.PathVector

object MinecraftParkourNeighborStrategy : INeighborStrategy {

  private val OFFSETS: List<PathVector> = buildOffsets()

  override fun getOffsets(): Iterable<PathVector> = OFFSETS

  private fun buildOffsets(): List<PathVector> {
    val offsets = ArrayList<PathVector>(128)
    val dirs = listOf(
      1 to 0,
      -1 to 0,
      0 to 1,
      0 to -1
    )
    val maxStepUp = MinecraftPathingRules.MAX_STEP_UP
    val maxStepDown = MinecraftPathingRules.MAX_STEP_DOWN
    val maxClimb = MinecraftPathingRules.MAX_CLIMB_SCAN
    val maxJump = MinecraftPathingRules.MAX_JUMP_LENGTH

    // Cardinal moves and vertical adjustments.
    for ((dx, dz) in dirs) {
      offsets.add(PathVector(dx.toDouble(), 0.0, dz.toDouble()))
      for (dy in 1..maxClimb) {
        offsets.add(PathVector(dx.toDouble(), dy.toDouble(), dz.toDouble()))
      }
      for (dy in 1..maxStepDown) {
        offsets.add(PathVector(dx.toDouble(), -dy.toDouble(), dz.toDouble()))
      }
    }

    // Diagonals with vertical adjustments.
    val diagonals = listOf(
      1 to 1,
      1 to -1,
      -1 to 1,
      -1 to -1
    )
    for ((dx, dz) in diagonals) {
      for (dy in -maxStepDown..maxStepUp) {
        offsets.add(PathVector(dx.toDouble(), dy.toDouble(), dz.toDouble()))
      }
    }

    // Pure vertical climb.
    offsets.add(PathVector(0.0, 1.0, 0.0))
    offsets.add(PathVector(0.0, -1.0, 0.0))

    // Parkour jumps along axes.
    for ((dx, dz) in dirs) {
      for (len in 2..maxJump) {
        val scaleX = dx * len
        val scaleZ = dz * len
        for (dy in -maxStepDown..maxStepUp) {
          offsets.add(PathVector(scaleX.toDouble(), dy.toDouble(), scaleZ.toDouble()))
        }
      }
    }

    return offsets
  }
}
