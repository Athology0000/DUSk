package org.cobalt.api.pathfinder.pathing.processing.impl

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import org.cobalt.api.pathfinder.pathing.processing.Cost
import org.cobalt.api.pathfinder.pathing.processing.NodeProcessor
import org.cobalt.api.pathfinder.pathing.processing.context.EvaluationContext

class AvoidanceProcessor : NodeProcessor {

  private val mc: Minecraft = Minecraft.getInstance()

  override fun isValid(context: EvaluationContext): Boolean {
    val level = mc.level ?: return true
    val pos = context.currentPathPosition
    val blockPos = BlockPos(pos.flooredX, pos.flooredY, pos.flooredZ)
    return !AvoidanceCache.isAvoided(level, blockPos)
  }

  override fun calculateCostContribution(context: EvaluationContext): Cost = Cost.ZERO
}
