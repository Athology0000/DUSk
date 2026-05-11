package org.cobalt.api.pathfinder.jni

import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.cobalt.api.util.AngleUtils
import org.cobalt.api.util.helper.Rotation
import java.util.Locale
import kotlin.math.*

object PathExecutorState {

    // ── Rotation state (read by PathfinderRotationStrategy per render frame) ──
    var rawTargetYaw: Float = 0f
    var rawTargetPitch: Float = 0f
    var currentYaw: Float = 0f
    var currentPitch: Float = 0f
    var yawVelocity: Double = 0.0
    var pitchVelocity: Double = 0.0
    var initialized: Boolean = false
    var pathCurvature: Double = 0.0
    var initialTurnBoostTicks: Int = 0
    var jumpSuppressTicks: Int = 0

    // ── Path position (indexed into lookPoints) ──
    var currentPathPosition: Double = 0.0
        private set
    var currentTargetPoint: Vec3? = null
        private set
    var currentLookaheadSplinePoint: Vec3? = null
        private set
    var lookaheadSplinePoints: List<Vec3> = emptyList()
        private set
    var currentSplineDistance: Double = 0.0
        private set
    var currentLookaheadDistance: Double = 0.0
        private set
    var executionDebugLine: String = "spline idle"
        private set
    var requiresPrecisionMovement: Boolean = false
        private set
    private var smoothedTargetInitialized: Boolean = false

    // ── Dense look points (eye-level, curvature-offset, cached by signature) ──
    var lookPoints: List<Vec3> = emptyList()
        private set
    private var lookPointsSignature: String = ""
    private var splinePath: SplinePath? = null

    // ── Adaptive lookahead ──
    var smoothedLookahead: Double = MAX_LOOKAHEAD
    private var lookaheadOverride: Double? = null
    private var lookaheadOverrideExpiry: Int = 0
    private var lookaheadOverrideDeadlineMs: Long = 0L

    // ── Post-teleport resync ──
    var postTeleportResyncTicks: Int = 0
        private set

    // ── Unseen target rollback ──
    private var unseenSinceMs: Long = 0L
    private var unseenStartPathPosition: Double = 0.0

    // ── NonChangeRecovery tracking ──
    private var nonChangeBestPathPosition: Double? = null
    private var nonChangeTicks: Int = 0

    // ── Per-update LOS cache ──
    private val losCache = HashMap<Long, Boolean>(64)

    // =========================================================================
    // Constants
    // =========================================================================

    private const val MIN_LOOKAHEAD = 2.35
    private const val MAX_LOOKAHEAD = 4.8
    private const val PRECISION_LOOKAHEAD = 1.25
    private const val PRECISION_CURVATURE = 0.34
    private const val DROP_SCAN_DISTANCE = 3.5
    private const val DROP_HEIGHT_THRESHOLD = 0.45
    private const val RECOVERY_MIN_LOOKAHEAD = 0.1
    private const val LOOKAHEAD_STEP = 0.4
    private const val RECOVERY_LOOKAHEAD_STEP = 0.15
    private const val MAX_DIRECTION_DIVERGENCE = 50.0
    private const val MAX_UPWARD_PITCH = -45.0
    private const val PROXIMITY_THRESHOLD = 4.0
    private const val COMPLETION_RADIUS = 1.9
    private const val MAX_LOOK_DISTANCE = 0.8
    private const val PLAYER_LOOK_HEIGHT = 1.62
    private const val PREDICTION_MAX_ADVANCE_GROUND = 0.9
    private const val PREDICTION_MAX_ADVANCE_AIR = 2.4
    private const val PREDICTION_MIN_SPEED_XZ = 0.05
    private const val PREDICTION_TICKS = 10
    private const val CATCHUP_MAX_ADVANCE_GROUND = 3.4
    private const val CATCHUP_MAX_ADVANCE_AIR = 1.8
    private const val CATCHUP_PROJECTION_DISTANCE = 6.0
    private const val YAW_DEADZONE = 0.18f
    private const val PITCH_DEADZONE = 0.25f
    private const val TARGET_SMOOTH_MIN = 0.22f
    private const val TARGET_SMOOTH_MAX = 0.65f
    private const val TARGET_SMOOTH_YAW_SCALE = 24.0
    private const val TARGET_SMOOTH_PITCH_SCALE = 18.0
    private const val TELEPORT_RESYNC_TICKS = 14
    private const val TELEPORT_RESYNC_SEARCH_WINDOW = 72
    private const val TELEPORT_RESYNC_SEARCH_DISTANCE = 72.0
    private const val UNSEEN_ROLLBACK_MS = 600L
    private const val NON_CHANGE_PATH_DELTA = 0.45
    private const val NON_CHANGE_TICKS_THRESHOLD = 35

    // Look point generation
    private const val SPLINE_STEPS = 10
    private const val LOOK_EYE_OFFSET = PLAYER_LOOK_HEIGHT
    private const val OUTWARD_OFFSET_STRENGTH = 0.6
    private const val LOOK_WINDOW = 4
    private const val MIN_LOOK_SPACING_SQ = 0.64
    private const val LOOK_MIN_INTERVAL = 1.2
    private const val LOOK_MAX_INTERVAL = 8.0

    // =========================================================================
    // Public API
    // =========================================================================

    fun reset() {
        rawTargetYaw = 0f; rawTargetPitch = 0f
        currentYaw = 0f; currentPitch = 0f
        yawVelocity = 0.0; pitchVelocity = 0.0
        initialized = false
        initialTurnBoostTicks = 10
        pathCurvature = 0.0
        currentPathPosition = 0.0
        currentTargetPoint = null
        currentLookaheadSplinePoint = null
        lookaheadSplinePoints = emptyList()
        currentSplineDistance = 0.0
        currentLookaheadDistance = 0.0
        executionDebugLine = "spline idle"
        requiresPrecisionMovement = false
        smoothedTargetInitialized = false
        jumpSuppressTicks = 0
        lookPoints = emptyList()
        lookPointsSignature = ""
        splinePath = null
        smoothedLookahead = MAX_LOOKAHEAD
        lookaheadOverride = null
        lookaheadOverrideExpiry = 0
        lookaheadOverrideDeadlineMs = 0L
        postTeleportResyncTicks = 0
        unseenSinceMs = 0L
        unseenStartPathPosition = 0.0
        nonChangeBestPathPosition = null
        nonChangeTicks = 0
        losCache.clear()
    }

    fun onTeleportTriggered(targetPathPosition: Double? = null) {
        postTeleportResyncTicks = TELEPORT_RESYNC_TICKS
        unseenSinceMs = 0L
        unseenStartPathPosition = currentPathPosition
        setTemporaryLookahead(MAX_LOOKAHEAD, TELEPORT_RESYNC_TICKS)
        if (targetPathPosition != null) {
            val resyncDistance = splinePath?.distanceAtControlIndex(targetPathPosition) ?: targetPathPosition
            currentSplineDistance = maxOf(currentSplineDistance, maxOf(0.0, resyncDistance - 2.0))
            currentPathPosition = currentSplineDistance
        }
    }

    fun setTemporaryLookahead(distance: Double, durationTicks: Int) {
        lookaheadOverride = distance
        lookaheadOverrideExpiry = durationTicks
        lookaheadOverrideDeadlineMs = System.currentTimeMillis() + durationTicks * 50L + 2000L
        smoothedLookahead = distance
    }

    fun isInRecoveryMode(): Boolean = lookaheadOverride != null && lookaheadOverrideExpiry > 0

    /** Call once per game tick to advance override and resync timers. */
    fun tickOverrides() {
        if (lookaheadOverrideExpiry > 0) {
            lookaheadOverrideExpiry--
            if (lookaheadOverrideExpiry <= 0) lookaheadOverride = null
        }
        if (postTeleportResyncTicks > 0) postTeleportResyncTicks--
    }

