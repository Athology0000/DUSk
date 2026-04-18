package org.cobalt.internal.grotto

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block

object BlockScanUtils {

  @JvmStatic
  fun scanAround(level: Level?, base: BlockPos?, radius: Int): Map<String, Int> {
    val result = HashMap<String, Int>()
    if (level == null || base == null || radius <= 0) return result

    val bx = base.x
    val by = base.y
    val bz = base.z

    for (dx in -radius..radius) {
      for (dy in -radius..radius) {
        for (dz in -radius..radius) {
          val pos = BlockPos(bx + dx, by + dy, bz + dz)
          val state = level.getBlockState(pos)
          val block = state.block
          val id = BuiltInRegistries.BLOCK.getKey(block).toString()
          val stateId = Block.getId(state)
          val key = "$id:$stateId"
          result[key] = (result[key] ?: 0) + 1
        }
      }
    }

    return result
  }

}
