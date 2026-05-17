package org.phantom.api.pathfinder.jni

import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.phantom.api.pathfinder.jni.executor.SplineProjection
import org.phantom.api.pathfinder.jni.executor.SplinePath
import org.phantom.api.pathfinder.jni.executor.buildLookPoints
import org.phantom.api.pathfinder.jni.executor.buildSplinePath
import org.phantom.api.util.AngleUtils
import org.phantom.api.util.helper.Rotation
import java.util.Locale
import kotlin.math.*

object PathExecutorState {

    // â”€â”€ Rotation state (read by PathfinderRotationStrategy per render frame) â”€â”€
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

    // â”€â”€ Block-to-block rotation handoff â”€â”€
    // Tracks whether pathfinding currently owns PhantomRotation.blockController so we
    // only cancel it when path-rotation actually started it (not when a macro is driving).
    var blockRotationOwned: Boolean = false
    var blockRotationLastTarget: BlockPos? = null

    // â”€â”€ Path position (indexed into lookPoints) â”€â”€
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
    var shouldUsePrecisionSneak: Boolean = false
        private set
    @JvmField var disablePrecisionSneak: Boolean = false
    private var smoothedTargetInitialized: Boolean = false

    // â”€â”€ Dense look points (eye-level, curvature-offset, cached by signature) â”€â”€
    var lookPoints: List<Vec3> = emptyList()
        private set
    private var lookPointsSignature: String = ""
    private var splinePath: SplinePath? = null

    // â”€â”€ Adaptive lookahead â”€â”€
    var smoothedLookahead: Double = MAX_LOOKAHEAD
    private var lookaheadOverride: Double? = null
    private var lookaheadOverrideExpiry: Int = 0
    private var lookaheadOverrideDeadlineMs: Long = 0L

    // â”€â”€ Post-teleport resync â”€â”€
    var postTeleportResyncTicks: Int = 0
        private set

    // â”€â”€ Unseen target rollback â”€â”€
    private var unseenSinceMs: Long = 0L
    private var unseenStartPathPosition: Double = 0.0

    // â”€â”€ NonChangeRecovery tracking â”€â”€
    private var nonChangeBestPathPosition: Double? = null
    private var nonChangeTicks: Int = 0

    // â”€â”€ Per-update LOS cache â”€â”€
    private val losCache = HashMap<Long, Boolean>(64)

    // â”€â”€ Keynode override state â”€â”€
    // Current keynode (rotation pin) â€” null when the override is disengaged.
    var currentKeynodePoint: Vec3? = null
        private set
    private var keynodeBlockedTicks: Int = 0
    private var keynodeClearTicks: Int = 0
    // Tracks the spline distance the keynode currently sits at, so the same
    // keynode can be re-selected next tick without rescanning when conditions
    // haven't materially changed.
    private var keynodeSplineDistance: Double = 0.0

    // â”€â”€ Adjustable lookahead (set from PathfindingModule settings) â”€â”€
    // Base "far" distance the lookahead point sits ahead of the player on the spline.
    @JvmField var lookaheadDistanceFar: Double = 7.0
    // 0.0 = lookahead never shrinks on turns/deviation (always stays far â†’ smoother rotations,
    //       wider cornering). 1.0 = original aggressive shrink behavior.
    @JvmField var lookaheadShrinkStrength: Double = 0.05

    // â”€â”€ Adjustable rotation catch-up (how fast the aim returns to the lookahead) â”€â”€
    // Min/Max are alpha bounds: per-frame fraction of remaining yaw/pitch delta closed.
    // Higher = quicker return. The nonlinear curve between them preserves smoothness on big deltas.
    @JvmField var rotationCatchupMin: Double = 0.30
    @JvmField var rotationCatchupMax: Double = 0.70
    // Yaw "ramp scale": smaller = alpha rises toward Max faster as delta grows.
    @JvmField var rotationRampScale: Double = 16.0

    // â”€â”€ Adjustable precision-mode aggressiveness â”€â”€
    // Scales how eagerly soft triggers (curvature, off-path deviation, arrival
    // brake distance, arrival sneak distance) engage precision movement / sneak.
    // 1.0 = original behavior. 0.0 = soft triggers effectively disabled â€” only
    // hard safety (dropAhead, safety.requiresPrecision) can engage precision.
    @JvmField var precisionAggressiveness: Double = 0.05

    // â”€â”€ Adjustable velocity-scaled lookahead â”€â”€
    // Additional lookahead distance (blocks) per block/sec of player speed.
    // Sprinting is ~5.6 b/s, so at 0.15 sprint adds ~0.84 blocks of lookahead.
    // At 0.0 the lookahead doesn't react to speed (original behavior).
    @JvmField var lookaheadVelocityBoost: Double = 0.32

    // â”€â”€ Adjustable pre-emptive sprint brake â”€â”€
    // When upcoming pathCurvature >= this threshold, sprint is dropped even
    // if the native command requested it. Lower = brakes earlier before turns.
    // Set very high (e.g. > 2.0) to disable.
    @JvmField var sprintBrakeCurvature: Double = 4.0

    // â”€â”€ Hysteresis: require sustained signal before engaging sprint / sneak.
    // Sprint releases instantly the moment conditions break, but only re-engages
    // after this many ticks of stable "want to sprint" conditions â€” kills the
    // tick-to-tick flicker as horizontalCollision / curvature toggles.
    @JvmField var sprintEngageTicks: Int = 1
    // Sneak engagement also requires sustained signal so brief safety flickers
    // (e.g. crossing a 1-block edge on a thin bridge) don't tap sneak on. Quick
    // release so we don't stay sneaked when the path actually demands a drop.
    @JvmField var sneakEngageTicks: Int = 5

    // Internal hysteresis counters (advanced each applyToPlayer call).
    var sprintReadyTicks: Int = 0
    var sneakRequestTicks: Int = 0

    // â”€â”€ Yaw-alignment gate on forward input â”€â”€
    // Only press W when player's current yaw is within this many degrees of
    // the desired travel direction. Stops "walking sideways into walls" while
    // the rotation catches up after a sharp replan or teleport.
    @JvmField var forwardYawTolerance: Double = 90.0

    // â”€â”€ Global minimum gap between consecutive jumps. â”€â”€
    // Each jumpTrue(N) sets jumpSuppressTicks to max(N, jumpCooldownFloor).
    // Stops the rare double-jump where one detector resets while another fires.
    @JvmField var jumpCooldownFloor: Int = 8

    // â”€â”€ Adjustable ledge / safety scan distance â”€â”€
    // How far ahead on the spline assessSplineSafety probes for unsafe
    // footprints. Was a fixed 4.25 â€” on thin bridges that engages sneak from
    // way too far back. Tunable; intentional drops (where the spline itself
    // descends) are also excluded so we don't sneak the player into a wall
    // when the path needs to fall.
    @JvmField var edgeScanDistance: Double = 0.9

