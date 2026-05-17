package org.phantom.api.pathfinder.jni.executor

import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Half
import net.minecraft.world.level.block.state.properties.SlabType
import net.minecraft.world.phys.Vec3
import org.phantom.api.pathfinder.jni.NativePathfinder
import org.phantom.api.pathfinder.jni.PathExecutorState
import org.phantom.api.pathfinder.jni.PathHazards
import kotlin.math.abs

/**
 * Phantom-equivalent jump-detection ladder. Stateless other than a per-tick block
 * state cache (cleared at the start of every detectJump call) and the global
 * PathExecutorState.jumpSuppressTicks counter.
 *
 * Check order matches Phantom PathJumps.detectJump():
 *   suppress ticks â†’ FLAG_LOW_HEADROOM â†’ fluid â†’ not-on-ground guard â†’
 *   low ceiling â†’ FLAG_STEP_UP_NEXT + preemptive climb â†’ upcoming step â†’
 *   hazard â†’ gap â†’ snow â†’ obstacle.
 */
internal object JumpDetector {

    /**
     * Minimum path-rise (next.y - prev.y) before geometry-fallback jumps trigger.
     * Raising this above 0.9 prevents spurious jumps at slabs / half-blocks /
     * small inclines where the rounded riseFromPlayer alone would say "1 block".
     * Settable from PathfindingModule.
     */
    @JvmField var jumpRiseMin: Double = 1.12

    /**
     * Multiplier on the squared trigger distances for hill / preemptive /
     * obstacle / gap / hazard jumps. 1.0 = original behavior. < 1.0 = jumps
     * only fire when the player is closer to the obstacle (more conservative,
     * fewer "random" early jumps).
     */
    @JvmField var jumpRangeMultiplier: Double = 0.55

    fun detectJump(player: LocalPlayer): Boolean {
        val state = PathExecutorState
        blockCache.clear()

        if (state.jumpSuppressTicks > 0) return false

        val mc = Minecraft.getInstance()
        val level = mc.level ?: return false
        val nodes = NativePathfinder.cachedPathNodes
        if (nodes.isEmpty()) return false

        val cursor = NativePathfinder.pathNodeCursor
        val nearIdx = cursor.coerceIn(0, nodes.lastIndex)

        // 1. FLAG_LOW_HEADROOM
        val nextFlags = NativePathfinder.cachedKeyNodeFlags.getOrElse(
            minOf(nearIdx + 1, nodes.lastIndex)
        ) { 0 }
        if (nextFlags and FLAG_LOW_HEADROOM != 0) {
            state.jumpSuppressTicks = 4
            return false
        }

        // 2. Fluid
        if (player.isInWater || player.isInLava) return true

        // 3. Not on ground
        if (!player.onGround()) return false

        // 4. Low ceiling
        val fx = player.blockX; val fy = player.blockY; val fz = player.blockZ
        if (hasLowCeiling(level, fx, fy, fz)) {
            state.jumpSuppressTicks = 4
            return false
        }

        val playerFloorY = Math.floor(player.y - 0.001).toInt()

        // 5. Native flag fast path + geometry fallback
        if (nextFlags and FLAG_STEP_UP_NEXT != 0) {
            if (checkPreemptiveClimbJump(player, nodes, nearIdx, playerFloorY)) return true
        }
        if (checkUpcomingStepJump(level, player, nodes, nearIdx, playerFloorY)) return true

        // 6. Hazard
        if (checkHazardJump(level, player, nodes, nearIdx)) return true

        // 7. Gap
        if (checkGapJump(player, nodes, nearIdx, playerFloorY)) return true

        // 8. Snow
        if (checkSnowJump(level, nodes, nearIdx, playerFloorY)) return true

        // 9. Obstacle
        if (checkObstacleJump(level, player, nodes, nearIdx, playerFloorY)) return true

        return false
    }

    /** Phantom hasLowCeiling: checks feet+2 and feet+3 for non-stair solid collision. */
    private fun hasLowCeiling(level: Level, x: Int, y: Int, z: Int): Boolean {
        for (offset in 2..3) {
            val pos = BlockPos(x, y + offset, z)
            val bs = cachedBlock(level, pos)
            if (bs.block is StairBlock && bs.getValue(StairBlock.HALF) == Half.BOTTOM) continue
            if (!bs.getCollisionShape(level, pos).isEmpty) return true
        }
        return false
    }

