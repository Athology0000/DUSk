package org.cobalt.api.pathfinder.jni

import net.minecraft.core.BlockPos
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.FenceBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.WallBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import kotlin.math.floor
import kotlin.math.roundToInt

internal object PathHazards {
    private val collisionContext = CollisionContext.empty()

    fun walkPosForNode(node: Vec3): BlockPos =
        BlockPos(floor(node.x).toInt(), node.y.roundToInt(), floor(node.z).toInt())

    fun isHarmfulStandPosition(level: Level, feetPos: BlockPos): Boolean {
        val feet = level.getBlockState(feetPos)
        val head = level.getBlockState(feetPos.above())
        val support = level.getBlockState(feetPos.below())

        if (isHarmfulFluid(feet) || isHarmfulFluid(head) || isHarmfulFluid(support)) return true
        if (isHarmfulBlock(feet.block) || isHarmfulBlock(head.block)) return true
        return isHarmfulSurface(support.block)
    }

    fun isReasonableLandingPosition(level: Level, feetPos: BlockPos): Boolean {
        if (isHarmfulStandPosition(level, feetPos)) return false

        val feet = level.getBlockState(feetPos)
        val head = level.getBlockState(feetPos.above())
        val supportPos = feetPos.below()
        val support = level.getBlockState(supportPos)

        if (!feet.getCollisionShape(level, feetPos, collisionContext).isEmpty) return false
        if (!head.getCollisionShape(level, feetPos.above(), collisionContext).isEmpty) return false
        return !support.getCollisionShape(level, supportPos, collisionContext).isEmpty
    }

    fun teleportFeetPos(level: Level, supportPos: BlockPos): BlockPos {
        val supportBlock = level.getBlockState(supportPos).block
        val standOffset = if (supportBlock is FenceBlock || supportBlock is FenceGateBlock || supportBlock is WallBlock) 2 else 1
        return supportPos.above(standOffset)
    }

    fun isSafeTeleportSupport(level: Level, supportPos: BlockPos): Boolean {
        val support = level.getBlockState(supportPos)
        val feetPos = teleportFeetPos(level, supportPos)
        val feet = level.getBlockState(feetPos)
        val headPos = feetPos.above()
        val head = level.getBlockState(headPos)

        if (isHarmfulFluid(support) || isHarmfulSurface(support.block)) return false
        if (isHarmfulFluid(feet) || isHarmfulFluid(head)) return false
        if (isHarmfulBlock(feet.block) || isHarmfulBlock(head.block)) return false
        if (support.getCollisionShape(level, supportPos, collisionContext).isEmpty) return false
        if (!feet.getCollisionShape(level, feetPos, collisionContext).isEmpty) return false
        return head.getCollisionShape(level, headPos, collisionContext).isEmpty
    }

    private fun isHarmfulFluid(state: BlockState): Boolean =
        state.fluidState.`is`(FluidTags.LAVA)

    private fun isHarmfulSurface(block: Block): Boolean =
        block == Blocks.MAGMA_BLOCK ||
            block == Blocks.CAMPFIRE ||
            block == Blocks.SOUL_CAMPFIRE ||
            block == Blocks.CACTUS ||
            block == Blocks.SWEET_BERRY_BUSH ||
            block == Blocks.WITHER_ROSE ||
            block == Blocks.POWDER_SNOW

    private fun isHarmfulBlock(block: Block): Boolean =
        isHarmfulSurface(block) ||
            block == Blocks.LAVA ||
            block == Blocks.FIRE ||
            block == Blocks.SOUL_FIRE ||
            block.descriptionId.contains("lava") ||
            block.descriptionId.contains("fire") ||
            block.descriptionId.contains("cactus") ||
            block.descriptionId.contains("magma") ||
            block.descriptionId.contains("berry_bush") ||
            block.descriptionId.contains("wither_rose") ||
            block.descriptionId.contains("powder_snow")
}
