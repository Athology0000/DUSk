package org.phantom.internal.pathfinding

import java.util.Locale
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import org.phantom.api.hud.HudAnchor
import org.phantom.api.hud.hudElement
import org.phantom.api.event.EventBus
import org.phantom.api.event.annotation.SubscribeEvent
import org.phantom.api.event.impl.client.MouseEvent
import org.phantom.api.event.impl.client.TickEvent
import org.phantom.api.event.impl.render.WorldRenderEvent
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.Vec3
import org.phantom.internal.pathfinding.OverlayRenderEngine
import org.phantom.internal.pathfinding.PathSplineRenderer
import org.phantom.api.pathfinder.PathFailReason
import org.phantom.api.pathfinder.PathOwner
import org.phantom.api.pathfinder.PathRequest
import org.phantom.api.pathfinder.PathService
import org.phantom.api.pathfinder.jni.MovementProfile
import org.phantom.api.module.setting.impl.SliderSetting
import org.phantom.api.module.Module
import org.phantom.api.module.setting.impl.ActionSetting
import org.phantom.api.module.setting.impl.CheckboxSetting
import org.phantom.api.module.setting.impl.InfoSetting
import org.phantom.api.module.setting.impl.InfoType
import org.phantom.api.module.setting.impl.ModeSetting
import org.phantom.api.module.setting.impl.TextSetting
import org.phantom.api.pathfinder.jni.ActionType
import org.phantom.api.pathfinder.jni.NativePathfinder
import org.phantom.api.pathfinder.jni.PathHazards
import org.phantom.api.pathfinder.jni.PathExecutorState
import org.phantom.api.pathfinder.jni.PathStatus
import org.phantom.api.pathfinder.minecraft.MinecraftPathingRules
import org.phantom.api.ui.theme.ThemeManager
import org.phantom.api.util.ChatUtils
import org.phantom.api.util.player.MovementManager
import org.phantom.api.util.ui.NVGRenderer
import org.phantom.internal.etherwarp.EtherwarpLogic
import kotlin.math.sqrt
import kotlin.math.max

private data class RouteWaypoint(val x: Double, val y: Double, val z: Double)
private data class KillWaypoint(val x: Double, val y: Double, val z: Double)

object PathfindingModule : Module("Pathfinding") {

  private val mc: Minecraft = Minecraft.getInstance()
  private var moduleOwnsPath = false
  private var cachedSpline: PathSplineRenderer.SplineResult? = null
  private var lastNodesRef: List<Vec3>? = null

  private val localRouteWaypoints = mutableListOf<RouteWaypoint>()
  private val localKillWaypoints  = mutableListOf<KillWaypoint>()

  private enum class PatrolState { IDLE, NAVIGATING, AT_KILL }

  private var patrolState: PatrolState = PatrolState.IDLE
  private var currentKillWp: KillWaypoint? = null
  private var dwellTicksRemaining: Int = 0
  // Sub-route waypoints for the current kill navigation leg - visited one at a time.
  private var subRouteWaypoints: List<Triple<Double, Double, Double>> = emptyList()
  private var subRouteIndex: Int = 0

  private val COLOR_PATH_NORMAL = OverlayRenderEngine.Color(0, 200, 255, 220)   // cyan
  private val COLOR_PATH_AOTV   = OverlayRenderEngine.Color(255, 160, 0, 220)   // orange
  private val COLOR_HEAD_RAY    = OverlayRenderEngine.Color(255, 255, 255, 200)  // white
  private val COLOR_JUMP_FILL   = OverlayRenderEngine.Color(0, 200, 255, 70)
  private val COLOR_JUMP_OUTLINE = OverlayRenderEngine.Color(0, 200, 255, 255)
  private val COLOR_JUMP_AIR_OUTLINE = OverlayRenderEngine.Color(140, 210, 255, 220)
  private val COLOR_LOOKAHEAD_SPLINE = OverlayRenderEngine.Color(210, 55, 255, 230)
  private val COLOR_LOOKAHEAD_POINT = OverlayRenderEngine.Color(0, 240, 255, 235)

  // Movement execution block-selection debug colors.
  private val COLOR_BLOCK_SELECTED = OverlayRenderEngine.Color(60, 255, 90, 210)     // green: selected / optimal path
  private val COLOR_BLOCK_POTENTIAL = OverlayRenderEngine.Color(255, 45, 55, 135)    // red: possible, not chosen
  private val COLOR_BLOCK_TOO_FAR = OverlayRenderEngine.Color(160, 55, 255, 115)     // purple: outside the executor corridor
  private val COLOR_BLOCK_HAZARD = OverlayRenderEngine.Color(255, 150, 0, 170)       // orange: harmful stand/support block
  private val COLOR_MOVE_CURSOR = OverlayRenderEngine.Color(255, 255, 255, 255)      // white: current cursor
  private val COLOR_MOVE_TARGET = OverlayRenderEngine.Color(255, 240, 0, 255)        // yellow: steering target
  private const val LOOK_POINT_EYE_OFFSET = 1.62

  // Weight-map quartiles: green = best alternatives, red = rejected/blocked.
  private val COLOR_WEIGHT_Q1 = OverlayRenderEngine.Color(  0, 220,  60, 120) // top quartile — viable, close to chosen
  private val COLOR_WEIGHT_Q2 = OverlayRenderEngine.Color(220, 220,   0, 110) // viable but slightly off
  private val COLOR_WEIGHT_Q3 = OverlayRenderEngine.Color(255, 140,   0, 110) // penalized (off-path / partial-visibility)
  private val COLOR_WEIGHT_Q4 = OverlayRenderEngine.Color(220,  40,  40,  95) // rejected (blocked / very off-path)

  val enabled = CheckboxSetting(
    "Enabled",
    "Enable pathfinding target selection and commands.",
    false
  )

  private val info = InfoSetting(
    "Target",
    "Use /phantom setpos or /phantom setposhere to set the target.",
    InfoType.INFO
  )

  private val statusInfo = InfoSetting(
    "Pathfinder Status",
    "Live status of the native pathfinder engine.",
    InfoType.INFO
  )

  val targetX = TextSetting("Target X", "Target X coordinate.", "0")
  val targetY = TextSetting("Target Y", "Target Y coordinate.", "0")
  val targetZ = TextSetting("Target Z", "Target Z coordinate.", "0")
  val targetBlock = TextSetting("Target Block", "Filled from right-click (informational).", "")

  val cacheHudEnabled = CheckboxSetting(
    "Cache HUD",
    "Show cached chunk map HUD.",
    false
  )

  val cacheHudRadius = SliderSetting(
    "Cache Radius",
    "Chunk radius shown in the cache HUD.",
    4.0,
    1.0,
    12.0
  )

  val cacheHudCellSize = SliderSetting(
    "Cache Cell Size",
    "Cell size for the cache HUD (pixels).",
    8.0,
    4.0,
    16.0
  )

  val cacheHudShowGrid = CheckboxSetting(
    "Cache Grid",
    "Show grid lines on the cache HUD.",
    true
  )

