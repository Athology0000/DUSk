package org.cobalt.api.pathfinder.pathing.processing.impl

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import org.cobalt.api.pathfinder.pathing.processing.Cost
import org.cobalt.api.pathfinder.pathing.processing.NodeProcessor
import org.cobalt.api.pathfinder.pathing.processing.context.EvaluationContext

/*
 * most logic in this file is derived from minecraft code
 * or writeups on pathfinding algorithms, if you want to help contribute
 * id prefer for you to keep it the same idea or whatever, but if not
 * please write a comment explaining WHY you did it that way. i dont like
 * magic numbers that i cant understand.
 */
class MinecraftPathProcessor : NodeProcessor {

  private val mc: Minecraft = Minecraft.getInstance()

  companion object {
    private const val DEFAULT_MOB_JUMP_HEIGHT = 1.125 // WalkNodeEvaluator
    private const val LOW_CEILING_PENALTY = 0.08
    private const val ADJACENT_WALL_PENALTY = 0.14
    private const val ADJACENT_CORNER_PENALTY = 0.1
    private const val SECOND_RING_WALL_PENALTY = 0.045
    private const val SECOND_RING_CORNER_PENALTY = 0.028
  }

  override fun isValid(context: EvaluationContext): Boolean {
    val provider = context.navigationPointProvider
    val pos = context.currentPathPosition
    val prev = context.previousPathPosition
    val env = context.environmentContext

    val currentPoint = provider.getNavigationPoint(pos, env)

    if (!currentPoint.isTraversable()) return false
    if (prev == null) return true

    val prevPoint = provider.getNavigationPoint(prev, env)
    val dy = pos.y - prev.y
    val dx = pos.flooredX - prev.flooredX
    val dz = pos.flooredZ - prev.flooredZ

    if (dy > DEFAULT_MOB_JUMP_HEIGHT) return false

    if (Math.abs(dx) == 1 && Math.abs(dz) == 1) {
      val corner1Pos = prev.add(dx.toDouble(), 0.0, 0.0)
      val corner2Pos = prev.add(0.0, 0.0, dz.toDouble())
      val c1Point = provider.getNavigationPoint(corner1Pos, env)
      val c2Point = provider.getNavigationPoint(corner2Pos, env)

      // node3.y <= node.y && node2.y <= node.y
      if (!c1Point.isTraversable() || !c2Point.isTraversable()) return false
    }

    return when {
      dy < -0.5 -> true // falling
      dy > 0.5 ->
        prevPoint.hasFloor() ||
          currentPoint.isClimbable() // jumping/climbing
      else ->
        currentPoint.hasFloor() ||
          prevPoint.hasFloor() ||
          currentPoint.isClimbable() ||
          prevPoint.isClimbable()
    }
  }

  override fun calculateCostContribution(context: EvaluationContext): Cost {
    val level = mc.level ?: return Cost.ZERO
    val currentPos = context.currentPathPosition
    val prevPos = context.previousPathPosition ?: return Cost.ZERO
    val provider = context.navigationPointProvider
    val env = context.environmentContext

    val currentPoint = provider.getNavigationPoint(currentPos, env)
    val prevPoint = provider.getNavigationPoint(prevPos, env)

    val dy = currentPoint.getFloorLevel() - prevPoint.getFloorLevel()
    var additionalCost = 0.0

    if (dy > 0.1) {
      additionalCost += 0.5 * dy
    } else if (dy < -0.1) {
      additionalCost += 0.1 * Math.abs(dy)
    }

    val blockPos = BlockPos(currentPos.flooredX, currentPos.flooredY, currentPos.flooredZ)
    additionalCost += calculateClearancePenalty(level, blockPos)

    // just make stuff smoother no more zigzags
    val gpPos = context.grandparentPathPosition
    if (gpPos != null) {
      val v1x = prevPos.x - gpPos.x
      val v1z = prevPos.z - gpPos.z
      val v2x = currentPos.x - prevPos.x
      val v2z = currentPos.z - prevPos.z
      val dot = v1x * v2x + v1z * v2z
      val mag1 = sqrt(v1x * v1x + v1z * v1z)
      val mag2 = sqrt(v2x * v2x + v2z * v2z)
      if (mag1 > 0.1 && mag2 > 0.1) {
        val normalizedDot = dot / (mag1 * mag2)
        if (normalizedDot < 0.99) additionalCost += 0.05
      }
    }

    return Cost.of(additionalCost)
  }

  // Prefer tiles with horizontal body clearance so the shared walker stops hugging walls.
  private fun calculateClearancePenalty(level: Level, blockPos: BlockPos): Double {
    var penalty = 0.0

    for (headOffset in 0..1) {
      for (dx in -2..2) {
        for (dz in -2..2) {
          if (dx == 0 && dz == 0) continue

          val samplePos = blockPos.offset(dx, headOffset, dz)
          if (!hasBlockingCollision(level, samplePos)) continue

          val absX = abs(dx)
          val absZ = abs(dz)
          val chebyshev = max(absX, absZ)
          val axial = absX == 0 || absZ == 0
          val basePenalty =
            when {
              chebyshev <= 1 && axial -> ADJACENT_WALL_PENALTY
              chebyshev <= 1 -> ADJACENT_CORNER_PENALTY
              axial -> SECOND_RING_WALL_PENALTY
              else -> SECOND_RING_CORNER_PENALTY
            }

          penalty += if (headOffset == 0) basePenalty else basePenalty * 0.85
        }
      }
    }

    for (i in 2..3) {
      if (hasBlockingCollision(level, blockPos.above(i))) {
        penalty += LOW_CEILING_PENALTY / (i - 1).toDouble()
      }
    }

    return penalty
  }

  private fun hasBlockingCollision(level: Level, pos: BlockPos): Boolean {
    return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty
  }

}
