package org.cobalt.internal.pathfinding

import java.util.Locale
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import org.cobalt.api.hud.HudAnchor
import org.cobalt.api.hud.hudElement
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.MouseEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.event.impl.render.WorldRenderEvent
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.Vec3
import org.cobalt.internal.pathfinding.OverlayRenderEngine
import org.cobalt.internal.pathfinding.PathSplineRenderer
import org.cobalt.api.pathfinder.PathFailReason
import org.cobalt.api.pathfinder.PathOwner
import org.cobalt.api.pathfinder.PathRequest
import org.cobalt.api.pathfinder.PathService
import org.cobalt.api.pathfinder.jni.MovementProfile
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.ActionSetting
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.InfoSetting
import org.cobalt.api.module.setting.impl.InfoType
import org.cobalt.api.module.setting.impl.ModeSetting
import org.cobalt.api.module.setting.impl.TextSetting
import org.cobalt.api.pathfinder.jni.ActionType
import org.cobalt.api.pathfinder.jni.NativePathfinder
import org.cobalt.api.pathfinder.jni.PathExecutorState
import org.cobalt.api.pathfinder.jni.PathStatus
import org.cobalt.api.pathfinder.minecraft.MinecraftPathingRules
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ChatUtils
import org.cobalt.api.util.player.MovementManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.internal.etherwarp.EtherwarpLogic
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

  val enabled = CheckboxSetting(
    "Enabled",
    "Enable pathfinding target selection and commands.",
    false
  )

  private val info = InfoSetting(
    "Target",
    "Use /cobalt setpos or /cobalt setposhere to set the target.",
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
    org.cobalt.internal.pathfinding.DebugLog.debugFileEnabled = debugFileLogging.value
    org.cobalt.internal.pathfinding.DebugLog.debugChatEnabled =
      debugChatLogging.value || debugFileLogging.value || isDebugEnabled()

    // Update live info settings
    routeCountInfo.value = "${localRouteWaypoints.size} points"
    killCountInfo.value  = "${localKillWaypoints.size} points"
    statusInfo.value = if (!NativePathfinder.isInitialized) "Not initialized" else {
      val s = NativePathfinder.status
      if (!moduleOwnsPath) "${s.name} (idle)" else "${s.name} | ${PathExecutorState.executionDebugLine}"
    }

    // Patrol state machine - runs independently of moduleOwnsPath,
    // but must yield when CombatPatrolModule owns the native pathfinder.
    if (patrolState != PatrolState.IDLE && !org.cobalt.internal.combat.CombatPatrolModule.patrolOwnsPathfinder) {
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

    if (!enabled.value || !moduleOwnsPath) return

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
    if (!enabled.value) return
    val level = mc.level ?: return

    // Spline path rendering - rebuild only when path nodes change.
    // When nodes drops to < 2 (planning/arrived) keep the old spline visible so the
    // path doesn't flash out during every replan; it's replaced when a fresh path arrives.
    val nodes = NativePathfinder.cachedPathNodes
    if (nodes !== lastNodesRef) {
      lastNodesRef = nodes
      if (nodes.size >= 2) {
        cachedSpline = PathSplineRenderer.buildSpline(nodes)
        val spline = cachedSpline!!
        val pts  = spline.points
        val isAv = spline.isAotv
        OverlayRenderEngine.clearTag("path-spline")
        for (i in 0 until pts.size - 1) {
          val a = pts[i]; val b = pts[i + 1]
          val color = if (isAv[i]) COLOR_PATH_AOTV else COLOR_PATH_NORMAL
          OverlayRenderEngine.addLine(
            level,
            a.x, a.y + 0.05, a.z,
            b.x, b.y + 0.05, b.z,
            color, 2.0f, durationTicks = 60, tag = "path-spline", forceRender = true
          )
        }
      } else {
        OverlayRenderEngine.clearTag("path-spline")
      }
    }

    renderLookaheadSpline(level)
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