  private val debugFileLogging = CheckboxSetting(
    "Debug File Logs",
    "Write path/rotation debug logs to file, and mirror them to chat while enabled.",
    false
  )

  private val debugChatLogging = CheckboxSetting(
    "Debug Chat Logs",
    "Show pathfinder diagnostics in chat without requiring file logging.",
    false
  )

  private val lookaheadDistance = SliderSetting(
    "Lookahead Distance",
    "How far ahead on the path the steering target sits. Higher = smoother rotations, wider cornering.",
    7.0, 1.5, 10.0
  )

  private val lookaheadShrink = SliderSetting(
    "Lookahead Shrink",
    "How aggressively the lookahead shortens on turns and when off-path. 0.0 = always stay far (smoothest rotations, may cut corners). 1.0 = original behavior.",
    0.05, 0.0, 1.0
  )

  private val movementDebugRender = CheckboxSetting(
    "Movement Debug Render",
    "Draw block-selection debug for any active path owner. Green = chosen path, red = possible but not chosen, purple = too far outside the corridor, orange = hazard.",
    false
  )

  private val weightMapRender = CheckboxSetting(
    "Weight Map",
    "Heatmap nearby non-walkable blocks around the active path. Bright red = adjacent obstacle, deep red = farther blocked cell. The path itself stays green.",
    false
  )

  private val rollingFootPath = CheckboxSetting(
    "Rolling Foot Path",
    "Render a transparent tile on the floor of every block the path walks on. Behind the cursor = blocks already walked, ahead = blocks the path will attempt. Alpha tapers with distance from the cursor for a rolling effect.",
    false
  )

  private val weightMapRadius = SliderSetting(
    "Weight Map Radius",
    "Horizontal sample radius around each path node when rendering the weight heatmap.",
    3.0, 1.0, 6.0
  )

  private val movementDebugRange = SliderSetting(
    "Debug Render Range",
    "How many upcoming path nodes and nearby candidate blocks to draw when Movement Debug Render is on.",
    24.0, 4.0, 64.0
  )

  private val movementDebugCandidateRadius = SliderSetting(
    "Debug Candidate Radius",
    "Horizontal radius around the player scanned for possible/hazard/too-far support blocks.",
    7.0, 3.0, 14.0
  )

  private val weightMapEnabled = CheckboxSetting(
    "Lookahead Weight Map",
    "Show all candidate support blocks the lookahead logic considered along the spline, color-binned by quartile of distance-from-chosen-target. Green = best alternatives, red = rejected / blocked / far from chosen.",
    false
  )

  private val weightMapForward = SliderSetting(
    "Weight Map Forward",
    "How far ahead along the spline (in spline-sample steps) to scan for candidate lookahead positions when the weight map is on.",
    16.0, 4.0, 48.0
  )

  private val rotationCatchupMin = SliderSetting(
    "Rotation Catch-up Min",
    "Per-frame fraction of remaining yaw/pitch delta closed when aim is near the target. Higher = quicker return when close.",
    0.30, 0.05, 0.60
  )

  private val rotationCatchupMax = SliderSetting(
    "Rotation Catch-up Max",
    "Per-frame closure ceiling when the aim is far from the target. Higher = quicker recovery on big deviations, but more snap.",
    0.70, 0.30, 0.95
  )

  private val rotationRampScale = SliderSetting(
    "Rotation Ramp Scale",
    "How quickly the catch-up ramps from Min toward Max as the angle delta grows. Smaller = ramps faster (snappier). Larger = stays smooth on big deltas.",
    16.0, 5.0, 60.0
  )

  private val precisionAggressiveness = SliderSetting(
    "Precision Aggressiveness",
    "How eagerly precision mode (slower walk + sneak near goal) engages on soft triggers: curvature, off-path deviation, arrival brake. 0 = soft triggers off (only physical hazards engage precision). 1 = original aggressive behavior.",
    0.05, 0.0, 1.0
  )

  private val precisionUsesSprint = CheckboxSetting(
    "Precision Uses Sprint",
    "When precision mode engages sneak (the 'slow mode'), also hold sprint so the player ninja-sneaks: sneak speed + sprint state (FOV, sprint-attack flag) at once. Off = sneak-only (slowest, original behavior).",
    true
  )

  private val jumpMinRise = SliderSetting(
    "Jump Min Rise",
    "Minimum path rise (blocks) before a geometry-fallback jump triggers. Raise above 0.9 if the bot is randomly jumping at slabs / small inclines.",
    1.12, 0.85, 1.4
  )

  private val lookaheadVelocityBoost = SliderSetting(
    "Lookahead Velocity Boost",
    "Extra lookahead distance (blocks) per block/sec of player speed. Sprinting (~5.6 b/s) at 0.15 adds ~0.84 blocks. 0 = original speed-agnostic behavior.",
    0.32, 0.0, 0.5
  )

  private val sprintBrakeCurvature = SliderSetting(
    "Sprint Brake Curvature",
    "Drop sprint pre-emptively when upcoming path curvature meets this threshold so the player slows before the turn. Lower = brakes earlier. Set near max to effectively disable.",
    4.0, 0.10, 4.0
  )

  private val sprintEngageTicks = SliderSetting(
    "Sprint Engage Ticks",
    "Sprint releases instantly when conditions break, but re-engaging requires this many ticks of stable conditions. Stops tick-to-tick sprint flicker.",
    1.0, 0.0, 10.0
  )

  private val sneakEngageTicks = SliderSetting(
    "Sneak Engage Ticks",
    "Sneak engagement requires this many ticks of sustained signal — kills brief edge-flicker sneaking on thin bridges. Quick release so the player still drops when the path needs it.",
    5.0, 0.0, 10.0
  )

  private val forwardYawTolerance = SliderSetting(
    "Forward Yaw Tolerance",
    "Only press forward when the player's yaw is within this many degrees of the target travel direction. Stops walking sideways into walls while the rotation catches up.",
    90.0, 15.0, 180.0
  )

  private val jumpCooldownFloor = SliderSetting(
    "Jump Cooldown Floor",
    "Minimum ticks between consecutive jumps. Stops double-jumps from one trigger resetting while another fires.",
    8.0, 1.0, 15.0
  )

  private val edgeScanDistance = SliderSetting(
    "Edge Scan Distance",
    "How far ahead on the spline ledge-risk safety is checked. Original was 4.25 (engages sneak on thin bridges way too early). Intentional drops are now excluded automatically. Lower = less eager sneaking.",
    0.9, 0.5, 6.0
  )

  private val jumpRangeMultiplier = SliderSetting(
    "Jump Range Multiplier",
    "Scales all jump trigger distances (preemptive / hill / obstacle / gap / hazard). 1.0 = original. Lower = jumps only fire when the player is closer to the obstacle — kills early/random jumps.",
    0.85, 0.4, 1.0
  )

  private val aotvSlot = ModeSetting(
    "AOTV Slot",
    "Hotbar slot (1-9) holding your AOTV item.",
    0,
    arrayOf("1","2","3","4","5","6","7","8","9")
  )

  private val startAction = ActionSetting(
    "Start",
    "Start pathfinding to the target coordinates.",
    "Start"
  ) {
    startFromSettings()
  }