    // â”€â”€ Precision mode movement profile â”€â”€
    // When true, the precision-sneak state holds BOTH sprint and sneak ("ninja
    // sneak"): sneak keeps the player's hitbox safe near edges and the speed
    // damped, while the sprint key stays pressed for sprint-related state
    // (FOV bonus, jump-boost on landing, sprint-attack flag for combat).
    @JvmField var precisionUsesSprint: Boolean = true

    // =========================================================================
    // Constants
    // =========================================================================

    private const val MIN_LOOKAHEAD = 2.35
    // Ceiling raised 10.0 -> 14.0: SkyBlock movement speed is well above vanilla
    // (often 8-16 b/s vs ~5.6 sprint), so the speed-scaled lead needs headroom
    // to keep a roughly constant lead *time*. At vanilla speeds the target
    // never approaches the old 10.0, so this only affects high SkyBlock speeds.
    private const val MAX_LOOKAHEAD = 14.0
    private const val PRECISION_LOOKAHEAD = 1.25
    private const val PRECISION_CURVATURE = 0.34
    private const val DROP_SCAN_DISTANCE = 3.5
    private const val DROP_HEIGHT_THRESHOLD = 0.45
    // Single source of truth for "is the path going down here?". The native
    // planner only routes the spline through traversable space, so ANY descent
    // on the spline is intentional and safe to walk/fall down: it must not
    // engage sneak/precision or freeze cursor progress (those are the stair
    // shift / edge-circling / freeze symptoms) â€” it only caps the downward
    // gaze. Off-spline hazards are still caught independently by edgeDrift.
    private const val DESCENT_CLASSIFY_DISTANCE = 3.0
    private const val DESCENT_MIN_DROP = 0.4
    // A walkable staircase descends at most ~1 block down per 1 block forward.
    // A ledge/cliff drops far more vertically than it travels horizontally.
    // Only a descent steeper than this slope, or a single sudden vertical
    // step bigger than one stair, counts as a real "drop" needing precision.
    private const val STAIR_MAX_SLOPE = 1.5
    private const val STAIR_STEP_MAX_DROP = 1.25
    private const val EDGE_SCAN_DISTANCE = 4.25
    private const val EDGE_SCAN_STEP = 0.7
    private const val FOOTPRINT_RADIUS = 0.38
    private const val FOOTPRINT_DROP_DEPTH = 1.25
    private const val CORRIDOR_SCAN_DISTANCE = 2.8
    private const val CORRIDOR_SCAN_STEP = 0.45
    private const val EDGE_BIAS_STRENGTH = 0.42
    private const val EDGE_DRIFT_DISTANCE = 0.85
    private const val ARRIVAL_BRAKE_DISTANCE = 3.25
    private const val TURN_BRAKE_CURVATURE = 0.26
    private const val STRAIGHT_STABILIZE_CURVATURE = 0.08
    private const val STRAIGHT_TANGENT_LOOKAHEAD = 4.2
    private const val STRAIGHT_TARGET_BLEND = 0.78
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
    // On a near-straight path the lookahead point micro-jitters as the player
    // moves; with the single passthrough smoother that jitter became visible
    // head wobble. A wide deadzone on straights ignores sub-degree changes so
    // the camera holds steady. Turns (curvature >= 0.15) keep the tight
    // YAW_DEADZONE so responsiveness is unaffected.
    private const val STRAIGHT_YAW_DEADZONE = 0.9f
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

    // â”€â”€ Walkable keynode override â”€â”€
    // After the spline lookahead picks an aim point, we verify a clear walk-line
    // from the player's foot position to it at two probe heights (above 1-block
    // step-up so single-step terrain isn't rejected). If the walk-line is
    // blocked the aim point is treated as "past a wall" and backed off along
    // the spline until the line clears â€” the resulting point is the keynode.
    // Intermediate spline nodes between the player and the keynode are treated
    // as movement suggestions; the rotation pins to the keynode. Spacing falls
    // out of the scan: the keynode is the farthest walkable spline point.
    private const val KEYNODE_PROBE_LOW = 1.15
    private const val KEYNODE_PROBE_HIGH = 1.55
    private const val KEYNODE_BACKOFF_STEP = 0.45
    private const val KEYNODE_MIN_SPACING = 1.2
    // Sticky hysteresis. The keynode override is only allowed to *engage*
    // (back off the aim) after N consecutive ticks of blocked walk-line, and
    // only allowed to *disengage* after N consecutive ticks of clear walk-line.
    // This kills tick-to-tick flicker that the rotation smoother can't absorb.
    private const val KEYNODE_ENGAGE_HYSTERESIS = 2
    private const val KEYNODE_DISENGAGE_HYSTERESIS = 4

    // Incline-end keynote aim â€” distinguishes a sustained climb/descent from
    // flat travel and lets the camera stare straight at the first flat point
    // past the incline (when LOS to it is clear) instead of jittering with the
    // per-tick lookahead. Movement decisions are untouched; this only changes
    // the rotation target.
    private const val INCLINE_SCAN_DISTANCE = 12.0
    private const val INCLINE_SAMPLE_STEP = 0.5
    private const val INCLINE_DY_THRESHOLD = 0.05
    private const val INCLINE_MIN_RUN_LENGTH = 1.5
    private const val INCLINE_KEYNOTE_OFFSET = 0.5
    // For a DESCENDING incline the raw keynote sits at the bottom step, so the
    // eye->keynote vector pitches steeply down ("staring at the ground" the
    // whole way down the stairs). Cap that downward pitch so the gaze leads
    // down the slope the way a human descends, not at their feet.
    private const val MAX_DESCENT_KEYNOTE_PITCH = 22.0
    // Turn-tighten: lookahead is firmly capped through corners independent of
    // lookaheadShrinkStrength (that slider governs off-path deviation, not
    // cornering). Cap ramps from no-shrink at TURN_BRAKE_CURVATURE down to
    // TURN_MIN_LOOKAHEAD at TURN_HARD_CURVATURE so the aim rounds the corner
    // instead of pointing past it and clipping the inside wall.
    private const val TURN_HARD_CURVATURE = 1.0
    private const val TURN_MIN_LOOKAHEAD = 3.0

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
        shouldUsePrecisionSneak = false
        disablePrecisionSneak = false
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
        currentKeynodePoint = null
        keynodeBlockedTicks = 0
        keynodeClearTicks = 0
        keynodeSplineDistance = 0.0
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