    /**
     * Returns true if path position has not advanced in NON_CHANGE_TICKS_THRESHOLD ticks.
     * Caller should trigger a replan when this returns true.
     */
    fun trackNonChangeProgress(pathPosition: Double): Boolean {
        val best = nonChangeBestPathPosition
        if (best == null) {
            nonChangeBestPathPosition = pathPosition
            nonChangeTicks = 0
            return false
        }
        if (pathPosition > best + NON_CHANGE_PATH_DELTA) {
            nonChangeBestPathPosition = pathPosition
            nonChangeTicks = 0
            return false
        }
        nonChangeTicks++
        if (nonChangeTicks >= NON_CHANGE_TICKS_THRESHOLD) {
            nonChangeBestPathPosition = null
            nonChangeTicks = 0
            return true
        }
        return false
    }

    fun resetNonChangeRecovery() {
        nonChangeBestPathPosition = null
        nonChangeTicks = 0
    }

    fun initialTurnBoostFactor(yawError: Float): Float =
        if (initialTurnBoostTicks > 0 && abs(yawError) >= maxOf(35f, YAW_DEADZONE * 4)) 1.35f else 1f

    /**
     * Rebuild look points from the dense path if the path signature has changed.
     * Must be called before update() each tick.
     */
    fun updateLookPoints(nodes: List<Vec3>, signature: String) {
        if (signature == lookPointsSignature && lookPoints.isNotEmpty()) return
        lookPointsSignature = signature
        lookPoints = if (nodes.size >= 2) buildLookPoints(nodes) else emptyList()
        splinePath = lookPoints.takeIf { it.size >= 2 }?.let(::buildSplinePath)
        lookaheadSplinePoints = splinePath?.samples ?: emptyList()
        currentPathPosition = 0.0
        currentSplineDistance = 0.0
        currentLookaheadDistance = 0.0
        smoothedTargetInitialized = false
        losCache.clear()
    }

    fun update(player: LocalPlayer, nodes: List<Vec3>) {
        losCache.clear()
        val pts = lookPoints.takeIf { it.size >= 2 }
        if (pts == null) {
            updateSimple(player, nodes)
        } else {
            updateFullSpline(player, pts)
        }
    }

    /** Used by PathfinderRotationStrategy for curvature-dependent KD scaling. */
    fun updateCurvature(nodes: List<Vec3>, nearIdx: Int, range: Int = 4) {
        if (nearIdx + 2 > nodes.lastIndex) { pathCurvature = 0.0; return }
        val a = nodes[nearIdx]
        val b = nodes[minOf(nearIdx + 1, nodes.lastIndex)]
        val bdx = b.x - a.x; val bdz = b.z - a.z
        val baseMag = sqrt(bdx * bdx + bdz * bdz)
        if (baseMag < 0.3) { pathCurvature = 0.0; return }
        var maxAngle = 0.0
        for (k in 2..range) {
            val fi = minOf(nearIdx + k, nodes.lastIndex - 1)
            if (fi >= nodes.lastIndex) break
            val fa = nodes[fi]; val fb = nodes[minOf(fi + 1, nodes.lastIndex)]
            val fdx = fb.x - fa.x; val fdz = fb.z - fa.z
            val fMag = sqrt(fdx * fdx + fdz * fdz)
            if (fMag < 0.3) continue
            val dot = (bdx * fdx + bdz * fdz) / (baseMag * fMag)
            val angle = acos(dot.coerceIn(-1.0, 1.0))
            if (angle > maxAngle) maxAngle = angle
        }
        pathCurvature = maxAngle
    }

    fun getInterpolatedPoint(pts: List<Vec3>, indexFloat: Double): Vec3 {
        if (pts.isEmpty()) return Vec3.ZERO
        val safe = indexFloat.coerceIn(0.0, pts.lastIndex.toDouble())
        val idx = safe.toInt().coerceIn(0, pts.lastIndex)
        val frac = safe - idx
        val a = pts[idx]; val b = pts[minOf(idx + 1, pts.lastIndex)]
        if (frac <= 0.0 || idx >= pts.lastIndex) return a
        return Vec3(a.x + (b.x - a.x) * frac, a.y + (b.y - a.y) * frac, a.z + (b.z - a.z) * frac)
    }

    // =========================================================================
    // Full V5-style update (uses look points with LOS, adaptive lookahead, rollback)
    // =========================================================================

    private fun updateFullSpline(player: LocalPlayer, pts: List<Vec3>) {
        val spline = splinePath ?: run {
            updateFull(player, pts)
            return
        }
        val level = Minecraft.getInstance().level
        val eye = player.eyePosition
        val motionY = player.deltaMovement.y
        val isFalling = motionY < -0.4 || isSplineDropping(spline)
        val pathAnchor = spline.sample(currentSplineDistance)
        val isJumpingHigh = motionY > 0.1 || player.y - pathAnchor.y > 2.0
        val isTeleportResync = postTeleportResyncTicks > 0
        val dropAhead = hasDropAhead(spline, DROP_SCAN_DISTANCE, DROP_HEIGHT_THRESHOLD)

        val projection = spline.project(
            point = eye,
            hintDistance = currentSplineDistance,
            searchBack = if (isTeleportResync) 24.0 else if (isFalling) 0.0 else 2.5,
            searchForward = when {
                isTeleportResync -> TELEPORT_RESYNC_SEARCH_DISTANCE
                dropAhead -> 3.0
                isFalling -> 5.0
                isJumpingHigh -> 12.0
                else -> 9.0
            },
            horizontalOnly = isFalling || isJumpingHigh
        )

        val effectiveThreshold = when {
            isTeleportResync -> PROXIMITY_THRESHOLD * 1.8
            isFalling -> 5.0
            isJumpingHigh -> PROXIMITY_THRESHOLD * 2
            else -> PROXIMITY_THRESHOLD
        }
        if (projection.distSq < effectiveThreshold * effectiveThreshold) {
            val maxAdvance = if (isTeleportResync) 14.0 else if (isFalling) 0.55 else 2.2
            val advanced = minOf(currentSplineDistance + maxAdvance, projection.distance)
            currentSplineDistance = maxOf(currentSplineDistance, advanced).coerceAtMost(spline.totalLength)
        }

        if (!isTeleportResync && !dropAhead) {
            catchUpSplineProgress(player, spline, isFalling || isJumpingHigh)
        }

        if (!isTeleportResync && !dropAhead) {
            applyPredictedSplineProgress(player, spline)
        }
        currentPathPosition = currentSplineDistance

        val adaptiveLookahead = getAdaptiveLookaheadDistance(eye, spline, dropAhead)
        currentLookaheadDistance = adaptiveLookahead
        requiresPrecisionMovement = dropAhead || pathCurvature >= PRECISION_CURVATURE || projection.distSq > 2.25
        val remainingPathForLookahead = spline.totalLength - currentSplineDistance
        val effectiveLookahead = minOf(adaptiveLookahead, remainingPathForLookahead.coerceAtLeast(0.1))
        var findResult = if (level != null) {
            findVisibleSplineLookahead(eye, spline, effectiveLookahead, level)
        } else {
            FindResult(spline.sample(minOf(spline.totalLength, currentSplineDistance + effectiveLookahead)), effectiveLookahead)
        }

        val effectiveMin = if (isInRecoveryMode()) RECOVERY_MIN_LOOKAHEAD else MIN_LOOKAHEAD
        var targetVisible = findResult.visible && (level == null || isPointVisible(eye, findResult.point, level))

        val now = System.currentTimeMillis()
        if (findResult.lookahead <= effectiveMin + 0.001 && !targetVisible) {
            if (unseenSinceMs == 0L) {
                unseenSinceMs = now
                unseenStartPathPosition = currentSplineDistance
            }
            if (now - unseenSinceMs >= UNSEEN_ROLLBACK_MS && level != null) {
                val testPoint = spline.sample(minOf(spline.totalLength, currentSplineDistance + effectiveMin))
                if (isPointVisible(eye, testPoint, level)) {
                    unseenSinceMs = 0L
                    unseenStartPathPosition = currentSplineDistance
                    findResult = FindResult(testPoint, effectiveMin)
                    targetVisible = true
                }
            }
        } else {
            unseenSinceMs = 0L
            unseenStartPathPosition = currentSplineDistance
        }

        if (!targetVisible && findResult.lookahead <= 0.0) {
            currentTargetPoint = null
            currentLookaheadSplinePoint = null
            requiresPrecisionMovement = true
            executionDebugLine = formatDebugLine(spline, projection.distSq, false)
            return
        }

        var targetPoint = findResult.point
        currentLookaheadSplinePoint = targetPoint
        val rawDx = targetPoint.x - eye.x
        val rawDy = targetPoint.y - eye.y
        val rawDz = targetPoint.z - eye.z
        val rawHorz = sqrt(rawDx * rawDx + rawDz * rawDz)
        val rawPitch = -atan2(rawDy, rawHorz) * (180.0 / PI)

        if (rawPitch < -50.0 && rawHorz < 1.0) {
            val newDy = rawHorz * tan(30.0 * (PI / 180.0))
            targetPoint = Vec3(targetPoint.x, eye.y + newDy, targetPoint.z)
        }
        if (isFalling && rawHorz < 0.5) {
            val fallTarget = spline.sample(minOf(spline.totalLength, currentSplineDistance + 2.5))
            if (level == null || isPointVisible(eye, fallTarget, level)) {
                targetPoint = fallTarget
            }
        }

        targetPoint = clampLookDistance(eye, targetPoint)
        currentTargetPoint = targetPoint

        val r = rotationTo(eye, targetPoint)
        val remainingPath = spline.totalLength - currentSplineDistance
        val finishFactor = if (remainingPath < 4.0) maxOf(0.25, remainingPath / 4.0) else 1.0
        val isStraight = pathCurvature < 0.15
        val dynamicYawDeadzone = (if (isStraight) YAW_DEADZONE * 1.5f else YAW_DEADZONE) * finishFactor.toFloat()

        updateSmoothedRotationTarget(
            targetYaw = r.yaw,
            targetPitch = r.pitch,
            yawDeadzone = dynamicYawDeadzone,
            pitchDeadzone = (PITCH_DEADZONE * finishFactor).toFloat(),
            finishFactor = finishFactor.toFloat()
        )

        val nearEnd3D = eye.distanceToSqr(spline.samples.last()) <= COMPLETION_RADIUS * COMPLETION_RADIUS &&
            currentSplineDistance >= spline.totalLength - 2.0
        if (nearEnd3D || currentSplineDistance >= spline.totalLength - 0.25) {
            currentSplineDistance = spline.totalLength
            currentPathPosition = currentSplineDistance
        }
        executionDebugLine = formatDebugLine(spline, projection.distSq, targetVisible)
    }