  private val stopAction = ActionSetting(
    "Stop",
    "Stop the current path.",
    "Stop"
  ) {
    stopPath()
  }

  private val patrolModeEnabled = CheckboxSetting(
    "Patrol Mode",
    "Randomly patrol between kill waypoints using the recorded route.",
    false
  )

  private val recordRouteAction = ActionSetting(
    "Record Route Point",
    "Appends your current position to the patrol route.",
    "Record"
  ) {
    val player = mc.player ?: return@ActionSetting
    localRouteWaypoints.add(RouteWaypoint(player.x, player.y, player.z))

    ChatUtils.sendMessage("Route point recorded (${localRouteWaypoints.size} total).")
  }

  private val clearRouteAction = ActionSetting(
    "Clear Route",
    "Removes all recorded route waypoints.",
    "Clear"
  ) {
    localRouteWaypoints.clear()

    ChatUtils.sendMessage("Route cleared.")
  }

  private val recordingKillPoints = CheckboxSetting(
    "Record Kill Points",
    "Right-click a block to record a kill waypoint while this is enabled.",
    false
  )

  private val clearKillAction = ActionSetting(
    "Clear Kill Points",
    "Removes all recorded kill waypoints.",
    "Clear"
  ) {
    localKillWaypoints.clear()

    ChatUtils.sendMessage("Kill waypoints cleared.")
  }

  private val killDwellTicks = SliderSetting(
    "Kill Dwell Ticks",
    "Ticks to wait at a kill spot before moving to the next.",
    20.0, 0.0, 100.0
  )

  private val routeCountInfo = InfoSetting(
    "Route Points",
    "Number of recorded route waypoints.",
    InfoType.INFO
  )

  private val killCountInfo = InfoSetting(
    "Kill Points",
    "Number of recorded kill waypoints.",
    InfoType.INFO
  )

  private val startPatrolAction = ActionSetting(
    "Start Patrol",
    "Start patrolling between kill waypoints.",
    "Start Patrol"
  ) {
    startPatrol()
  }

  private val stopPatrolAction = ActionSetting(
    "Stop Patrol",
    "Stop the patrol loop.",
    "Stop Patrol"
  ) {
    stopPatrol()
  }

  val cacheHud = hudElement(
    "path-cache-hud",
    "Cache Map",
    "Shows cached chunks around you."
  ) {
    anchor = HudAnchor.TOP_RIGHT
    offsetX = -12f
    offsetY = 12f

    width {
      val radius = cacheHudRadius.value.toInt().coerceAtLeast(1)
      val cell = cacheHudCellSize.value.toFloat()
      (radius * 2 + 1) * cell + 8f
    }
    height {
      val radius = cacheHudRadius.value.toInt().coerceAtLeast(1)
      val cell = cacheHudCellSize.value.toFloat()
      (radius * 2 + 1) * cell + 8f
    }

    render { screenX, screenY, _ ->
      if (!cacheHudEnabled.value) return@render
      val player = mc.player ?: return@render
      val level = mc.level ?: return@render

      val radius = cacheHudRadius.value.toInt().coerceAtLeast(1)
      val cell = cacheHudCellSize.value.toFloat()
      val size = radius * 2 + 1
      val mapW = size * cell
      val mapH = size * cell

      NVGRenderer.rect(screenX, screenY, mapW + 8f, mapH + 8f, ThemeManager.currentTheme.panel, 6f)

      val originX = screenX + 4f
      val originY = screenY + 4f
      val centerChunkX = player.blockX shr 4
      val centerChunkZ = player.blockZ shr 4

      for (dz in -radius..radius) {
        for (dx in -radius..radius) {
          val chunkX = centerChunkX + dx
          val chunkZ = centerChunkZ + dz
          val cached = MinecraftPathingRules.isChunkCached(level, chunkX, chunkZ)
          val color =
            when {
              dx == 0 && dz == 0 -> ThemeManager.currentTheme.accentSecondary
              cached -> ThemeManager.currentTheme.accent
              else -> ThemeManager.currentTheme.overlay
            }
          val x = originX + (dx + radius) * cell
          val y = originY + (dz + radius) * cell
          NVGRenderer.rect(x, y, cell - 1f, cell - 1f, color)
        }
      }

      if (cacheHudShowGrid.value) {
        NVGRenderer.hollowRect(
          originX - 0.5f,
          originY - 0.5f,
          mapW + 1f,
          mapH + 1f,
          1f,
          ThemeManager.currentTheme.moduleDivider,
          4f
        )
      }
    }
  }

  init {
    addSetting(
      enabled,
      info,
      statusInfo,
      targetX,
      targetY,
      targetZ,
      targetBlock,
      cacheHudEnabled,
      cacheHudRadius,
      cacheHudCellSize,
      cacheHudShowGrid,
      movementDebugRender,
      weightMapRender,
      rollingFootPath,
      weightMapRadius,
      movementDebugRange,
      movementDebugCandidateRadius,
      weightMapEnabled,
      weightMapForward,
      debugFileLogging,
      debugChatLogging,
      aotvSlot,
      startAction,
      stopAction,
      patrolModeEnabled,
      recordRouteAction,
      clearRouteAction,
      recordingKillPoints,
      clearKillAction,
      killDwellTicks,
      routeCountInfo,
      killCountInfo,
      startPatrolAction,
      stopPatrolAction,
    )

    EventBus.register(this)
  }