    /**
     * Phantom checkPreemptiveClimbJump: next node strictly higher than current,
     * player within PREEMPTIVE_JUMP_DISTANCE horizontally, rise 1..2 blocks.
     */
    private fun checkPreemptiveClimbJump(
        player: LocalPlayer,
        nodes: List<Vec3>,
        nearIdx: Int,
        playerFloorY: Int,
    ): Boolean {
        val level = Minecraft.getInstance().level ?: return false
        if (nearIdx + 1 > nodes.lastIndex) return false
        val curr = nodes[nearIdx]; val next = nodes[nearIdx + 1]
        if (next.y - curr.y < effectiveJumpRiseMin()) return false
        val rise = Math.round(next.y).toInt() - playerFloorY
        if (rise !in 1..2) return false
        if (isWalkablePartialStairStep(level, player, next, playerFloorY)) return false
        val tdx = next.x + 0.5 - player.x; val tdz = next.z + 0.5 - player.z
        if (tdx * tdx + tdz * tdz > PREEMPTIVE_JUMP_DIST_SQ * effectiveJumpRangeMultiplier()) return false
        return jumpTrue(JUMP_SUPPRESS_TICKS)
    }

    private fun checkUpcomingStepJump(
        level: Level,
        player: LocalPlayer,
        nodes: List<Vec3>,
        nearIdx: Int,
        playerFloorY: Int,
    ): Boolean {
        val end = minOf(nodes.lastIndex, nearIdx + STEP_LOOKAHEAD_NODES)

        for (i in (nearIdx + 1)..end) {
            val previous = nodes[(i - 1).coerceAtLeast(nearIdx)]
            val next = nodes[i]
            val pathRise = next.y - previous.y
            val riseFromPlayer = Math.round(next.y).toInt() - playerFloorY
            if (pathRise < effectiveJumpRiseMin()) continue
            if (riseFromPlayer !in 1..2) continue
            if (isWalkablePartialStairStep(level, player, next, playerFloorY)) continue

            val nextFeet = BlockPos(Math.floor(next.x).toInt(), Math.round(next.y).toInt(), Math.floor(next.z).toInt())
            val supportPos = nextFeet.below()
            val landingState = cachedBlock(level, supportPos)
            val landingName = landingState.block.descriptionId
            if (landingName.contains("slab")) continue
            if (landingState.block is StairBlock) {
                try {
                    if (landingState.getValue(StairBlock.HALF) == Half.BOTTOM) continue
                } catch (_: Exception) {
                }
            }
            if (landingState.getCollisionShape(level, supportPos).isEmpty) continue

            val feetState = cachedBlock(level, nextFeet)
            val headState = cachedBlock(level, nextFeet.above())
            if (!feetState.getCollisionShape(level, nextFeet).isEmpty) continue
            if (!headState.getCollisionShape(level, nextFeet.above()).isEmpty) continue

            val takeoff = Vec3(previous.x + 0.5, previous.y, previous.z + 0.5)
            val landing = Vec3(next.x + 0.5, next.y, next.z + 0.5)
            if (!isAheadOnSegment(player.position(), takeoff, landing)) continue

            val triggerDistSq = minOf(
                distanceToSegmentHorizontalSq(player.x, player.z, takeoff, landing),
                horizontalDistanceSq(player.x, player.z, takeoff.x, takeoff.z),
            )
            if (triggerDistSq > HILL_JUMP_TRIGGER_DIST_SQ * effectiveJumpRangeMultiplier()) continue
            return jumpTrue(JUMP_SUPPRESS_TICKS)
        }

        return false
    }