    private fun updateFull(player: LocalPlayer, pts: List<Vec3>) {
        val level = Minecraft.getInstance().level
        val eye = player.eyePosition
        val motionY = player.deltaMovement.y
        val isFalling = motionY < -0.4 || isPathDropping(pts)
        val pathAnchor = getInterpolatedPoint(pts, currentPathPosition)
        val isJumpingHigh = motionY > 0.1 || player.y - pathAnchor.y > 2.0

        // ── Project player onto closest path segment ──
        var bestT = currentPathPosition
        var minDistSq = Double.POSITIVE_INFINITY
        val isTeleportResync = postTeleportResyncTicks > 0

        val searchWindow = when {
            isTeleportResync -> maxOf(TELEPORT_RESYNC_SEARCH_WINDOW, if (isJumpingHigh) 12 else 8)
            isFalling -> 4
            isJumpingHigh -> 12
            else -> 8
        }
        val startIdx = when {
            isTeleportResync -> maxOf(0, currentPathPosition.toInt() - 24)
            isFalling -> currentPathPosition.toInt()
            else -> maxOf(0, currentPathPosition.toInt() - 2)
        }
        val endIdx = minOf(pts.size - 2, startIdx + searchWindow)

        for (i in startIdx..endIdx) {
            val p1 = pts[i]; val p2 = pts[i + 1]
            val segProgress = if (isFalling || isJumpingHigh)
                getClosestPointOnSegmentHorizontal(eye, p1, p2)
            else
                getClosestPointOnSegment(eye, p1, p2)
            val candidateT = i + segProgress
            if (isFalling && candidateT < currentPathPosition) continue
            val proj = getInterpolatedPoint(pts, candidateT)
            val distSq = if (isFalling)
                (eye.x - proj.x).pow(2) + (eye.z - proj.z).pow(2)
            else
                eye.distanceToSqr(proj)
            if (distSq < minDistSq) { minDistSq = distSq; bestT = candidateT }
        }

        val effectiveThreshold = when {
            isTeleportResync -> PROXIMITY_THRESHOLD * 1.8
            isFalling -> 5.0
            isJumpingHigh -> PROXIMITY_THRESHOLD * 2
            else -> PROXIMITY_THRESHOLD
        }
        if (minDistSq < effectiveThreshold * effectiveThreshold) {
            val maxJump = if (isTeleportResync) 14.0 else if (isFalling) 0.5 else 2.0
            val advancedT = minOf(currentPathPosition + maxJump, bestT)
            currentPathPosition = maxOf(currentPathPosition, advancedT)
        }

        if (!isTeleportResync) {
            applyPredictedPathProgress(player, pts)
        }

        // ── Curvature + adaptive lookahead ──
        val nearIdx = currentPathPosition.toInt().coerceIn(0, pts.lastIndex)
        updateCurvatureFromLookPoints(pts, nearIdx)
        val adaptiveLookahead = getAdaptiveLookahead(eye, pts)

        // ── Find visible lookahead point (with LOS check) ──
        // Cap lookahead to remaining path so the look target never goes past the final node.
        val remainingPathForLookahead = pts.lastIndex.toDouble() - currentPathPosition
        val effectiveLookahead = minOf(adaptiveLookahead, remainingPathForLookahead.coerceAtLeast(0.1))
        var findResult = if (level != null)
            findVisibleLookahead(eye, pts, effectiveLookahead, level)
        else
            FindResult(getInterpolatedPoint(pts, minOf(pts.lastIndex.toDouble(), currentPathPosition + effectiveLookahead)), effectiveLookahead)

        val effectiveMin = if (isInRecoveryMode()) RECOVERY_MIN_LOOKAHEAD else MIN_LOOKAHEAD
        var targetVisible = findResult.visible && (level == null || isPointVisible(eye, findResult.point, level))

        // ── Unseen rollback: if no visible target for 600ms, rewind path position ──
        val now = System.currentTimeMillis()
        if (findResult.lookahead <= effectiveMin + 0.001 && !targetVisible) {
            if (unseenSinceMs == 0L) {
                unseenSinceMs = now
                unseenStartPathPosition = currentPathPosition
            }
            if (now - unseenSinceMs >= UNSEEN_ROLLBACK_MS && level != null) {
                val testT = minOf(pts.lastIndex.toDouble(), currentPathPosition + effectiveMin)
                val testPoint = getInterpolatedPoint(pts, testT)
                if (isPointVisible(eye, testPoint, level)) {
                    unseenSinceMs = 0L
                    unseenStartPathPosition = currentPathPosition
                    findResult = FindResult(testPoint, effectiveMin)
                    targetVisible = true
                }
            }
        } else {
            unseenSinceMs = 0L
            unseenStartPathPosition = currentPathPosition
        }

        if (!targetVisible && findResult.lookahead <= 0.0) {
            currentTargetPoint = null
            currentLookaheadSplinePoint = null
            return
        }

        // ── Pitch clamping for steep upward angles ──
        var targetPoint = findResult.point
        currentLookaheadSplinePoint = targetPoint
        val rawDx = targetPoint.x - eye.x
        val rawDy = targetPoint.y - eye.y
        val rawDz = targetPoint.z - eye.z
        val rawHorz = sqrt(rawDx * rawDx + rawDz * rawDz)
        val rawPitch = -atan2(rawDy, rawHorz) * (180.0 / PI)

        if (rawPitch < -50.0 && rawHorz < 1.0) {
            val newDy = rawHorz * tan(30.0 * (PI / 180.0))
            targetPoint = Vec3(targetPoint.x, eye.y + newDy, targetPoint.z)
        }
        if (isFalling && rawHorz < 0.5) {
            val fallTarget = getInterpolatedPoint(pts, minOf(pts.lastIndex.toDouble(), currentPathPosition + 2.5))
            if (level == null || isPointVisible(eye, fallTarget, level)) {
                targetPoint = fallTarget
            }
        }

        // ── Clamp look distance ──
        val dx = targetPoint.x - eye.x
        val dy = targetPoint.y - eye.y
        val dz = targetPoint.z - eye.z
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        if (dist > MAX_LOOK_DISTANCE) {
            val scale = MAX_LOOK_DISTANCE / dist
            targetPoint = Vec3(eye.x + dx * scale, eye.y + dy * scale, eye.z + dz * scale)
        }

        currentTargetPoint = targetPoint

        // ── Compute rotation target ──
        val tdx = targetPoint.x - eye.x
        val tdy = targetPoint.y - eye.y
        val tdz = targetPoint.z - eye.z
        val tHorz = sqrt(tdx * tdx + tdz * tdz)
        val targetYaw = Math.toDegrees(atan2(-tdx, tdz)).toFloat()
        val targetPitch = -Math.toDegrees(atan2(tdy, tHorz)).toFloat()

        // ── Feed look-target directly to the PD controller ──
        // The PD controller in PathfinderRotationStrategy provides all frame-level smoothing via
        // its acceleration/friction physics. Tick-level exponential decay caused stutter:
        // the PD would sprint to each 10%-step then idle until the next tick (20 Hz micro-jitter).
        val lastIndex = pts.lastIndex
        val remainingPath = lastIndex - currentPathPosition
        val finishFactor = if (remainingPath < 4.0) maxOf(0.25, remainingPath / 4.0) else 1.0
        val isStraight = pathCurvature < 0.15
        val dynamicYawDeadzone = (if (isStraight) YAW_DEADZONE * 1.5f else YAW_DEADZONE) * finishFactor.toFloat()

        updateSmoothedRotationTarget(
            targetYaw = targetYaw,
            targetPitch = targetPitch,
            yawDeadzone = dynamicYawDeadzone,
            pitchDeadzone = (PITCH_DEADZONE * finishFactor).toFloat(),
            finishFactor = finishFactor.toFloat()
        )

        // ── Completion ──
        val lastPoint = pts[lastIndex]
        val nearEnd3D = eye.distanceToSqr(lastPoint) <= COMPLETION_RADIUS * COMPLETION_RADIUS && currentPathPosition >= lastIndex - 2.0
        if (nearEnd3D || currentPathPosition >= lastIndex - 0.25) {
            currentPathPosition = lastIndex.toDouble()
        }
    }