  @SubscribeEvent
  fun onRightClick(event: MouseEvent.RightClick) {
    if (!recordingKillPoints.value) return
    val mc = Minecraft.getInstance()
    val hit = mc.hitResult ?: return
    if (hit !is net.minecraft.world.phys.BlockHitResult) return
    val pos = hit.blockPos
    val wp = KillWaypoint(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5)
    localKillWaypoints.add(wp)

    ChatUtils.sendMessage("Kill waypoint recorded (${localKillWaypoints.size} total).")
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
    org.phantom.internal.pathfinding.DebugLog.debugFileEnabled = debugFileLogging.value
    org.phantom.internal.pathfinding.DebugLog.debugChatEnabled =
      debugChatLogging.value || debugFileLogging.value || isDebugEnabled()
    PathExecutorState.lookaheadDistanceFar = lookaheadDistance.value
    PathExecutorState.lookaheadShrinkStrength = lookaheadShrink.value
    PathExecutorState.rotationCatchupMin = rotationCatchupMin.value
    PathExecutorState.rotationCatchupMax = rotationCatchupMax.value
    PathExecutorState.rotationRampScale = rotationRampScale.value
    PathExecutorState.precisionAggressiveness = precisionAggressiveness.value
    PathExecutorState.precisionUsesSprint = precisionUsesSprint.value
    PathExecutorState.lookaheadVelocityBoost = lookaheadVelocityBoost.value
    PathExecutorState.sprintBrakeCurvature = sprintBrakeCurvature.value
    PathExecutorState.sprintEngageTicks = sprintEngageTicks.value.toInt()
    PathExecutorState.sneakEngageTicks = sneakEngageTicks.value.toInt()
    PathExecutorState.forwardYawTolerance = forwardYawTolerance.value
    PathExecutorState.jumpCooldownFloor = jumpCooldownFloor.value.toInt()
    PathExecutorState.edgeScanDistance = edgeScanDistance.value
    org.phantom.api.pathfinder.jni.executor.JumpDetector.jumpRiseMin = jumpMinRise.value
    org.phantom.api.pathfinder.jni.executor.JumpDetector.jumpRangeMultiplier = jumpRangeMultiplier.value

    // Update live info settings
    routeCountInfo.value = "${localRouteWaypoints.size} points"
    killCountInfo.value  = "${localKillWaypoints.size} points"
    statusInfo.value = if (!NativePathfinder.isInitialized) "Not initialized" else {
      val s = NativePathfinder.status
      if (!moduleOwnsPath) "${s.name} (idle)" else "${s.name} | ${PathExecutorState.executionDebugLine}"
    }

    // Patrol state machine - runs independently of moduleOwnsPath,
    // but must yield when CombatPatrolModule owns the native pathfinder.
    if (patrolState != PatrolState.IDLE && !org.phantom.internal.combat.CombatPatrolModule.patrolOwnsPathfinder) {
      when (patrolState) {
        PatrolState.NAVIGATING -> {
          val nativeStatus = NativePathfinder.status
          if (nativeStatus == PathStatus.ARRIVED || nativeStatus == PathStatus.FAILED) {
            subRouteIndex++
            if (subRouteIndex < subRouteWaypoints.size) {
              // More intermediate waypoints to visit - navigate to next one.
              val (wx, wy, wz) = subRouteWaypoints[subRouteIndex]
              NativePathfinder.setTarget(wx, wy, wz)
            } else {
              // All sub-route waypoints reached - transition to kill dwell.
              dwellTicksRemaining = killDwellTicks.value.toInt()
              patrolState = PatrolState.AT_KILL
            }
          }
        }
        PatrolState.AT_KILL -> {
          if (--dwellTicksRemaining <= 0) {
            val kills = localKillWaypoints
            val next = kills.filter { it != currentKillWp }
            if (next.isEmpty()) {
              stopPatrol()
              ChatUtils.sendMessage("No other kill waypoints available.")
            } else {
              navigateToKill(next.random())
            }
          }
        }
        PatrolState.IDLE -> { /* nothing */ }
      }
    }

    if (enabled.value) {
      val player = mc.player
      val level = mc.level
      if (player != null && level != null) {
        OverlayRenderEngine.clearTag("head-ray")
        val pfStatus = NativePathfinder.status
        if (pfStatus == PathStatus.EXECUTING || pfStatus == PathStatus.REPLANNING ||
            pfStatus == PathStatus.RECOVERING) {
          val eye  = player.getEyePosition()
          val look = player.getLookAngle()
          OverlayRenderEngine.addLine(
            level,
            eye.x, eye.y, eye.z,
            eye.x + look.x * 5.0, eye.y + look.y * 5.0, eye.z + look.z * 5.0,
            COLOR_HEAD_RAY, 1.5f, durationTicks = 2, tag = "head-ray"
          )
        }
        OverlayRenderEngine.clearTag("jump-guide")
        renderJumpGuides(level, player.position(), NativePathfinder.cachedPathNodes)
      }
    }

    // Movement debug render works for ANY path owner (mining, combat, etc.),
    // not just when this module owns the path — purely diagnostic.
    val mPlayer = mc.player
    val mLevel = mc.level
    if (mPlayer != null && mLevel != null) {
      OverlayRenderEngine.clearTag("movement-debug")
      if (movementDebugRender.value && NativePathfinder.cachedPathNodes.isNotEmpty()) {
        renderMovementDebug(mLevel, NativePathfinder.cachedPathNodes, NativePathfinder.pathNodeCursor)
      }
    }

    if (!enabled.value || !moduleOwnsPath || PathService.isActive) return

    val cmd = NativePathfinder.tick()
    if (cmd != null) {
      cmd.applyToPlayer()
      if (cmd.activeAction == ActionType.AOTV) {
        val player = mc.player
        if (player != null) {
          val prevSlot = player.inventory.selectedSlot
          player.inventory.selectedSlot = aotvSlot.value
          if (EtherwarpLogic.tryConsumeTeleportUseThisTick()) {
            mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
          }
          player.inventory.selectedSlot = prevSlot
        }
      }
    } else {
      val s = NativePathfinder.status
      if (s == PathStatus.IDLE || s == PathStatus.ARRIVED || s == PathStatus.FAILED) {
        moduleOwnsPath = false
        MovementManager.setMovementLock(false)
      } else {
        // PLANNING/REPLANNING: no movement command yet - clear stale flags to prevent jump spam
        MovementManager.clearForcedMovement()
      }
    }
  }

  @SubscribeEvent
  fun onRender(event: WorldRenderEvent.Last) {
    val level = mc.level ?: return

    if (movementDebugRender.value) {
      OverlayRenderEngine.clearTag("path-spline")
      renderLookaheadSpline(level)
      return
    }

    if (!enabled.value) return

    // Block-by-block highlight render. Each cached path node gets an outlined
    // box; if the weight map is enabled, surrounding non-walkable blocks get
    // a red heat overlay.
    OverlayRenderEngine.clearTag("path-spline")
    OverlayRenderEngine.clearTag("path-blocks")
    OverlayRenderEngine.clearTag("path-weights")

    val nodes = NativePathfinder.cachedPathNodes
    if (nodes.size >= 2) {
      val pad = 0.02
      val fill = OverlayRenderEngine.Color(60, 235, 110, 70)
      val outline = OverlayRenderEngine.Color(60, 235, 110, 220)
      val aotvFill = OverlayRenderEngine.Color(255, 170, 30, 70)
      val aotvOutline = OverlayRenderEngine.Color(255, 170, 30, 220)
      val flags = NativePathfinder.cachedPathFlags
      for (i in nodes.indices) {
        val v = nodes[i]
        val bx = kotlin.math.floor(v.x)
        val by = kotlin.math.floor(v.y)
        val bz = kotlin.math.floor(v.z)
        val isAotv = i < flags.size && (flags[i] and 0x1) != 0
        OverlayRenderEngine.addBox(
          level,
          bx - pad, by - pad, bz - pad,
          bx + 1.0 + pad, by + 1.0 + pad, bz + 1.0 + pad,
          if (isAotv) aotvFill else fill,
          if (isAotv) aotvOutline else outline,
          1.6f,
          durationTicks = 4,
          tag = "path-blocks",
          forceRender = true,
        )
      }
    }

    if (weightMapRender.value) {
      renderWeightMap(level, nodes)
    }

    OverlayRenderEngine.clearTag("path-foot")
    if (rollingFootPath.value && nodes.isNotEmpty()) {
      renderRollingFootPath(level, nodes, NativePathfinder.pathNodeCursor)
    }

    renderLookaheadSpline(level)
  }