    /** Jump over one/two harmful blocks only when the landing is safe. */
    private fun checkHazardJump(
        level: Level,
        player: LocalPlayer,
        nodes: List<Vec3>,
        nearIdx: Int,
    ): Boolean {
        val lookEnd = minOf(nodes.lastIndex, nearIdx + HAZARD_LOOKAHEAD_NODES)
        for (hazardIdx in (nearIdx + 1)..lookEnd) {
            val hazardPos = PathHazards.walkPosForNode(nodes[hazardIdx])
            if (!PathHazards.isHarmfulStandPosition(level, hazardPos)) continue

            val landingIdx = findHazardLanding(level, nodes, hazardIdx) ?: return false
            val takeoffNode = nodes[(hazardIdx - 1).coerceAtLeast(nearIdx)]
            val landingNode = nodes[landingIdx]
            val spanDx = landingNode.x - takeoffNode.x
            val spanDz = landingNode.z - takeoffNode.z
            if (spanDx * spanDx + spanDz * spanDz > HAZARD_JUMP_MAX_DIST_SQ) return false

            val takeoff = Vec3(takeoffNode.x + 0.5, takeoffNode.y, takeoffNode.z + 0.5)
            val landing = Vec3(landingNode.x + 0.5, landingNode.y, landingNode.z + 0.5)
            if (!isAheadOnSegment(player.position(), takeoff, landing)) return false
            if (distanceToSegmentHorizontalSq(player.x, player.z, takeoff, landing) > HAZARD_JUMP_TRIGGER_DIST_SQ * effectiveJumpRangeMultiplier()) return false

            return jumpTrue(HAZARD_JUMP_SUPPRESS_TICKS)
        }
        return false
    }

    private fun findHazardLanding(level: Level, nodes: List<Vec3>, hazardIdx: Int): Int? {
        val end = minOf(nodes.lastIndex, hazardIdx + HAZARD_MAX_JUMP_NODES)
        for (i in (hazardIdx + 1)..end) {
            val landingPos = PathHazards.walkPosForNode(nodes[i])
            if (PathHazards.isReasonableLandingPosition(level, landingPos)) return i
        }
        return null
    }

    /**
     * Phantom checkGapJump: scans for a path that drops below baseY then recovers
     * â‰¥ baseY within 7 nodes, horizontal gap â‰¤ sqrt(20), triggers when player
     * is within 1.75 blocks of the last node before the drop.
     */
    private fun checkGapJump(
        player: LocalPlayer,
        nodes: List<Vec3>,
        nearIdx: Int,
        @Suppress("UNUSED_PARAMETER") playerFloorY: Int,
    ): Boolean {
        val baseY = Math.round(nodes[nearIdx].y).toInt()
        if (abs(player.y - (baseY + 1)) > 1.8) return false
        val gapEnd = minOf(nodes.lastIndex, nearIdx + 7)
        var dropIdx = -1; var recoveryIdx = -1
        for (i in (nearIdx + 1)..gapEnd) {
            val ny = Math.round(nodes[i].y).toInt()
            when {
                ny < baseY && dropIdx == -1 -> dropIdx = i
                dropIdx != -1 && ny >= baseY -> { recoveryIdx = i; break }
                ny > baseY && dropIdx == -1 -> return false
            }
        }
        if (dropIdx == -1 || recoveryIdx == -1) return false
        val edgeNode = nodes[dropIdx - 1]; val recovNode = nodes[recoveryIdx]
        val gdx = recovNode.x - edgeNode.x; val gdz = recovNode.z - edgeNode.z
        val gapDistSq = gdx * gdx + gdz * gdz
        if (gapDistSq <= 0.0 || gapDistSq > 20.0) return false
        val edge = Vec3(edgeNode.x + 0.5, edgeNode.y, edgeNode.z + 0.5)
        val landing = Vec3(recovNode.x + 0.5, recovNode.y, recovNode.z + 0.5)
        if (!isAheadOnSegment(player.position(), edge, landing)) return false
        if (distanceToSegmentHorizontalSq(player.x, player.z, edge, landing) > GAP_TRIGGER_DIST_SQ * effectiveJumpRangeMultiplier()) return false
        return jumpTrue(GAP_JUMP_SUPPRESS_TICKS)
    }

    /** Phantom checkSnowJump: upcoming node is snow â‰¥ 7 layers, height diff > 0.75. */
    private fun checkSnowJump(
        level: Level,
        nodes: List<Vec3>,
        nearIdx: Int,
        playerFloorY: Int,
    ): Boolean {
        val nextIdx = (nearIdx + 1).coerceAtMost(nodes.lastIndex)
        val n = nodes[nextIdx]

        val floorPos = BlockPos(
            Math.floor(n.x).toInt(),
            Math.round(n.y).toInt(),
            Math.floor(n.z).toInt(),
        )

        val bs = cachedBlock(level, floorPos)
        if (!bs.block.descriptionId.contains("snow")) return false

        val shape = bs.getCollisionShape(level, floorPos)
        if (shape.isEmpty) return false
        val topY = shape.bounds().maxY
        val heightAboveFloor = (Math.round(n.y).toInt() + topY) - (playerFloorY + 1)
        if (topY < 0.875 || heightAboveFloor <= 0.75) return false
        return jumpTrue(JUMP_SUPPRESS_TICKS)
    }