        // Snapshot the world-space position of the cursor on the OLD spline
        // before swapping. If we just reset currentSplineDistance to 0, every
        // replan causes a visible steering stutter as the executor scans from
        // the front of the new path. Carrying the anchor through preserves
        // continuity across replans when the new path overlaps the old.
        val previousAnchor: Vec3? = splinePath?.let { old ->
            if (old.totalLength > 0.0 && currentSplineDistance > 0.0 && old.samples.size >= 2) {
                old.sample(currentSplineDistance)
            } else null
        }

        lookPointsSignature = signature
        lookPoints = if (nodes.size >= 2) buildLookPoints(nodes) else emptyList()
        splinePath = lookPoints.takeIf { it.size >= 2 }?.let(::buildSplinePath)
        lookaheadSplinePoints = splinePath?.samples ?: emptyList()

        val newSpline = splinePath
        val seedDistance = if (newSpline != null && previousAnchor != null && newSpline.totalLength > 0.0) {
            newSpline.project(
                point = previousAnchor,
                hintDistance = currentSplineDistance.coerceIn(0.0, newSpline.totalLength),
                searchBack = 6.0,
                searchForward = newSpline.totalLength,
                horizontalOnly = true,
            ).distance.coerceIn(0.0, newSpline.totalLength)
        } else {
            0.0
        }

        currentPathPosition = seedDistance
        currentSplineDistance = seedDistance
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
    // Full Phantom-style update (uses look points with LOS, adaptive lookahead, rollback)
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
        val descent = classifyDescent(spline)

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

        // An intended on-spline descent must NOT freeze cursor progress â€” that
        // freeze is what made the player stop at the lip and circle. dropAhead's
        // other (conservative lookahead/precision) uses are left intact.
        if (!isTeleportResync && (!dropAhead || descent.descending)) {
            catchUpSplineProgress(player, spline, isFalling || isJumpingHigh)
        }

        if (!isTeleportResync && (!dropAhead || descent.descending)) {
            applyPredictedSplineProgress(player, spline)
        }
        currentPathPosition = currentSplineDistance