  private fun renderWeightMap(level: net.minecraft.world.level.Level, nodes: List<net.minecraft.world.phys.Vec3>) {
    if (nodes.isEmpty()) return
    val r = weightMapRadius.value.toInt().coerceIn(1, 6)
    val pathSet = HashSet<Long>(nodes.size)
    for (v in nodes) {
      pathSet.add(net.minecraft.core.BlockPos.asLong(
        kotlin.math.floor(v.x).toInt(),
        kotlin.math.floor(v.y).toInt(),
        kotlin.math.floor(v.z).toInt(),
      ))
    }
    val visited = HashSet<Long>(nodes.size * (2 * r + 1) * (2 * r + 1))
    val mp = net.minecraft.core.BlockPos.MutableBlockPos()

    for (v in nodes) {
      val cx = kotlin.math.floor(v.x).toInt()
      val cy = kotlin.math.floor(v.y).toInt()
      val cz = kotlin.math.floor(v.z).toInt()
      for (dy in -1..1) for (dx in -r..r) for (dz in -r..r) {
        mp.set(cx + dx, cy + dy, cz + dz)
        val key = mp.asLong()
        if (!visited.add(key)) continue
        if (pathSet.contains(key)) continue
        // Cost approximation: blocks adjacent to the path are "low cost" (light red),
        // farther non-walkable blocks are "high cost" (deep red). Walkable cells aren't
        // drawn so the path itself stays the focal point.
        if (MinecraftPathingRules.isWalkable(level, mp)) continue
        val dist = kotlin.math.sqrt((dx * dx + dy * dy + dz * dz).toDouble())
        val t = (dist / r.toDouble()).coerceIn(0.0, 1.0)
        val alpha = (60 + (1.0 - t) * 90).toInt().coerceIn(40, 160)
        val color = OverlayRenderEngine.Color(255, 60, 50, alpha)
        val outlineCol = OverlayRenderEngine.Color(255, 90, 80, (alpha + 40).coerceAtMost(220))
        OverlayRenderEngine.addBox(
          level,
          mp.x + 0.04, mp.y + 0.04, mp.z + 0.04,
          mp.x + 0.96, mp.y + 0.96, mp.z + 0.96,
          color,
          outlineCol,
          0.9f,
          durationTicks = 4,
          tag = "path-weights",
          forceRender = true,
        )
      }
    }
  }

  /**
   * Rolling foot-path overlay. For each node along the path, paint a flat
   * fill tile on top of the floor (block below the node) with no outline.
   * Nodes behind the cursor render with a "walked" color; nodes at and ahead
   * of the cursor render with an "attempting" color. Alpha tapers with
   * distance from the cursor so the trail fades in/out as the player moves.
   */
  private fun renderRollingFootPath(
    level: net.minecraft.world.level.Level,
    nodes: List<net.minecraft.world.phys.Vec3>,
    cursor: Int,
  ) {
    if (nodes.isEmpty()) return
    val clampedCursor = cursor.coerceIn(0, nodes.size - 1)
    val falloff = 12.0  // nodes; how quickly the trail fades from cursor
    val tileHeight = 0.02
    val inset = 0.03

    val seen = HashSet<Long>(nodes.size)
    for (i in nodes.indices) {
      val v = nodes[i]
      val bx = kotlin.math.floor(v.x).toInt()
      val by = kotlin.math.floor(v.y).toInt()
      val bz = kotlin.math.floor(v.z).toInt()
      // Coalesce repeated nodes onto the same floor block — common when the
      // path lingers at the same cell across a few cached samples.
      val key = net.minecraft.core.BlockPos.asLong(bx, by, bz)
      if (!seen.add(key)) continue

      val offset = i - clampedCursor
      val falloffT = (kotlin.math.abs(offset).toDouble() / falloff).coerceIn(0.0, 1.0)
      val alphaScale = (1.0 - falloffT * 0.75)
      val baseAlpha = (160.0 * alphaScale).toInt().coerceIn(28, 200)

      val color = if (offset < 0) {
        // Walked — soft green.
        OverlayRenderEngine.Color(110, 235, 140, baseAlpha)
      } else if (offset == 0) {
        // Current cursor — bright white for emphasis.
        OverlayRenderEngine.Color(255, 255, 255, (baseAlpha + 30).coerceAtMost(220))
      } else {
        // Attempting — soft cyan.
        OverlayRenderEngine.Color(80, 200, 255, baseAlpha)
      }

      val floorY = by.toDouble()
      OverlayRenderEngine.addBox(
        level,
        bx + inset, floorY, bz + inset,
        bx + 1.0 - inset, floorY + tileHeight, bz + 1.0 - inset,
        color,
        null,
        0f,
        durationTicks = 3,
        tag = "path-foot",
        forceRender = true,
      )
    }
  }

  private fun renderLookaheadSpline(level: net.minecraft.world.level.Level) {
    OverlayRenderEngine.clearTag("lookahead-spline")
    OverlayRenderEngine.clearTag("lookahead-target")
    OverlayRenderEngine.clearTag("lookahead-ray")

    val spline = PathExecutorState.lookaheadSplinePoints
    if (spline.size >= 2) {
      for (i in 0 until spline.size - 1) {
        val a = spline[i]
        val b = spline[i + 1]
        OverlayRenderEngine.addLine(
          level,
          a.x, a.y, a.z,
          b.x, b.y, b.z,
          COLOR_LOOKAHEAD_SPLINE,
          1.6f,
          durationTicks = 2,
          tag = "lookahead-spline",
          forceRender = true
        )
      }
    }

    val marker = PathExecutorState.currentLookaheadSplinePoint ?: return
    val r = LOOKAHEAD_POINT_RADIUS
    OverlayRenderEngine.addBox(
      level,
      marker.x - r, marker.y - r, marker.z - r,
      marker.x + r, marker.y + r, marker.z + r,
      COLOR_LOOKAHEAD_POINT,
      COLOR_LOOKAHEAD_SPLINE,
      lineWidth = 1.4f,
      durationTicks = 2,
      tag = "lookahead-target",
      forceRender = true
    )

    val eye = mc.player?.eyePosition ?: return
    OverlayRenderEngine.addLine(
      level,
      eye.x, eye.y, eye.z,
      marker.x, marker.y, marker.z,
      COLOR_LOOKAHEAD_SPLINE.withAlpha(190),
      1.2f,
      durationTicks = 2,
      tag = "lookahead-ray",
      forceRender = true
    )
  }

  private fun supportBlockOf(node: Vec3): BlockPos =
    BlockPos(
      Math.floor(node.x).toInt(),
      Math.round(node.y).toInt() - 1,
      Math.floor(node.z).toInt()
    )