    // =========================================================================
    // Fallback simple update (before look points are ready)
    // =========================================================================

    private fun updateSimple(player: LocalPlayer, nodes: List<Vec3>) {
        if (nodes.isEmpty()) {
            rawTargetYaw = player.yRot; rawTargetPitch = player.xRot
            pathCurvature = 0.0; currentPathPosition = 0.0; currentTargetPoint = null; currentLookaheadSplinePoint = null
            requiresPrecisionMovement = false
            return
        }
        val eye = player.eyePosition
        val projected = projectPathPositionHorizontal(player.x, player.z, currentPathPosition, nodes)
        if (projected.isFinite() && projected > currentPathPosition) {
            val steadyAdvance = minOf(currentPathPosition + PREDICTION_MAX_ADVANCE_GROUND, projected)
            val catchUpAdvance = projected - 2.5 + 0.75
            currentPathPosition = maxOf(steadyAdvance, catchUpAdvance, currentPathPosition)
                .coerceAtMost(nodes.lastIndex.toDouble())
        }
        val nearIdx = currentPathPosition.toInt().coerceIn(0, nodes.lastIndex)
        updateCurvature(nodes, nearIdx)
        val lookT = maxOf(currentPathPosition + 2.5, (projected.takeIf { it.isFinite() } ?: currentPathPosition) + 0.75)
            .coerceIn(0.0, nodes.lastIndex.toDouble())
        val splinePoint = pointAtSimple(nodes, lookT)
        val targetPoint = clampLookDistance(eye, splinePoint)
        currentTargetPoint = targetPoint
        currentLookaheadSplinePoint = splinePoint
        val r = rotationTo(eye, targetPoint)
        rawTargetYaw = r.yaw; rawTargetPitch = r.pitch
    }

    private fun updateSmoothedRotationTarget(
        targetYaw: Float,
        targetPitch: Float,
        yawDeadzone: Float,
        pitchDeadzone: Float,
        finishFactor: Float
    ) {
        if (!smoothedTargetInitialized) {
            rawTargetYaw = AngleUtils.normalizeAngle(targetYaw)
            rawTargetPitch = targetPitch.coerceIn(-89.9f, 89.9f)
            smoothedTargetInitialized = true
            return
        }

        val finishDamping = (0.45f + 0.55f * finishFactor).coerceIn(0.25f, 1f)
        val yawDelta = AngleUtils.getRotationDelta(rawTargetYaw, targetYaw)
        val yawAbs = abs(yawDelta)
        if (yawAbs > yawDeadzone) {
            val alpha = nonlinearBlend(yawAbs.toDouble(), TARGET_SMOOTH_YAW_SCALE) * finishDamping
            rawTargetYaw = AngleUtils.normalizeAngle(rawTargetYaw + yawDelta * alpha)
        }

        val pitchDelta = targetPitch - rawTargetPitch
        val pitchAbs = abs(pitchDelta)
        if (pitchAbs > pitchDeadzone) {
            val alpha = nonlinearBlend(pitchAbs.toDouble(), TARGET_SMOOTH_PITCH_SCALE) * finishDamping
            rawTargetPitch = (rawTargetPitch + pitchDelta * alpha).coerceIn(-89.9f, 89.9f)
        }
    }

    private fun nonlinearBlend(delta: Double, scale: Double): Float {
        val t = 1.0 - exp(-delta / scale)
        return (TARGET_SMOOTH_MIN + (TARGET_SMOOTH_MAX - TARGET_SMOOTH_MIN) * t)
            .toFloat()
            .coerceIn(TARGET_SMOOTH_MIN, TARGET_SMOOTH_MAX)
    }

    // =========================================================================
    // Dense look point generation
    // =========================================================================

