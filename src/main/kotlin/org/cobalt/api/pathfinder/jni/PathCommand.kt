package org.cobalt.api.pathfinder.jni

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.phys.Vec3
import org.cobalt.api.pathfinder.minecraft.MinecraftPathingRules
import org.cobalt.api.rotation.RotationExecutor
import org.cobalt.api.util.AngleUtils
import org.cobalt.api.util.helper.Rotation
import org.cobalt.api.util.player.MovementManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class PathCommand(
    val forward: Boolean,
    val back: Boolean,
    val jump: Boolean,
    val sneak: Boolean,
    val sprint: Boolean,
    val targetYaw: Float,
    val targetPitch: Float,
    val status: PathStatus,
    val activeAction: ActionType,
    val distanceToTarget: Float,
) {
    fun applyToPlayer(applyRotation: Boolean = true, movementYawOverride: Float? = null) {
        val player = Minecraft.getInstance().player
        val targetRotation = player?.let(::resolveGuidedRotation) ?: Rotation(targetYaw, targetPitch)
        val effectiveMovementYaw =
            movementYawOverride ?: if (applyRotation) player?.yRot else null
        val shouldJump = jump || shouldAssistJump(player)
        val movement = resolveMovementInputs(player, targetRotation, effectiveMovementYaw)
        MovementManager.setMovementLock(true)
        MovementManager.setForcedMovement(
            movement.forward,
            movement.backward,
            left = movement.left,
            right = movement.right,
            jump = shouldJump,
            shift = sneak,
            sprint = movement.sprint
        )
        player?.setSprinting(movement.sprint)
        if (applyRotation) {
            MovementManager.setLookLock(true)
            RotationExecutor.rotateTo(targetRotation, PathfinderRotationStrategy)
        } else if (RotationExecutor.isUsing(PathfinderRotationStrategy)) {
            MovementManager.setLookLock(false)
            RotationExecutor.stopIfUsing(PathfinderRotationStrategy)
        }
    }

    private fun resolveMovementInputs(
        player: net.minecraft.client.player.LocalPlayer?,
        targetRotation: Rotation,
        movementYawOverride: Float? = null
    ): MovementInputs {
        if (player == null || !usesGroundMovement()) {
            val shouldSprint = sprint && forward && !back
            return MovementInputs(forward, back, false, false, shouldSprint)
        }

        val guidePoint = resolveGuidePoint(player) ?: run {
            val yawRad = Math.toRadians(targetRotation.yaw.toDouble())
            Vec3(player.x - kotlin.math.sin(yawRad), player.y, player.z + kotlin.math.cos(yawRad))
        }
        val dx = guidePoint.x - player.x
        val dz = guidePoint.z - player.z
        val len = kotlin.math.sqrt(dx * dx + dz * dz)
        if (len < 0.05) {
            return MovementInputs(false, false, false, false, false)
        }

        val nx = dx / len
        val nz = dz / len
        val movementYaw = movementYawOverride ?: targetRotation.yaw
        val facingError = abs(AngleUtils.getRotationDelta(movementYaw.toFloat(), targetRotation.yaw))
        val yawRad = Math.toRadians(movementYaw.toDouble())
        val sinYaw = kotlin.math.sin(yawRad)
        val cosYaw = kotlin.math.cos(yawRad)
        val localForward = (-nx * sinYaw + nz * cosYaw).toFloat()
        val localStrafe = (nx * cosYaw + nz * sinYaw).toFloat()
        val turnInPlace = facingError >= TURN_IN_PLACE_YAW
        val cautiousTurn = facingError >= CAUTIOUS_TURN_YAW
        val forwardThreshold = if (distanceToTarget <= CLOSE_ALIGN_DISTANCE) 0.08f else 0.18f
        val strafeThreshold = if (distanceToTarget <= CLOSE_ALIGN_DISTANCE) 0.08f else 0.18f

        val shouldMoveForward =
            forward && when {
                turnInPlace -> localForward > TURN_IN_PLACE_FORWARD_THRESHOLD
                cautiousTurn -> localForward > CAUTIOUS_TURN_FORWARD_THRESHOLD
                else -> localForward > -0.05f
            }
        val shouldMoveBackward = back && localForward < -0.18f
        val shouldMoveLeft =
            !turnInPlace &&
                usesStrafeAdjustment() &&
                localStrafe < -strafeThreshold &&
                localForward > MIN_STRAFE_FORWARD
        val shouldMoveRight =
            !turnInPlace &&
                usesStrafeAdjustment() &&
                localStrafe > strafeThreshold &&
                localForward > MIN_STRAFE_FORWARD
        val shouldSprint =
            !cautiousTurn &&
                sprint &&
                shouldMoveForward &&
                !shouldMoveBackward &&
                localForward > forwardThreshold

        return MovementInputs(
            forward = shouldMoveForward,
            backward = shouldMoveBackward,
            left = shouldMoveLeft,
            right = shouldMoveRight,
            sprint = shouldSprint
        )
    }

    private fun shouldAssistJump(player: net.minecraft.client.player.LocalPlayer?): Boolean {
        if (activeAction == ActionType.JUMP || activeAction == ActionType.SPRINT_JUMP) return true
        if (player == null || !player.onGround()) return false
        if (!usesGroundMovement()) return false

        val level = Minecraft.getInstance().level ?: return false
        val nodes = NativePathfinder.cachedPathNodes
        if (nodes.size < 2) return false

        val nearestIndex = nearestNodeIndex(player, nodes)
        if (nearestIndex < 0) return false

        val traversalIndex = firstUpcomingTraversalNode(player, nodes, nearestIndex) ?: return false
        val previousNode = nodes[traversalIndex - 1]
        val traversalNode = nodes[traversalIndex]
        val rise = traversalNode.y - previousNode.y
        if (rise < AUTO_JUMP_MIN_RISE || rise > AUTO_JUMP_MAX_RISE) return false
        if (isAutoStepLanding(level, traversalNode)) return false

        val dx = traversalNode.x - player.x
        val dz = traversalNode.z - player.z
        val horizontalDistSq = dx * dx + dz * dz
        return horizontalDistSq <= AUTO_JUMP_TRIGGER_DISTANCE_SQ
    }

    private fun isAutoStepLanding(level: net.minecraft.world.level.Level, traversalNode: Vec3): Boolean {
        val landingPos = BlockPos.containing(traversalNode.x, traversalNode.y - 1.0, traversalNode.z)
        val landingState = level.getBlockState(landingPos)
        val landingBlock = landingState.block
        if (landingBlock is SlabBlock || landingBlock is StairBlock) return true

        val shape = landingState.getCollisionShape(level, landingPos)
        if (shape.isEmpty) return false
        return shape.bounds().maxY < AUTO_JUMP_SOLID_BLOCK_HEIGHT
    }

    private fun resolveGuidedRotation(player: net.minecraft.client.player.LocalPlayer): Rotation {
        if (!usesGroundMovement()) return Rotation(targetYaw, targetPitch)
        val guide = resolveAveragedGuidePoint(player) ?: return Rotation(targetYaw, targetPitch)
        return AngleUtils.getRotation(player.eyePosition, guide)
    }

    /**
     * Averages [ROTATION_LOOKAHEAD] upcoming nodes to produce a stable rotation target.
     *
     * Using a single node at +2 causes the target yaw to flip every step on zigzag diagonal
     * paths (E→N→E→N), producing visible left/right oscillation — the same issue the native
     * RotationController already fixed with its 4-node centroid approach. Nodes come from JNI
     * as integer block positions, so +0.5 is added to land on block centres.
     */
    private fun resolveAveragedGuidePoint(player: net.minecraft.client.player.LocalPlayer): Vec3? {
        val nodes = NativePathfinder.cachedPathNodes
        if (nodes.isEmpty()) return null
        val nearestIndex = nearestNodeIndex(player, nodes)
        if (nearestIndex < 0) return null
        var sumX = 0.0
        var sumZ = 0.0
        var count = 0
        for (i in 1..ROTATION_LOOKAHEAD) {
            val node = nodes[min(nodes.lastIndex, nearestIndex + i)]
            sumX += node.x
            sumZ += node.z
            count++
        }
        val refNode = nodes[min(nodes.lastIndex, nearestIndex + 2)]
        val guideY = max(refNode.y + GUIDE_LOOK_HEIGHT, player.eyePosition.y)
        return Vec3(sumX / count + 0.5, guideY, sumZ / count + 0.5)
    }

    private fun resolveGuidePoint(player: net.minecraft.client.player.LocalPlayer): Vec3? {
        val nodes = NativePathfinder.cachedPathNodes
        if (nodes.isEmpty()) return null

        val nearestIndex = nearestNodeIndex(player, nodes)
        if (nearestIndex < 0) return null

        val guideIndex = min(nodes.lastIndex, nearestIndex + LOOKAHEAD_NODE_COUNT)
        val baseNode = nodes[guideIndex]
        val direction = nodeDirection(nodes, guideIndex)
        val centered = centerTunnelNode(baseNode, direction)
        val guideY = max(baseNode.y + GUIDE_LOOK_HEIGHT, player.eyePosition.y)
        return Vec3(centered.x, guideY, centered.z)
    }

    private fun nearestNodeIndex(
        player: net.minecraft.client.player.LocalPlayer,
        nodes: List<Vec3>
    ): Int {
        // Search a bounded window ahead of the persistent cursor so the result can
        // never snap backward to already-passed nodes. Allowing one node behind the
        // cursor handles the common case where the player slightly overshoots and the
        // previous node is marginally closer — the lookahead still points forward.
        val cursor = NativePathfinder.pathNodeCursor
        val searchStart = maxOf(0, cursor - 1)
        val searchEnd = minOf(nodes.lastIndex, cursor + FORWARD_SEARCH_WINDOW)
        var nearestIndex = searchStart
        var nearestDistSq = Double.POSITIVE_INFINITY
        val px = player.x
        val pz = player.z
        for (index in searchStart..searchEnd) {
            val node = nodes[index]
            val dx = node.x - px
            val dz = node.z - pz
            val distSq = dx * dx + dz * dz
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq
                nearestIndex = index
            }
        }
        if (nearestIndex > cursor) {
            NativePathfinder.pathNodeCursor = nearestIndex
        }
        return nearestIndex
    }

    private fun firstUpcomingTraversalNode(
        player: net.minecraft.client.player.LocalPlayer,
        nodes: List<Vec3>,
        nearestIndex: Int
    ): Int? {
        val px = player.x
        val pz = player.z
        val nearest = nodes[nearestIndex]
        val nearestDx = nearest.x - px
        val nearestDz = nearest.z - pz
        val nearestHorizontalDistSq = nearestDx * nearestDx + nearestDz * nearestDz
        val startIndex =
            if (nearestHorizontalDistSq <= AUTO_JUMP_CURRENT_NODE_DISTANCE_SQ && nearestIndex < nodes.lastIndex) {
                nearestIndex + 1
            } else {
                nearestIndex
            }

        val firstIndex = max(1, startIndex)
        val endIndex = min(nodes.lastIndex, startIndex + AUTO_JUMP_NODE_LOOKAHEAD)
        if (firstIndex > endIndex) return null

        for (index in firstIndex..endIndex) {
            val node = nodes[index]
            val dx = node.x - px
            val dz = node.z - pz
            val horizontalDistSq = dx * dx + dz * dz
            if (horizontalDistSq < AUTO_JUMP_MIN_NODE_DISTANCE_SQ) continue
            if (horizontalDistSq > AUTO_JUMP_MAX_NODE_DISTANCE_SQ) return null
            return index
        }
        return null
    }

    private fun nodeDirection(nodes: List<Vec3>, index: Int): Vec3 {
        val prev = nodes[max(0, index - 1)]
        val next = nodes[min(nodes.lastIndex, index + 1)]
        return Vec3(next.x - prev.x, next.y - prev.y, next.z - prev.z)
    }

    private fun centerTunnelNode(baseNode: Vec3, direction: Vec3): Vec3 {
        val level = Minecraft.getInstance().level ?: return baseNode
        val basePos = BlockPos.containing(baseNode.x, baseNode.y, baseNode.z)
        val absDx = abs(direction.x)
        val absDz = abs(direction.z)

        if (absDx < MIN_DIRECTION_MAG && absDz < MIN_DIRECTION_MAG) return baseNode

        return when {
            absDz >= absDx * STRAIGHT_AXIS_RATIO -> {
                val minX = scanContiguousWalkable(level, basePos, -1, 0)
                val maxX = scanContiguousWalkable(level, basePos, 1, 0)
                val centerX = (minX + maxX + 1) / 2.0
                Vec3(centerX, basePos.y.toDouble(), basePos.z + 0.5)
            }
            absDx >= absDz * STRAIGHT_AXIS_RATIO -> {
                val minZ = scanContiguousWalkable(level, basePos, 0, -1)
                val maxZ = scanContiguousWalkable(level, basePos, 0, 1)
                val centerZ = (minZ + maxZ + 1) / 2.0
                Vec3(basePos.x + 0.5, basePos.y.toDouble(), centerZ)
            }
            else -> baseNode
        }
    }

    private fun scanContiguousWalkable(
        level: net.minecraft.world.level.Level,
        origin: BlockPos,
        stepX: Int,
        stepZ: Int
    ): Int {
        var current = origin
        for (@Suppress("UNUSED_VARIABLE") step in 1..CENTER_SCAN_RADIUS) {
            val next = current.offset(stepX, 0, stepZ)
            if (!MinecraftPathingRules.isWalkable(level, next)) break
            current = next
        }
        return if (stepX != 0) current.x else current.z
    }

    private fun usesGroundMovement(): Boolean =
        activeAction == ActionType.WALK ||
            activeAction == ActionType.SPRINT ||
            activeAction == ActionType.JUMP ||
            activeAction == ActionType.SPRINT_JUMP

    private fun usesStrafeAdjustment(): Boolean = usesGroundMovement() && !back

    companion object {
        private const val AUTO_JUMP_MIN_RISE = 0.45
        private const val AUTO_JUMP_MAX_RISE = 1.25
        private const val AUTO_JUMP_CURRENT_NODE_DISTANCE_SQ = 0.25
        private const val AUTO_JUMP_MIN_NODE_DISTANCE_SQ = 0.04
        private const val AUTO_JUMP_MAX_NODE_DISTANCE_SQ = 2.25
        private const val AUTO_JUMP_TRIGGER_DISTANCE_SQ = 1.96
        private const val AUTO_JUMP_NODE_LOOKAHEAD = 2
        private const val AUTO_JUMP_SOLID_BLOCK_HEIGHT = 0.75
        private const val CLOSE_ALIGN_DISTANCE = 1.6f
        private const val CAUTIOUS_TURN_YAW = 28f
        private const val TURN_IN_PLACE_YAW = 55f
        private const val CAUTIOUS_TURN_FORWARD_THRESHOLD = 0.20f
        private const val TURN_IN_PLACE_FORWARD_THRESHOLD = 0.52f
        private const val MIN_STRAFE_FORWARD = 0.12f
        private const val LOOKAHEAD_NODE_COUNT = 2
        private const val ROTATION_LOOKAHEAD = 4
        private const val FORWARD_SEARCH_WINDOW = 16
        private const val CENTER_SCAN_RADIUS = 5
        private const val STRAIGHT_AXIS_RATIO = 1.5
        private const val MIN_DIRECTION_MAG = 0.05
        // Eye-level offset — camera aims at a "standing player" head at the guide node, not the floor.
        // Previous 0.55 made the pitch dip toward adjacent wall faces at corners / when pathing flat.
        private const val GUIDE_LOOK_HEIGHT = 1.62
    }
}

private data class MovementInputs(
    val forward: Boolean,
    val backward: Boolean,
    val left: Boolean,
    val right: Boolean,
    val sprint: Boolean,
)