  /**
   * Walks the dense spline-sample list forward from the sample closest to the
   * player and collects support-block candidates. Each candidate is scored by
   * 3D distance from the executor's currently-chosen lookahead point (the
   * yellow target block). Best candidates per BlockPos win when multiple
   * samples map to the same block. Quartile-binned and rendered behind the
   * main path tiers so the user can see which alternatives were close to
   * being chosen vs which were too far / rejected.
   */
  private fun renderLookaheadWeightMap(level: net.minecraft.world.level.Level, playerPos: Vec3) {
    val samples = PathExecutorState.lookaheadSplinePoints
    if (samples.size < 4) return
    val chosen = PathExecutorState.currentLookaheadSplinePoint
      ?: PathExecutorState.currentTargetPoint
      ?: return

    // Find the spline sample closest (horizontally) to the player — that's
    // where we start the forward scan.
    var nearest = 0
    var nearestDistSq = Double.MAX_VALUE
    for (i in samples.indices) {
      val s = samples[i]
      val dx = s.x - playerPos.x
      val dz = s.z - playerPos.z
      val ds = dx * dx + dz * dz
      if (ds < nearestDistSq) {
        nearestDistSq = ds
        nearest = i
      }
    }

    val forward = weightMapForward.value.toInt().coerceAtLeast(2)
    val end = minOf(samples.lastIndex, nearest + forward)
    if (end - nearest < 2) return

    // Map BlockPos.asLong() -> (BlockPos, best score for this block).
    val scoreByBlock = HashMap<Long, Pair<BlockPos, Double>>(forward * 2)
    for (i in nearest..end) {
      val sample = samples[i]
      val pos = BlockPos(
        Math.floor(sample.x).toInt(),
        Math.round(sample.y).toInt() - 1,
        Math.floor(sample.z).toInt(),
      )
      val dx = sample.x - chosen.x
      val dy = sample.y - chosen.y
      val dz = sample.z - chosen.z
      val score = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
      val key = pos.asLong()
      val existing = scoreByBlock[key]
      if (existing == null || score < existing.second) {
        scoreByBlock[key] = pos to score
      }
    }

    if (scoreByBlock.size < 4) return

    // Quartile bin.
    val sorted = scoreByBlock.values.sortedBy { it.second }
    val q1End = sorted.size / 4
    val q2End = sorted.size / 2
    val q3End = sorted.size * 3 / 4

    for ((idx, entry) in sorted.withIndex()) {
      val color = when {
        idx < q1End -> COLOR_WEIGHT_Q1
        idx < q2End -> COLOR_WEIGHT_Q2
        idx < q3End -> COLOR_WEIGHT_Q3
        else -> COLOR_WEIGHT_Q4
      }
      OverlayRenderEngine.outlineBlockColor(
        level, entry.first, color,
        durationTicks = 4,
        tag = "movement-debug",
        lineWidth = 0.8f,
        forceRender = true,
      )
    }
  }

  /**
   * In-world overlay of the executor's block selection:
   *  - green: upcoming blocks the active path will walk on
   *  - red: valid nearby support blocks that were not selected
   *  - purple: valid support blocks outside the selected-path corridor
   *  - orange: harmful stand/support blocks
   */
  private fun renderMovementDebug(
    level: net.minecraft.world.level.Level,
    nodes: List<Vec3>,
    cursor: Int
  ) {
    if (nodes.isEmpty()) return
    val rangeFar = movementDebugRange.value.toInt().coerceAtLeast(1)
    val startIdx = cursor.coerceIn(0, nodes.lastIndex)
    val endIdxFar = minOf(nodes.lastIndex, startIdx + rangeFar)

    val mPlayer = mc.player
    if (weightMapEnabled.value && mPlayer != null) {
      renderLookaheadWeightMap(level, mPlayer.position())
    }

    val selectedBlocks = HashSet<Long>(rangeFar * 2)
    val selectedPositions = ArrayList<BlockPos>(rangeFar)
    for (i in startIdx..endIdxFar) {
      val pos = supportBlockOf(nodes[i])
      if (selectedBlocks.add(pos.asLong())) selectedPositions.add(pos)
    }

    if (mPlayer != null) {
      renderMovementCandidateBlocks(level, mPlayer.blockPosition(), selectedBlocks, selectedPositions)
    }

    for (pos in selectedPositions) {
      renderDebugBlock(level, pos, COLOR_BLOCK_SELECTED, lineWidth = 2.0f)
    }

    // Cursor node.
    OverlayRenderEngine.outlineBlockColor(
      level, supportBlockOf(nodes[startIdx]),
      COLOR_MOVE_CURSOR,
      durationTicks = 4,
      tag = "movement-debug",
      lineWidth = 2.4f,
      forceRender = true,
    )

    // Steering target block. Prefer the raw lookahead spline point so it
    // reflects the path-space decision the executor made.
    val targetPoint = PathExecutorState.currentLookaheadSplinePoint
      ?: PathExecutorState.currentTargetPoint
    if (targetPoint != null) {
      val targetBlock = steeringTargetSupportBlock(level, targetPoint)
      OverlayRenderEngine.outlineBlockColor(
        level, targetBlock,
        COLOR_MOVE_TARGET,
        durationTicks = 4,
        tag = "movement-debug",
        lineWidth = 3.0f,
        forceRender = true,
      )
    }
  }

  private fun steeringTargetSupportBlock(level: net.minecraft.world.level.Level, point: Vec3): BlockPos {
    val x = Math.floor(point.x).toInt()
    val z = Math.floor(point.z).toInt()
    val estimatedFeetY = point.y - LOOK_POINT_EYE_OFFSET
    val baseSupportY = Math.floor(estimatedFeetY - 0.05).toInt()

    var best: BlockPos? = null
    var bestScore = Double.MAX_VALUE
    for (y in baseSupportY + 1 downTo baseSupportY - 3) {
      val support = BlockPos(x, y, z)
      val feet = support.above()
      if (!isPassable(level, feet) || !isPassable(level, feet.above()) || !isStandable(level, support)) {
        continue
      }
      val feetDelta = kotlin.math.abs((support.y + 1.0) - estimatedFeetY)
      val descentBias = if (support.y <= baseSupportY) 0.08 else 0.0
      val score = feetDelta - descentBias
      if (score < bestScore) {
        bestScore = score
        best = support
      }
    }

    return best ?: BlockPos(x, baseSupportY, z)
  }

  private fun renderMovementCandidateBlocks(
    level: net.minecraft.world.level.Level,
    center: BlockPos,
    selectedBlocks: Set<Long>,
    selectedPositions: List<BlockPos>
  ) {
    if (selectedPositions.isEmpty()) return

    val radius = movementDebugCandidateRadius.value.toInt().coerceIn(3, 14)
    val corridorDistanceSq = 2.25 * 2.25
    val minY = center.y - 3
    val maxY = center.y + 2

    for (x in center.x - radius..center.x + radius) {
      for (z in center.z - radius..center.z + radius) {
        val dx = x - center.x
        val dz = z - center.z
        if (dx * dx + dz * dz > radius * radius) continue

        val candidate = bestSupportCandidate(level, x, z, minY, maxY) ?: continue
        val key = candidate.asLong()
        if (key in selectedBlocks) continue

        val color = if (isMovementHazard(level, candidate)) {
          COLOR_BLOCK_HAZARD
        } else if (distanceSqToSelected(candidate, selectedPositions) > corridorDistanceSq) {
          COLOR_BLOCK_TOO_FAR
        } else {
          COLOR_BLOCK_POTENTIAL
        }

        renderDebugBlock(level, candidate, color, lineWidth = 1.2f)
      }
    }
  }