    /**
     * Phantom checkObstacleJump: lookahead blocks higher than STEP_HEIGHT that
     * cannot be walked up as bottom-half stairs or slabs â†’ jump.
     */
    private fun checkObstacleJump(
        level: Level,
        player: LocalPlayer,
        nodes: List<Vec3>,
        nearIdx: Int,
        playerFloorY: Int,
    ): Boolean {
        val standingPos = BlockPos(
            Math.floor(player.x).toInt(),
            playerFloorY,
            Math.floor(player.z).toInt(),
        )

        val standingBlock = cachedBlock(level, standingPos).block
        val standingOnPartial = standingBlock is StairBlock || standingBlock is SlabBlock
        val stepLimit = if (standingOnPartial) 1.05 else STEP_HEIGHT

        val lookEnd = minOf(nodes.lastIndex, nearIdx + LOOKAHEAD_NODES)
        for (i in (nearIdx + 1)..lookEnd) {
            val n = nodes[i]
            val floorY = Math.round(n.y).toInt()
            val floorPos = BlockPos(
                Math.floor(n.x).toInt(),
                floorY,
                Math.floor(n.z).toInt(),
            )

            val bs = cachedBlock(level, floorPos)
            val block = bs.block
            val name = block.descriptionId

            val dx = n.x + 0.5 - player.x
            val dz = n.z + 0.5 - player.z
            if (dx * dx + dz * dz > OBSTACLE_TRIGGER_DIST_SQ * effectiveJumpRangeMultiplier()) continue
            if (name.contains("snow")) continue
            if (bs.getCollisionShape(level, floorPos).isEmpty) continue
            if (floorY - playerFloorY <= stepLimit) continue

            when {
                name.contains("slab") -> return false
                name.contains("stair") -> {
                    if (block is StairBlock) {
                        try {
                            if (bs.getValue(StairBlock.HALF) != Half.BOTTOM) continue
                            val facing = bs.getValue(StairBlock.FACING)
                            val approach = if (abs(dx) > abs(dz)) {
                                if (dx > 0) Direction.WEST else Direction.EAST
                            } else {
                                if (dz > 0) Direction.NORTH else Direction.SOUTH
                            }
                            if (approach == facing.opposite) return false
                        } catch (_: Exception) {
                        }
                    } else {
                        return false
                    }
                }
            }

            return jumpTrue(OBSTACLE_JUMP_SUPPRESS_TICKS)
        }
        return false
    }

    private fun jumpTrue(suppressTicks: Int): Boolean {
        // Enforce a global minimum gap between jumps so brief detector resets
        // (one trigger clearing and another firing the same tick / next tick)
        // can't cause double-jumps.
        PathExecutorState.jumpSuppressTicks = maxOf(suppressTicks, PathExecutorState.jumpCooldownFloor, 8)
        return true
    }

    private fun effectiveJumpRiseMin(): Double = jumpRiseMin.coerceAtLeast(0.95)

    private fun effectiveJumpRangeMultiplier(): Double = jumpRangeMultiplier.coerceAtMost(0.55)

    private fun isAheadOnSegment(playerPos: Vec3, start: Vec3, end: Vec3): Boolean {
        val sx = end.x - start.x
        val sz = end.z - start.z
        val lenSq = sx * sx + sz * sz
        if (lenSq <= 1e-6) return true
        val px = playerPos.x - start.x
        val pz = playerPos.z - start.z
        val progress = (px * sx + pz * sz) / lenSq
        return progress >= -0.45 && progress <= 1.15
    }

    private fun distanceToSegmentHorizontalSq(x: Double, z: Double, start: Vec3, end: Vec3): Double {
        val sx = end.x - start.x
        val sz = end.z - start.z
        val lenSq = sx * sx + sz * sz
        if (lenSq <= 1e-6) {
            val dx = x - start.x
            val dz = z - start.z
            return dx * dx + dz * dz
        }
        val t = (((x - start.x) * sx + (z - start.z) * sz) / lenSq).coerceIn(0.0, 1.0)
        val px = start.x + sx * t
        val pz = start.z + sz * t
        val dx = x - px
        val dz = z - pz
        return dx * dx + dz * dz
    }