    private fun buildLookPoints(nodes: List<Vec3>): List<Vec3> {
        val result = mutableListOf<Vec3>()
        val first = nodes[0]
        result.add(Vec3(first.x + 0.5, first.y + LOOK_EYE_OFFSET, first.z + 0.5))
        var lastPlaced = nodes[0]

        for (i in 1 until nodes.lastIndex) {
            val curr = nodes[i]
            val dist = dist3d(curr, lastPlaced)

            // Curvature + outward offset
            val prev = nodes[maxOf(0, i - LOOK_WINDOW)]
            val next = nodes[minOf(nodes.lastIndex, i + LOOK_WINDOW)]
            val v1x = curr.x - prev.x; val v1z = curr.z - prev.z
            val v2x = next.x - curr.x; val v2z = next.z - curr.z
            val m1 = sqrt(v1x * v1x + v1z * v1z); val m2 = sqrt(v2x * v2x + v2z * v2z)

            var curvature = 0.0
            var offsetX = 0.0; var offsetZ = 0.0

            if (m1 > 0.05 && m2 > 0.05) {
                val dot = ((v1x * v2x + v1z * v2z) / (m1 * m2)).coerceIn(-1.0, 1.0)
                val angle = acos(dot)
                curvature = (angle / (PI / 2.5)).coerceAtMost(1.0)
                val cross = v1x * v2z - v1z * v2x
                val dir = if (cross > 0) 1.0 else -1.0
                val fwdX = v1x / m1 + v2x / m2; val fwdZ = v1z / m1 + v2z / m2
                val fMag = sqrt(fwdX * fwdX + fwdZ * fwdZ)
                if (fMag > 0.01) {
                    offsetX = -(fwdZ / fMag) * dir * curvature * OUTWARD_OFFSET_STRENGTH
                    offsetZ = (fwdX / fMag) * dir * curvature * OUTWARD_OFFSET_STRENGTH
                }
            }

            val dynamicInterval = LOOK_MAX_INTERVAL - curvature * (LOOK_MAX_INTERVAL - LOOK_MIN_INTERVAL)
            if (dist < dynamicInterval) continue

            val raw = Vec3(curr.x + 0.5 + offsetX, curr.y + LOOK_EYE_OFFSET, curr.z + 0.5 + offsetZ)
            val last = result.last()
            val sdx = raw.x - last.x; val sdz = raw.z - last.z
            if (sdx * sdx + sdz * sdz >= MIN_LOOK_SPACING_SQ) {
                result.add(raw)
            } else {
                result[result.lastIndex] = raw
            }
            lastPlaced = curr
        }

        val last = nodes.last()
        result.add(Vec3(last.x + 0.5, last.y + LOOK_EYE_OFFSET, last.z + 0.5))
        return result
    }

    private data class SplineProjection(val distance: Double, val point: Vec3, val distSq: Double)

    private data class SplinePath(
        val controlPoints: List<Vec3>,
        val samples: List<Vec3>,
        val cumulative: DoubleArray
    ) {
        val totalLength: Double = cumulative.lastOrNull() ?: 0.0

        fun sample(distance: Double): Vec3 {
            if (samples.isEmpty()) return Vec3.ZERO
            if (samples.size == 1 || totalLength <= 0.0) return samples.first()
            val d = distance.coerceIn(0.0, totalLength)
            val rawIndex = cumulative.binarySearch(d)
            val upper = if (rawIndex >= 0) rawIndex else (-rawIndex - 1).coerceIn(1, cumulative.lastIndex)
            val lower = (upper - 1).coerceAtLeast(0)
            val span = cumulative[upper] - cumulative[lower]
            if (span <= 1e-6) return samples[upper]
            val t = (d - cumulative[lower]) / span
            return lerp(samples[lower], samples[upper], t)
        }

        fun project(
            point: Vec3,
            hintDistance: Double,
            searchBack: Double,
            searchForward: Double,
            horizontalOnly: Boolean
        ): SplineProjection {
            if (samples.size < 2) return SplineProjection(0.0, samples.firstOrNull() ?: Vec3.ZERO, Double.POSITIVE_INFINITY)
            val startDistance = (hintDistance - searchBack).coerceAtLeast(0.0)
            val endDistance = (hintDistance + searchForward).coerceAtMost(totalLength)
            var bestDistance = hintDistance.coerceIn(0.0, totalLength)
            var bestPoint = sample(bestDistance)
            var bestDistSq = Double.POSITIVE_INFINITY

            for (i in 0 until samples.lastIndex) {
                val segStart = cumulative[i]
                val segEnd = cumulative[i + 1]
                if (segEnd < startDistance || segStart > endDistance) continue

                val a = samples[i]
                val b = samples[i + 1]
                val frac = if (horizontalOnly) closestHorizontalFraction(point, a, b) else closestFraction(point, a, b)
                val projected = lerp(a, b, frac)
                val distance = (segStart + (segEnd - segStart) * frac).coerceIn(startDistance, endDistance)
                val distSq = if (horizontalOnly) {
                    val dx = point.x - projected.x
                    val dz = point.z - projected.z
                    dx * dx + dz * dz
                } else {
                    point.distanceToSqr(projected)
                }

                if (distSq < bestDistSq) {
                    bestDistSq = distSq
                    bestDistance = distance
                    bestPoint = projected
                }
            }

            return SplineProjection(bestDistance, bestPoint, bestDistSq)
        }

        fun distanceAtControlIndex(indexFloat: Double): Double {
            if (controlPoints.isEmpty() || samples.isEmpty()) return indexFloat
            val control = sampleControl(indexFloat)
            return project(control, indexFloat.coerceAtLeast(0.0), searchBack = 12.0, searchForward = 18.0, horizontalOnly = false).distance
        }

        private fun sampleControl(indexFloat: Double): Vec3 {
            val safe = indexFloat.coerceIn(0.0, controlPoints.lastIndex.toDouble())
            val index = safe.toInt().coerceIn(0, controlPoints.lastIndex)
            val frac = safe - index
            val a = controlPoints[index]
            val b = controlPoints[minOf(index + 1, controlPoints.lastIndex)]
            return lerp(a, b, frac)
        }
    }

    private fun buildSplinePath(points: List<Vec3>): SplinePath {
        if (points.size < 2) return SplinePath(points, points, DoubleArray(points.size) { 0.0 })

        val samples = ArrayList<Vec3>((points.size - 1) * SPLINE_STEPS + 1)
        for (i in 0 until points.lastIndex) {
            val p0 = points[(i - 1).coerceAtLeast(0)]
            val p1 = points[i]
            val p2 = points[i + 1]
            val p3 = points[(i + 2).coerceAtMost(points.lastIndex)]
            for (step in 0 until SPLINE_STEPS) {
                samples.add(catmullRom(p0, p1, p2, p3, step.toDouble() / SPLINE_STEPS))
            }
        }
        samples.add(points.last())

        val cumulative = DoubleArray(samples.size)
        for (i in 1 until samples.size) {
            cumulative[i] = cumulative[i - 1] + samples[i - 1].distanceTo(samples[i])
        }
        return SplinePath(points, samples, cumulative)
    }