  private fun bestSupportCandidate(
    level: net.minecraft.world.level.Level,
    x: Int,
    z: Int,
    minY: Int,
    maxY: Int
  ): BlockPos? {
    for (y in maxY downTo minY) {
      val support = BlockPos(x, y, z)
      val feet = support.above()
      if (isPassable(level, feet) && isPassable(level, feet.above()) && isStandable(level, support)) {
        return support
      }
      if (PathHazards.isHarmfulStandPosition(level, feet)) {
        return support
      }
    }
    return null
  }

  private fun isMovementHazard(level: net.minecraft.world.level.Level, support: BlockPos): Boolean =
    PathHazards.isHarmfulStandPosition(level, support.above())

  private fun isPassable(level: net.minecraft.world.level.Level, pos: BlockPos): Boolean {
    val state = level.getBlockState(pos)
    return state.fluidState.isEmpty && state.getCollisionShape(level, pos).isEmpty
  }

  private fun isStandable(level: net.minecraft.world.level.Level, pos: BlockPos): Boolean {
    val state = level.getBlockState(pos)
    return state.fluidState.isEmpty && !state.getCollisionShape(level, pos).isEmpty
  }

  private fun distanceSqToSelected(pos: BlockPos, selectedPositions: List<BlockPos>): Double {
    var best = Double.MAX_VALUE
    for (selected in selectedPositions) {
      val dx = (pos.x - selected.x).toDouble()
      val dz = (pos.z - selected.z).toDouble()
      val distSq = dx * dx + dz * dz
      if (distSq < best) best = distSq
    }
    return best
  }

  private fun renderDebugBlock(
    level: net.minecraft.world.level.Level,
    pos: BlockPos,
    color: OverlayRenderEngine.Color,
    lineWidth: Float
  ) {
    val pad = 0.002
    OverlayRenderEngine.addBox(
      level,
      pos.x - pad,
      pos.y - pad,
      pos.z - pad,
      pos.x + 1.0 + pad,
      pos.y + 1.0 + pad,
      pos.z + 1.0 + pad,
      color.withAlpha((color.a * 0.28).toInt().coerceIn(25, 90)),
      color,
      lineWidth = lineWidth,
      durationTicks = 4,
      tag = "movement-debug",
      forceRender = true,
    )
  }

  private fun renderJumpGuides(level: net.minecraft.world.level.Level, playerPos: Vec3, nodes: List<Vec3>) {
    if (nodes.size < 2) return

    val nearestIndex = nearestNodeIndex(playerPos, nodes)
    if (nearestIndex < 0) return

    var shown = 0
    for (index in max(1, nearestIndex)..nodes.lastIndex) {
      if (shown >= MAX_JUMP_GUIDES) break

      val previous = nodes[index - 1]
      val current = nodes[index]
      val rise = current.y - previous.y
      if (rise < JUMP_GUIDE_MIN_RISE || rise > JUMP_GUIDE_MAX_RISE) continue

      val dx = current.x - playerPos.x
      val dy = current.y - playerPos.y
      val dz = current.z - playerPos.z
      if (dx * dx + dy * dy + dz * dz > JUMP_GUIDE_MAX_DISTANCE_SQ) continue

      val landingAir = BlockPos.containing(current.x, current.y, current.z)
      val landingBlock = landingAir.below()
      val pad = 0.01

      OverlayRenderEngine.addBox(
        level,
        landingBlock.x - pad,
        landingBlock.y - pad,
        landingBlock.z - pad,
        landingBlock.x + 1.0 + pad,
        landingBlock.y + 1.0 + pad,
        landingBlock.z + 1.0 + pad,
        COLOR_JUMP_FILL,
        COLOR_JUMP_OUTLINE,
        lineWidth = 2.0f,
        durationTicks = 3,
        tag = "jump-guide"
      )
      OverlayRenderEngine.outlineBlockColor(
        level,
        landingAir,
        COLOR_JUMP_AIR_OUTLINE,
        durationTicks = 3,
        tag = "jump-guide",
        lineWidth = 1.8f
      )
      shown++
    }
  }

  private fun nearestNodeIndex(playerPos: Vec3, nodes: List<Vec3>): Int {
    var nearestIndex = -1
    var nearestDistSq = Double.POSITIVE_INFINITY
    for (index in nodes.indices) {
      val node = nodes[index]
      val dx = node.x - playerPos.x
      val dz = node.z - playerPos.z
      val distSq = dx * dx + dz * dz
      if (distSq < nearestDistSq) {
        nearestDistSq = distSq
        nearestIndex = index
      }
    }
    return nearestIndex
  }

  val isPatrolActive: Boolean get() = patrolState != PatrolState.IDLE

  fun ensureEnabledForAutomation(source: String) {
    if (enabled.value) return
    enabled.value = true
    ChatUtils.sendMessage("Pathfinding auto-enabled for $source.")
  }

  fun startFromSettings() {
    if (!enabled.value) {
      ensureEnabledForAutomation("pathfinding")
    }

    val x = parseCoordinate(targetX.value) ?: return invalidTarget("X", targetX.value)
    val y = parseCoordinate(targetY.value) ?: return invalidTarget("Y", targetY.value)
    val z = parseCoordinate(targetZ.value) ?: return invalidTarget("Z", targetZ.value)
    val resolved = resolvePathTarget(x, y, z) ?: return invalidPathTarget(x, y, z)
    setTarget(resolved.x, resolved.y, resolved.z, targetBlock.value.ifBlank { null })
    NativePathfinder.setTarget(resolved.x, resolved.y, resolved.z)
    moduleOwnsPath = true
    ChatUtils.sendMessage("Pathfinding to ${formatCoord(resolved.x)}, ${formatCoord(resolved.y)}, ${formatCoord(resolved.z)}.")
  }

  fun setTargetOnly(x: Double, y: Double, z: Double) {
    val resolved = resolvePathTarget(x, y, z)
    val target = resolved ?: Vec3(x, y, z)
    setTarget(target.x, target.y, target.z, null)
    ChatUtils.sendMessage("Target set to ${formatCoord(target.x)}, ${formatCoord(target.y)}, ${formatCoord(target.z)}.")
  }

  fun startTo(x: Double, y: Double, z: Double) {
    val resolved = resolvePathTarget(x, y, z) ?: return invalidPathTarget(x, y, z)
    setTarget(resolved.x, resolved.y, resolved.z, null)
    if (!enabled.value) {
      ensureEnabledForAutomation("pathfinding")
    }
    NativePathfinder.setTarget(resolved.x, resolved.y, resolved.z)
    moduleOwnsPath = true
  }

  fun startTo(
    x: Double,
    y: Double,
    z: Double,
    owner: PathOwner,
    source: String,
    timeoutTicks: Int = 1200,
    arrivalRadius: Double = 1.8,
    onArrive: () -> Unit = {},
    onFail: (PathFailReason) -> Unit = {},
  ): Boolean {
    val resolved = resolvePathTarget(x, y, z)
    if (resolved == null) {
      invalidPathTarget(x, y, z)
      onFail(PathFailReason.PATHFINDER_FAILED)
      return false
    }

    setTarget(resolved.x, resolved.y, resolved.z, null)
    if (!enabled.value) {
      ensureEnabledForAutomation(source)
    }

    val accepted = PathService.requestPath(
      PathRequest(
        x = resolved.x,
        y = resolved.y,
        z = resolved.z,
        owner = owner,
        source = source,
        timeoutTicks = timeoutTicks,
        arrivalRadius = arrivalRadius,
        onArrive = {
          moduleOwnsPath = false
          onArrive()
        },
        onFail = { reason ->
          moduleOwnsPath = false
          onFail(reason)
        },
      )
    )
    moduleOwnsPath = accepted
    return accepted
  }

