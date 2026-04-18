package org.cobalt.internal.grotto

import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult

object LookedAtBlockUtils {

  data class LookedAtBlockInfo(
    val name: String,
    val id: Int,
    val meta: Int,
    val x: Int,
    val y: Int,
    val z: Int,
  )

  @JvmStatic
  fun getLookedAtBlockInfo(): LookedAtBlockInfo? {
    val mc = Minecraft.getInstance()
    val level = mc.level ?: return null
    val hit = mc.hitResult ?: return null

    if (hit.type != HitResult.Type.BLOCK) return null
    val blockHit = hit as BlockHitResult
    val pos = blockHit.blockPos
    val state = level.getBlockState(pos)
    val block = state.block

    val name = BuiltInRegistries.BLOCK.getKey(block).toString()
    val id = BuiltInRegistries.BLOCK.getId(block)
    val meta = Block.getId(state)

    return LookedAtBlockInfo(name, id, meta, pos.x, pos.y, pos.z)
  }

}