        val adaptiveLookahead = getAdaptiveLookaheadDistance(eye, spline, dropAhead)
        currentLookaheadDistance = adaptiveLookahead
        val safety = if (level != null) assessSplineSafety(level, player, spline, projection, descent) else SafetyAssessment.NONE
        // Soft-trigger thresholds scale with precisionAggressiveness. At 0 they are
        // effectively disabled and only hard safety (dropAhead / safety.requiresPrecision)
        // can engage precision. At 1 the original thresholds apply.
        val aggro = precisionAggressiveness.coerceIn(0.0, 1.0)
        val curvatureTrigger = PRECISION_CURVATURE / aggro.coerceAtLeast(0.05)
        val deviationTriggerSq = 2.25 / aggro.coerceAtLeast(0.05)
        val arrivalBrake = ARRIVAL_BRAKE_DISTANCE * aggro
        requiresPrecisionMovement =
            dropAhead ||
                safety.requiresPrecision ||
                pathCurvature >= curvatureTrigger ||
                projection.distSq > deviationTriggerSq ||
                spline.totalLength - currentSplineDistance <= arrivalBrake
        shouldUsePrecisionSneak = safety.shouldSneak
        val remainingPathForLookahead = spline.totalLength - currentSplineDistance
        val safetyLookaheadCap = when {
            dropAhead || safety.ledgeRisk || safety.corridorUnsafe -> PRECISION_LOOKAHEAD
            pathCurvature >= TURN_BRAKE_CURVATURE -> {
                // Firm turn cap, independent of lookaheadShrinkStrength (that
                // slider governs off-path deviation, not cornering). Without a
                // real cap the aim stays ~10 blocks out and points PAST the
                // corner, so the rotation-only steering walks the chord into
                // the inside wall and stalls. Tighten progressively from
                // no-shrink at TURN_BRAKE_CURVATURE to TURN_MIN_LOOKAHEAD at
                // TURN_HARD_CURVATURE.
                val sharpness = ((pathCurvature - TURN_BRAKE_CURVATURE) /
                    (TURN_HARD_CURVATURE - TURN_BRAKE_CURVATURE)).coerceIn(0.0, 1.0)
                val turnCap = adaptiveLookahead -
                    (adaptiveLookahead - TURN_MIN_LOOKAHEAD).coerceAtLeast(0.0) * sharpness
                minOf(adaptiveLookahead, turnCap)
            }
            else -> adaptiveLookahead
        }
        val effectiveLookahead = minOf(safetyLookaheadCap, remainingPathForLookahead.coerceAtLeast(0.1))
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
            shouldUsePrecisionSneak = safety.shouldSneak
            executionDebugLine = formatDebugLine(spline, projection.distSq, false, safety)
            return
        }

        var targetPoint = findResult.point
        // Incline-end keynote aim: when an ascending/descending spline run lies
        // ahead and the eye has clear LOS to the first flat point past it,
        // pin the rotation target to that point. Gives a stable, human-looking
        // gaze across long stair/hill traversals. Curves between the player
        // and the incline-end break LOS so the spline aim is used instead;
        // when the corridor opens back up the smoother handles the transition.
        var inclineKeynoteActive = false
        if (level != null) {
            val incline = findInclineAhead(spline)
            if (incline != null && isPointVisible(eye, incline.endPoint, level)) {
                targetPoint = if (incline.descending) {
                    levelDescentKeynote(eye, incline.endPoint)
                } else {
                    incline.endPoint
                }
                inclineKeynoteActive = true
            }
        }
        // World-space distance floor on the spline aim: when the player has
        // drifted off the spline, `cursor + N` along the path can be only a
        // few world-blocks from the player, which makes the camera trail.
        // Walk further along the spline until the world-space distance to the
        // chosen sample is â‰¥ lookaheadDistanceFar (and LOS stays clear). Only
        // runs in the spline-aim branch â€” the keynote aim already has a known
        // geometry.
        if (!inclineKeynoteActive && level != null) {
            targetPoint = extendToMinWorldDistance(eye, spline, targetPoint, level, lookaheadDistanceFar)
        }
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
        if (level != null && safety.edgeDrift) {
            targetPoint = biasTargetTowardSafeFootprint(level, player, targetPoint)
        }
        if (!requiresPrecisionMovement && pathCurvature <= STRAIGHT_STABILIZE_CURVATURE) {
            targetPoint = stabilizeStraightTarget(eye, spline, targetPoint)
        }

        // Universal descent gaze cap: whenever the path goes down, bound the
        // downward pitch so it leads down the slope, not at the player's feet.
        // This used to require findInclineAhead + endpoint LOS (fragile) â€” every
        // case it missed was the "looks directly down on stairs" symptom.
        if (descent.descending) {
            targetPoint = levelDescentKeynote(eye, targetPoint)
        }

        targetPoint = clampLookDistance(eye, targetPoint)
        currentTargetPoint = targetPoint

        // Blended forward-window aim: average of direction vectors to N samples
        // along [lookahead, lookahead + window]. Falls back to single-point aim
        // when safety/precision is active or when the blend has no visible
        // samples â€” keeps the existing safe behavior in tight spots.
        val safeForBlend = !requiresPrecisionMovement && !dropAhead && targetVisible && !inclineKeynoteActive
        val blended = if (safeForBlend) blendedAimRotation(
            eye = eye,
            sample = { d -> spline.sample(minOf(spline.totalLength, d)) },
            basePos = currentSplineDistance,
            baseLookahead = findResult.lookahead,
            maxPos = spline.totalLength,
            level = level,
        ) else null
        val rawRotation = blended ?: rotationTo(eye, targetPoint)

        // Keynode override. If the walk-line from the player's foot to the
        // spline aim point is blocked at body height, the rotation is aiming
        // through (or past) a wall â€” the "walking into a wall while looking
        // sideways" symptom. Back off the aim along the spline until the
        // walk-line clears; that closer point becomes the keynode and the
        // rotation re-pins to it. When the walk-line is already clear (the
        // common case in open corridors) the override is a no-op.
        val r = if (level != null) {
            val keynode = selectKeynode(player, level, spline, targetPoint)
            if (keynode !== targetPoint && keynode !== currentTargetPoint) {
                currentTargetPoint = keynode
                rotationTo(eye, keynode)
            } else {
                rawRotation
            }
        } else rawRotation
        val remainingPath = spline.totalLength - currentSplineDistance
        val finishFactor = if (remainingPath < 4.0) maxOf(0.25, remainingPath / 4.0) else 1.0
        val isStraight = pathCurvature < 0.15
        val dynamicYawDeadzone = (if (isStraight) STRAIGHT_YAW_DEADZONE else YAW_DEADZONE) * finishFactor.toFloat()

        updateSmoothedRotationTarget(
            targetYaw = r.yaw,
            targetPitch = r.pitch,
            yawDeadzone = dynamicYawDeadzone,
            pitchDeadzone = (PITCH_DEADZONE * finishFactor).toFloat(),
        )

        val nearEnd3D = eye.distanceToSqr(spline.samples.last()) <= COMPLETION_RADIUS * COMPLETION_RADIUS &&
            currentSplineDistance >= spline.totalLength - 2.0
        if (nearEnd3D || currentSplineDistance >= spline.totalLength - 0.25) {
            currentSplineDistance = spline.totalLength
            currentPathPosition = currentSplineDistance
        }
        executionDebugLine = formatDebugLine(spline, projection.distSq, targetVisible, safety)
    }

    private fun updateFull(player: LocalPlayer, pts: List<Vec3>) {
        val level = Minecraft.getInstance().level
        val eye = player.eyePosition
        val motionY = player.deltaMovement.y
        val isFalling = motionY < -0.4 || isPathDropping(pts)
        val pathAnchor = getInterpolatedPoint(pts, currentPathPosition)
        val isJumpingHigh = motionY > 0.1 || player.y - pathAnchor.y > 2.0

        // â”€â”€ Project player onto closest path segment â”€â”€
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

        // â”€â”€ Curvature + adaptive lookahead â”€â”€
        val nearIdx = currentPathPosition.toInt().coerceIn(0, pts.lastIndex)
        updateCurvatureFromLookPoints(pts, nearIdx)
        val adaptiveLookahead = getAdaptiveLookahead(eye, pts)

        // â”€â”€ Find visible lookahead point (with LOS check) â”€â”€
        // Cap lookahead to remaining path so the look target never goes past the final node.
        val remainingPathForLookahead = pts.lastIndex.toDouble() - currentPathPosition
        val effectiveLookahead = minOf(adaptiveLookahead, remainingPathForLookahead.coerceAtLeast(0.1))
        var findResult = if (level != null)
            findVisibleLookahead(eye, pts, effectiveLookahead, level)
        else
            FindResult(getInterpolatedPoint(pts, minOf(pts.lastIndex.toDouble(), currentPathPosition + effectiveLookahead)), effectiveLookahead)

        val effectiveMin = if (isInRecoveryMode()) RECOVERY_MIN_LOOKAHEAD else MIN_LOOKAHEAD
        var targetVisible = findResult.visible && (level == null || isPointVisible(eye, findResult.point, level))

        // â”€â”€ Unseen rollback: if no visible target for 600ms, rewind path position â”€â”€
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

        // â”€â”€ Pitch clamping for steep upward angles â”€â”€
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

        // â”€â”€ Clamp look distance â”€â”€
        val dx = targetPoint.x - eye.x
        val dy = targetPoint.y - eye.y
        val dz = targetPoint.z - eye.z
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        if (dist > MAX_LOOK_DISTANCE) {
            val scale = MAX_LOOK_DISTANCE / dist
            targetPoint = Vec3(eye.x + dx * scale, eye.y + dy * scale, eye.z + dz * scale)
        }

        currentTargetPoint = targetPoint

        // â”€â”€ Compute rotation target â”€â”€
        // Try the blended forward-window aim first (averages direction across the
        // next few blocks instead of locking onto a single sample). Falls back to
        // the single-point aim when the blend can't get a clean read.
        val blended = blendedAimRotation(
            eye = eye,
            sample = { d -> getInterpolatedPoint(pts, minOf(pts.lastIndex.toDouble(), d)) },
            basePos = currentPathPosition,
            baseLookahead = findResult.lookahead,
            maxPos = pts.lastIndex.toDouble(),
            level = level,
        )
        val targetYaw: Float
        val targetPitch: Float
        if (blended != null) {
            targetYaw = blended.yaw
            targetPitch = blended.pitch
        } else {
            val tdx = targetPoint.x - eye.x
            val tdy = targetPoint.y - eye.y
            val tdz = targetPoint.z - eye.z
            val tHorz = sqrt(tdx * tdx + tdz * tdz)
            targetYaw = Math.toDegrees(atan2(-tdx, tdz)).toFloat()
            targetPitch = -Math.toDegrees(atan2(tdy, tHorz)).toFloat()
        }

        // â”€â”€ Feed look-target directly to the PD controller â”€â”€
        // The PD controller in PathfinderRotationStrategy provides all frame-level smoothing via
        // its acceleration/friction physics. Tick-level exponential decay caused stutter:
        // the PD would sprint to each 10%-step then idle until the next tick (20 Hz micro-jitter).
        val lastIndex = pts.lastIndex
        val remainingPath = lastIndex - currentPathPosition
        val finishFactor = if (remainingPath < 4.0) maxOf(0.25, remainingPath / 4.0) else 1.0
        val isStraight = pathCurvature < 0.15
        val dynamicYawDeadzone = (if (isStraight) STRAIGHT_YAW_DEADZONE else YAW_DEADZONE) * finishFactor.toFloat()

        updateSmoothedRotationTarget(
            targetYaw = targetYaw,
            targetPitch = targetPitch,
            yawDeadzone = dynamicYawDeadzone,
            pitchDeadzone = (PITCH_DEADZONE * finishFactor).toFloat(),
        )

        // â”€â”€ Completion â”€â”€
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
            shouldUsePrecisionSneak = false
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
    ) {
        if (!smoothedTargetInitialized) {
            rawTargetYaw = AngleUtils.normalizeAngle(targetYaw)
            rawTargetPitch = targetPitch.coerceIn(-89.9f, 89.9f)
            smoothedTargetInitialized = true
            return
        }

        // Single-smoother model: this layer is now a deadzone-gated passthrough.
        // The only rotation easing for pathing happens in BlockRotationController's
        // exp-smoother (driven by drivePathRotation). Blending here too produced
        // double-lag (camera trailing, undershooting turns). The deadzone still
        // swallows sub-threshold lookahead jitter so we don't feed noise downstream.
        val yawDelta = AngleUtils.getRotationDelta(rawTargetYaw, targetYaw)
        if (abs(yawDelta) > yawDeadzone) {
            rawTargetYaw = AngleUtils.normalizeAngle(targetYaw)
        }

        val pitchDelta = targetPitch - rawTargetPitch
        if (abs(pitchDelta) > pitchDeadzone) {
            rawTargetPitch = targetPitch.coerceIn(-89.9f, 89.9f)
        }
    }

    private fun nonlinearBlend(delta: Double, scale: Double): Float {
        val mn = rotationCatchupMin.toFloat()
        val mx = rotationCatchupMax.coerceAtLeast(rotationCatchupMin).toFloat()
        val t = 1.0 - exp(-delta / scale.coerceAtLeast(0.5))
        return (mn + (mx - mn) * t)
            .toFloat()
            .coerceIn(mn, mx)
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
        // Setting-controlled shrink: 0.0 â†’ never shrink (always stay far), 1.0 â†’ original.
        val adjustFactor = maxOf(deviationFactor, curveFactor) * lookaheadShrinkStrength
        val maxL = lookaheadDistanceFar
        val target = maxL - (maxL - MIN_LOOKAHEAD) * adjustFactor + velocityLookaheadBoost()
        val lerpFactor = if (target > smoothedLookahead) 0.1 else 0.05
        smoothedLookahead += (target - smoothedLookahead) * lerpFactor
        return smoothedLookahead
    }

    /**
     * Extra lookahead distance proportional to the player's horizontal speed,
     * so the aim leads by a roughly constant *time* (~lookaheadVelocityBoost
     * seconds) regardless of how fast SkyBlock movement is. The old cap was
     * `lookaheadDistanceFar * 0.5` (= 3.5 b), a vanilla-sprint-derived value
     * that saturated by ~11 b/s â€” at SkyBlock speeds the lead distance stopped
     * growing while speed kept rising, so the lead time collapsed and the aim
     * turned late (slight overshoot / wide corners). Cap now scales with the
     * real lookahead ceiling; the curvature/turn caps still rein this in
     * through corners, so the extra reach only applies on straights.
     */
    private fun velocityLookaheadBoost(): Double {
        if (lookaheadVelocityBoost <= 0.0) return 0.0
        val motion = Minecraft.getInstance().player?.deltaMovement ?: return 0.0
        val speedXZ = sqrt(motion.x * motion.x + motion.z * motion.z) * 20.0 // blocks/sec
        if (speedXZ < 0.5) return 0.0
        val boost = speedXZ * lookaheadVelocityBoost
        return boost.coerceAtMost(MAX_LOOKAHEAD - MIN_LOOKAHEAD)
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
        // dropAhead still forces precision (safety); curve/deviation shrink is gated by setting.
        val adjustFactor = maxOf(
            maxOf(deviationFactor, curveFactor) * lookaheadShrinkStrength,
            safetyFactor
        )
        val maxL = lookaheadDistanceFar
        val target = if (dropAhead) {
            PRECISION_LOOKAHEAD
        } else {
            maxL - (maxL - MIN_LOOKAHEAD) * adjustFactor + velocityLookaheadBoost()
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

    // =========================================================================
    // Incline-end keynote aim
    // =========================================================================

    private data class InclineSegment(val endPoint: Vec3, val descending: Boolean)

    /**
     * Scans the spline ahead of the cursor for the next sustained run of
     * ascending or descending samples and returns the first flat-ish point
     * just past the run end. Used as a stable rotation target for the camera
     * while the player walks a stair / hill â€” keeps the gaze on the
     * incline-end keynote instead of jittering with the per-tick lookahead.
     *
     * Returns null when no incline of [INCLINE_MIN_RUN_LENGTH] blocks of
     * horizontal extent is found within [INCLINE_SCAN_DISTANCE].
     */
    private fun findInclineAhead(spline: SplinePath): InclineSegment? {
        val maxScan = minOf(INCLINE_SCAN_DISTANCE, spline.totalLength - currentSplineDistance)
        if (maxScan < INCLINE_MIN_RUN_LENGTH) return null
        val step = INCLINE_SAMPLE_STEP
        var prev = spline.sample(currentSplineDistance)
        var runDir = 0
        var runStartDist = 0.0
        var runEndDist = 0.0
        var d = step
        while (d <= maxScan) {
            val sample = spline.sample(currentSplineDistance + d)
            val dy = sample.y - prev.y
            val dir = when {
                dy > INCLINE_DY_THRESHOLD -> 1
                dy < -INCLINE_DY_THRESHOLD -> -1
                else -> 0
            }
            if (runDir == 0) {
                if (dir != 0) {
                    runDir = dir
                    runStartDist = d - step
                    runEndDist = d
                }
            } else if (dir == runDir) {
                runEndDist = d
            } else {
                break
            }
            prev = sample
            d += step
        }
        if (runDir == 0) return null
        if (runEndDist - runStartDist < INCLINE_MIN_RUN_LENGTH) return null
        val keynoteDist = (runEndDist + INCLINE_KEYNOTE_OFFSET).coerceAtMost(maxScan)
        val keynotePoint = spline.sample(currentSplineDistance + keynoteDist)
        return InclineSegment(keynotePoint, runDir == -1)
    }

    /**
     * The raw descending keynote sits at the bottom step of the staircase, so
     * the eye->keynote vector pitches steeply down and the camera stares at the
     * ground for the entire descent. Raise the aim point's Y so the downward
     * pitch is bounded to [MAX_DESCENT_KEYNOTE_PITCH] â€” the gaze leads down the
     * slope the way a human walks down stairs instead of at their feet. Yaw is
     * untouched (x/z preserved); only the vertical component is lifted.
     */
    private fun levelDescentKeynote(eye: Vec3, endPoint: Vec3): Vec3 {
        val dx = endPoint.x - eye.x
        val dz = endPoint.z - eye.z
        val horz = sqrt(dx * dx + dz * dz)
        if (horz < 0.5) return endPoint
        val dy = endPoint.y - eye.y
        if (dy >= 0.0) return endPoint
        val pitchDown = atan2(-dy, horz) * (180.0 / PI)
        if (pitchDown <= MAX_DESCENT_KEYNOTE_PITCH) return endPoint
        val maxDrop = horz * tan(MAX_DESCENT_KEYNOTE_PITCH * (PI / 180.0))
        return Vec3(endPoint.x, eye.y - maxDrop, endPoint.z)
    }

    /**
     * Walks the spline forward from the cursor until the world-space distance
     * from [eye] to the sampled point is at least [minDist], OR the spline
     * ends, OR the line of sight breaks. Used to compensate for the player
     * drifting off the path: when the spline cursor is the nearest projection
     * of a drifted player, `cursor + N along the spline` can be only 2-3
     * world-blocks away from the player. This walks until the chosen aim is
     * far enough in world space that the camera stops trailing.
     *
     * Returns [current] unchanged when it is already far enough or no
     * far-enough LOS-clear sample exists.
     */
    private fun extendToMinWorldDistance(
        eye: Vec3,
        spline: SplinePath,
        current: Vec3,
        level: Level,
        minDist: Double,
    ): Vec3 {
        val cdx = current.x - eye.x
        val cdy = current.y - eye.y
        val cdz = current.z - eye.z
        if (sqrt(cdx * cdx + cdy * cdy + cdz * cdz) >= minDist) return current
        val step = 0.5
        val minDistSq = minDist * minDist
        var probe = currentSplineDistance + step
        while (probe <= spline.totalLength) {
            val sample = spline.sample(probe)
            val dx = sample.x - eye.x
            val dy = sample.y - eye.y
            val dz = sample.z - eye.z
            if (dx * dx + dy * dy + dz * dz >= minDistSq) {
                return if (isPointVisible(eye, sample, level)) sample else current
            }
            probe += step
        }
        return current
    }

    /**
     * Checks whether a horizontal straight line from [fromFoot] to [toFoot] is
     * walkable â€” i.e. not crossed by any solid block at torso/head height.
     * Probe heights are above 1-block step-up so single-step terrain doesn't
     * trigger a false block. The vertical (foot Y) of the probes follows the
     * player's foot height, not the candidate's â€” we're testing what stops the
     * *body* moving in that direction, not what blocks an arbitrary aim ray.
     */
    private fun isWalkLineClear(
        level: Level,
        player: LocalPlayer,
        fromFoot: Vec3,
        toFoot: Vec3,
    ): Boolean {
        val dx = toFoot.x - fromFoot.x
        val dz = toFoot.z - fromFoot.z
        if (dx * dx + dz * dz < 0.04) return true
        val probeOffsets = doubleArrayOf(KEYNODE_PROBE_LOW, KEYNODE_PROBE_HIGH)
        for (off in probeOffsets) {
            val from = Vec3(fromFoot.x, fromFoot.y + off, fromFoot.z)
            val to = Vec3(toFoot.x, fromFoot.y + off, toFoot.z)
            val clip = try {
                level.clip(ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
            } catch (_: Exception) { return true }
            if (clip.type == HitResult.Type.BLOCK) return false
        }
        return true
    }

    /**
     * Walkable keynode selector. If the walk-line from the player's foot to
     * [proposedAim] is clear at torso/head height, returns [proposedAim] (no
     * override needed â€” the existing spline lookahead is already valid). If
     * blocked, backs off along the spline toward the player until the walk-line
     * clears, and returns that point as the keynode. Engage/disengage
     * hysteresis prevents the override from flickering tick-to-tick.
     *
     * Spacing: the backoff scan uses [KEYNODE_BACKOFF_STEP] granularity and
     * stops [KEYNODE_MIN_SPACING] blocks ahead of the player at minimum, so
     * the keynode is never placed *behind* or *on top of* the player.
     */
    private fun selectKeynode(
        player: LocalPlayer,
        level: Level,
        spline: SplinePath,
        proposedAim: Vec3,
    ): Vec3 {
        val playerFoot = Vec3(player.x, player.y, player.z)
        val proposedFoot = Vec3(proposedAim.x, player.y, proposedAim.z)

        // Cheap-pass: if the proposed aim is already walkable, just take it.
        // The keynode override only engages after KEYNODE_ENGAGE_HYSTERESIS
        // ticks of sustained blockage â€” short flashes from a single-tile lip
        // or a moving entity shouldn't pull the rotation around.
        val proposedClear = isWalkLineClear(level, player, playerFoot, proposedFoot)
        if (proposedClear) {
            keynodeBlockedTicks = 0
            if (currentKeynodePoint != null) {
                keynodeClearTicks++
                if (keynodeClearTicks >= KEYNODE_DISENGAGE_HYSTERESIS) {
                    currentKeynodePoint = null
                    keynodeSplineDistance = 0.0
                }
            }
            return currentKeynodePoint ?: proposedAim
        }
        keynodeClearTicks = 0
        keynodeBlockedTicks++
        if (keynodeBlockedTicks < KEYNODE_ENGAGE_HYSTERESIS) {
            return currentKeynodePoint ?: proposedAim
        }

        // Engaged â€” back off along the spline until the walk-line clears.
        val minDist = currentSplineDistance + KEYNODE_MIN_SPACING
        if (minDist > spline.totalLength) {
            currentKeynodePoint = null
            keynodeSplineDistance = 0.0
            return proposedAim
        }
        val proposedDist = run {
            val proj = spline.project(
                point = proposedAim,
                hintDistance = currentSplineDistance,
                searchBack = 0.0,
                searchForward = spline.totalLength,
                horizontalOnly = true,
            )
            proj.distance.coerceIn(minDist, spline.totalLength)
        }
        var probe = proposedDist
        while (probe >= minDist) {
            val candidate = spline.sample(probe)
            val candidateFoot = Vec3(candidate.x, player.y, candidate.z)
            if (isWalkLineClear(level, player, playerFoot, candidateFoot)) {
                currentKeynodePoint = candidate
                keynodeSplineDistance = probe
                return candidate
            }
            probe -= KEYNODE_BACKOFF_STEP
        }
        // Nothing along the spline is walkable from here. Hold the previous
        // keynode if we had one (rotation freezes rather than flailing); the
        // movement layer's stuck-detector handles the actual recovery.
        return currentKeynodePoint ?: proposedAim
    }

    private fun isPointVisible(from: Vec3, to: Vec3, level: net.minecraft.world.level.Level): Boolean {
        val dx = to.x - from.x; val dy = to.y - from.y; val dz = to.z - from.z
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        if (dist < 0.2) return true

        // Cache key: offset into unsigned range so negatives don't collide with positives.
        // 26 bits for x/z (Â±33M), 12 bits for y (Â±2048) â€” covers all valid Minecraft coords.
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
        var prev = base
        while (step <= distance) {
            val ahead = spline.sample(minOf(spline.totalLength, currentSplineDistance + step))
            val totalDrop = base.y - ahead.y
            if (totalDrop >= threshold) {
                // Walkable staircase vs. true fall: a stair run descends at a
                // shallow slope and never drops more than ~1 block between two
                // close samples. A ledge plunges far more vertically than it
                // travels forward. Only the latter should force precision/sneak.
                val dx = ahead.x - base.x
                val dz = ahead.z - base.z
                val horiz = sqrt(dx * dx + dz * dz)
                val slope = if (horiz > 1.0e-3) totalDrop / horiz else Double.MAX_VALUE
                val suddenStep = (prev.y - ahead.y) >= STAIR_STEP_MAX_DROP
                if (slope > STAIR_MAX_SLOPE || suddenStep) return true
            }
            prev = ahead
            step += 0.75
        }
        return false
    }

    private data class DescentInfo(
        /** The spline itself drops over the next horizon (planner-intended). */
        val descending: Boolean,
        /** Total vertical drop over the horizon, blocks (>= 0). */
        val drop: Double,
    )

    /**
     * The one classifier for "is the path descending here?". Spline-only and
     * planner-trusted: the native A* never routes through space it can't
     * traverse, so a descent on the spline is always an intended, safe
     * walk-down or fall. Pitch-cap, sneak suppression, and cursor progression
     * all read this instead of the old web of disagreeing heuristics.
     */
    private fun classifyDescent(spline: SplinePath): DescentInfo {
        val horizon = minOf(DESCENT_CLASSIFY_DISTANCE, spline.totalLength - currentSplineDistance)
        if (horizon <= 0.0) return DescentInfo(false, 0.0)
        val base = spline.sample(currentSplineDistance)
        val ahead = spline.sample(currentSplineDistance + horizon)
        val drop = base.y - ahead.y
        return DescentInfo(drop > DESCENT_MIN_DROP, drop.coerceAtLeast(0.0))
    }

    private data class SafetyAssessment(
        val ledgeRisk: Boolean,
        val corridorUnsafe: Boolean,
        val edgeDrift: Boolean,
        val arrival: Boolean,
        val progressBlockingDrop: Boolean,
    ) {
        val requiresPrecision: Boolean get() = ledgeRisk || corridorUnsafe || edgeDrift
        val shouldSneak: Boolean get() = progressBlockingDrop

        companion object {
            val NONE = SafetyAssessment(false, false, false, false, false)
        }
    }

    private fun assessSplineSafety(
        level: Level,
        player: LocalPlayer,
        spline: SplinePath,
        projection: SplineProjection,
        descent: DescentInfo
    ): SafetyAssessment {
        val remaining = spline.totalLength - currentSplineDistance
        val arrival = remaining <= ARRIVAL_BRAKE_DISTANCE
        val edgeDrift = projection.distSq >= EDGE_DRIFT_DISTANCE * EDGE_DRIFT_DISTANCE &&
            !isFootprintSafe(level, player.x, player.y, player.z)

        // Single source of truth: if the spline itself is descending here, the
        // planner routed it, so it's an intended walk-down/fall â€” the ledge and
        // corridor footprint scans (which sample the smoothed spline floating
        // above the treads and false-positive on every staircase) are skipped
        // entirely. Off-spline danger is still caught by edgeDrift above. This
        // replaces the old coarse per-sample FOOTPRINT_DROP_DEPTH bail.
        var ledgeRisk = false
        var corridorUnsafe = false
        if (!descent.descending) {
            var distance = 0.0
            val effectiveEdgeScan = edgeScanDistance.coerceAtLeast(0.5)
            while (distance <= effectiveEdgeScan && currentSplineDistance + distance <= spline.totalLength) {
                val sample = spline.sample(currentSplineDistance + distance)
                if (!isSplineFootprintSafe(level, sample)) {
                    ledgeRisk = true
                    break
                }
                distance += EDGE_SCAN_STEP
            }

            distance = CORRIDOR_SCAN_STEP
            while (distance <= CORRIDOR_SCAN_DISTANCE && currentSplineDistance + distance <= spline.totalLength) {
                val sample = spline.sample(currentSplineDistance + distance)
                if (!isSplineFootprintSafe(level, sample)) {
                    corridorUnsafe = true
                    break
                }
                distance += CORRIDOR_SCAN_STEP
            }
        }

        return SafetyAssessment(
            ledgeRisk = ledgeRisk,
            corridorUnsafe = corridorUnsafe,
            edgeDrift = edgeDrift,
            arrival = arrival,
            progressBlockingDrop = ledgeRisk || corridorUnsafe || edgeDrift
        )
    }

    private fun isSplineFootprintSafe(level: Level, point: Vec3): Boolean =
        isFootprintSafe(level, point.x, point.y - PLAYER_LOOK_HEIGHT, point.z)

    private fun isFootprintSafe(level: Level, x: Double, feetY: Double, z: Double): Boolean {
        // Mandatory: centre + 4 cardinal samples at FOOTPRINT_RADIUS. These lie
        // within the player's real ~0.3 collision half-width, so if any of them
        // lacks support the player would actually fall.
        val mandatory = doubleArrayOf(
            0.0, 0.0,
            FOOTPRINT_RADIUS, 0.0,
            -FOOTPRINT_RADIUS, 0.0,
            0.0, FOOTPRINT_RADIUS,
            0.0, -FOOTPRINT_RADIUS
        )
        var i = 0
        while (i < mandatory.size) {
            if (!hasNearbySupport(level, x + mandatory[i], feetY, z + mandatory[i + 1])) return false
            i += 2
        }

        // Advisory: the 4 diagonal corners sit ~0.54 blocks out — beyond the
        // player's collision box. Requiring them tripped a phantom ledge on
        // every 1-wide path / doorway the native planner deliberately chose.
        // A genuine cliff also fails the mandatory ring, so dropping the corner
        // requirement keeps real edge detection while letting tight routes pass.
        return true
    }

    private fun hasNearbySupport(level: Level, x: Double, feetY: Double, z: Double): Boolean {
        val blockX = floor(x).toInt()
        val blockZ = floor(z).toInt()
        val feetBlockY = floor(feetY + 0.05).toInt()
        val minTop = feetY - FOOTPRINT_DROP_DEPTH

        for (y in feetBlockY - 1 downTo feetBlockY - 3) {
            val pos = BlockPos(blockX, y, blockZ)
            val top = collisionTop(level, pos) ?: continue
            if (top >= minTop) return true
        }
        return false
    }

    private fun collisionTop(level: Level, pos: BlockPos): Double? {
        val state = level.getBlockState(pos)
        if (state.isAir) return null
        val shape = state.getCollisionShape(level, pos)
        if (shape.isEmpty) return null
        return pos.y + shape.bounds().maxY
    }

    private fun biasTargetTowardSafeFootprint(level: Level, player: LocalPlayer, target: Vec3): Vec3 {
        val candidates = arrayOf(
            Vec3(EDGE_BIAS_STRENGTH, 0.0, 0.0),
            Vec3(-EDGE_BIAS_STRENGTH, 0.0, 0.0),
            Vec3(0.0, 0.0, EDGE_BIAS_STRENGTH),
            Vec3(0.0, 0.0, -EDGE_BIAS_STRENGTH),
            Vec3(EDGE_BIAS_STRENGTH, 0.0, EDGE_BIAS_STRENGTH),
            Vec3(EDGE_BIAS_STRENGTH, 0.0, -EDGE_BIAS_STRENGTH),
            Vec3(-EDGE_BIAS_STRENGTH, 0.0, EDGE_BIAS_STRENGTH),
            Vec3(-EDGE_BIAS_STRENGTH, 0.0, -EDGE_BIAS_STRENGTH)
        )

        var best = target
        var bestScore = Double.POSITIVE_INFINITY
        for (offset in candidates) {
            val candidate = Vec3(player.x + offset.x, target.y, player.z + offset.z)
            if (!isFootprintSafe(level, candidate.x, player.y, candidate.z)) continue
            val score = candidate.distanceToSqr(target)
            if (score < bestScore) {
                bestScore = score
                best = Vec3((target.x + candidate.x) * 0.5, target.y, (target.z + candidate.z) * 0.5)
            }
        }
        return best
    }

    private fun stabilizeStraightTarget(eye: Vec3, spline: SplinePath, target: Vec3): Vec3 {
        val from = spline.sample(currentSplineDistance)
        val to = spline.sample(minOf(spline.totalLength, currentSplineDistance + STRAIGHT_TANGENT_LOOKAHEAD))
        val dx = to.x - from.x
        val dz = to.z - from.z
        val mag = sqrt(dx * dx + dz * dz)
        if (mag < 0.05) return target

        val tangentTarget = Vec3(
            eye.x + (dx / mag) * MAX_LOOK_DISTANCE,
            target.y,
            eye.z + (dz / mag) * MAX_LOOK_DISTANCE
        )
        return Vec3(
            target.x + (tangentTarget.x - target.x) * STRAIGHT_TARGET_BLEND,
            target.y,
            target.z + (tangentTarget.z - target.z) * STRAIGHT_TARGET_BLEND
        )
    }

    private fun formatDebugLine(
        spline: SplinePath,
        projectionDistSq: Double,
        targetVisible: Boolean,
        safety: SafetyAssessment = SafetyAssessment.NONE
    ): String =
        String.format(
            Locale.US,
            "spline %.1f/%.1fm | look %.2fm | curve %.2f | proj %.2fm | %s%s%s%s%s",
            currentSplineDistance,
            spline.totalLength,
            currentLookaheadDistance,
            pathCurvature,
            sqrt(projectionDistSq.coerceAtLeast(0.0)),
            if (targetVisible) "visible" else "blocked",
            if (requiresPrecisionMovement) " | precision" else "",
            if (safety.ledgeRisk) " | edge" else "",
            if (safety.corridorUnsafe) " | corridor" else "",
            if (safety.progressBlockingDrop && shouldUsePrecisionSneak) " | sneak-drop" else "",
            if (safety.arrival) " | brake" else ""
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

    /**
     * Builds a rotation by averaging unit direction vectors from the eye to a
     * window of forward sample points along the spline/path. This produces a
     * "look toward where I'm going over the next few blocks" aim rather than
     * locking onto one lookahead sample. Samples that aren't visible (LOS
     * blocked) are dropped so the average doesn't phantom-aim through walls.
     * Near samples carry slightly more weight than the farthest so the camera
     * tracks the corridor instead of overshooting into the next turn.
     */
    private fun blendedAimRotation(
        eye: Vec3,
        sample: (Double) -> Vec3,
        basePos: Double,
        baseLookahead: Double,
        maxPos: Double,
        level: net.minecraft.world.level.Level?,
        window: Double = 8.0,
        samples: Int = 7,
        horizonBoost: Double = 2.5,
    ): Rotation? {
        // Sample a window that extends well past the immediate lookahead point.
        // The far end of the window gets the heaviest weight so the rotation is
        // anchored to a "horizon" point â€” where the path will be several blocks
        // from now â€” instead of the closer-in lookahead sample. The near samples
        // are still in the average to keep the camera honest if the horizon is
        // occluded by a wall; in that case LOS drops them and the visible near
        // samples take over so the bot doesn't aim through geometry.
        var sx = 0.0; var sy = 0.0; var sz = 0.0; var wsum = 0.0
        var horizonVisible = false
        for (i in 0 until samples) {
            val t = if (samples == 1) 0.0 else i.toDouble() / (samples - 1).toDouble()
            val d = baseLookahead + window * t
            val pos = (basePos + d).coerceIn(0.0, maxPos)
            val p = sample(pos)
            val dx = p.x - eye.x; val dy = p.y - eye.y; val dz = p.z - eye.z
            val mag = sqrt(dx * dx + dy * dy + dz * dz)
            if (mag < 1e-3) continue
            if (level != null && !isPointVisible(eye, p, level)) continue
            // Front-biased ramp: w grows from 0.4 at t=0 to (1.0 + horizonBoost) at t=1.
            // The horizon sample dominates when visible; near samples backstop it.
            val w = 0.4 + (0.6 + horizonBoost) * t
            val inv = w / mag
            sx += dx * inv; sy += dy * inv; sz += dz * inv; wsum += w
            if (t >= 0.999) horizonVisible = true
        }
        if (wsum <= 0.0) return null
        // If the horizon was occluded, also pull a small additional weight from
        // the deepest visible sample so the aim still leans forward instead of
        // collapsing onto the near point.
        @Suppress("UNUSED_VARIABLE") val _hv = horizonVisible
        val nx = sx / wsum; val ny = sy / wsum; val nz = sz / wsum
        val h = sqrt(nx * nx + nz * nz)
        if (h < 1e-4 && abs(ny) < 1e-4) return null
        return Rotation(
            Math.toDegrees(atan2(-nx, nz)).toFloat(),
            -Math.toDegrees(atan2(ny, h)).toFloat()
        )
    }

}