    private fun catmullRom(p0: Vec3, p1: Vec3, p2: Vec3, p3: Vec3, t: Double): Vec3 {
        val t2 = t * t
        val t3 = t2 * t
        return Vec3(
            0.5 * (2 * p1.x + (-p0.x + p2.x) * t + (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t2 + (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * t3),
            0.5 * (2 * p1.y + (-p0.y + p2.y) * t + (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * t2 + (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * t3),
            0.5 * (2 * p1.z + (-p0.z + p2.z) * t + (2 * p0.z - 5 * p1.z + 4 * p2.z - p3.z) * t2 + (-p0.z + 3 * p1.z - 3 * p2.z + p3.z) * t3)
        )
    }

    // =========================================================================
    // Adaptive lookahead
    // =========================================================================

    private fun getAdaptiveLookahead(eye: Vec3, pts: List<Vec3>): Double {
        lookaheadOverride?.let {
            if (System.currentTimeMillis() > lookaheadOverrideDeadlineMs) {
                lookaheadOverride = null
                lookaheadOverrideExpiry = 0
            } else {
                return it
            }
        }
        val ti = currentPathPosition.toInt()
        if (ti + 3 >= pts.size) return smoothedLookahead

        val pathPoint = getInterpolatedPoint(pts, currentPathPosition)
        val pdx = eye.x - pathPoint.x; val pdz = eye.z - pathPoint.z
        val deviationFactor = ((sqrt(pdx * pdx + pdz * pdz) - 1.6) / 2.0).coerceIn(0.0, 1.0)

        val sa = pts[ti]; val sb = pts[minOf(ti + 2, pts.lastIndex)]
        val sdx = sb.x - sa.x; val sdy = sb.y - sa.y; val sdz = sb.z - sa.z
        val sMag = sqrt(sdx * sdx + sdy * sdy + sdz * sdz)

        var maxAngle = 0.0
        for (look in intArrayOf(4, 6, 8)) {
            val fi = minOf(ti + look, pts.size - 3)
            if (fi <= ti + 2) continue
            val fa = pts[fi]; val fb = pts[minOf(fi + 2, pts.lastIndex)]
            val fdx = fb.x - fa.x; val fdy = fb.y - fa.y; val fdz = fb.z - fa.z
            val fMag = sqrt(fdx * fdx + fdy * fdy + fdz * fdz)
            if (sMag > 0.8 && fMag > 0.8) {
                val dot = ((sdx * fdx + sdy * fdy + sdz * fdz) / (sMag * fMag)).coerceIn(-1.0, 1.0)
                val angle = acos(dot)
                if (angle > maxAngle) maxAngle = angle
            }
        }

        pathCurvature = maxAngle
        val isFalling = Minecraft.getInstance().player?.deltaMovement?.y?.let { it < -0.1 } ?: false
        val effectiveAngle = if (isFalling) maxAngle * 0.5 else maxAngle
        val curveFactor = ((effectiveAngle - 0.61) / 0.7).coerceIn(0.0, 1.0)
        val adjustFactor = maxOf(deviationFactor, curveFactor)
        val target = MAX_LOOKAHEAD - (MAX_LOOKAHEAD - MIN_LOOKAHEAD) * adjustFactor
        val lerpFactor = if (target > smoothedLookahead) 0.1 else 0.05
        smoothedLookahead += (target - smoothedLookahead) * lerpFactor
        return smoothedLookahead
    }

    private fun getAdaptiveLookaheadDistance(eye: Vec3, spline: SplinePath, dropAhead: Boolean): Double {
        lookaheadOverride?.let {
            if (System.currentTimeMillis() > lookaheadOverrideDeadlineMs) {
                lookaheadOverride = null
                lookaheadOverrideExpiry = 0
            } else {
                return it
            }
        }

        if (currentSplineDistance + 1.0 >= spline.totalLength) return smoothedLookahead

        val pathPoint = spline.sample(currentSplineDistance)
        val pdx = eye.x - pathPoint.x
        val pdz = eye.z - pathPoint.z
        val deviationFactor = ((sqrt(pdx * pdx + pdz * pdz) - 1.6) / 2.0).coerceIn(0.0, 1.0)

        val baseA = spline.sample(currentSplineDistance)
        val baseB = spline.sample(minOf(spline.totalLength, currentSplineDistance + 0.9))
        val bdx = baseB.x - baseA.x
        val bdy = baseB.y - baseA.y
        val bdz = baseB.z - baseA.z
        val baseMag = sqrt(bdx * bdx + bdy * bdy + bdz * bdz)

        var maxAngle = 0.0
        if (baseMag > 0.4) {
            for (look in doubleArrayOf(1.4, 2.2, 3.2, 4.8)) {
                val fa = spline.sample(minOf(spline.totalLength, currentSplineDistance + look))
                val fb = spline.sample(minOf(spline.totalLength, currentSplineDistance + look + 0.9))
                val fdx = fb.x - fa.x
                val fdy = fb.y - fa.y
                val fdz = fb.z - fa.z
                val fMag = sqrt(fdx * fdx + fdy * fdy + fdz * fdz)
                if (fMag > 0.4) {
                    val dot = ((bdx * fdx + bdy * fdy + bdz * fdz) / (baseMag * fMag)).coerceIn(-1.0, 1.0)
                    maxAngle = maxOf(maxAngle, acos(dot))
                }
            }
        }

        pathCurvature = maxAngle
        val isFalling = Minecraft.getInstance().player?.deltaMovement?.y?.let { it < -0.1 } ?: false
        val effectiveAngle = if (isFalling) maxAngle * 0.5 else maxAngle
        val curveFactor = ((effectiveAngle - 0.52) / 0.75).coerceIn(0.0, 1.0)
        val safetyFactor = if (dropAhead) 1.0 else 0.0
        val adjustFactor = maxOf(deviationFactor, curveFactor, safetyFactor)
        val target = if (dropAhead) {
            PRECISION_LOOKAHEAD
        } else {
            MAX_LOOKAHEAD - (MAX_LOOKAHEAD - MIN_LOOKAHEAD) * adjustFactor
        }
        val lerpFactor = if (dropAhead) 0.45 else if (target > smoothedLookahead) 0.14 else 0.08
        smoothedLookahead += (target - smoothedLookahead) * lerpFactor
        val lowerBound = when {
            dropAhead -> PRECISION_LOOKAHEAD
            isInRecoveryMode() -> RECOVERY_MIN_LOOKAHEAD
            else -> MIN_LOOKAHEAD
        }
        return smoothedLookahead.coerceIn(lowerBound, MAX_LOOKAHEAD)
    }

    // =========================================================================
    // LOS + visible lookahead finder
    // =========================================================================

    private data class FindResult(val point: Vec3, val lookahead: Double, val visible: Boolean = true)

    private fun findVisibleLookahead(
        eye: Vec3,
        pts: List<Vec3>,
        idealLookahead: Double,
        level: net.minecraft.world.level.Level
    ): FindResult {
        val immT = minOf(pts.lastIndex.toDouble(), currentPathPosition + 0.5)
        val imm = getInterpolatedPoint(pts, immT)
        val immDx = imm.x - eye.x; val immDy = imm.y - eye.y; val immDz = imm.z - eye.z

        val inRecovery = isInRecoveryMode()
        val effectiveMin = if (inRecovery) RECOVERY_MIN_LOOKAHEAD else MIN_LOOKAHEAD
        var lookahead = if (inRecovery) idealLookahead else maxOf(idealLookahead, MIN_LOOKAHEAD)

        while (lookahead >= effectiveMin) {
            val t = minOf(pts.lastIndex.toDouble(), currentPathPosition + lookahead)
            val point = getInterpolatedPoint(pts, t)
            val dx = point.x - eye.x; val dy = point.y - eye.y; val dz = point.z - eye.z
            val horzDist = sqrt(dx * dx + dz * dz)

            if (lookahead >= MIN_LOOKAHEAD) {
                if (dy > 1.8 && horzDist < 0.8) { lookahead -= LOOKAHEAD_STEP; continue }
                val pitch = -atan2(dy, horzDist) * (180.0 / PI)
                if (pitch < MAX_UPWARD_PITCH && horzDist < 1.5) { lookahead -= LOOKAHEAD_STEP; continue }
                val immMag = sqrt(immDx * immDx + immDy * immDy + immDz * immDz)
                val tMag = sqrt(dx * dx + dy * dy + dz * dz)
                if (immMag > 0.001 && tMag > 0.001) {
                    val dotDiv = (immDx * dx + immDy * dy + immDz * dz) / (immMag * tMag)
                    val angle = acos(dotDiv.coerceIn(-1.0, 1.0)) * (180.0 / PI)
                    if (angle > MAX_DIRECTION_DIVERGENCE) { lookahead -= LOOKAHEAD_STEP; continue }
                }
            }

            return FindResult(point, lookahead, isPointVisible(eye, point, level))
        }

        val close = maxOf(RECOVERY_MIN_LOOKAHEAD, MIN_LOOKAHEAD - 0.2)
        val t = minOf(pts.lastIndex.toDouble(), currentPathPosition + close)
        val point = getInterpolatedPoint(pts, t)
        return FindResult(point, close, isPointVisible(eye, point, level))
    }

    private fun findVisibleSplineLookahead(
        eye: Vec3,
        spline: SplinePath,
        idealLookahead: Double,
        level: net.minecraft.world.level.Level
    ): FindResult {
        val imm = spline.sample(minOf(spline.totalLength, currentSplineDistance + 0.5))
        val immDx = imm.x - eye.x
        val immDy = imm.y - eye.y
        val immDz = imm.z - eye.z

        val inRecovery = isInRecoveryMode()
        val effectiveMin = if (inRecovery) RECOVERY_MIN_LOOKAHEAD else minOf(MIN_LOOKAHEAD, idealLookahead)
        var lookahead = if (inRecovery) idealLookahead else maxOf(idealLookahead, effectiveMin)

        while (lookahead >= effectiveMin) {
            val point = spline.sample(minOf(spline.totalLength, currentSplineDistance + lookahead))
            val dx = point.x - eye.x
            val dy = point.y - eye.y
            val dz = point.z - eye.z
            val horzDist = sqrt(dx * dx + dz * dz)

            if (lookahead >= MIN_LOOKAHEAD) {
                if (dy > 1.8 && horzDist < 0.8) {
                    lookahead -= LOOKAHEAD_STEP
                    continue
                }
                val pitch = -atan2(dy, horzDist) * (180.0 / PI)
                if (pitch < MAX_UPWARD_PITCH && horzDist < 1.5) {
                    lookahead -= LOOKAHEAD_STEP
                    continue
                }
                val immMag = sqrt(immDx * immDx + immDy * immDy + immDz * immDz)
                val tMag = sqrt(dx * dx + dy * dy + dz * dz)
                if (immMag > 0.001 && tMag > 0.001) {
                    val dotDiv = (immDx * dx + immDy * dy + immDz * dz) / (immMag * tMag)
                    val angle = acos(dotDiv.coerceIn(-1.0, 1.0)) * (180.0 / PI)
                    if (angle > MAX_DIRECTION_DIVERGENCE) {
                        lookahead -= LOOKAHEAD_STEP
                        continue
                    }
                }
            }

            return FindResult(point, lookahead, isPointVisible(eye, point, level))
        }

        val close = maxOf(RECOVERY_MIN_LOOKAHEAD, MIN_LOOKAHEAD - 0.2)
        val point = spline.sample(minOf(spline.totalLength, currentSplineDistance + close))
        return FindResult(point, close, isPointVisible(eye, point, level))
    }

    private fun isPointVisible(from: Vec3, to: Vec3, level: net.minecraft.world.level.Level): Boolean {
        val dx = to.x - from.x; val dy = to.y - from.y; val dz = to.z - from.z
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        if (dist < 0.2) return true

        // Cache key: offset into unsigned range so negatives don't collide with positives.
        // 26 bits for x/z (±33M), 12 bits for y (±2048) — covers all valid Minecraft coords.
        val key = ((to.x.toLong() + 33_554_432L) and 0x3FFFFFFL) or
            (((to.y.toLong() + 2048L) and 0xFFFL) shl 26) or
            (((to.z.toLong() + 33_554_432L) and 0x3FFFFFFL) shl 38)
        losCache[key]?.let { return it }

        val player = Minecraft.getInstance().player ?: run {
            losCache[key] = true
            return true
        }

        val visible = try {
            val clip = ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)
            val hit = level.clip(clip)
            hit.type == HitResult.Type.MISS || hit.location.distanceTo(from) >= dist - 0.5
        } catch (_: Exception) { true }

        losCache[key] = visible
        return visible
    }

    // =========================================================================
    // Physics prediction for path progress
    // =========================================================================

    private fun applyPredictedPathProgress(player: LocalPlayer, pts: List<Vec3>) {
        if (pts.size < 2) return
        val motion = player.deltaMovement
        val speedXZ = sqrt(motion.x * motion.x + motion.z * motion.z)
        val onGround = player.onGround()
        if (onGround && speedXZ < PREDICTION_MIN_SPEED_XZ) return

        val (predX, predZ) = if (onGround) Pair(player.x, player.z)
        else predictXZ(player, PREDICTION_TICKS)

        val projectedT = projectLookPathPositionHorizontal(predX, predZ, currentPathPosition, pts)
        if (!projectedT.isFinite() || projectedT <= currentPathPosition) return
        val maxAdvance = if (onGround) PREDICTION_MAX_ADVANCE_GROUND else PREDICTION_MAX_ADVANCE_AIR
        currentPathPosition = minOf(currentPathPosition + maxAdvance, projectedT)
    }

    private fun applyPredictedSplineProgress(player: LocalPlayer, spline: SplinePath) {
        val motion = player.deltaMovement
        val speedXZ = sqrt(motion.x * motion.x + motion.z * motion.z)
        val onGround = player.onGround()
        if (onGround && speedXZ < PREDICTION_MIN_SPEED_XZ) return

        val (predX, predZ) = if (onGround) Pair(player.x, player.z) else predictXZ(player, PREDICTION_TICKS)
        val projected = spline.project(
            point = Vec3(predX, player.eyeY, predZ),
            hintDistance = currentSplineDistance,
            searchBack = 0.5,
            searchForward = 5.5,
            horizontalOnly = true
        )
        if (!projected.distance.isFinite() || projected.distance <= currentSplineDistance) return
        val maxAdvance = if (onGround) PREDICTION_MAX_ADVANCE_GROUND else PREDICTION_MAX_ADVANCE_AIR
        currentSplineDistance = minOf(currentSplineDistance + maxAdvance, projected.distance, spline.totalLength)
    }

    private fun catchUpSplineProgress(player: LocalPlayer, spline: SplinePath, airborneOrVertical: Boolean) {
        val projection = spline.project(
            point = Vec3(player.x, player.eyeY, player.z),
            hintDistance = currentSplineDistance,
            searchBack = if (airborneOrVertical) 0.0 else 0.75,
            searchForward = 12.0,
            horizontalOnly = true
        )
        if (!projection.distance.isFinite() || projection.distance <= currentSplineDistance) return
        if (projection.distSq > CATCHUP_PROJECTION_DISTANCE * CATCHUP_PROJECTION_DISTANCE) return

        val maxAdvance = if (airborneOrVertical) CATCHUP_MAX_ADVANCE_AIR else CATCHUP_MAX_ADVANCE_GROUND
        currentSplineDistance = minOf(currentSplineDistance + maxAdvance, projection.distance, spline.totalLength)
    }

    private fun predictXZ(player: LocalPlayer, ticks: Int): Pair<Double, Double> {
        var px = player.x; var pz = player.z
        var vx = player.deltaMovement.x; var vy = player.deltaMovement.y; var vz = player.deltaMovement.z
        repeat(ticks) {
            px += vx; pz += vz
            vy = (vy - 0.08) * 0.98
            vx *= 0.91; vz *= 0.91
            if (abs(vx) < 0.002 && abs(vz) < 0.002) return Pair(px, pz)
        }
        return Pair(px, pz)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun isPathDropping(pts: List<Vec3>): Boolean {
        if (currentPathPosition >= pts.lastIndex - 2) return false
        val curr = getInterpolatedPoint(pts, currentPathPosition)
        val ahead = getInterpolatedPoint(pts, minOf(pts.lastIndex.toDouble(), currentPathPosition + 2.0))
        return curr.y - ahead.y > 0.8
    }

    private fun isSplineDropping(spline: SplinePath): Boolean {
        if (currentSplineDistance >= spline.totalLength - 2.0) return false
        val curr = spline.sample(currentSplineDistance)
        val ahead = spline.sample(minOf(spline.totalLength, currentSplineDistance + 2.0))
        return curr.y - ahead.y > 0.8
    }

    private fun hasDropAhead(spline: SplinePath, distance: Double, threshold: Double): Boolean {
        val base = spline.sample(currentSplineDistance)
        var step = 0.75
        while (step <= distance) {
            val ahead = spline.sample(minOf(spline.totalLength, currentSplineDistance + step))
            if (base.y - ahead.y >= threshold) return true
            step += 0.75
        }
        return false
    }

    private fun formatDebugLine(spline: SplinePath, projectionDistSq: Double, targetVisible: Boolean): String =
        String.format(
            Locale.US,
            "spline %.1f/%.1fm | look %.2fm | curve %.2f | proj %.2fm | %s%s",
            currentSplineDistance,
            spline.totalLength,
            currentLookaheadDistance,
            pathCurvature,
            sqrt(projectionDistSq.coerceAtLeast(0.0)),
            if (targetVisible) "visible" else "blocked",
            if (requiresPrecisionMovement) " | precision" else ""
        )

    private fun updateCurvatureFromLookPoints(pts: List<Vec3>, nearIdx: Int) {
        if (nearIdx + 2 > pts.lastIndex) return
        val a = pts[nearIdx]; val b = pts[minOf(nearIdx + 2, pts.lastIndex)]
        val bdx = b.x - a.x; val bdz = b.z - a.z
        val baseMag = sqrt(bdx * bdx + bdz * bdz)
        if (baseMag < 0.3) return
        var maxAngle = 0.0
        // Include nearby indices (2,3) in addition to far (4,6,8) so corners that are
        // only 1-3 look-points ahead are detected before the player reaches them.
        for (k in intArrayOf(2, 3, 4, 6, 8)) {
            val fi = minOf(nearIdx + k, pts.lastIndex - 1)
            if (fi >= pts.lastIndex) break
            val fa = pts[fi]; val fb = pts[minOf(fi + 2, pts.lastIndex)]
            val fdx = fb.x - fa.x; val fdz = fb.z - fa.z
            val fMag = sqrt(fdx * fdx + fdz * fdz)
            if (fMag < 0.3) continue
            val dot = (bdx * fdx + bdz * fdz) / (baseMag * fMag)
            val angle = acos(dot.coerceIn(-1.0, 1.0))
            if (angle > maxAngle) maxAngle = angle
        }
        pathCurvature = maxAngle
    }

    private fun getClosestPointOnSegment(p: Vec3, p1: Vec3, p2: Vec3): Double {
        val dx = p2.x - p1.x; val dy = p2.y - p1.y; val dz = p2.z - p1.z
        val dSq = dx * dx + dy * dy + dz * dz
        if (dSq == 0.0) return 0.0
        return (((p.x - p1.x) * dx + (p.y - p1.y) * dy + (p.z - p1.z) * dz) / dSq).coerceIn(0.0, 1.0)
    }

    private fun getClosestPointOnSegmentHorizontal(p: Vec3, p1: Vec3, p2: Vec3): Double {
        val dx = p2.x - p1.x; val dz = p2.z - p1.z
        val dSq = dx * dx + dz * dz
        if (dSq == 0.0) return 0.0
        return (((p.x - p1.x) * dx + (p.z - p1.z) * dz) / dSq).coerceIn(0.0, 1.0)
    }

    private fun closestFraction(p: Vec3, a: Vec3, b: Vec3): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val dz = b.z - a.z
        val lenSq = dx * dx + dy * dy + dz * dz
        if (lenSq <= 1e-8) return 0.0
        return (((p.x - a.x) * dx + (p.y - a.y) * dy + (p.z - a.z) * dz) / lenSq).coerceIn(0.0, 1.0)
    }

    private fun closestHorizontalFraction(p: Vec3, a: Vec3, b: Vec3): Double {
        val dx = b.x - a.x
        val dz = b.z - a.z
        val lenSq = dx * dx + dz * dz
        if (lenSq <= 1e-8) return 0.0
        return (((p.x - a.x) * dx + (p.z - a.z) * dz) / lenSq).coerceIn(0.0, 1.0)
    }

    private fun lerp(a: Vec3, b: Vec3, t: Double): Vec3 =
        Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t)

    private fun projectLookPathPositionHorizontal(x: Double, z: Double, hint: Double, pts: List<Vec3>): Double {
        if (pts.size < 2) return 0.0
        val lastSeg = pts.size - 2
        val base = hint.toInt().coerceIn(0, lastSeg)
        val start = maxOf(0, base - 8); val end = minOf(lastSeg, base + 28)
        var bestT = hint.coerceIn(0.0, pts.lastIndex.toDouble())
        var bestDistSq = Double.POSITIVE_INFINITY
        for (i in start..end) {
            val a = pts[i]; val b = pts[i + 1]
            val abx = b.x - a.x; val abz = b.z - a.z
            val lenSq = abx * abx + abz * abz
            val frac = if (lenSq <= 1e-8) 0.0 else (((x - a.x) * abx + (z - a.z) * abz) / lenSq).coerceIn(0.0, 1.0)
            val px = a.x + abx * frac; val pz = a.z + abz * frac
            val dx = x - px; val dz = z - pz
            val distSq = dx * dx + dz * dz
            if (distSq < bestDistSq) { bestDistSq = distSq; bestT = i + frac }
        }
        return bestT
    }

    private fun projectPathPositionHorizontal(x: Double, z: Double, hint: Double, nodes: List<Vec3>): Double {
        if (nodes.size < 2) return 0.0
        val lastSeg = nodes.size - 2
        val base = hint.toInt().coerceIn(0, lastSeg)
        val start = maxOf(0, base - 2); val end = minOf(lastSeg, base + 28)
        var bestT = hint.coerceIn(0.0, nodes.lastIndex.toDouble())
        var bestDistSq = Double.POSITIVE_INFINITY
        for (i in start..end) {
            val a = nodes[i]; val b = nodes[i + 1]
            val ax = a.x + 0.5; val az = a.z + 0.5; val bx = b.x + 0.5; val bz = b.z + 0.5
            val abx = bx - ax; val abz = bz - az
            val lenSq = abx * abx + abz * abz
            val frac = if (lenSq <= 1e-8) 0.0 else (((x - ax) * abx + (z - az) * abz) / lenSq).coerceIn(0.0, 1.0)
            val px = ax + abx * frac; val pz = az + abz * frac
            val dx = x - px; val dz = z - pz
            val distSq = dx * dx + dz * dz
            if (distSq < bestDistSq) { bestDistSq = distSq; bestT = i + frac }
        }
        return bestT
    }

    private fun clampLookDistance(eye: Vec3, target: Vec3): Vec3 {
        val dx = target.x - eye.x; val dy = target.y - eye.y; val dz = target.z - eye.z
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        if (dist <= MAX_LOOK_DISTANCE || dist <= 1e-6) return target
        val scale = MAX_LOOK_DISTANCE / dist
        return Vec3(eye.x + dx * scale, eye.y + dy * scale, eye.z + dz * scale)
    }

    private fun pointAtSimple(nodes: List<Vec3>, indexFloat: Double): Vec3 {
        val safe = indexFloat.coerceIn(0.0, nodes.lastIndex.toDouble())
        val idx = safe.toInt().coerceIn(0, nodes.lastIndex)
        val frac = safe - idx
        val a = nodes[idx]; val b = nodes[minOf(idx + 1, nodes.lastIndex)]
        return Vec3(
            a.x + 0.5 + (b.x - a.x) * frac,
            a.y + PLAYER_LOOK_HEIGHT + (b.y - a.y) * frac,
            a.z + 0.5 + (b.z - a.z) * frac
        )
    }

    private fun rotationTo(from: Vec3, to: Vec3): Rotation {
        val dx = to.x - from.x; val dy = to.y - from.y; val dz = to.z - from.z
        val h = sqrt(dx * dx + dz * dz)
        return Rotation(Math.toDegrees(atan2(-dx, dz)).toFloat(), -Math.toDegrees(atan2(dy, h)).toFloat())
    }

    private fun dist3d(a: Vec3, b: Vec3): Double {
        val dx = a.x - b.x; val dy = a.y - b.y; val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
