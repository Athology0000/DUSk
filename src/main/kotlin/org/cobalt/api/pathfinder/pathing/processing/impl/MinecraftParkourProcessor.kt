package org.cobalt.api.pathfinder.pathing.processing.impl

import kotlin.math.abs
import kotlin.math.max
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import org.cobalt.api.pathfinder.minecraft.MinecraftPathingRules
import org.cobalt.api.pathfinder.pathing.processing.Cost
import org.cobalt.api.pathfinder.pathing.processing.NodeProcessor
import org.cobalt.api.pathfinder.pathing.processing.context.EvaluationContext

class MinecraftParkourProcessor : NodeProcessor {

  private val mc: Minecraft = Minecraft.getInstance()

  override fun isValid(context: EvaluationContext): Boolean {
    val level = mc.level ?: return false
    val prev = context.previousPathPosition ?: return true
    val current = context.currentPathPosition

    val prevPos = BlockPos(prev.flooredX, prev.flooredY, prev.flooredZ)
    val currPos = BlockPos(current.flooredX, current.flooredY, current.flooredZ)

    if (!MinecraftPathingRules.isWalkable(level, currPos)) {
      return false
    }

    val dx = currPos.x - prevPos.x
    val dy = currPos.y - prevPos.y
    val dz = currPos.z - prevPos.z
    val absDx = abs(dx)
    val absDz = abs(dz)

    if (absDx == 0 && absDz == 0) {
      if (dy == 0) return true
      return MinecraftPathingRules.isClimbable(level, prevPos) ||
        MinecraftPathingRules.isClimbable(level, currPos)
    }

    if (absDx <= 1 && absDz <= 1) {
      if (dy > MinecraftPathingRules.MAX_STEP_UP || dy < -MinecraftPathingRules.MAX_STEP_DOWN) {
        return false
      }
      if (absDx == 1 && absDz == 1) {
        if (!MinecraftPathingRules.canMoveDiagonal(level, prevPos, dx, dz)) {
          return false
        }
      }
      return true
    }

    val isAxisJump = (absDx >= 2 && absDz == 0) || (absDz >= 2 && absDx == 0)
    if (!isAxisJump) {
      return false
    }

    val len = max(absDx, absDz)
    if (len > MinecraftPathingRules.MAX_JUMP_LENGTH) {
      return false
    }

    if (dy > MinecraftPathingRules.MAX_STEP_UP || dy < -MinecraftPathingRules.MAX_STEP_DOWN) {
      return false
    }

    if (!MinecraftPathingRules.isStandable(level, prevPos.below())) {
      return false
    }

    val dir =
      if (absDx > absDz) {
        if (dx > 0) Direction.EAST else Direction.WEST
      } else {
        if (dz > 0) Direction.SOUTH else Direction.NORTH
      }

    val landingBase = prevPos.relative(dir, len)
    val resolved = MinecraftPathingRules.walkableAt(level, landingBase) ?: return false
    if (resolved != currPos) {
      return false
    }

    if (!MinecraftPathingRules.gapClear(level, prevPos, dir, len)) {
      return false
    }

    if (!MinecraftPathingRules.hasRunway(level, prevPos, dir)) {
      return false
    }

    return true
  }

  override fun calculateCostContribution(context: EvaluationContext): Cost = Cost.ZERO
}