    private fun horizontalDistanceSq(x1: Double, z1: Double, x2: Double, z2: Double): Double {
        val dx = x1 - x2
        val dz = z1 - z2
        return dx * dx + dz * dz
    }

    private fun isWalkablePartialStairStep(
        level: Level,
        player: LocalPlayer,
        next: Vec3,
        playerFloorY: Int,
    ): Boolean {
        val standingPos = BlockPos(Math.floor(player.x).toInt(), playerFloorY, Math.floor(player.z).toInt())
        val nextFeet = BlockPos(Math.floor(next.x).toInt(), Math.round(next.y).toInt(), Math.floor(next.z).toInt())
        val landingSupport = nextFeet.below()

        val standingState = cachedBlock(level, standingPos)
        val landingState = cachedBlock(level, landingSupport)
        val standingTop = collisionTopY(level, standingPos, standingState) ?: return false
        val landingTop = collisionTopY(level, landingSupport, landingState) ?: return false
        val heightDelta = landingTop - standingTop
        if (heightDelta <= 0.0 || heightDelta > PARTIAL_STAIR_STEP_HEIGHT) return false

        val standingPartial = isWalkablePartialStepBlock(standingState)
        val landingPartial = isWalkablePartialStepBlock(landingState)
        return standingPartial || landingPartial
    }

    private fun collisionTopY(level: Level, pos: BlockPos, state: BlockState): Double? {
        val shape = state.getCollisionShape(level, pos)
        if (shape.isEmpty) return null
        return pos.y + shape.bounds().maxY
    }

    private fun isWalkablePartialStepBlock(state: BlockState): Boolean {
        val block = state.block
        if (block is StairBlock) {
            return runCatching { state.getValue(StairBlock.HALF) == Half.BOTTOM }.getOrDefault(false)
        }
        if (block is SlabBlock) {
            return runCatching { state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM }.getOrDefault(false)
        }
        return false
    }

    // â”€â”€ Per-node flag bits (mirrored from NativePathfinder) â”€â”€
    private const val FLAG_LOW_HEADROOM = 1 shl 2
    private const val FLAG_STEP_UP_NEXT = 1 shl 5

    // â”€â”€ Jump detection (Phantom values) â”€â”€
    private const val STEP_HEIGHT = 0.6
    private const val LOOKAHEAD_NODES = 5
    private const val STEP_LOOKAHEAD_NODES = 4
    private const val PARTIAL_STAIR_STEP_HEIGHT = 0.6
    private const val HILL_JUMP_TRIGGER_DISTANCE = 1.55
    private const val HILL_JUMP_TRIGGER_DIST_SQ = HILL_JUMP_TRIGGER_DISTANCE * HILL_JUMP_TRIGGER_DISTANCE
    private const val PREEMPTIVE_JUMP_DISTANCE = 2.15
    private const val PREEMPTIVE_JUMP_DIST_SQ = PREEMPTIVE_JUMP_DISTANCE * PREEMPTIVE_JUMP_DISTANCE
    private const val GAP_TRIGGER_DIST_SQ = 1.75 * 1.75
    private const val JUMP_SUPPRESS_TICKS = 3
    private const val GAP_JUMP_SUPPRESS_TICKS = 2
    private const val OBSTACLE_JUMP_SUPPRESS_TICKS = 2
    private const val HAZARD_LOOKAHEAD_NODES = 4
    private const val HAZARD_MAX_JUMP_NODES = 3
    private const val HAZARD_JUMP_TRIGGER_DIST_SQ = 1.85 * 1.85
    private const val HAZARD_JUMP_MAX_DIST_SQ = 3.25 * 3.25
    private const val HAZARD_JUMP_SUPPRESS_TICKS = 2
    private const val OBSTACLE_TRIGGER_DIST_SQ = 2.05 * 2.05

    // â”€â”€ Per-tick block state cache, cleared at the start of each detectJump() â”€â”€
    private val blockCache = HashMap<Long, BlockState>(32)

    private fun cachedBlock(level: Level, pos: BlockPos): BlockState {
        val key = pos.asLong()
        return blockCache.getOrPut(key) { level.getBlockState(pos) }
    }
}