  fun stopPath() {
    PathService.forceCancel(PathFailReason.USER_CANCELLED)
    NativePathfinder.stop()
    moduleOwnsPath = false
    MovementManager.setMovementLock(false)
    cachedSpline = null
    lastNodesRef = null
    OverlayRenderEngine.clearTag("path-spline")
    OverlayRenderEngine.clearTag("lookahead-spline")
    OverlayRenderEngine.clearTag("lookahead-target")
    OverlayRenderEngine.clearTag("lookahead-ray")
  }

  private fun buildSubRoute(
    fromX: Double, fromY: Double, fromZ: Double,
    killWp: KillWaypoint
  ): DoubleArray {
    val route = localRouteWaypoints
    if (route.isEmpty()) {
      // No route - navigate directly (player position + kill target)
      return doubleArrayOf(fromX, fromY, fromZ, killWp.x, killWp.y, killWp.z)
    }

    fun dist3(wp: RouteWaypoint, x: Double, y: Double, z: Double): Double {
      val dx = wp.x - x; val dy = wp.y - y; val dz = wp.z - z
      return sqrt(dx*dx + dy*dy + dz*dz)
    }

    val j = route.indices.minByOrNull { dist3(route[it], fromX, fromY, fromZ) } ?: 0
    val i = route.indices.minByOrNull { dist3(route[it], killWp.x, killWp.y, killWp.z) } ?: 0

    val slice: List<RouteWaypoint> = if (j == i) {
      emptyList()
    } else {
      val fwd = if (j <= i) route.subList(j, i + 1) else emptyList()
      val bwd = if (i <= j) route.subList(i, j + 1).reversed() else emptyList()
      when {
        fwd.isEmpty() -> bwd
        bwd.isEmpty() -> fwd
        else          -> if (fwd.size <= bwd.size) fwd else bwd
      }
    }

    // Build flat double[] for NativePathfinder.setRoute: [x0,y0,z0, x1,y1,z1, ...]
    val points = mutableListOf<Double>()
    // Start at player position
    points += listOf(fromX, fromY, fromZ)
    // Route slice
    slice.forEach { wp -> points += listOf(wp.x, wp.y, wp.z) }
    // Kill waypoint as final destination
    points += listOf(killWp.x, killWp.y, killWp.z)

    return points.toDoubleArray()
  }

  fun startPatrol() {
    if (patrolState != PatrolState.IDLE) stopPatrol()
    if (localKillWaypoints.size < 2) {
      ChatUtils.sendMessage("Need at least 2 kill waypoints to patrol. Use 'Record Kill Points'.")
      return
    }
    if (!enabled.value) ensureEnabledForAutomation("patrol")
    val first = localKillWaypoints.random()
    navigateToKill(first)
  }

  private fun navigateToKill(target: KillWaypoint) {
    val player = mc.player ?: run {
      patrolState = PatrolState.IDLE
      return
    }
    currentKillWp = target
    // Build the sub-route as individual waypoints (skip index 0 which is the player start pos).
    val flat = buildSubRoute(player.x, player.y, player.z, target)
    val wps = mutableListOf<Triple<Double, Double, Double>>()
    var i = 3  // skip first triple (player position)
    while (i + 2 < flat.size) {
      wps.add(Triple(flat[i], flat[i + 1], flat[i + 2]))
      i += 3
    }
    if (i + 2 == flat.size) wps.add(Triple(flat[i], flat[i + 1], flat[i + 2]))
    subRouteWaypoints = wps
    subRouteIndex = 0
    if (wps.isEmpty()) {
      // Degenerate case - already at kill point.
      dwellTicksRemaining = killDwellTicks.value.toInt()
      patrolState = PatrolState.AT_KILL
      return
    }
    val (wx, wy, wz) = wps[0]
    NativePathfinder.setTarget(wx, wy, wz)
    moduleOwnsPath = true
    patrolState = PatrolState.NAVIGATING
    ChatUtils.sendMessage("Patrolling to kill spot (${localKillWaypoints.indexOf(target) + 1}/${localKillWaypoints.size}).")
  }

  fun stopPatrol() {
    patrolState = PatrolState.IDLE
    stopPath()
  }

  private fun nativeActive(): Boolean {
    val s = NativePathfinder.status
    return s != PathStatus.IDLE &&
           s != PathStatus.ARRIVED &&
           s != PathStatus.FAILED
  }

  fun setTargetAtPlayer() {
    val player = mc.player ?: return
    val targetPos = mc.level?.let { level ->
      MinecraftPathingRules.walkableAt(level, player.blockPosition())
    } ?: player.blockPosition()
    val x = targetPos.x + 0.5
    val y = targetPos.y.toDouble()
    val z = targetPos.z + 0.5
    setTarget(x, y, z, "player")
    ChatUtils.sendMessage("Target set to ${formatCoord(x)}, ${formatCoord(y)}, ${formatCoord(z)}.")
  }

  private fun setTarget(x: Double, y: Double, z: Double, blockName: String?) {
    targetX.value = formatCoord(x)
    targetY.value = formatCoord(y)
    targetZ.value = formatCoord(z)
    if (blockName != null) {
      targetBlock.value = blockName
    }
  }

  private fun formatCoord(value: Double): String {
    val intVal = value.toInt()
    return if (value == intVal.toDouble()) {
      intVal.toString()
    } else {
      String.format(Locale.US, "%.3f", value)
    }
  }

  private fun parseCoordinate(value: String): Double? {
    return value.trim().toDoubleOrNull()
  }

  private fun resolvePathTarget(x: Double, y: Double, z: Double): Vec3? {
    val level = mc.level ?: return Vec3(x, y, z)
    val raw = BlockPos.containing(x, y, z)
    val resolved = MinecraftPathingRules.resolveTarget(level, raw) ?: return null
    return Vec3(resolved.x + 0.5, resolved.y.toDouble(), resolved.z + 0.5)
  }

  private fun invalidTarget(axis: String, value: String) {
    ChatUtils.sendMessage("Invalid $axis coordinate: \"$value\"")
  }

  private fun invalidPathTarget(x: Double, y: Double, z: Double) {
    ChatUtils.sendMessage("Target is not walkable: ${formatCoord(x)}, ${formatCoord(y)}, ${formatCoord(z)}")
  }

  private const val JUMP_GUIDE_MIN_RISE = 0.45
  private const val JUMP_GUIDE_MAX_RISE = 1.25
  private const val JUMP_GUIDE_MAX_DISTANCE_SQ = 144.0
  private const val MAX_JUMP_GUIDES = 3
  private const val LOOKAHEAD_POINT_RADIUS = 0.11
}
