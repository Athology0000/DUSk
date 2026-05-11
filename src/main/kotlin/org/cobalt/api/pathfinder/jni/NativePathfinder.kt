package org.cobalt.api.pathfinder.jni

import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.cobalt.api.module.ModuleDebug
import org.cobalt.api.pathfinder.cache.CachedWorld
import org.cobalt.api.rotation.RotationExecutor
import org.cobalt.api.util.AngleUtils
import org.cobalt.api.util.InventoryUtils
import org.cobalt.api.util.TickScheduler
import org.cobalt.api.util.player.MovementManager
import org.cobalt.internal.etherwarp.EtherwarpLogic
import org.cobalt.internal.pathfinding.DebugLog
import org.cobalt.internal.pathfinding.PathRecoveryController
import org.cobalt.internal.pathfinding.PathTeleportConfig
import org.cobalt.internal.pathfinding.TeleportValidationController
import org.cobalt.internal.skyblock.HypixelManager
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object NativePathfinder {

    val isInitialized: Boolean get() = true

    var cachedFullPathNodes: List<Vec3> = emptyList()
        private set

    var cachedPathNodes: List<Vec3> = emptyList()
        private set

    var cachedPathFlags: IntArray = IntArray(0)
        private set

    var cachedKeyNodeFlags: IntArray = IntArray(0)
        private set

    var cachedKeyNodeMetrics: IntArray = IntArray(0)
        private set

    var selectedStartIndex: Int = -1
        private set

    var lastPathSignature: String = ""
        private set

    var lastTimeMs: Long = 0L
        private set

    var lastNodesExplored: Int = 0
        private set

    var lastNanosecondsPerNode: Double = 0.0
        private set

    var lastError: String = ""
        private set

    var pathNodeCursor: Int = 0
        internal set

    var availabilityFlagsOverride: Int? = null

    var noTunnelCenter: Boolean = false

    private var state: PathStatus = PathStatus.IDLE
    private var lastTickStatus: PathStatus = PathStatus.IDLE

    private var goalX: Int = 0
    private var goalY: Int = 0
    private var goalZ: Int = 0
    private var arrivalRadius: Double = 1.8

    private var routeWaypoints: List<Triple<Int, Int, Int>> = emptyList()
    private var routeLoop: Boolean = false
    private var routeProfile: MovementProfile = MovementProfile.DEFAULT
    private var routeArrivalRadius: Double = 1.8
    private var routeWpIndex: Int = 0

    private var searchVariantSeed: Int = 0

    private data class SearchPoint(val x: Int, val y: Int, val z: Int)

    private data class AvoidPoint(
        val x: Int,
        val y: Int,
        val z: Int,
        val radiusSq: Int,
        val maxYDiff: Int,
        val penalty: Double,
        var ttlSearches: Int
    )

    private val transientAvoidPoints = mutableListOf<AvoidPoint>()

    private val searchExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cobalt-pathfinder").apply { isDaemon = true }
    }

    @Volatile private var searchResult: NativePathResult? = null
    @Volatile private var searchFailed: Boolean = false
    @Volatile private var lastSearchSummary: String = ""
    private var searchFuture: Future<*>? = null

    private var teleportFiredNodeKey: Long = -1L
    private var teleportRestoreSlot: Int = -1
    private var teleportCooldownTicks: Int = 0
    private var selectedTeleportIndex: Int = -1
    private var selectedTeleportYaw: Float = 0f
    private var selectedTeleportPitch: Float = 0f
    private var selectedTeleportAction: ActionType = ActionType.WALK

    private var stuckTicks: Int = 0
    private var lastStuckCheckPos: Vec3 = Vec3.ZERO
    private var collisionTicks: Int = 0
    private var lastCollisionCheckPos: Vec3 = Vec3.ZERO
    private var verticalStallTicks: Int = 0
    private var lastVerticalCheckPos: Vec3 = Vec3.ZERO
    private val stuckPositions: ArrayDeque<BlockPos> = ArrayDeque(4)
    private var cleanExecTicks: Int = 0
    private var lastSearchGoalX: Int = Int.MIN_VALUE
    private var lastSearchGoalY: Int = Int.MIN_VALUE
    private var lastSearchGoalZ: Int = Int.MIN_VALUE
    private var sprintSuppressHysteresisTicks: Int = 0
    private var originalGoalBeforeFrontier: SearchPoint? = null
    private var walkingToCachedFrontier: Boolean = false

    // Debug fields
    var debugAvoidPoints: List<Pair<BlockPos, AvoidReason>> = emptyList()
        private set
    var debugReplanCount: Int = 0
        private set
    var debugLastRecoveryReason: String = "none"
        private set
    var debugLastAvoidPoint: BlockPos? = null
        private set

    private const val STUCK_CHECK_INTERVAL = 20
    private const val STUCK_THRESHOLD = 0.5
    private const val COLLISION_REPLAN_TICKS = 12
    private const val COLLISION_STUCK_THRESHOLD = 0.22
    private const val TELEPORT_YAW_THRESHOLD = 8f
    private const val TELEPORT_PITCH_THRESHOLD = 8f
    private const val MAX_ITERATIONS = 2_000_000
    private const val HEURISTIC_WEIGHT = 1.5
    private const val NON_PRIMARY_START_PENALTY = 18.0
    private const val AOTV_NODE_DISTANCE_MIN = 6.0
    private const val AOTV_NODE_DISTANCE_MAX = 54.0
    private const val AOTV_MIN_TOTAL_PATH_LENGTH = 8.0
    private const val AOTV_FLUID_STRAIGHTNESS_BONUS = 10.0
    private const val AOTV_FLUID_MIN_GAIN_FACTOR = 0.7
    private const val AOTV_NEEDED_MIN_GAIN = 4.0
    private const val TELEPORT_FORWARD_DOT_MIN = 0.92
    private const val TELEPORT_LOOK_DOT_MIN = 0.45
    private const val NODE_REACHED_RANGE = 0.75

    private const val FLAG_LOW_HEADROOM   = 1 shl 2
    private const val FLAG_STEP_UP_NEXT   = 1 shl 5
    private const val FLAG_TIGHT_CORRIDOR = 1 shl 7

    private const val VERTICAL_STALL_CHECK_INTERVAL = 15
    private const val VERTICAL_STALL_MIN_RISE = 0.2
    private const val STUCK_POSITIONS_MAX = 4
    private const val AVOID_PENALTY = 25.0
    private const val AVOID_RADIUS_SQ = 4
    private const val AVOID_MAX_Y_DIFF = 2
    private const val CLEAN_EXEC_TICKS = 30
    private const val SPRINT_SUPPRESS_CURVATURE = 0.4
    private const val SPRINT_SUPPRESS_HYSTERESIS_TICKS = 6
    private const val FRONTIER_MIN_DISTANCE_SQ = 64.0
    private const val FRONTIER_Y_SCAN = 12
    private const val HAZARD_LOOKAHEAD_NODES = 5
    private const val HAZARD_MAX_JUMP_NODES = 3
    private const val HAZARD_JUMP_MAX_DIST_SQ = 3.25 * 3.25
    private const val HAZARD_AVOID_RADIUS = 3
    private const val HAZARD_AVOID_PENALTY = 160.0
    private const val HAZARD_AVOID_TTL_SEARCHES = 4
    private const val TELEPORT_POST_LANDING_SAFE_NODES = 2
    private const val TELEPORT_NEED_LOOKAHEAD_NODES = 7

    fun init() {
        NativePathfinderJNI.initNative()
        searchExecutor.submit {}
    }

    fun destroy() {
        cancelSearch()
        searchExecutor.shutdownNow()
        releaseGuidedControl()
    }

    fun setTarget(x: Double, y: Double, z: Double) {
        setTargetWithRadius(x, y, z, 1.8)
    }

    fun setTargetWithRadius(x: Double, y: Double, z: Double, radius: Double) {
        routeWaypoints = emptyList()
        originalGoalBeforeFrontier = null
        walkingToCachedFrontier = false
        goalX = x.toInt(); goalY = y.toInt(); goalZ = z.toInt()
        arrivalRadius = radius
        startSearch()
    }

    fun setTargetWithStarts(
        starts: List<BlockPos>,
        goals: List<BlockPos>,
        isFly: Boolean = false,
        radius: Double = 1.8
    ): Boolean {
        if (starts.isEmpty() || goals.isEmpty()) {
            lastError = "No start or goal points were provided"
            state = PathStatus.FAILED
            return false
        }
        routeWaypoints = emptyList()
        originalGoalBeforeFrontier = null
        walkingToCachedFrontier = false
        routeProfile = if (isFly) MovementProfile.FLY else MovementProfile.DEFAULT
        val finalGoal = goals.first()
        goalX = finalGoal.x; goalY = finalGoal.y; goalZ = finalGoal.z
        arrivalRadius = radius
        return startSearch(
            starts.map { SearchPoint(it.x, if (isFly) it.y else it.y + 1, it.z) },
            goals.map { SearchPoint(it.x, if (isFly) it.y else it.y + 1, it.z) },
            isFly
        )
    }

    fun setFlyTarget(x: Double, y: Double, z: Double, radius: Double = 2.5) {
        routeWaypoints = emptyList()
        originalGoalBeforeFrontier = null
        walkingToCachedFrontier = false
        routeProfile = MovementProfile.FLY
        goalX = x.toInt(); goalY = y.toInt(); goalZ = z.toInt()
        arrivalRadius = radius
        startSearch(isFly = true)
    }

    fun setRoute(waypoints: DoubleArray, loop: Boolean, profile: MovementProfile) {
        setRouteWithRadius(waypoints, loop, profile, 1.8)
    }

    fun setRouteWithRadius(waypoints: DoubleArray, loop: Boolean, profile: MovementProfile, arrivalRadius: Double) {
        routeLoop = loop
        originalGoalBeforeFrontier = null
        walkingToCachedFrontier = false
        routeProfile = profile
        routeArrivalRadius = arrivalRadius
        routeWpIndex = 0
        routeWaypoints = buildList {
            var i = 0
            while (i + 2 < waypoints.size) {
                add(Triple(waypoints[i].toInt(), waypoints[i + 1].toInt(), waypoints[i + 2].toInt()))
                i += 3
            }
        }
        if (routeWaypoints.isNotEmpty()) {
            val wp = routeWaypoints[0]
            goalX = wp.first; goalY = wp.second; goalZ = wp.third
            this.arrivalRadius = arrivalRadius
            startSearch()
        }
    }

    fun stop() {
        cancelSearch()
        resetPathState()
        lastSearchGoalX = Int.MIN_VALUE
        lastSearchGoalY = Int.MIN_VALUE
        lastSearchGoalZ = Int.MIN_VALUE
        sprintSuppressHysteresisTicks = 0
    }

    fun onLevelChange() {
        cancelSearch()
        ChunkSerializer.invalidate()
        resetPathState()
    }

    private fun resetPathState() {
        state = PathStatus.IDLE
        cachedFullPathNodes = emptyList()
        cachedPathNodes = emptyList()
        cachedPathFlags = IntArray(0)
        cachedKeyNodeFlags = IntArray(0)
        cachedKeyNodeMetrics = IntArray(0)
        selectedStartIndex = -1
        lastPathSignature = ""
        pathNodeCursor = 0
        teleportFiredNodeKey = -1L
        teleportCooldownTicks = 0
        resetSelectedTeleport()
        availabilityFlagsOverride = null
        noTunnelCenter = false
        routeWpIndex = 0
        stuckTicks = 0
        collisionTicks = 0
        lastStuckCheckPos = Vec3.ZERO
        lastCollisionCheckPos = Vec3.ZERO
        verticalStallTicks = 0
        lastVerticalCheckPos = Vec3.ZERO
        stuckPositions.clear()
        transientAvoidPoints.clear()
        originalGoalBeforeFrontier = null
        walkingToCachedFrontier = false
        cleanExecTicks = 0
        PathExecutorState.reset()
        releaseGuidedControl()
    }

    val status: PathStatus get() = state

    fun tick(): PathCommand? {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: run {
            releaseGuidedControl()
            return null
        }
        val level = mc.level ?: run {
            releaseGuidedControl()
            return null
        }

        if (searchFailed) {
            searchFailed = false
            searchFuture = null
            if (state == PathStatus.PLANNING) {
                if (tryStartCachedFrontierFallback("findPath returned no result")) {
                    lastTickStatus = state
                    return null
                }
                state = PathStatus.FAILED
                logFailure("findPath returned no result")
            } else {
                state = PathStatus.EXECUTING
            }
        } else {
            val result = searchResult
            if (result != null) {
                searchResult = null
                searchFuture = null
                applySearchResult(result)
            }
        }

        when (state) {
            PathStatus.IDLE, PathStatus.FAILED, PathStatus.ARRIVED -> {
                if (state != lastTickStatus) {
                    cachedFullPathNodes = emptyList()
                    cachedPathNodes = emptyList()
                    pathNodeCursor = 0
                    releaseGuidedControl()
                }
                lastTickStatus = state
                return null
            }
            PathStatus.PLANNING -> {
                lastTickStatus = state
                return null
            }

            PathStatus.RECOVERING -> {
                state = PathStatus.EXECUTING
            }

            else -> {}
        }

        val nodes = cachedPathNodes
        if (nodes.isEmpty()) {
            state = PathStatus.FAILED
            releaseGuidedControl()
            lastTickStatus = state
            return null
        }

        val playerPos = Vec3(player.x, player.y, player.z)
        if (lastStuckCheckPos == Vec3.ZERO) {
            lastStuckCheckPos = playerPos
        }

        if (player.horizontalCollision && player.onGround() && state == PathStatus.EXECUTING) {
            if (collisionTicks == 0 || lastCollisionCheckPos == Vec3.ZERO) {
                lastCollisionCheckPos = playerPos
            }
            collisionTicks++
            if (collisionTicks >= COLLISION_REPLAN_TICKS) {
                collisionTicks = 0
                val moved = playerPos.distanceTo(lastCollisionCheckPos)
                if (moved < COLLISION_STUCK_THRESHOLD) {
                    startReplan(playerPos)
                }
                lastCollisionCheckPos = playerPos
            }
        } else {
            collisionTicks = 0
            lastCollisionCheckPos = playerPos
        }

        val dx = goalX - player.x
        val dy = goalY - player.y
        val dz = goalZ - player.z
        val distToGoal = sqrt(dx * dx + dy * dy + dz * dz)

        if (routeProfile == MovementProfile.FLY) {
            val flyUpdate =
                PathFlyMovementController.update(
                    player,
                    cachedFullPathNodes.ifEmpty { nodes },
                    Vec3(goalX + 0.5, goalY.toDouble(), goalZ + 0.5),
                    state,
                    distToGoal.toFloat()
                )
            if (flyUpdate.arrived) {
                advanceRouteOrArrive()
                lastTickStatus = state
                return null
            }
            lastTickStatus = state
            return flyUpdate.command
        }

        if (distToGoal <= arrivalRadius) {
            if (advanceRouteOrArrive()) {
                lastTickStatus = state
                return null
            }
        }

        if (state == PathStatus.EXECUTING) {
            val upcomingFlags = cachedKeyNodeFlags.getOrElse(pathNodeCursor) { 0 }
            if (upcomingFlags and FLAG_STEP_UP_NEXT != 0) {
                if (lastVerticalCheckPos == Vec3.ZERO) lastVerticalCheckPos = playerPos
                verticalStallTicks++
                if (verticalStallTicks >= VERTICAL_STALL_CHECK_INTERVAL) {
                    verticalStallTicks = 0
                    val rise = playerPos.y - lastVerticalCheckPos.y
                    if (rise < VERTICAL_STALL_MIN_RISE) startReplan(playerPos)
                    lastVerticalCheckPos = playerPos
                }
            } else {
                verticalStallTicks = 0
                lastVerticalCheckPos = Vec3.ZERO
            }
        }

        stuckTicks++
        if (stuckTicks >= STUCK_CHECK_INTERVAL && state == PathStatus.EXECUTING) {
            stuckTicks = 0
            val moved = playerPos.distanceTo(lastStuckCheckPos)
            if (moved < STUCK_THRESHOLD) startReplan(playerPos)
            lastStuckCheckPos = playerPos
        }

        if (state == PathStatus.EXECUTING && stuckPositions.isNotEmpty()) {
            cleanExecTicks++
            if (cleanExecTicks >= CLEAN_EXEC_TICKS) {
                stuckPositions.clear()
                cleanExecTicks = 0
            }
        }

        val rotationNodes = cachedFullPathNodes.ifEmpty { nodes }
        PathExecutorState.tickOverrides()
        PathExecutorState.updateLookPoints(rotationNodes, lastPathSignature)
        PathExecutorState.update(player, rotationNodes)
        advanceKeyCursor(player, nodes)

        if (state == PathStatus.EXECUTING && checkUpcomingHazard(level, playerPos, nodes)) {
            lastTickStatus = state
            return null
        }

        if (state == PathStatus.EXECUTING &&
            PathExecutorState.trackNonChangeProgress(PathExecutorState.currentPathPosition)
        ) {
            startRecovery("nonchange", AvoidReason.STUCK, playerPos)
            lastTickStatus = state
            return null
        }

        if (teleportCooldownTicks > 0) {
            teleportCooldownTicks--
        }

        val aotvSlot = EtherwarpLogic.findEtherwarpHotbarSlot()
        val computedFlags = if (aotvSlot >= 0) 0x1 or 0x2 else 0
        val availabilityFlags = availabilityFlagsOverride ?: computedFlags

        val flagsAtCursor = cachedKeyNodeFlags.getOrElse(pathNodeCursor) { 0 }
        val lowHeadroom   = flagsAtCursor and FLAG_LOW_HEADROOM   != 0
        val tightCorridor = flagsAtCursor and FLAG_TIGHT_CORRIDOR != 0

        val activeAction = computeActiveAction(nodes, pathNodeCursor, aotvSlot >= 0, flagsAtCursor)
        val directTargetIndex = directRotationTargetIndex(activeAction, pathNodeCursor, nodes.lastIndex)
        val directTargetNode = nodes.getOrNull(directTargetIndex) ?: nodes.last()

        val targetYaw = if (activeAction == ActionType.ETHERWARP || activeAction == ActionType.AOTV) {
            selectedTeleportYaw
        } else {
            val ndx = directTargetNode.x + 0.5 - player.x
            val ndz = directTargetNode.z + 0.5 - player.z
            Math.toDegrees(atan2(-ndx, ndz)).toFloat()
        }
        val targetPitch = if (activeAction == ActionType.ETHERWARP || activeAction == ActionType.AOTV) {
            selectedTeleportPitch
        } else {
            0f
        }

        val precisionMovement = PathExecutorState.requiresPrecisionMovement
        val highCurvature = PathExecutorState.pathCurvature >= SPRINT_SUPPRESS_CURVATURE || precisionMovement
        if (highCurvature) {
            sprintSuppressHysteresisTicks = SPRINT_SUPPRESS_HYSTERESIS_TICKS
        } else if (sprintSuppressHysteresisTicks > 0) {
            sprintSuppressHysteresisTicks--
        }

        val cmd = PathCommand(
            forward = true,
            back = false,
            jump = false,
            sneak = false,
            sprint = !lowHeadroom &&
                !tightCorridor &&
                !highCurvature &&
                sprintSuppressHysteresisTicks == 0,
            targetYaw = targetYaw,
            targetPitch = targetPitch,
            status = state,
            activeAction = activeAction,
            distanceToTarget = distToGoal.toFloat()
        )

        if (state == PathStatus.EXECUTING &&
            (cmd.activeAction == ActionType.AOTV || cmd.activeAction == ActionType.ETHERWARP)) {
            val yawErr = abs(AngleUtils.getRotationDelta(player.yRot, cmd.targetYaw))
            val pitchErr = abs(player.xRot - cmd.targetPitch)
            val aligned = yawErr < TELEPORT_YAW_THRESHOLD &&
                (cmd.activeAction == ActionType.AOTV || pitchErr < TELEPORT_PITCH_THRESHOLD)
            val nodeKey = cachedPathNodes.getOrNull(directTargetIndex)
                ?.let { n -> (n.x.toLong() and 0x1FFFFF) or ((n.z.toLong() and 0x1FFFFF) shl 21) }
                ?: -1L

            if (aligned && nodeKey != teleportFiredNodeKey && aotvSlot >= 0) {
                teleportFiredNodeKey = nodeKey
                fireTeleport(cmd.activeAction == ActionType.ETHERWARP, aotvSlot)
                teleportCooldownTicks = PathTeleportConfig.teleportCooldownTicks.coerceAtLeast(1)
                if (selectedTeleportIndex > pathNodeCursor) {
                    pathNodeCursor = selectedTeleportIndex.coerceIn(0, nodes.lastIndex)
                }
            }
        } else if (cmd.activeAction != ActionType.AOTV && cmd.activeAction != ActionType.ETHERWARP) {
            teleportFiredNodeKey = -1L
        }

        if (state != lastTickStatus && state == PathStatus.EXECUTING) {
            DebugLog.debug(mc, "Pathfinding", "NativePathfinder: executing path, ${nodes.size} key nodes")
        }
        lastTickStatus = state
        return cmd
    }

    fun setSearchVariantSeed(seed: Int) {
        searchVariantSeed = seed.coerceAtLeast(0)
    }

    private fun advanceRouteOrArrive(): Boolean {
        if (walkingToCachedFrontier) {
            val original = originalGoalBeforeFrontier
            if (original != null) {
                walkingToCachedFrontier = false
                goalX = original.x
                goalY = original.y
                goalZ = original.z
                DebugLog.status(
                    Minecraft.getInstance(),
                    "Pathfinding",
                    "Pathfinder: reached cached frontier, retrying original goal ${formatPoint(original)}."
                )
                startSearch()
                return true
            }
        }
        if (routeWaypoints.isNotEmpty()) {
            routeWpIndex++
            val totalWp = routeWaypoints.size
            val nextIndex = if (routeLoop) routeWpIndex % totalWp else routeWpIndex
            if (nextIndex < totalWp) {
                routeWpIndex = nextIndex
                val wp = routeWaypoints[nextIndex]
                goalX = wp.first; goalY = wp.second; goalZ = wp.third
                arrivalRadius = routeArrivalRadius
                startSearch()
                return true
            }
        }
        state = PathStatus.ARRIVED
        originalGoalBeforeFrontier = null
        walkingToCachedFrontier = false
        cachedFullPathNodes = emptyList()
        cachedPathNodes = emptyList()
        pathNodeCursor = 0
        releaseGuidedControl()
        return true
    }

    fun addTransientAvoidPoint(
        x: Int,
        y: Int,
        z: Int,
        radius: Int = 2,
        penalty: Double = 36.0,
        ttlSearches: Int = 2,
        maxYDiff: Int = 2
    ) {
        transientAvoidPoints += AvoidPoint(
            x = x,
            y = y,
            z = z,
            radiusSq = (radius.coerceAtLeast(1) * radius.coerceAtLeast(1)),
            maxYDiff = maxYDiff.coerceAtLeast(0),
            penalty = penalty,
            ttlSearches = ttlSearches.coerceAtLeast(1)
        )
    }

    fun clearTransientAvoidPoints() {
        transientAvoidPoints.clear()
    }

    private fun addDebugAvoidPoint(pos: BlockPos, reason: AvoidReason, radius: Int, penalty: Double, ttlSearches: Int) {
        addTransientAvoidPoint(pos.x, pos.y, pos.z, radius, penalty, ttlSearches)
        debugLastAvoidPoint = pos
        debugAvoidPoints = debugAvoidPoints + (pos to reason)
    }

    private fun buildAvoidInputs(): Pair<IntArray, DoubleArray> {
        val total = stuckPositions.size + transientAvoidPoints.size
        if (total == 0) return intArrayOf() to doubleArrayOf()

        val meta = IntArray(total * 5)
        val penalties = DoubleArray(total)
        var i = 0
        var p = 0
        for (pos in stuckPositions) {
            meta[i++] = pos.x
            meta[i++] = pos.y
            meta[i++] = pos.z
            meta[i++] = AVOID_RADIUS_SQ
            meta[i++] = AVOID_MAX_Y_DIFF
            penalties[p++] = AVOID_PENALTY
        }

        for (point in transientAvoidPoints) {
            meta[i++] = point.x
            meta[i++] = point.y
            meta[i++] = point.z
            meta[i++] = point.radiusSq
            meta[i++] = point.maxYDiff
            penalties[p++] = point.penalty
        }

        transientAvoidPoints.removeAll { point ->
            point.ttlSearches--
            point.ttlSearches <= 0
        }

        return meta to penalties
    }

    private fun submitSearch(starts: List<SearchPoint>, goals: List<SearchPoint>, isFly: Boolean) {
        val (avoidMeta, avoidPenalty) = buildAvoidInputs()
        val moveOrderOffset = searchVariantSeed.coerceAtLeast(0)
        val flatStarts = starts.toFlatIntArray()
        val flatGoals = goals.toFlatIntArray()
        lastSearchSummary = buildSearchSummary(starts, goals, isFly, avoidMeta.size / 5, moveOrderOffset)
        DebugLog.status(Minecraft.getInstance(), "Pathfinding", "Pathfinder: planning $lastSearchSummary")
        searchFuture = searchExecutor.submit {
            try {
                val result = NativePathfinderJNI.findPath(
                    startPoints = flatStarts,
                    endPoints = flatGoals,
                    isFly = isFly,
                    maxIterations = MAX_ITERATIONS,
                    heuristicWeight = HEURISTIC_WEIGHT,
                    nonPrimaryStartPenalty = NON_PRIMARY_START_PENALTY,
                    moveOrderOffset = moveOrderOffset,
                    avoidMeta = avoidMeta,
                    avoidPenalty = avoidPenalty
                )
                if (result != null && result.keyPath.size >= 3) {
                    searchResult = result
                } else {
                    lastError = "findPath returned no result"
                    searchFailed = true
                }
            } catch (e: Exception) {
                lastError = e.message ?: "Native path search failed"
                searchFailed = true
            }
        }
    }

    private fun startSearch(isFly: Boolean = routeProfile == MovementProfile.FLY): Boolean {
        cancelSearch()

        val sameGoal = goalX == lastSearchGoalX && goalY == lastSearchGoalY && goalZ == lastSearchGoalZ
        val preserveAvoidance = sameGoal && state == PathStatus.FAILED
        lastSearchGoalX = goalX
        lastSearchGoalY = goalY
        lastSearchGoalZ = goalZ

        if (!preserveAvoidance) {
            stuckPositions.clear()
            transientAvoidPoints.clear()
            debugAvoidPoints = emptyList()
        }
        cleanExecTicks = 0
        debugReplanCount = 0
        debugLastRecoveryReason = "none"
        debugLastAvoidPoint = null
        PathRecoveryController.reset()
        TeleportValidationController.reset()

        state = PathStatus.PLANNING
        val player = Minecraft.getInstance().player ?: run {
            lastError = "Player is missing"
            state = PathStatus.FAILED
            return false
        }

        val startY = player.blockY
        val goalYAdjusted = goalY

        submitSearch(
            starts = listOf(SearchPoint(player.blockX, startY, player.blockZ)),
            goals = listOf(SearchPoint(goalX, goalYAdjusted, goalZ)),
            isFly = isFly
        )
        return true
    }

    private fun startSearch(starts: List<SearchPoint>, goals: List<SearchPoint>, isFly: Boolean): Boolean {
        cancelSearch()
        stuckPositions.clear()
        cleanExecTicks = 0
        state = PathStatus.PLANNING
        submitSearch(starts, goals, isFly)
        return true
    }

    private fun startRecovery(reason: String, avoidReason: AvoidReason, playerPos: Vec3) {
        debugLastRecoveryReason = reason
        addDebugAvoidPoint(
            pos = BlockPos.containing(playerPos),
            reason = avoidReason,
            radius = 2,
            penalty = 36.0,
            ttlSearches = 2
        )
        startReplan(playerPos)
    }

    private fun startReplan(playerPos: Vec3) {
        if (state == PathStatus.REPLANNING) return
        state = PathStatus.REPLANNING
        NativePathfinderJNI.cancelSearch()
        searchFuture?.cancel(true)
        searchFuture = null
        cleanExecTicks = 0
        if (stuckPositions.size >= STUCK_POSITIONS_MAX) stuckPositions.removeFirst()
        stuckPositions.addLast(BlockPos.containing(playerPos))
        val startPos = BlockPos.containing(playerPos)
        submitSearch(
            starts = listOf(SearchPoint(startPos.x, startPos.y, startPos.z)),
            goals = listOf(SearchPoint(goalX, goalY, goalZ)),
            isFly = routeProfile == MovementProfile.FLY
        )
    }

    private fun applySearchResult(result: NativePathResult) {
        cachedFullPathNodes = result.path.toVec3List()
        cachedPathNodes = result.keyPath.toVec3List()
        cachedPathFlags = result.pathFlags
        cachedKeyNodeFlags = result.keyNodeFlags
        cachedKeyNodeMetrics = result.keyNodeMetrics
        selectedStartIndex = result.selectedStartIndex
        lastPathSignature = result.signature
        lastTimeMs = result.timeMs
        lastNodesExplored = result.nodesExplored
        lastNanosecondsPerNode = result.nanosecondsPerNode
        lastError = ""
        pathNodeCursor = 0
        stuckTicks = 0
        collisionTicks = 0
        PathExecutorState.reset()
        PathFlyMovementController.reset()
        state = PathStatus.EXECUTING
        val mc = Minecraft.getInstance()
        val playerPos = mc.player?.position() ?: Vec3.ZERO
        lastStuckCheckPos = playerPos
        lastCollisionCheckPos = playerPos
        ModuleDebug.log("Pathfinding", "Path built in ${result.timeMs}ms")
        DebugLog.status(
            mc,
            "Pathfinding",
            "Pathfinder: path found: ${cachedPathNodes.size} key/${cachedFullPathNodes.size} dense nodes, " +
                "length=${formatBlocks(pathLength(cachedFullPathNodes))}, goal=($goalX,$goalY,$goalZ), " +
                "time=${result.timeMs}ms, explored=${result.nodesExplored}, selectedStart=${result.selectedStartIndex}, " +
                "flags=${summarizeKeyFlags()}, ${cacheSummary()}, sig=${result.signature.take(10)}"
        )
    }

    private fun cancelSearch() {
        NativePathfinderJNI.cancelSearch()
        searchFuture?.cancel(true)
        searchFuture = null
        searchResult = null
        searchFailed = false
        state = PathStatus.IDLE
    }

    private fun List<SearchPoint>.toFlatIntArray(): IntArray {
        val out = IntArray(size * 3)
        var i = 0
        for (point in this) {
            out[i++] = point.x
            out[i++] = point.y
            out[i++] = point.z
        }
        return out
    }

    private fun IntArray.toVec3List(): List<Vec3> {
        val out = ArrayList<Vec3>(size / 3)
        var i = 0
        while (i + 2 < size) {
            out.add(Vec3(this[i].toDouble(), this[i + 1].toDouble(), this[i + 2].toDouble()))
            i += 3
        }
        return out
    }

    private fun fireTeleport(isEtherwarp: Boolean, aotvSlot: Int) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (!EtherwarpLogic.tryConsumeTeleportUseThisTick()) return

        PathExecutorState.onTeleportTriggered(selectedTeleportIndex.toDouble())

        teleportRestoreSlot = player.inventory.selectedSlot
        InventoryUtils.holdHotbarSlot(aotvSlot)
        if (isEtherwarp) mc.options.keyShift?.setDown(true)
        mc.options.keyUse?.setDown(true)
        TickScheduler.schedule(1L) {
            mc.options.keyUse?.setDown(false)
            if (isEtherwarp) mc.options.keyShift?.setDown(false)
        }
        TickScheduler.schedule(4L) {
            val restore = teleportRestoreSlot
            if (restore in 0..8) InventoryUtils.holdHotbarSlot(restore)
            teleportRestoreSlot = -1
        }
    }

    private fun advanceKeyCursor(player: net.minecraft.client.player.LocalPlayer, nodes: List<Vec3>) {
        if (nodes.isEmpty()) return
        val playerPos = player.position()

        while (
            pathNodeCursor < nodes.lastIndex &&
            isOnBlock(centerNode(nodes[pathNodeCursor]), playerPos, NODE_REACHED_RANGE)
        ) {
            pathNodeCursor++
        }

        while (
            pathNodeCursor < nodes.lastIndex &&
            isOnBlock(centerNode(nodes[pathNodeCursor + 1]), playerPos, NODE_REACHED_RANGE)
        ) {
            pathNodeCursor++
        }

        val cursor = pathNodeCursor.coerceIn(0, nodes.lastIndex)
        val searchEnd = minOf(nodes.lastIndex, cursor + 16)
        var nearestIdx = cursor
        var nearestDistSq = Double.POSITIVE_INFINITY
        for (i in maxOf(0, cursor - 1)..searchEnd) {
            val n = nodes[i]
            val dx = n.x + 0.5 - player.x
            val dz = n.z + 0.5 - player.z
            val distSq = dx * dx + dz * dz
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq
                nearestIdx = i
            }
        }
        if (nearestIdx > pathNodeCursor) {
            pathNodeCursor = nearestIdx
        }
    }

    private fun isOnBlock(point: Vec3, playerPos: Vec3, range: Double): Boolean {
        return playerPos.x >= point.x - range && playerPos.x < point.x + range &&
            playerPos.z >= point.z - range && playerPos.z < point.z + range
    }

    private fun centerNode(node: Vec3): Vec3 =
        Vec3(node.x + 0.5, node.y, node.z + 0.5)

    private fun checkUpcomingHazard(level: Level, playerPos: Vec3, nodes: List<Vec3>): Boolean {
        if (routeProfile == MovementProfile.FLY || nodes.isEmpty()) return false

        val scanEnd = minOf(nodes.lastIndex, pathNodeCursor + HAZARD_LOOKAHEAD_NODES)
        for (hazardIdx in (pathNodeCursor + 1)..scanEnd) {
            val hazardPos = PathHazards.walkPosForNode(nodes[hazardIdx])
            if (!PathHazards.isHarmfulStandPosition(level, hazardPos)) continue
            if (canJumpAcrossHazard(level, nodes, hazardIdx)) return false
            if (canTeleportAcrossHazard(nodes)) return false

            addDebugAvoidPoint(
                pos = hazardPos,
                reason = AvoidReason.HAZARD,
                radius = HAZARD_AVOID_RADIUS,
                penalty = HAZARD_AVOID_PENALTY,
                ttlSearches = HAZARD_AVOID_TTL_SEARCHES
            )
            startReplan(playerPos)
            return true
        }

        return false
    }

    private fun canTeleportAcrossHazard(nodes: List<Vec3>): Boolean =
        teleportCooldownTicks <= 0 &&
            EtherwarpLogic.findEtherwarpHotbarSlot() >= 0 &&
            selectV5TeleportCandidate(nodes, pathNodeCursor, forceNeeded = true) != null

    private fun canJumpAcrossHazard(level: Level, nodes: List<Vec3>, hazardIdx: Int): Boolean {
        val takeoffNode = nodes[(hazardIdx - 1).coerceAtLeast(pathNodeCursor)]
        val landingEnd = minOf(nodes.lastIndex, hazardIdx + HAZARD_MAX_JUMP_NODES)

        for (landingIdx in (hazardIdx + 1)..landingEnd) {
            val landingPos = PathHazards.walkPosForNode(nodes[landingIdx])
            if (!PathHazards.isReasonableLandingPosition(level, landingPos)) continue

            val landingNode = nodes[landingIdx]
            val dx = landingNode.x - takeoffNode.x
            val dz = landingNode.z - takeoffNode.z
            return dx * dx + dz * dz <= HAZARD_JUMP_MAX_DIST_SQ
        }

        return false
    }

    private fun computeActiveAction(
        nodes: List<Vec3>,
        cursor: Int,
        aotvAvailable: Boolean,
        nodeFlags: Int
    ): ActionType {
        resetSelectedTeleport()

        if (aotvAvailable && nodes.size >= 2 && teleportCooldownTicks <= 0) {
            val teleport = selectV5TeleportCandidate(nodes, cursor)
            if (teleport != null) {
                selectedTeleportIndex = teleport.index
                selectedTeleportYaw = teleport.yaw
                selectedTeleportPitch = teleport.pitch
                selectedTeleportAction = teleport.action
                return teleport.action
            }
        }
        if (nodeFlags and FLAG_STEP_UP_NEXT != 0) return ActionType.SPRINT_JUMP
        return ActionType.WALK
    }

    private data class TeleportCandidate(
        val index: Int,
        val yaw: Float,
        val pitch: Float,
        val action: ActionType
    )

    private fun selectV5TeleportCandidate(
        nodes: List<Vec3>,
        cursor: Int,
        forceNeeded: Boolean = false
    ): TeleportCandidate? {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        if (nodes.isEmpty()) return null

        val teleportNeeded = forceNeeded || player.horizontalCollision || hasTeleportNeedAhead(level, nodes, cursor)
        if (!teleportNeeded) return null
        val remainingLength = estimatePathGain(nodes, cursor, nodes.lastIndex)
        if (remainingLength < AOTV_MIN_TOTAL_PATH_LENGTH) return null

        val inFluid = player.isInWater || player.isInLava
        val effectiveStraightnessTolerance = if (inFluid)
            PathTeleportConfig.teleportStraightnessTolerance + AOTV_FLUID_STRAIGHTNESS_BONUS
        else
            PathTeleportConfig.teleportStraightnessTolerance
        val effectiveMinGain = when {
            teleportNeeded -> AOTV_NEEDED_MIN_GAIN
            inFluid -> PathTeleportConfig.minTeleportGain * AOTV_FLUID_MIN_GAIN_FACTOR
            else -> PathTeleportConfig.minTeleportGain
        }

        val maxRange = EtherwarpLogic.getEtherwarpRange().toDouble().coerceAtLeast(AOTV_NODE_DISTANCE_MAX)
        val maxIndex = minOf(nodes.lastIndex, cursor + PathTeleportConfig.maxLookAheadNodes.coerceAtLeast(1))

        var best: TeleportCandidate? = null

        for (i in maxIndex downTo cursor + 1) {
            val node = nodes[i]
            val aimBlock = BlockPos(
                Math.floor(node.x).toInt(),
                Math.floor(node.y - 1.0).toInt(),
                Math.floor(node.z).toInt()
            )
            val aimPoint = Vec3(aimBlock.x + 0.5, aimBlock.y + 0.92, aimBlock.z + 0.5)
            val gain = estimatePathGain(nodes, cursor, i)
            if (gain < effectiveMinGain) continue
            if (!isForwardTeleportCandidate(player, nodes, cursor, i)) continue
            if (!isTeleportSegmentStraightEnough(nodes, cursor, i, effectiveStraightnessTolerance)) continue
            if (!isTeleportLandingCandidateSafe(level, nodes, i, aimBlock)) continue

            val dx = aimPoint.x - player.eyePosition.x
            val dy = aimPoint.y - player.eyePosition.y
            val dz = aimPoint.z - player.eyePosition.z
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            if (dist > maxRange + 0.75) continue

            val yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
            val pitch = Math.toDegrees(-atan2(dy, sqrt(dx * dx + dz * dz))).toFloat()

            if (PathTeleportConfig.v5EtherwarpEnabled) {
                val direct = EtherwarpLogic.getEtherwarpResultTo(aimBlock, aimPoint)
                if (direct.succeeded && direct.pos == aimBlock) {
                    best = TeleportCandidate(i, yaw, pitch, ActionType.ETHERWARP)
                    break
                }
            }

            if (PathTeleportConfig.v5AotvEnabled && gain >= AOTV_NODE_DISTANCE_MIN && gain <= AOTV_NODE_DISTANCE_MAX) {
                best = TeleportCandidate(i, yaw, 0f, ActionType.AOTV)
                break
            }
        }

        return best
    }

    private fun isForwardTeleportCandidate(
        player: LocalPlayer,
        nodes: List<Vec3>,
        cursor: Int,
        candidateIndex: Int
    ): Boolean {
        if (candidateIndex <= cursor || cursor >= nodes.lastIndex) return false

        val from = centerNode(nodes[cursor.coerceIn(0, nodes.lastIndex)])
        val next = centerNode(nodes[(cursor + 1).coerceAtMost(nodes.lastIndex)])
        val target = centerNode(nodes[candidateIndex])

        val pathDx = next.x - from.x
        val pathDz = next.z - from.z
        val targetDx = target.x - from.x
        val targetDz = target.z - from.z

        val pathLen = sqrt(pathDx * pathDx + pathDz * pathDz)
        val targetLen = sqrt(targetDx * targetDx + targetDz * targetDz)
        if (pathLen < 0.001 || targetLen < 0.001) return false

        val pathDot = (pathDx * targetDx + pathDz * targetDz) / (pathLen * targetLen)
        if (pathDot < TELEPORT_FORWARD_DOT_MIN) return false

        val yawRad = Math.toRadians(player.yRot.toDouble())
        val lookX = -sin(yawRad)
        val lookZ = cos(yawRad)
        val playerDx = target.x - player.x
        val playerDz = target.z - player.z
        val playerLen = sqrt(playerDx * playerDx + playerDz * playerDz)
        if (playerLen < 0.001) return false

        val lookDot = (lookX * playerDx + lookZ * playerDz) / playerLen
        return lookDot >= TELEPORT_LOOK_DOT_MIN
    }

    private fun isTeleportLandingCandidateSafe(
        level: Level,
        nodes: List<Vec3>,
        candidateIndex: Int,
        supportPos: BlockPos
    ): Boolean {
        if (!PathHazards.isSafeTeleportSupport(level, supportPos)) return false

        val predictedFeet = PathHazards.teleportFeetPos(level, supportPos)
        if (PathHazards.isHarmfulStandPosition(level, predictedFeet)) return false

        val nodeFeet = PathHazards.walkPosForNode(nodes[candidateIndex])
        if (PathHazards.isHarmfulStandPosition(level, nodeFeet)) return false

        val postEnd = minOf(nodes.lastIndex, candidateIndex + TELEPORT_POST_LANDING_SAFE_NODES)
        for (i in candidateIndex..postEnd) {
            if (PathHazards.isHarmfulStandPosition(level, PathHazards.walkPosForNode(nodes[i]))) {
                return false
            }
        }

        return true
    }

    private fun hasTeleportNeedAhead(level: Level, nodes: List<Vec3>, cursor: Int): Boolean {
        val scanEnd = minOf(nodes.lastIndex, cursor + TELEPORT_NEED_LOOKAHEAD_NODES)
        for (i in cursor..scanEnd) {
            val flags = cachedKeyNodeFlags.getOrElse(i) { 0 }
            if (flags and FLAG_LOW_HEADROOM != 0 || flags and FLAG_TIGHT_CORRIDOR != 0) return true
            if (flags and FLAG_STEP_UP_NEXT != 0 && i > cursor) return true
            if (PathHazards.isHarmfulStandPosition(level, PathHazards.walkPosForNode(nodes[i]))) return true
        }
        return false
    }

    private fun isTeleportSegmentOpen(level: Level, nodes: List<Vec3>, start: Int, end: Int): Boolean {
        if (end <= start) return false
        for (i in start..end) {
            val flags = cachedKeyNodeFlags.getOrElse(i) { 0 }
            if (flags and FLAG_LOW_HEADROOM != 0 || flags and FLAG_TIGHT_CORRIDOR != 0) return false
            if (PathHazards.isHarmfulStandPosition(level, PathHazards.walkPosForNode(nodes[i]))) return false
        }
        return true
    }

    private fun estimatePathGain(nodes: List<Vec3>, start: Int, end: Int): Double {
        if (start >= end) return 0.0
        var total = 0.0
        var prev = nodes[start]
        for (i in (start + 1)..end) {
            val next = nodes[i]
            val dx = next.x - prev.x
            val dy = next.y - prev.y
            val dz = next.z - prev.z
            total += sqrt(dx * dx + dy * dy + dz * dz)
            prev = next
        }
        return total
    }

    private fun isTeleportSegmentStraightEnough(
        nodes: List<Vec3>,
        start: Int,
        end: Int,
        tolerance: Double = PathTeleportConfig.teleportStraightnessTolerance
    ): Boolean {
        if (end <= start + 1) return true
        val a = nodes[start]
        val b = nodes[end]
        val baseX = b.x - a.x
        val baseZ = b.z - a.z
        val baseLen = sqrt(baseX * baseX + baseZ * baseZ)
        if (baseLen < 0.001) return false

        val nx = -baseZ / baseLen
        val nz = baseX / baseLen
        var maxOffset = 0.0

        for (i in start + 1 until end) {
            val p = nodes[i]
            val ox = p.x - a.x
            val oz = p.z - a.z
            maxOffset = maxOf(maxOffset, abs(ox * nx + oz * nz))
        }

        return maxOffset <= tolerance
    }

    private fun resetSelectedTeleport() {
        selectedTeleportIndex = -1
        selectedTeleportYaw = 0f
        selectedTeleportPitch = 0f
        selectedTeleportAction = ActionType.WALK
    }

    private fun directRotationTargetIndex(
        activeAction: ActionType,
        cursor: Int,
        lastIndex: Int
    ): Int {
        return when (activeAction) {
            ActionType.AOTV,
            ActionType.ETHERWARP -> if (selectedTeleportIndex >= 0) selectedTeleportIndex else minOf(cursor + 1, lastIndex)
            else -> minOf(cursor + 1, lastIndex)
        }.coerceIn(0, lastIndex)
    }

    private fun tryStartCachedFrontierFallback(reason: String): Boolean {
        if (walkingToCachedFrontier || routeProfile == MovementProfile.FLY || routeWaypoints.isNotEmpty()) return false
        if (CachedWorld.getChunk(goalX shr 4, goalZ shr 4) == null) return false

        val player = Minecraft.getInstance().player ?: return false
        val start = SearchPoint(player.blockX, player.blockY, player.blockZ)
        val original = originalGoalBeforeFrontier ?: SearchPoint(goalX, goalY, goalZ).also {
            originalGoalBeforeFrontier = it
        }
        val frontier = findCachedFrontierTarget(start, original) ?: return false
        val dx = frontier.x + 0.5 - player.x
        val dy = frontier.y.toDouble() - player.y
        val dz = frontier.z + 0.5 - player.z
        if (dx * dx + dy * dy + dz * dz < FRONTIER_MIN_DISTANCE_SQ) return false

        walkingToCachedFrontier = true
        goalX = frontier.x
        goalY = frontier.y
        goalZ = frontier.z
        DebugLog.status(
            Minecraft.getInstance(),
            "Pathfinding",
            "Pathfinder: full path unavailable ($reason). Walking to cached frontier ${formatPoint(frontier)} " +
                "before retrying original goal ${formatPoint(original)}."
        )
        submitSearch(
            starts = listOf(start),
            goals = listOf(frontier),
            isFly = false
        )
        return true
    }

    private fun findCachedFrontierTarget(start: SearchPoint, goal: SearchPoint): SearchPoint? {
        val chunks = lineChunks(start.x shr 4, start.z shr 4, goal.x shr 4, goal.z shr 4)
        if (chunks.size < 2) return null

        var lastCached: Pair<Int, Int>? = null
        for (chunk in chunks) {
            if (CachedWorld.getChunk(chunk.first, chunk.second) == null) break
            lastCached = chunk
        }

        val frontierChunk = lastCached ?: return null
        if (frontierChunk.first == (start.x shr 4) && frontierChunk.second == (start.z shr 4)) return null
        return findCachedWalkableInChunk(frontierChunk.first, frontierChunk.second, start.y, goal.x, goal.z)
    }

    private fun lineChunks(startX: Int, startZ: Int, goalX: Int, goalZ: Int): List<Pair<Int, Int>> {
        val dx = goalX - startX
        val dz = goalZ - startZ
        val steps = max(abs(dx), abs(dz))
        if (steps == 0) return listOf(startX to startZ)

        val out = ArrayList<Pair<Int, Int>>(steps + 1)
        var last: Pair<Int, Int>? = null
        for (i in 0..steps) {
            val x = (startX + dx * (i.toDouble() / steps.toDouble())).roundToInt()
            val z = (startZ + dz * (i.toDouble() / steps.toDouble())).roundToInt()
            val next = x to z
            if (next != last) {
                out += next
                last = next
            }
        }
        return out
    }

    private fun findCachedWalkableInChunk(
        chunkX: Int,
        chunkZ: Int,
        aroundY: Int,
        preferBlockX: Int,
        preferBlockZ: Int
    ): SearchPoint? {
        val minX = chunkX shl 4
        val minZ = chunkZ shl 4
        val candidates = ArrayList<Pair<Int, Int>>(256)
        for (lx in 0..15) {
            for (lz in 0..15) {
                val x = minX + lx
                val z = minZ + lz
                candidates += x to z
            }
        }
        candidates.sortBy { (x, z) ->
            val dx = x - preferBlockX
            val dz = z - preferBlockZ
            dx * dx + dz * dz
        }

        for (dy in 0..FRONTIER_Y_SCAN) {
            val yCandidates = if (dy == 0) intArrayOf(aroundY) else intArrayOf(aroundY + dy, aroundY - dy)
            for (y in yCandidates) {
                for ((x, z) in candidates) {
                    val point = SearchPoint(x, y, z)
                    if (isCachedWalkable(point)) return point
                }
            }
        }
        return null
    }

    private fun isCachedWalkable(point: SearchPoint): Boolean =
        isCachedPassable(point.x, point.y, point.z) &&
            isCachedPassable(point.x, point.y + 1, point.z) &&
            isCachedStandable(point.x, point.y - 1, point.z)

    private fun isCachedPassable(x: Int, y: Int, z: Int): Boolean {
        val flags = CachedWorld.getBlockFlags(x, y, z)?.toInt()?.and(0xFFFF) ?: return false
        return flags and NativeVoxelFlags.FLUID == 0 &&
            flags and NativeVoxelFlags.PASSABLE != 0
    }

    private fun isCachedStandable(x: Int, y: Int, z: Int): Boolean {
        val flags = CachedWorld.getBlockFlags(x, y, z)?.toInt()?.and(0xFFFF) ?: return false
        return flags and NativeVoxelFlags.FLUID == 0 &&
            flags and NativeVoxelFlags.SOLID != 0
    }

    private fun logFailure(reason: String) {
        val mc = Minecraft.getInstance()
        val summary = lastSearchSummary.ifBlank { "goal=($goalX,$goalY,$goalZ)" }
        DebugLog.status(mc, "Pathfinding", "Pathfinder: path failed: $reason | $summary | ${cacheSummary()}")
    }

    private fun buildSearchSummary(
        starts: List<SearchPoint>,
        goals: List<SearchPoint>,
        isFly: Boolean,
        avoidCount: Int,
        moveOrderOffset: Int
    ): String {
        val profile = if (isFly) "fly" else "walk"
        val startText = starts.joinToString(prefix = "[", postfix = "]", limit = 3) { formatPoint(it) }
        val goalText = goals.joinToString(prefix = "[", postfix = "]", limit = 3) { formatPoint(it) }
        return "profile=$profile, starts=$startText, goals=$goalText, maxIter=$MAX_ITERATIONS, " +
            "avoid=$avoidCount, variant=$moveOrderOffset, ${cacheSummary()}"
    }

    private fun cacheSummary(): String =
        "${CachedWorld.getCacheStats()}, place=${HypixelManager.currentPlaceName()}"

    private fun formatPoint(point: SearchPoint): String = "(${point.x},${point.y},${point.z})"

    private fun pathLength(nodes: List<Vec3>): Double {
        if (nodes.size < 2) return 0.0
        var total = 0.0
        var prev = nodes.first()
        for (i in 1..nodes.lastIndex) {
            val next = nodes[i]
            total += prev.distanceTo(next)
            prev = next
        }
        return total
    }

    private fun summarizeKeyFlags(): String {
        var lowHeadroom = 0
        var stepUp = 0
        var tight = 0
        for (flags in cachedKeyNodeFlags) {
            if (flags and FLAG_LOW_HEADROOM != 0) lowHeadroom++
            if (flags and FLAG_STEP_UP_NEXT != 0) stepUp++
            if (flags and FLAG_TIGHT_CORRIDOR != 0) tight++
        }
        return "low=$lowHeadroom,step=$stepUp,tight=$tight"
    }

    private fun formatBlocks(value: Double): String =
        String.format(Locale.US, "%.1fb", value)

    private fun releaseGuidedControl() {
        PathFlyMovementController.reset()
        MovementManager.setLookLock(false)
        RotationExecutor.stopIfUsing(PathfinderRotationStrategy)
    }
}
