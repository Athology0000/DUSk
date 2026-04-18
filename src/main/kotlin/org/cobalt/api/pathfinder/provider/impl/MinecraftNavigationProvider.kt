package org.cobalt.api.pathfinder.provider.impl

import java.util.concurrent.ConcurrentHashMap
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction.Axis
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.*
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.pathfinder.PathComputationType
import net.minecraft.world.phys.shapes.CollisionContext
import org.cobalt.api.pathfinder.pathing.context.EnvironmentContext
import org.cobalt.api.pathfinder.provider.NavigationPoint
import org.cobalt.api.pathfinder.provider.NavigationPointProvider
import org.cobalt.api.pathfinder.wrapper.PathPosition

class MinecraftNavigationProvider : NavigationPointProvider {

  private val mc: Minecraft = Minecraft.getInstance()
  private val cache = ConcurrentHashMap<Long, NavigationPoint>(8192)

  companion object {
    private const val MAX_CACHE_SIZE = 50000
  }

  override fun getNavigationPoint(
    position: PathPosition,
    environmentContext: EnvironmentContext?,
  ): NavigationPoint {
    val level =
      mc.level
        ?: return object : NavigationPoint {
          override fun isTraversable() = false
          override fun hasFloor() = false
          override fun getFloorLevel() = 0.0
          override fun isClimbable() = false
          override fun isLiquid() = false
        }

    val x = position.flooredX
    val y = position.flooredY
    val z = position.flooredZ
    val blockPos = BlockPos(x, y, z)
    val key = blockPos.asLong()
    cache[key]?.let { return it }

    val feetState = level.getBlockState(blockPos)
    val headState = level.getBlockState(blockPos.above())
    val belowState = level.getBlockState(blockPos.below())

    val canPassFeetVal = canWalkThrough(level, feetState, blockPos)
    val canPassHeadVal = canWalkThrough(level, headState, blockPos.above())
    val hasStableFloorVal = canWalkOn(level, belowState, blockPos.below())
    val floorLevelVal = calculateFloorLevel(level, blockPos)
    val isClimbingVal = feetState.block is LadderBlock || feetState.block is VineBlock
    val isLiquidVal = !feetState.fluidState.isEmpty

    val point = object : NavigationPoint {
      override fun isTraversable(): Boolean = canPassFeetVal && canPassHeadVal
      override fun hasFloor(): Boolean = hasStableFloorVal
      override fun getFloorLevel(): Double = floorLevelVal
      override fun isClimbable(): Boolean = isClimbingVal
      override fun isLiquid(): Boolean = isLiquidVal
    }
    if (cache.size > MAX_CACHE_SIZE) {
      cache.clear()
    }
    cache[key] = point
    return point
  }

  private fun canWalkThrough(level: Level, state: BlockState, pos: BlockPos): Boolean {
    if (state.isAir) return true
    if (!state.fluidState.isEmpty) return false

    if (state.`is`(BlockTags.TRAPDOORS) ||
      state.`is`(Blocks.LILY_PAD) ||
      state.`is`(Blocks.BIG_DRIPLEAF)
    ) {
      return true
    }

    if (state.`is`(Blocks.POWDER_SNOW) ||
      state.`is`(Blocks.CACTUS) ||
      state.`is`(Blocks.SWEET_BERRY_BUSH) ||
      state.`is`(Blocks.HONEY_BLOCK) ||
      state.`is`(Blocks.COCOA) ||
      state.`is`(Blocks.WITHER_ROSE) ||
      state.`is`(Blocks.POINTED_DRIPSTONE)
    ) {
      return true
    }

    val block = state.block
    if (block is DoorBlock) {
      return if (state.getValue(DoorBlock.OPEN)) true else block.type().canOpenByHand()
    }

    if (block is FenceGateBlock) {
      return state.getValue(FenceGateBlock.OPEN)
    }

    if (block is BaseRailBlock) {
      return true
    }

    if (state.`is`(BlockTags.FENCES) || state.`is`(BlockTags.WALLS)) {
      return false
    }

    return state.isPathfindable(PathComputationType.LAND) || state.fluidState.`is`(FluidTags.WATER)
  }

  private fun canWalkOn(level: Level, state: BlockState, pos: BlockPos): Boolean {
    val block = state.block
    if (state.isCollisionShapeFullBlock(level, pos) &&
      block != Blocks.MAGMA_BLOCK &&
      block != Blocks.BUBBLE_COLUMN &&
      block != Blocks.HONEY_BLOCK
    ) {
      return true
    }

    return block is AzaleaBlock ||
      block is LadderBlock ||
      block is VineBlock ||
      block == Blocks.FARMLAND ||
      block == Blocks.DIRT_PATH ||
      block == Blocks.SOUL_SAND ||
      block == Blocks.CHEST ||
      block == Blocks.ENDER_CHEST ||
      block == Blocks.GLASS ||
      block is StairBlock ||
      block is SlabBlock ||
      block is BaseRailBlock
  }

  private fun calculateFloorLevel(level: Level, pos: BlockPos): Double {
    val state = level.getFluidState(pos)
    if (state.`is`(FluidTags.WATER)) {
      return pos.y.toDouble() + 0.5
    }

    val belowPos = pos.below()
    val belowState = level.getBlockState(belowPos)
    val shape = belowState.getCollisionShape(level, belowPos, CollisionContext.empty())
    return if (shape.isEmpty) {
      belowPos.y.toDouble()
    } else {
      belowPos.y.toDouble() + shape.max(Axis.Y)
    }
  }

}
