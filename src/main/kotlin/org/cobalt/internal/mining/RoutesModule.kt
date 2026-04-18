package org.cobalt.internal.mining

import com.mojang.blaze3d.opengl.GlStateManager
import com.mojang.blaze3d.vertex.PoseStack
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.awt.Color
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.LightTexture
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.AABB
import net.minecraft.core.Direction
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.MouseEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.event.impl.render.WorldRenderEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.ActionSetting
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.InfoSetting
import org.cobalt.api.module.setting.impl.InfoType
import org.cobalt.api.module.setting.impl.ModeSetting
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.module.setting.impl.TextSetting
import org.cobalt.api.rotation.RotationExecutor
import org.cobalt.api.rotation.strategy.BezierTrackingRotationStrategy
import org.cobalt.api.rotation.strategy.HeadRotationStrategy
import org.cobalt.api.util.AngleUtils
import org.cobalt.api.util.ChatUtils
import org.cobalt.api.util.InventoryUtils
import org.cobalt.api.util.MouseUtils
import org.cobalt.api.util.helper.Rotation
import org.cobalt.api.util.render.Render3D
import org.cobalt.api.pathfinder.minecraft.MinecraftPathingRules
import org.cobalt.api.pathfinder.jni.MovementProfile
import org.cobalt.api.pathfinder.jni.NativePathfinder
import org.cobalt.api.pathfinder.jni.PathStatus
import org.cobalt.api.util.player.MovementManager
import org.cobalt.internal.etherwarp.EtherwarpLogic
import org.cobalt.internal.pathfinding.PathfindingModule
import org.cobalt.internal.rotation.RotationsModule
import org.cobalt.internal.routes.RouteStore
import org.cobalt.internal.routes.RoutePointType as NewRoutePointType

object RoutesModule : Module("Routes") {

  enum class RoutePointType(val id: String) {
    NORMAL("normal"),
    WARP("warp"),
    MINE("mine");

    companion object {
      fun fromId(id: String?): RoutePointType {
        return when (id?.lowercase()) {
          "warp" -> WARP
          "mine" -> MINE
          else -> NORMAL
        }
      }
    }
  }

  data class RoutePoint(
    val pos: BlockPos,
    val type: RoutePointType,
    val mineEnd: BlockPos? = null,
    val mineBlockId: String? = null
  )

  data class SavedRouteInfo(
    val name: String,
    val mineTypes: List<String>,
    val hasMinePoints: Boolean,
    val hasWarpPoints: Boolean,
    val pointCount: Int,
  )

  private enum class RouteAction {
    NONE,
    WALK,
    WARP,
    MINE
  }

  private enum class RouteMineMode {
    NONE,
    LEGACY_VEIN,
    ANCHOR_LIST,
    SINGLE_ANCHOR_MACRO
  }

  private val mc: Minecraft = Minecraft.getInstance()
  private val gson = GsonBuilder().setPrettyPrinting().create()
  private val routesDirectory = File(mc.gameDirectory, "config/cobalt/routes")
  private val legacyRoutesFile = File(mc.gameDirectory, "config/cobalt/routes.json")

  private val routePoints = mutableListOf<RoutePoint>()
  private var routeIndex = 0
  private var routeRunning = false
  private var awaitingArrival = false
  private var walkCompletePointOnArrival = true
  private var walkRetryCount = 0
  private var lastTarget: BlockPos? = null
  private var lastResolvedTarget: BlockPos? = null
  private var action = RouteAction.NONE
  private var activePoint: RoutePoint? = null
  private var pendingClickPos: BlockPos? = null
  private var anchorPlacementActive = false

  private val rotationStrategy = BezierTrackingRotationStrategy(
    yawStepSampler = { RotationsModule.sample(RotationsModule.routeYawStep.value).toFloat() },
    pitchStepSampler = { RotationsModule.sample(RotationsModule.routePitchStep.value).toFloat() },
    curveInProvider = { RotationsModule.bezierCurveIn.value.toFloat() },
    curveOutProvider = { RotationsModule.bezierCurveOut.value.toFloat() },
    minScaleProvider = { RotationsModule.bezierMinScale.value.toFloat() },
    snapThresholdProvider = { RotationsModule.bezierSnapThreshold.value.toFloat() },
  )
  private val routeFollowRotationStrategy = HeadRotationStrategy(
    speedScaleSampler = { RotationsModule.routeFollowSpeedScale.value.toFloat() },
    accelScaleSampler = { RotationsModule.routeFollowAccelScale.value.toFloat() },
    pitchStepSampler = { RotationsModule.routeFollowPitchStep.value.toFloat() },
    maxSpeedSampler = { RotationsModule.routeFollowMaxSpeed.value.toFloat() },
    maxAccelSampler = { RotationsModule.routeFollowMaxAccel.value.toFloat() },
    easeModeSampler = { RotationsModule.currentRouteEase() },
    snapThresholdSampler = { RotationsModule.routeFollowSnapThreshold.value.toFloat() },
  )

  private var warpStage = 0
  private var warpTarget: BlockPos? = null
  private var warpStageElapsedMs = 0.0
  private var warpStageLastNs = 0L
  private var warpLookLastNs = 0L
  private var warpCooldownUntil = 0L
  private var warpRestoreSlot = -1
  private var pendingWarpUseRelease = false
  private var warpCompletePointOnArrival = true
  private var warpResumeAction = RouteAction.NONE
  private var lastSuccessfulWarpTarget: BlockPos? = null
  private var lastSuccessfulWarpTick = -1L
  private var screenPauseNoticeTick = 0L

  private var mineMode = RouteMineMode.NONE
  private var mineBlocks: MutableSet<BlockPos> = LinkedHashSet()
  private var mineAnchorBlockIds = LinkedHashMap<BlockPos, String>()
  private var mineOrderedBlocks: List<BlockPos> = emptyList()
  private var mineBlockId: String? = null
  private var mineTarget: BlockPos? = null
  private var minePathTarget: BlockPos? = null
  private var mineAnchors: List<BlockPos> = emptyList()
  private var minePointStart: BlockPos? = null
  private var minePointEnd: BlockPos? = null
  private var mineTravelWaypoints: List<BlockPos> = emptyList()
  private var activeMineEndIndex = -1
  private var walkSegmentEndIndex = -1
  private var mineSingleAnchorStarted = false
  private var routeOwnsMiningMacro = false
  private var lastPathStartTick = 0L
  private var miningActive = false
  private var mineDrillWarnTick = 0L
  private var activeLoopOverride: Boolean? = null
  private var lastAutomationCompletionPos: BlockPos? = null

  // Auto-detected mining loop: two route points with the same block position.
  // loopStartIndex = first occurrence, loopEndIndex = second occurrence.
  // All points between them are the mining area. Points before loopStartIndex
  // are the one-time travel route.
  private var loopStartIndex = -1
  private var loopEndIndex = -1

  val enabled = CheckboxSetting(
    "Enabled",
    "Enable route tools.",
    false
  )

  private val info = InfoSetting(
    "Route Builder",
    "Add points, save/load, and run routes with the pathfinder.",
    InfoType.INFO
  )

  val routeName = TextSetting(
    "Route Name",
    "Name used for save/load.",
    "default"
  )

  val pointsText = TextSetting(
    "Points",
    "Number of points in the current route.",
    "0"
  )

  val statusText = TextSetting(
    "Status",
    "Current route status.",
    "Idle"
  )

  val renderRoute = CheckboxSetting(
    "Render Route",
    "Render the route in-world.",
    true
  )

  val recordOnRightClick = CheckboxSetting(
    "Record on Right Click",
    "Add a route point when you right-click a block.",
    false
  )

  private val loopRoute = CheckboxSetting(
    "Loop Route",
    "Restart from the first point when the route reaches the end.",
    true
  )

  private val startFromNearest = CheckboxSetting(
    "Start From Nearest",
    "When starting a route, begin at the checkpoint closest to your current position.",
    false
  )

  private val pointType = ModeSetting(
    "Point Type",
    "Type used when adding points.",
    0,
    arrayOf("Normal", "Warp", "Mine")
  )

  private val veinOccupancyRadius = SliderSetting(
    "Vein Occupancy Radius",
    "Radius around a vein considered occupied by other players.",
    6.0,
    1.0,
    16.0
  )

  private val openPickerAction = ActionSetting(
    "Point Picker",
    "Open a picker to choose the next point type.",
    "Open"
  ) { /* point picker popup removed — use the Routes screen edit mode */ }

  private val addPointAction = ActionSetting(
    "Add Point",
    "Add a point at your position.",
    "Add"
  ) {
    addPointFromPlayer()
  }

  private val removePointAction = ActionSetting(
    "Remove Last",
    "Remove the last route point.",
    "Remove"
  ) {
    if (routePoints.isNotEmpty()) {
      routePoints.removeAt(routePoints.lastIndex)
      updateStatus()
    }
  }

  private val clearRouteAction = ActionSetting(
    "Clear Route",
    "Remove all route points.",
    "Clear"
  ) {
    routePoints.clear()
    routeIndex = 0
    routeRunning = false
    resetRuntimeState()
    nativeStop("Route cleared.")
    updateStatus()
  }

  private val saveRouteAction = ActionSetting(
    "Save Route",
    "Save route points under the current name.",
    "Save"
  ) {
    saveRoute()
  }

  private val loadRouteAction = ActionSetting(
    "Load Route",
    "Load route points by name.",
    "Load"
  ) {
    loadRoute()
  }

  private val startRouteAction = ActionSetting(
    "Start Route",
    "Run the current route with the pathfinder.",
    "Start"
  ) {
    startRoute()
  }

  private val startClosestVeinAction = ActionSetting(
    "Path Closest Vein",
    "Pathfind to the nearest unoccupied mine point.",
    "Start"
  ) {
    pathToClosestVein()
  }

  private val stopRouteAction = ActionSetting(
    "Stop Route",
    "Stop running the route.",
    "Stop"
  ) {
    stopRoute("Stopped.")
  }

  init {
    addSetting(
      enabled,
      info,
      routeName,
      pointsText,
      statusText,
      renderRoute,
      recordOnRightClick,
      loopRoute,
      startFromNearest,
      pointType,
      veinOccupancyRadius,
      openPickerAction,
      addPointAction,
      removePointAction,
      clearRouteAction,
      saveRouteAction,
      loadRouteAction,
      startRouteAction,
      startClosestVeinAction,
      stopRouteAction,
    )

    EventBus.register(this)
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
    if (pendingWarpUseRelease) {
      mc.options.keyUse?.setDown(false)
      pendingWarpUseRelease = false
    }

    if (!enabled.value) {
      stopAnchorPlacement(notify = false)
      stopOwnedMiningMacro()
      if (routeRunning) {
        stopRoute("Routes disabled.")
      }
      return
    }

    if (!recordOnRightClick.value) {
      stopAnchorPlacement(notify = false)
    } else if (anchorPlacementActive && isChatScreenOpen()) {
      stopAnchorPlacement()
    }

    updateStatus()

    if (!routeRunning) return

    val player = mc.player ?: return
    val level = mc.level ?: return
    if (routePoints.isEmpty()) {
      stopRoute("Route has no points.")
      return
    }

    if (mc.screen != null) {
      mc.options.keyUse?.setDown(false)
      mc.options.keyShift?.setDown(false)
      setWalkKeys(false, false, false, false, false, false, false)
      stopMiningKeys()
      stopRouteRotation()
      if (level.gameTime >= screenPauseNoticeTick) {
        ChatUtils.sendMessage("Route paused while a menu is open.")
        screenPauseNoticeTick = level.gameTime + 40L
      }
      return
    }
    screenPauseNoticeTick = 0L

    if (warpTarget != null || warpStage > 0) {
      return
    }

    if (action == RouteAction.MINE) {
      handleMine(player, level)
      return
    }

    if (action == RouteAction.WALK) {
      if (nativeActive()) {
        val cmd = NativePathfinder.tick()
        applyWalkCameraRotation(player)
        if (cmd != null) {
          cmd.applyToPlayer(applyRotation = false, movementYawOverride = player.yRot)
          applyWalkEdgeSafety(player, level)
        } else {
          holdWalkAutonomousMovement()
        }
        syncWalkKeys()
        if (!handleWalkArrival(player, level)) {
          return
        }
      }
      if (awaitingArrival) {
        applyWalkCameraRotation(player)
        holdWalkAutonomousMovement()
        syncWalkKeys()
        if (!handleWalkArrival(player, level)) {
          if (level.gameTime - lastPathStartTick < WALK_REPATH_GRACE_TICKS) {
            return
          }
          if (walkCompletePointOnArrival) {
            // Before giving up, restart the current walk path fully so route
            // segments keep their recorded waypoint shape during autonomous retries.
            if (walkRetryCount < MAX_WALK_RETRIES && restartWalkAutonomousPath(level)) {
              walkRetryCount++
              return
            }
            walkRetryCount = 0
            if (attemptRouteVisibleEtherwarpRecovery(level, player)) {
              awaitingArrival = false
              lastTarget = null
              lastResolvedTarget = null
              walkCompletePointOnArrival = true
              return
            }
            stopRoute("Route failed: could not reach point ${routeIndex + 1}.")
          } else {
            action = RouteAction.NONE
            awaitingArrival = false
            lastTarget = null
            lastResolvedTarget = null
            walkCompletePointOnArrival = true
          }
        }
      }
    }

    if (action == RouteAction.NONE) {
      startNextPoint(player, level)
    }
  }

  @SubscribeEvent
  fun onFrame(@Suppress("UNUSED_PARAMETER") event: WorldRenderEvent.Last) {
    if (!enabled.value || !routeRunning) return
    if (mc.screen != null) return
    val player = mc.player ?: return
    val level = mc.level ?: return
    if (warpTarget != null || warpStage > 0) {
      handleWarp(player, level)
    }
  }

  @SubscribeEvent
  fun onRightClick(@Suppress("UNUSED_PARAMETER") event: MouseEvent.RightClick) {
    if (!enabled.value || !recordOnRightClick.value) return
    val hit = mc.hitResult
    if (hit is BlockHitResult && hit.type == HitResult.Type.BLOCK) {
      val clicked = hit.blockPos
      if (anchorPlacementActive && currentPointType() == RoutePointType.MINE) {
        addRoutePoint(createRoutePoint(clicked, RoutePointType.MINE))
        return
      }
      pendingClickPos = clicked
      applyPickedType(currentPointType())
    }
  }

  @SubscribeEvent
  fun onRender(event: WorldRenderEvent.Last) {
    if (!enabled.value || !renderRoute.value) return
    if (routePoints.isEmpty()) return
    val level = mc.level ?: return
    val travelNodes = buildTravelRenderNodes()
    val mineNodes = buildMineRenderNodes()
    val mineSegments = collectMineSegments()
    if (travelNodes.size >= 2) {
      for (i in 0 until travelNodes.size - 1) {
        val a = travelNodes[i]
        val b = travelNodes[i + 1]
        val start = Vec3(a.pos.x + 0.5, a.pos.y + 0.2, a.pos.z + 0.5)
        val end = Vec3(b.pos.x + 0.5, b.pos.y + 0.2, b.pos.z + 0.5)
        Render3D.drawLine(event.context, start, end, a.color, true, 2.0f)
      }
    }
    for (node in travelNodes) {
      val p = node.pos
      val box = AABB(
        p.x.toDouble(),
        p.y.toDouble(),
        p.z.toDouble(),
        p.x + 1.0,
        p.y + 1.0,
        p.z + 1.0
      )
      Render3D.drawBox(event.context, box, node.color, true)
    }
    for (node in mineNodes) {
      val p = node.pos
      val box = AABB(
        p.x.toDouble(),
        p.y.toDouble(),
        p.z.toDouble(),
        p.x + 1.0,
        p.y + 1.0,
        p.z + 1.0
      )
      Render3D.drawBox(event.context, box, node.color, true)
    }
    for (segment in mineSegments) {
      val color = pointTypeColor(RoutePointType.MINE, pointRenderRatio(segment.startIndex))
      for (i in 0 until segment.anchors.size - 1) {
        val startAnchor = segment.anchors[i]
        val endAnchor = segment.anchors[i + 1]
        val startVec = Vec3(startAnchor.x + 0.5, startAnchor.y + 0.4, startAnchor.z + 0.5)
        val endVec = Vec3(endAnchor.x + 0.5, endAnchor.y + 0.4, endAnchor.z + 0.5)
          Render3D.drawLine(event.context, startVec, endVec, color, true, 2.5f)
      }
      if (segment.waypoints.size >= 2) {
        highlightVein(level, segment.waypoints.first(), segment.waypoints.last(), color, event.context)
      }
    }
    renderRouteLabels(event.context, mineNodes)
  }

  private fun addPointFromPlayer() {
    val player = mc.player ?: return
    addRoutePoint(createRoutePoint(player.blockPosition(), currentPointType()))
  }

  private fun addRoutePoint(point: RoutePoint) {
    routePoints.add(point)
    updateStatus()
  }

  private fun createRoutePoint(pos: BlockPos, type: RoutePointType): RoutePoint {
    val recordedBlockId =
      if (type == RoutePointType.MINE) {
        mc.level
          ?.getBlockState(pos)
          ?.takeUnless { it.isAir }
          ?.let { net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(it.block).toString() }
      } else {
        null
      }
    return RoutePoint(pos, type, mineBlockId = recordedBlockId)
  }

  val isRunning: Boolean get() = routeRunning
  val routeOwnsMining: Boolean get() = routeOwnsMiningMacro

  fun getLastAutomationCompletionPos(): BlockPos? = lastAutomationCompletionPos?.immutable()

  fun getSavedRouteInfos(): List<SavedRouteInfo> {
    return listSavedRouteNames()
      .mapNotNull { name ->
        val points = loadRoutePointsByName(name) ?: return@mapNotNull null
        buildSavedRouteInfo(name, points)
      }
  }

  fun loadAndStartAutomationRoute(
    name: String,
    startNearest: Boolean = true,
    loop: Boolean = false,
    automationSource: String = "automation",
  ): Boolean {
    if (routeRunning) return false
    val trimmedName = name.trim()
    if (trimmedName.isEmpty() || !isValidRouteName(trimmedName)) return false
    val loaded = loadRoutePointsByName(trimmedName) ?: return false
    if (loaded.isEmpty()) return false

    routePoints.clear()
    routePoints.addAll(loaded)
    routeName.value = trimmedName
    routeIndex = if (startNearest) findNearestPointIndex() else 0
    routeRunning = true
    resetRuntimeState()
    lastAutomationCompletionPos = null
    activeLoopOverride = loop
    detectMiningLoop()
    if (!enabled.value) {
      enabled.value = true
    }
    if (mc.screen != null) {
      mc.setScreen(null)
    }
    PathfindingModule.stopPath()
    PathfindingModule.ensureEnabledForAutomation("routes")
    updateStatus()
    ChatUtils.sendMessage("Route \"$trimmedName\" started for $automationSource.")
    notifyWarpFallback(trimmedName, loaded)
    return true
  }

  fun stopForAutomation(reason: String = "") {
    stopRoute(reason)
  }

  private fun findNearestPointIndex(): Int {
    val origin = mc.player?.blockPosition() ?: return 0
    return routePoints.indices.minByOrNull { i ->
      routePoints[i].pos.distSqr(origin).toDouble()
    } ?: 0
  }

  private fun startRoute() {
    if (routePoints.isEmpty()) {
      ChatUtils.sendMessage("Route has no points.")
      return
    }
    if (!enabled.value) {
      enabled.value = true
    }
    if (mc.screen != null) {
      mc.setScreen(null)
    }
    // Ensure PathfindingModule does not double-tick the native pathfinder by holding
    // stale moduleOwnsPath state. stopPath() sets moduleOwnsPath=false without
    // disrupting the route - startWalk will set a fresh target immediately after.
    PathfindingModule.stopPath()
    PathfindingModule.ensureEnabledForAutomation("routes")
    routeIndex = if (startFromNearest.value) findNearestPointIndex() else 0
    routeRunning = true
    resetRuntimeState()
    lastAutomationCompletionPos = null
    activeLoopOverride = null
    detectMiningLoop()
    updateStatus()
    val startMsg = if (startFromNearest.value) "from checkpoint ${routeIndex + 1}" else ""
    if (loopStartIndex >= 0) {
      ChatUtils.sendMessage(
        "Route started${ if (startMsg.isNotEmpty()) " $startMsg" else "" }. Mining loop: " +
          "travel 0-${loopStartIndex}, loop ${loopStartIndex}-${loopEndIndex}."
      )
    } else {
      ChatUtils.sendMessage("Route started${ if (startMsg.isNotEmpty()) " $startMsg" else "" }.")
    }
    notifyWarpFallback(routeName.value.trim().takeIf { it.isNotEmpty() }, routePoints)
  }

  /**
   * Loads route [name] from disk and starts it.
   * When [reverse] is true, the stored route is reversed before starting.
   * If [fromEndOffset] > 0, starts that many checkpoints before the end of the active route.
   * Otherwise starts at the checkpoint nearest to the player.
   * Returns true if the route was loaded and started successfully.
   */
  fun loadAndStartWalkback(name: String, fromEndOffset: Int = 0, reverse: Boolean = true): Boolean {
    // Don't restart if a walkback is already in progress.
    if (routeRunning) return false
    val trimmedName = name.trim()
    if (trimmedName.isEmpty() || !isValidRouteName(trimmedName)) return false
    val routeFile = routeFileForName(trimmedName)
    val loaded = when {
      routeFile.exists() -> readRouteFile(routeFile)
      else -> readLegacyRoute(trimmedName)
    } ?: return false
    if (loaded.isEmpty()) return false

    val activeRoute = if (reverse) loaded.asReversed() else loaded
    routePoints.clear()
    routePoints.addAll(activeRoute)
    routeIndex =
      if (fromEndOffset > 0) {
        if (reverse) {
          fromEndOffset.coerceIn(0, routePoints.lastIndex)
        } else {
          (routePoints.lastIndex - fromEndOffset).coerceAtLeast(0)
        }
      } else {
        findNearestPointIndex()
      }
    routeRunning = true
    resetRuntimeState()
    lastAutomationCompletionPos = null
    activeLoopOverride = false
    detectMiningLoop()
    if (!enabled.value) enabled.value = true
    PathfindingModule.stopPath()
    PathfindingModule.ensureEnabledForAutomation("routes")

    updateStatus()
    val runType = if (reverse) "Walkback" else "Route"
    ChatUtils.sendMessage("$runType \"$trimmedName\" started at checkpoint ${routeIndex + 1}/${loaded.size}.")
    return true
  }

  private fun pathToClosestVein() {
    val player = mc.player ?: return
    val level = mc.level ?: return
    if (routePoints.isEmpty()) {
      ChatUtils.sendMessage("Route has no points.")
      return
    }

    if (routeRunning) {
      stopRoute("Switching to closest vein.")
    }

    val candidates = collectMineSegments()
    if (candidates.isEmpty()) {
      ChatUtils.sendMessage("No mine points available.")
      return
    }

    var bestSegment: MineSegment? = null
    var bestTarget: BlockPos? = null
    var bestDistSq = Double.POSITIVE_INFINITY
    val origin = player.blockPosition()

    for (segment in candidates) {
      val anchor = segment.anchors.firstOrNull() ?: continue
      if (isVeinOccupied(level, segment.waypoints, player)) continue
      val target = resolveApproxTarget(anchor) ?: continue
      val distSq = target.distSqr(origin).toDouble()
      if (distSq < bestDistSq) {
        bestDistSq = distSq
        bestSegment = segment
        bestTarget = target
      }
    }

    if (bestSegment == null || bestTarget == null) {
      ChatUtils.sendMessage("No unoccupied mine points found.")
      return
    }

    PathfindingModule.ensureEnabledForAutomation("routes")

    NativePathfinder.setTarget(bestTarget.x + 0.5, bestTarget.y.toDouble(), bestTarget.z + 0.5)
    lastPathStartTick = level.gameTime

    ChatUtils.sendMessage(
      "Pathing to vein at ${bestSegment.anchors.first().x} ${bestSegment.anchors.first().y} ${bestSegment.anchors.first().z}."
    )
  }

  private fun nativeActive(): Boolean =
    NativePathfinder.status.let { it != PathStatus.IDLE && it != PathStatus.ARRIVED && it != PathStatus.FAILED }

  private fun nativeStop(reason: String?) {
    NativePathfinder.stop()
    MovementManager.setMovementLock(false)
    setWalkKeys(false, false, false, false, false, false, false)
    stopRouteRotation()
    if (reason != null) ChatUtils.sendMessage(reason)
  }

  private fun stopRoute(reason: String) {
    val wasRunning = routeRunning
    routeRunning = false
    resetRuntimeState()
    nativeStop(null)
    updateStatus()
    if (wasRunning && reason.isNotBlank()) {
      ChatUtils.sendMessage(reason)
    }
  }

  private fun updateStatus() {
    pointsText.value = routePoints.size.toString()
    statusText.value = if (!routeRunning) {
      "Idle"
    } else {
      if (routePoints.isEmpty()) {
        "Running 0/0"
      } else {
        val current = (routeIndex + 1).coerceAtMost(routePoints.size)
        "Running $current/${routePoints.size}"
      }
    }
  }

  private fun saveRoute() {
    val name = routeName.value.trim()
    if (name.isEmpty()) {
      ChatUtils.sendMessage("Route name is empty.")
      return
    }
    if (!isValidRouteName(name)) {
      ChatUtils.sendMessage("Route name contains invalid filename characters.")
      return
    }
    if (!routesDirectory.exists()) {
      routesDirectory.mkdirs()
    }
    val routeFile = routeFileForName(name)
    writeRouteFile(routeFile, routePoints)
    ChatUtils.sendMessage("Saved route \"$name\" (${routePoints.size} points) to ${routeFile.name}.")
  }

  private fun loadRoute() {
    val name = routeName.value.trim()
    if (name.isEmpty()) {
      ChatUtils.sendMessage("Route name is empty.")
      return
    }
    if (!isValidRouteName(name)) {
      ChatUtils.sendMessage("Route name contains invalid filename characters.")
      return
    }

    val routeFile = routeFileForName(name)
    val loaded = when {
      routeFile.exists() -> readRouteFile(routeFile) ?: run {
        ChatUtils.sendMessage("Route file \"${routeFile.name}\" is invalid.")
        return
      }
      else -> readLegacyRoute(name) ?: run {
        ChatUtils.sendMessage("Route \"$name\" not found.")
        return
      }
    }

    routePoints.clear()
    routePoints.addAll(loaded)
    routeIndex = 0
    routeRunning = false
    resetRuntimeState()
    detectMiningLoop()
    updateStatus()
    val loopMsg = if (loopStartIndex >= 0) " Loop: pts ${loopStartIndex}-${loopEndIndex}." else ""
    ChatUtils.sendMessage("Loaded route \"$name\" (${routePoints.size} points).$loopMsg")
  }

  private fun isValidRouteName(name: String): Boolean {
    if (name == "." || name == "..") return false
    if (name.endsWith(".") || name.endsWith(" ")) return false
    val invalidChars = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
    return name.none { it in invalidChars }
  }

  private fun routeFileForName(name: String): File {
    return File(routesDirectory, "$name.json")
  }

  private fun loadRoutePointsByName(name: String): List<RoutePoint>? {
    val routeFile = routeFileForName(name)
    return when {
      routeFile.exists() -> readRouteFile(routeFile)
      else -> readLegacyRoute(name) ?: loadFromRouteStore(name)
    }
  }

  /**
   * Bridge: read a route saved by the new [RouteStore] (config/cobalt/routes2/) and
   * flatten it into this module's legacy [RoutePoint] list so commission / mining
   * macros that still call [loadAndStartAutomationRoute] can use it.
   *
   * - Dual sub-route types (ORE_MINER / PATROL): concatenates travelRoute + loopOrArea.
   * - Single sub-route types (COMMISSION / GEMSTONE / TUNNEL): uses points.
   * - Point types: WALK → NORMAL, WARP → WARP, MINE/VEIN → MINE, LANTERN/KILL → skipped.
   */
  private fun loadFromRouteStore(name: String): List<RoutePoint>? {
    val route = RouteStore.loadAll().firstOrNull { it.name.equals(name, ignoreCase = true) }
      ?: return null
    val source = route.travelRoute + route.loopOrArea + route.points
    if (source.isEmpty()) return emptyList()
    val converted = mutableListOf<RoutePoint>()
    source.forEach { p ->
      val legacyType = when (p.type) {
        NewRoutePointType.WALK -> RoutePointType.NORMAL
        NewRoutePointType.WARP -> RoutePointType.WARP
        NewRoutePointType.MINE,
        NewRoutePointType.VEIN -> RoutePointType.MINE
        NewRoutePointType.LANTERN,
        NewRoutePointType.KILL -> return@forEach
      }
      converted.add(RoutePoint(p.pos, legacyType, p.mineEnd, p.blockId))
    }
    return converted
  }

  private fun listSavedRouteNames(): List<String> {
    val names = linkedSetOf<String>()

    if (routesDirectory.exists()) {
      routesDirectory
        .listFiles { file -> file.isFile && file.extension.equals("json", ignoreCase = true) }
        ?.forEach { file ->
          val name = file.nameWithoutExtension.trim()
          if (name.isNotEmpty() && isValidRouteName(name)) {
            names.add(name)
          }
        }
    }

    readLegacyRoutesJson()
      .getAsJsonObject("routes")
      ?.entrySet()
      ?.forEach { (name, _) ->
        val trimmed = name.trim()
        if (trimmed.isNotEmpty() && isValidRouteName(trimmed)) {
          names.add(trimmed)
        }
      }

    // Bridge: routes created by the new picker live in config/cobalt/routes2/ and are
    // otherwise invisible to commission / mining macros that resolve routes by name.
    RouteStore.loadAll().forEach { route ->
      val trimmed = route.name.trim()
      if (trimmed.isNotEmpty() && isValidRouteName(trimmed)) {
        names.add(trimmed)
      }
    }

    return names.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
  }

  private fun buildSavedRouteInfo(name: String, points: List<RoutePoint>): SavedRouteInfo {
    val mineTypes = linkedSetOf<String>()
    points.forEach { point ->
      val type = point.mineBlockId
        ?.let(MiningBlockRegistry.BLOCK_ID_TO_TYPE::get)
        ?.let(MiningBlockRegistry::normalizeType)
      if (!type.isNullOrBlank()) {
        mineTypes.add(type)
      }
    }
    return SavedRouteInfo(
      name = name,
      mineTypes = mineTypes.toList(),
      hasMinePoints = points.any { it.type == RoutePointType.MINE },
      hasWarpPoints = points.any { it.type == RoutePointType.WARP },
      pointCount = points.size,
    )
  }

  private fun writeRouteFile(routeFile: File, points: List<RoutePoint>) {
    val root = JsonObject()
    root.add("points", serializeRoutePoints(points))
    routeFile.writeText(gson.toJson(root))
  }

  private fun serializeRoutePoints(points: List<RoutePoint>): JsonArray {
    val pointsArray = JsonArray()
    points.forEach { point ->
      val obj = JsonObject()
      obj.addProperty("x", point.pos.x)
      obj.addProperty("y", point.pos.y)
      obj.addProperty("z", point.pos.z)
      obj.addProperty("type", point.type.id)
      point.mineBlockId?.let { blockId ->
        obj.addProperty("bid", blockId)
      }
      point.mineEnd?.let { end ->
        obj.addProperty("mx", end.x)
        obj.addProperty("my", end.y)
        obj.addProperty("mz", end.z)
      }
      pointsArray.add(obj)
    }
    return pointsArray
  }

  private fun readRouteFile(routeFile: File): List<RoutePoint>? {
    val text = runCatching { routeFile.readText() }.getOrNull()?.trim().orEmpty()
    if (text.isEmpty()) return emptyList()
    val parsed = runCatching { JsonParser.parseString(text) }.getOrNull() ?: return null
    val pointsArray = when {
      parsed.isJsonArray -> parsed.asJsonArray
      parsed.isJsonObject -> parsed.asJsonObject.getAsJsonArray("points")
      else -> null
    } ?: return null
    return parseRoutePoints(pointsArray)
  }

  private fun readLegacyRoute(name: String): List<RoutePoint>? {
    val root = readLegacyRoutesJson()
    val routesObj = root.getAsJsonObject("routes") ?: return null
    val pointsArray = routesObj.getAsJsonArray(name) ?: return null
    return parseRoutePoints(pointsArray)
  }

  private fun parseRoutePoints(pointsArray: JsonArray): List<RoutePoint> {
    val loaded = mutableListOf<RoutePoint>()
    pointsArray.forEach { el ->
      val obj = el.asJsonObject
      val x = obj.get("x")?.asInt ?: return@forEach
      val y = obj.get("y")?.asInt ?: return@forEach
      val z = obj.get("z")?.asInt ?: return@forEach
      val type = RoutePointType.fromId(obj.get("type")?.asString)
      val mx = obj.get("mx")?.asInt
      val my = obj.get("my")?.asInt
      val mz = obj.get("mz")?.asInt
      val mineEnd = if (mx != null && my != null && mz != null) BlockPos(mx, my, mz) else null
      val mineBlockId = obj.get("bid")?.asString?.trim()?.takeIf { it.isNotEmpty() }
      loaded.add(RoutePoint(BlockPos(x, y, z), type, mineEnd, mineBlockId))
    }
    return loaded
  }

  private fun readLegacyRoutesJson(): JsonObject {
    if (!legacyRoutesFile.exists()) return JsonObject()
    val text = runCatching { legacyRoutesFile.readText() }.getOrNull()?.trim().orEmpty()
    if (text.isEmpty()) return JsonObject()
    return runCatching { JsonParser.parseString(text).asJsonObject }.getOrDefault(JsonObject())
  }

  private fun resolveApproxTarget(target: BlockPos): BlockPos? {
    val level = mc.level ?: return null
    MinecraftPathingRules.resolveTarget(level, target)?.let { return it }
    return findNearestWalkable(level, target, APPROX_SCAN_RADIUS, APPROX_SCAN_VERTICAL)
  }

  private fun findNearestWalkable(
    level: net.minecraft.world.level.Level,
    origin: BlockPos,
    radius: Int,
    vertical: Int
  ): BlockPos? {
    var best: BlockPos? = null
    var bestDistSq = Double.POSITIVE_INFINITY
    for (dy in -vertical..vertical) {
      for (dx in -radius..radius) {
        for (dz in -radius..radius) {
          val pos = origin.offset(dx, dy, dz)
          if (!MinecraftPathingRules.isWalkable(level, pos)) continue
          val distSq = pos.distSqr(origin).toDouble()
          if (distSq < bestDistSq) {
            bestDistSq = distSq
            best = pos
          }
        }
      }
    }
    return best
  }

  private fun gradientColor(t: Double): Color {
    val clamped = t.coerceIn(0.0, 1.0)
    val r = (0 + (255 - 0) * clamped).toInt()
    val g = (255 + (0 - 255) * clamped).toInt()
    val b = 255
    return Color(r, g, b, 255)
  }

  private fun pointTypeColor(type: RoutePointType, t: Double): Color {
    return when (type) {
      RoutePointType.WARP -> Color(175, 120, 255, 255)
      RoutePointType.MINE -> Color(80, 255, 140, 255)
      RoutePointType.NORMAL -> gradientColor(t)
    }
  }

  private fun pointRenderRatio(index: Int): Double {
    val segments = max(1, routePoints.size - 1)
    return if (segments <= 1) 0.0 else index / (segments - 1.0)
  }

  private fun buildTravelRenderNodes(): List<RouteRenderNode> {
    val nodes = ArrayList<RouteRenderNode>(routePoints.size)
    for ((index, point) in routePoints.withIndex()) {
      if (point.type == RoutePointType.MINE) continue
      nodes.add(RouteRenderNode(point.pos, pointTypeColor(point.type, pointRenderRatio(index)), null))
    }
    return nodes
  }

  private fun buildMineRenderNodes(): List<RouteRenderNode> {
    val nodes = ArrayList<RouteRenderNode>(routePoints.size * 2)
    val segments = collectMineSegments()
    for ((segmentIndex, segment) in segments.withIndex()) {
      val color = pointTypeColor(RoutePointType.MINE, pointRenderRatio(segment.startIndex))
      for ((anchorIndex, anchor) in segment.anchors.withIndex()) {
        nodes.add(RouteRenderNode(anchor, color, "${segmentIndex + 1} anchor ${anchorIndex + 1}"))
      }
    }
    return nodes
  }

  private fun renderRouteLabels(
    context: org.cobalt.api.event.impl.render.WorldRenderContext,
    nodes: List<RouteRenderNode>
  ) {
    if (nodes.none { !it.label.isNullOrBlank() }) return
    val matrices = context.matrixStack ?: PoseStack()
    val font: Font = mc.font
    val buffer = mc.renderBuffers().bufferSource()
    val cam = context.camera.position()
    val light = LightTexture.FULL_BRIGHT
    val scale = 0.05f

    try {
      GlStateManager._enableBlend()
      GlStateManager._blendFuncSeparate(770, 771, 1, 771)
      GlStateManager._disableDepthTest()
      GlStateManager._depthMask(false)

      for (node in nodes) {
        val label = node.label ?: continue
        val textWidth = font.width(label).toFloat()
        val labelX = node.pos.x + 0.5
        val labelY = node.pos.y + 1.08
        val labelZ = node.pos.z + 0.5

        matrices.pushPose()
        matrices.translate(labelX - cam.x, labelY - cam.y, labelZ - cam.z)
        matrices.mulPose(context.camera.rotation())
        matrices.scale(-scale, -scale, scale)

        font.drawInBatch(
          label,
          -textWidth / 2.0f,
          0.0f,
          0xFFFFFFFF.toInt(),
          false,
          matrices.last().pose(),
          buffer,
          Font.DisplayMode.SEE_THROUGH,
          0,
          light
        )
        matrices.popPose()
      }
      buffer.endBatch()
    } finally {
      GlStateManager._depthMask(true)
      GlStateManager._enableDepthTest()
      GlStateManager._disableBlend()
    }
  }

  private fun highlightVein(
    level: net.minecraft.world.level.Level,
    start: BlockPos,
    end: BlockPos,
    color: Color,
    context: org.cobalt.api.event.impl.render.WorldRenderContext
  ) {
    val state = level.getBlockState(start)
    if (state.isAir) return
    val block = state.block

    val minX = minOf(start.x, end.x)
    val maxX = maxOf(start.x, end.x)
    val minY = minOf(start.y, end.y)
    val maxY = maxOf(start.y, end.y)
    val minZ = minOf(start.z, end.z)
    val maxZ = maxOf(start.z, end.z)

    val queue = ArrayDeque<BlockPos>()
    val visited = HashSet<Long>()
    queue.add(start)
    visited.add(start.asLong())

    var rendered = 0
    val maxBlocks = 256

    while (queue.isNotEmpty() && rendered < maxBlocks) {
      val pos = queue.removeFirst()
      if (pos.x !in minX..maxX || pos.y !in minY..maxY || pos.z !in minZ..maxZ) {
        continue
      }
      val curState = level.getBlockState(pos)
      if (curState.isAir || curState.block != block) {
        continue
      }

      if (isExposed(level, pos)) {
        val box = AABB(
          pos.x.toDouble(),
          pos.y.toDouble(),
          pos.z.toDouble(),
          pos.x + 1.0,
          pos.y + 1.0,
          pos.z + 1.0
        )
        Render3D.drawBox(context, box, color, true)
        rendered++
      }

      for (dir in Direction.values()) {
        val next = pos.relative(dir)
        val key = next.asLong()
        if (visited.add(key)) {
          queue.add(next)
        }
      }
    }
  }

  private fun isExposed(level: net.minecraft.world.level.Level, pos: BlockPos): Boolean {
    for (dir in Direction.values()) {
      val adj = pos.relative(dir)
      if (MinecraftPathingRules.isPassable(level, adj)) {
        return true
      }
    }
    return false
  }

  private fun currentPointType(): RoutePointType {
    return when (pointType.value) {
      1 -> RoutePointType.WARP
      2 -> RoutePointType.MINE
      else -> RoutePointType.NORMAL
    }
  }

  fun setPointType(type: RoutePointType) {
    if (type != RoutePointType.MINE) {
      anchorPlacementActive = false
    }
    pointType.value = when (type) {
      RoutePointType.WARP -> 1
      RoutePointType.MINE -> 2
      else -> 0
    }
  }

  fun applyPickedType(type: RoutePointType) {
    setPointType(type)
    val clicked = pendingClickPos
    if (clicked == null) {
      return
    }
    addRoutePoint(createRoutePoint(clicked, type))
    if (type == RoutePointType.MINE) {
      anchorPlacementActive = true
      ChatUtils.sendMessage("Mine anchor placement active. Right-click blocks to add anchors. Open chat to stop.")
    }
    pendingClickPos = null
  }

  fun cancelPendingPick() {
    pendingClickPos = null
  }

  private fun isChatScreenOpen(): Boolean =
    mc.screen?.javaClass?.simpleName == "ChatScreen"

  private fun stopAnchorPlacement(notify: Boolean = true) {
    if (!anchorPlacementActive) return
    anchorPlacementActive = false
    if (notify) {
      ChatUtils.sendMessage("Mine anchor placement stopped.")
    }
  }

  private fun stopOwnedMiningMacro() {
    if (!routeOwnsMiningMacro) return
    MiningMacroModule.stopForAutomation()
    routeOwnsMiningMacro = false
  }

  private fun isVeinOccupied(
    level: net.minecraft.world.level.Level,
    waypoints: List<BlockPos>,
    player: net.minecraft.world.entity.player.Player
  ): Boolean {
    if (waypoints.isEmpty()) return false
    val radius = veinOccupancyRadius.value
    val minX = waypoints.minOf { it.x }.toDouble() - radius
    val minY = waypoints.minOf { it.y }.toDouble() - radius
    val minZ = waypoints.minOf { it.z }.toDouble() - radius
    val maxX = waypoints.maxOf { it.x }.toDouble() + 1.0 + radius
    val maxY = waypoints.maxOf { it.y }.toDouble() + 1.0 + radius
    val maxZ = waypoints.maxOf { it.z }.toDouble() + 1.0 + radius
    val aabb = AABB(minX, minY, minZ, maxX, maxY, maxZ)

    for (other in level.players()) {
      if (other == player) continue
      if (other.isSpectator) continue
      if (aabb.intersects(other.boundingBox)) {
        return true
      }
    }
    return false
  }

  private fun startNextPoint(player: Player, level: net.minecraft.world.level.Level) {
    if (!advanceRouteIndexForLoop()) {
      stopRoute("Route complete.")
      return
    }

    startPointAtCurrentIndex(player, level)
  }

  private fun startPointAtCurrentIndex(
    player: Player,
    level: net.minecraft.world.level.Level,
  ) {
    if (routeIndex !in routePoints.indices) {
      stopRoute("Route complete.")
      return
    }

    val point = routePoints[routeIndex]
    activePoint = point

    when (point.type) {
      RoutePointType.WARP -> {
        val warpPoint = resolveWarpPoint(level, point.pos) ?: run {
          stopRoute("Route failed: invalid warp point ${routeIndex + 1}.")
          return
        }
        if (isStandingOnWarpTarget(player, warpPoint)) {
          completePoint()
          return
        }
        if (!hasEtherwarpAvailable()) {
          startWalk(warpPoint)
          return
        }
        if (startWarp(warpPoint)) {
          action = RouteAction.WARP
          return
        }
        if (hasArrived(player, warpPoint)) {
          completePoint()
          return
        }
        if (
          level.gameTime < warpCooldownUntil &&
          (EtherwarpLogic.holdingEtherwarpItem() || EtherwarpLogic.findEtherwarpHotbarSlot() in 0..8)
        ) {
          return
        }
        if (!attemptRouteVisibleEtherwarpRecovery(level, player)) {
          stopRoute("Route failed: no visible route etherwarp target.")
        }
        return
      }
      RoutePointType.MINE -> {
        startMine(level, routeIndex)
      }
      else -> {
        // Run consecutive normal checkpoints as a single native waypoint route
        // so the planner stays in control between points.
        startWalkSegment(level)
      }
    }
  }

  private fun startWalk(
    target: BlockPos,
    completePointOnArrival: Boolean = true,
  ) {
    val resolved = resolveApproxTarget(target)
    if (resolved == null) {
      stopRoute("Route failed: no walkable target near point ${routeIndex + 1}.")
      return
    }
    val level = mc.level ?: return
    beginSingleWalkPath(level, target, resolved, completePointOnArrival)
    walkRetryCount = 0
  }

  private fun startWalkSegment(level: net.minecraft.world.level.Level) {
    PathfindingModule.ensureEnabledForAutomation("routes")
    NativePathfinder.stop()
    MovementManager.setMovementLock(false)
    stopRouteRotation()

    var segEnd = routeIndex
    while (segEnd + 1 < routePoints.size && routePoints[segEnd + 1].type == RoutePointType.NORMAL) {
      segEnd++
    }

    val seg = routePoints.subList(routeIndex, segEnd + 1)
    val resolvedWaypoints = ArrayList<BlockPos>(seg.size)
    for ((offset, pt) in seg.withIndex()) {
      val absoluteIndex = routeIndex + offset
      val resolvedPoint = resolveApproxTarget(pt.pos)
      if (resolvedPoint == null) {
        stopRoute("Route failed: no walkable target near point ${absoluteIndex + 1}.")
        return
      }
      resolvedWaypoints.add(resolvedPoint)
    }

    val resolved = resolvedWaypoints.last()
    val wps = DoubleArray(seg.size * 3)
    for ((i, resolvedPoint) in resolvedWaypoints.withIndex()) {
      wps[i * 3]     = resolvedPoint.x + 0.5
      wps[i * 3 + 1] = resolvedPoint.y.toDouble()
      wps[i * 3 + 2] = resolvedPoint.z + 0.5
    }

    NativePathfinder.setRoute(wps, loop = false, profile = MovementProfile.DEFAULT)
    lastPathStartTick = level.gameTime
    walkSegmentEndIndex = segEnd
    action = RouteAction.WALK
    awaitingArrival = true
    walkCompletePointOnArrival = true
    activePoint = routePoints[segEnd]
    lastTarget = routePoints[segEnd].pos
    lastResolvedTarget = resolved
    walkRetryCount = 0
  }

  private fun startMine(level: net.minecraft.world.level.Level, pointIndex: Int) {
    val segment = resolveMineSegment(pointIndex) ?: run {
      startWalk(routePoints[pointIndex].pos)
      return
    }

    if (segment.anchors.isEmpty()) {
      completePoint()
      return
    }

    stopOwnedMiningMacro()
    mineAnchors = segment.anchors
    minePointStart = segment.anchors.first()
    minePointEnd = segment.anchors.last()
    mineTravelWaypoints = segment.waypoints
    activeMineEndIndex = segment.endIndex

    mineTarget = null
    minePathTarget = null
    action = RouteAction.MINE

    if (segment.legacyVein) {
      startLegacyVeinMine(level, segment)
      return
    }

    if (segment.anchors.size == 1 && !segmentHasRecordedAnchorBlock(segment)) {
      startSingleAnchorMacroMine(level, segment)
      return
    }

    startAnchorListMine(segment)
  }

  private fun startLegacyVeinMine(
    level: net.minecraft.world.level.Level,
    segment: MineSegment
  ) {
    mineMode = RouteMineMode.LEGACY_VEIN
    mineTravelWaypoints = segment.waypoints
    minePointStart = segment.anchors.firstOrNull()
    minePointEnd = segment.anchors.lastOrNull()
    stopMiningKeys()
    MovementManager.clearForcedMovement()
    stopRouteRotation()

    if (!startLegacyVeinAutomation(level, segment.waypoints)) {
      ChatUtils.sendMessage("Mine point empty; skipping.")
      completePoint()
      return
    }
  }

  private fun startAnchorListMine(segment: MineSegment) {
    mineMode = RouteMineMode.ANCHOR_LIST
    mineBlocks = LinkedHashSet(segment.anchors)
    mineAnchorBlockIds.clear()
    for ((pos, blockId) in segment.anchorBlockIds) {
      if (!blockId.isNullOrBlank()) {
        mineAnchorBlockIds[pos] = blockId
      }
    }
    mineOrderedBlocks = segment.anchors
    mineBlockId = null
    stopMiningKeys()
    MovementManager.clearForcedMovement()
    stopRouteRotation()
  }

  private fun startSingleAnchorMacroMine(
    level: net.minecraft.world.level.Level,
    segment: MineSegment
  ) {
    mineMode = RouteMineMode.SINGLE_ANCHOR_MACRO
    mineSingleAnchorStarted = false
    val anchor = segment.anchors.first()
    val recordedBlockId = segment.anchorBlockIds[anchor]
    mineBlockId = recordedBlockId ?: resolveMineBlockIdAt(level, anchor)
    mineBlocks.clear()
    mineAnchorBlockIds.clear()
    mineOrderedBlocks = segment.anchors
    val anchorCenter = Vec3(anchor.x + 0.5, anchor.y + 0.5, anchor.z + 0.5)
    RotationExecutor.rotateTo(AngleUtils.getRotation(anchorCenter), rotationStrategy)
  }

  private fun segmentHasRecordedAnchorBlock(segment: MineSegment): Boolean {
    return segment.anchors.any { anchor ->
      !segment.anchorBlockIds[anchor].isNullOrBlank()
    }
  }

  private fun resolveMineSegment(pointIndex: Int): MineSegment? {
    val base = routePoints.getOrNull(pointIndex) ?: return null
    if (base.type != RoutePointType.MINE) return null

    base.mineEnd?.let { legacyEnd ->
      val waypoints = resolveLegacyMineWaypoints(pointIndex, base.pos, legacyEnd)
      if (waypoints.isNotEmpty()) {
        return MineSegment(
          pointIndex,
          findLegacyMineEndPointIndex(pointIndex, legacyEnd) ?: pointIndex,
          waypoints,
          waypoints,
          linkedMapOf(base.pos to base.mineBlockId),
          true
        )
      }
    }

    val anchors = mutableListOf<BlockPos>()
    val anchorBlockIds = LinkedHashMap<BlockPos, String?>()
    var endIndex = pointIndex
    var i = pointIndex
    while (i < routePoints.size && routePoints[i].type == RoutePointType.MINE) {
      val point = routePoints[i]
      val pos = point.pos
      if (anchors.isEmpty() || anchors.last() != pos) {
        anchors.add(pos)
      }
      if (!anchorBlockIds.containsKey(pos)) {
        anchorBlockIds[pos] = point.mineBlockId
      }
      endIndex = i
      i++
    }
    if (anchors.isEmpty()) return null
    return MineSegment(pointIndex, endIndex, anchors, anchors, anchorBlockIds, false)
  }

  private fun collectMineSegments(): List<MineSegment> {
    val segments = mutableListOf<MineSegment>()
    var index = 0
    while (index < routePoints.size) {
      val point = routePoints[index]
      if (point.type != RoutePointType.MINE) {
        index++
        continue
      }
      val segment = resolveMineSegment(index)
      if (segment == null) {
        index++
        continue
      }
      segments.add(segment)
      index = (segment.endIndex + 1).coerceAtLeast(index + 1)
    }
    return segments
  }

  private fun resolveLegacyMineWaypoints(
    pointIndex: Int,
    start: BlockPos,
    end: BlockPos
  ): List<BlockPos> {
    val waypoints = mutableListOf<BlockPos>()
    waypoints.add(start)

    val matchingEndIndex = findLegacyMineEndPointIndex(pointIndex, end)
    if (matchingEndIndex != null && matchingEndIndex > pointIndex + 1) {
      for (i in (pointIndex + 1) until matchingEndIndex) {
        val pos = routePoints[i].pos
        if (waypoints.last() != pos) {
          waypoints.add(pos)
        }
      }
    }

    if (waypoints.last() != end) {
      waypoints.add(end)
    }
    return waypoints
  }

  private fun findLegacyMineEndPointIndex(pointIndex: Int, end: BlockPos): Int? {
    for (i in (pointIndex + 1) until routePoints.size) {
      if (routePoints[i].pos == end) {
        return i
      }
    }
    return null
  }

  private fun handleMine(player: Player, level: net.minecraft.world.level.Level) {
    if (mineMode == RouteMineMode.SINGLE_ANCHOR_MACRO) {
      handleSingleAnchorMacroMine(player, level)
      return
    }

    if (mineMode == RouteMineMode.LEGACY_VEIN) {
      handleLegacyVeinMacroMine(level)
      return
    }

    if (mineMode == RouteMineMode.ANCHOR_LIST) {
      handleAnchorListMacroMine(player, level)
      return
    }

    pruneMineBlocks(level, player)
    if (mineBlocks.isEmpty()) {
      if (mineMode == RouteMineMode.LEGACY_VEIN) {
        val waypoints =
          if (mineTravelWaypoints.isNotEmpty()) {
            mineTravelWaypoints
          } else {
            val start = minePointStart
            val end = minePointEnd
            if (start != null && end != null) {
              listOf(start, end)
            } else {
              emptyList()
            }
          }

        if (waypoints.isNotEmpty()) {
          val vein = buildMineVein(level, waypoints)
          if (vein != null && vein.blocks.isNotEmpty()) {
            mineBlocks = vein.blocks
            mineBlockId = vein.blockId
            mineOrderedBlocks = buildMineOrder(vein.blocks, waypoints)
          } else {
            finishMine("Vein complete.")
            return
          }
        } else {
          finishMine("Vein complete.")
          return
        }
      } else {
        finishMine("Anchors complete.")
        return
      }
    }

    val target = selectMineTarget(level, player, mineBlocks)
    if (target != null) {
      mineTarget = target
      // Keep pathfinder running so the player continues moving while mining.
      // The drill fires while moving through the vein rather than stopping per block.
      startMining(target, level.gameTime)
      return
    }

    stopMiningKeys()
    stopRouteRotation()

    val nextPending = selectNextPendingMineBlock(mineBlocks)
    if (nextPending != null) {
      if (!attemptMineEtherwarpRecovery(level, player, nextPending)) {
        moveToward(level, player, nextPending)
      }
      return
    } else {
      finishMine("No mine targets.")
    }
  }

  private fun startLegacyVeinAutomation(
    level: net.minecraft.world.level.Level,
    waypoints: List<BlockPos>
  ): Boolean {
    val vein = buildMineVein(level, waypoints) ?: return false
    if (vein.blocks.isEmpty()) return false

    mineBlocks = vein.blocks
    mineOrderedBlocks = buildMineOrder(vein.blocks, waypoints)
    mineBlockId = vein.blockId

    val anchor = waypoints.firstOrNull() ?: return false
    val macroType = MiningBlockRegistry.BLOCK_ID_TO_TYPE[vein.blockId] ?: "Custom"
    MiningMacroModule.startForAutomation(macroType, anchor = anchor, customBlockId = vein.blockId)
    routeOwnsMiningMacro = true
    return true
  }

  private fun handleLegacyVeinMacroMine(level: net.minecraft.world.level.Level) {
    val waypoints =
      if (mineTravelWaypoints.isNotEmpty()) {
        mineTravelWaypoints
      } else {
        listOfNotNull(minePointStart, minePointEnd).distinct()
      }

    if (waypoints.isEmpty()) {
      finishMine("Vein complete.")
      return
    }

    if (routeOwnsMiningMacro && !MiningMacroModule.isActive) {
      routeOwnsMiningMacro = false
    }

    val hasVein = buildMineVein(level, waypoints)?.blocks?.isNotEmpty() == true
    if (!routeOwnsMiningMacro) {
      stopMiningKeys()
      stopRouteRotation()
      MovementManager.clearForcedMovement()

      if (!hasVein) {
        finishMine("Vein complete.")
        return
      }

      if (!startLegacyVeinAutomation(level, waypoints)) {
        finishMine("Vein complete.")
      }
      return
    }

    if (!MiningMacroModule.hasCurrentVein() && !hasVein) {
      stopOwnedMiningMacro()
      finishMine("Vein complete.")
    }
  }

  private fun handleSingleAnchorMacroMine(player: Player, level: net.minecraft.world.level.Level) {
    val anchor = mineAnchors.firstOrNull() ?: run {
      finishMine("Mine anchor missing.")
      return
    }

    val knownBlockId = resolveMineBlockIdNearAnchor(level, player, anchor, mineBlockId)
    if (!knownBlockId.isNullOrBlank()) {
      mineBlockId = knownBlockId
    }

    if (routeOwnsMiningMacro && !MiningMacroModule.isActive) {
      routeOwnsMiningMacro = false
      mineSingleAnchorStarted = false
    }

    val hasOre =
      knownBlockId != null && hasMatchingOreNearAnchor(level, player, anchor, knownBlockId)

    if (!mineSingleAnchorStarted) {
      stopMiningKeys()
      stopRouteRotation()
      MovementManager.clearForcedMovement()

      if (!hasOre) {
        return
      }

      val nearbyIds = collectMineBlockIdsNearAnchor(level, player, anchor).apply {
        if (!knownBlockId.isNullOrBlank()) add(knownBlockId)
      }
      val macroType = deriveMacroTypeLabel(nearbyIds)
      MiningMacroModule.startForAutomation(
        macroType,
        anchor = anchor,
        customBlockIds = nearbyIds,
      )
      routeOwnsMiningMacro = true
      mineSingleAnchorStarted = true
      return
    }

    if (!routeOwnsMiningMacro) {
      mineSingleAnchorStarted = false
      return
    }

    if (!MiningMacroModule.hasCurrentVein() && !hasOre) {
      stopOwnedMiningMacro()
      finishMine("Vein complete.")
    }
  }

  private fun handleAnchorListMacroMine(player: Player, level: net.minecraft.world.level.Level) {
    if (routeOwnsMiningMacro && !MiningMacroModule.isActive) {
      routeOwnsMiningMacro = false
    }

    while (true) {
      val anchor = mineOrderedBlocks.firstOrNull { it in mineBlocks } ?: run {
        finishMine("Anchors complete.")
        return
      }

      val knownBlockId = resolveMineBlockIdNearAnchor(level, player, anchor, mineAnchorBlockIds[anchor])
      if (!knownBlockId.isNullOrBlank()) {
        mineAnchorBlockIds[anchor] = knownBlockId
      }
      val hasOre = !knownBlockId.isNullOrBlank()

      if (!routeOwnsMiningMacro) {
        stopMiningKeys()
        stopRouteRotation()
        MovementManager.clearForcedMovement()

        if (!hasOre) {
          mineBlocks.remove(anchor)
          continue
        }

        val nearbyIds = collectMineBlockIdsNearAnchor(level, player, anchor).apply {
          if (!knownBlockId.isNullOrBlank()) add(knownBlockId)
        }
        val macroType = deriveMacroTypeLabel(nearbyIds)
        MiningMacroModule.startForAutomation(
          macroType,
          anchor = anchor,
          customBlockIds = nearbyIds,
        )
        routeOwnsMiningMacro = true
        return
      }

      if (MiningMacroModule.hasCurrentVein() || hasOre) {
        return
      }

      stopOwnedMiningMacro()
      mineBlocks.remove(anchor)
    }
  }

  private fun finishMine(reason: String) {
    ChatUtils.sendMessage(reason)
    stopOwnedMiningMacro()
    stopMiningKeys()
    mc.options.keyShift?.setDown(false)
    stopRouteRotation()
    resetMineState()
    completePoint()
  }

  private fun completePoint(chainNextWalkImmediately: Boolean = false) {
    val completedPoint = activePoint
    lastAutomationCompletionPos =
      completedPoint?.pos?.immutable()
        ?: lastResolvedTarget?.immutable()
        ?: lastTarget?.immutable()
    val nextIndex = when {
      completedPoint?.type == RoutePointType.MINE && activeMineEndIndex >= routeIndex ->
        activeMineEndIndex + 1
      completedPoint?.type == RoutePointType.NORMAL && walkSegmentEndIndex > routeIndex ->
        walkSegmentEndIndex + 1
      else -> routeIndex + 1
    }
    action = RouteAction.NONE
    awaitingArrival = false
    walkCompletePointOnArrival = true
    lastTarget = null
    lastResolvedTarget = null
    activePoint = null
    walkRetryCount = 0
    walkSegmentEndIndex = -1
    routeIndex = nextIndex
    stopMiningKeys()
    if (completedPoint?.type == RoutePointType.MINE) {
      mc.options.keyShift?.setDown(false)
    }
    stopOwnedMiningMacro()
    stopRouteRotation()
    resetMineState()

    if (!advanceRouteIndexForLoop()) {
      stopRoute("Route complete.")
      return
    }
    if (!routeRunning) {
      return
    }

    val keepEtherwarp = shouldKeepEtherwarpForNextPoint()
    if (keepEtherwarp) {
      ensureEtherwarpHotbarSelected()
    } else {
      restoreEtherwarpSlot()
    }
    resetWarp(releaseSneak = !keepEtherwarp)
    updateStatus()

    if (chainNextWalkImmediately) {
      val player = mc.player
      val level = mc.level
      if (player != null && level != null && shouldChainWalkPoint(level)) {
        startPointAtCurrentIndex(player, level)
      }
    }
  }

  private fun shouldChainWalkPoint(level: net.minecraft.world.level.Level): Boolean {
    val point = routePoints.getOrNull(routeIndex) ?: return false
    return when (point.type) {
      RoutePointType.NORMAL -> true
      RoutePointType.WARP -> !hasEtherwarpAvailable() && resolveWarpPoint(level, point.pos) != null
      else -> false
    }
  }

  private fun advanceRouteIndexForLoop(): Boolean {
    if (routeIndex < routePoints.size) return true
    val shouldLoop = activeLoopOverride ?: loopRoute.value
    if (!shouldLoop || routePoints.isEmpty()) return false
    // Loop is on: restart from the very beginning (point 0 = warp to forge/camp).
    routeIndex = 0
    return true
  }

  /**
   * Scans route points for two entries with the same block position (at least one
   * point apart). The first match defines the mining loop: points before
   * [loopStartIndex] are the one-time travel route; points from [loopStartIndex]
   * to [loopEndIndex] (inclusive) are the repeating mining loop.
   */
  private fun detectMiningLoop() {
    // Auto-loop detection removed: intersection points in a route no longer create
    // implicit loops. Looping is controlled exclusively by the Loop Route setting,
    // which always restarts from index 0 (the forge/camp warp at the route start).
    loopStartIndex = -1
    loopEndIndex = -1
  }

  private fun resetRuntimeState() {
    activeLoopOverride = null
    action = RouteAction.NONE
    awaitingArrival = false
    walkCompletePointOnArrival = true
    lastTarget = null
    lastResolvedTarget = null
    activePoint = null
    walkRetryCount = 0
    activeMineEndIndex = -1
    walkSegmentEndIndex = -1
    loopStartIndex = -1
    loopEndIndex = -1
    stopOwnedMiningMacro()
    stopMiningKeys()
    stopRouteRotation()
    mc.options.keyUse?.setDown(false)
    mc.options.keyShift?.setDown(false)
    setWalkKeys(false, false, false, false, false, false, false)
    restoreEtherwarpSlot()
    resetWarp()
    resetMineState()
    lastPathStartTick = 0L
    screenPauseNoticeTick = 0L
    lastSuccessfulWarpTarget = null
    lastSuccessfulWarpTick = -1L
  }

  private fun resetMineState() {
    mineMode = RouteMineMode.NONE
    mineBlocks.clear()
    mineAnchorBlockIds.clear()
    mineOrderedBlocks = emptyList()
    mineBlockId = null
    mineTarget = null
    minePathTarget = null
    mineAnchors = emptyList()
    minePointStart = null
    minePointEnd = null
    mineTravelWaypoints = emptyList()
    activeMineEndIndex = -1
    mineSingleAnchorStarted = false
    miningActive = false
    mineDrillWarnTick = 0L
  }

  private fun startMining(target: BlockPos, nowTick: Long) {
    ensureMineDrillSelected(nowTick)
    val aim = Vec3(target.x + 0.5, target.y + 0.5, target.z + 0.5)
    RotationExecutor.rotateTo(AngleUtils.getRotation(aim), rotationStrategy)
    mc.options.keyAttack?.setDown(true)
    miningActive = true
  }

  private fun ensureMineDrillSelected(nowTick: Long) {
    val player = mc.player ?: return
    val selected = player.inventory.getItem(player.inventory.selectedSlot)
    val selectedName = selected.hoverName?.string.orEmpty().lowercase()
    if (selectedName.contains("drill")) return

    val drillSlot = InventoryUtils.findItemInHotbar("drill")
    if (drillSlot in 0..8) {
      InventoryUtils.holdHotbarSlot(drillSlot)
      return
    }

    if (nowTick - mineDrillWarnTick >= MINE_DRILL_WARN_INTERVAL_TICKS) {
      mineDrillWarnTick = nowTick
      ChatUtils.sendMessage("Routes mine: no drill found in hotbar.")
    }
  }

  private fun stopMiningKeys() {
    if (miningActive) {
      mc.options.keyAttack?.setDown(false)
      miningActive = false
    }
  }

  private fun selectMineTarget(
    level: net.minecraft.world.level.Level,
    player: Player,
    blocks: Set<BlockPos>
  ): BlockPos? {
    val ordered = if (mineOrderedBlocks.isNotEmpty()) mineOrderedBlocks else blocks.toList()
    if (ordered.isEmpty()) return null
    val rangeSq = MINE_RANGE * MINE_RANGE
    // Anchor blocks are ordered first so explicit route anchors are mined in sequence.
    for (pos in ordered) {
      if (!blocks.contains(pos)) continue
      if (distanceToBlockSq(player, pos) > rangeSq) continue
      if (MINE_REQUIRE_LOS && !hasMineLineOfSight(level, player, pos)) continue
      return pos
    }
    return null
  }

  private fun selectNextPendingMineBlock(blocks: Set<BlockPos>): BlockPos? {
    val ordered = if (mineOrderedBlocks.isNotEmpty()) mineOrderedBlocks else blocks.toList()
    // Return the first remaining anchor/path-ordered block for etherwarp/pathing recovery.
    for (pos in ordered) {
      if (blocks.contains(pos)) return pos
    }
    return null
  }

  private fun buildMineOrder(
    blocks: Set<BlockPos>,
    travelWaypoints: List<BlockPos>
  ): List<BlockPos> {
    if (blocks.isEmpty()) return emptyList()

    val waypoints = mutableListOf<BlockPos>()
    for (pos in travelWaypoints) {
      if (waypoints.isEmpty() || waypoints.last() != pos) {
        waypoints.add(pos)
      }
    }
    if (waypoints.isEmpty()) {
      return blocks.toList()
    }
    if (waypoints.size == 1) {
      val only = waypoints.first()
      val anchorPriority = waypoints.filter { it in blocks }.distinct()
      val remaining = blocks.asSequence().filter { it !in anchorPriority }
      return anchorPriority + remaining
        .sortedBy { pos -> pos.distSqr(only).toDouble() }
        .toList()
    }

    val centers = waypoints.map { pos ->
      Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
    }
    val prefixDistances = DoubleArray(centers.size)
    val segmentLengths = DoubleArray(centers.size - 1)
    for (i in 0 until centers.size - 1) {
      val a = centers[i]
      val b = centers[i + 1]
      val segDx = b.x - a.x
      val segDy = b.y - a.y
      val segDz = b.z - a.z
      val len = kotlin.math.sqrt(segDx * segDx + segDy * segDy + segDz * segDz)
      segmentLengths[i] = len
      prefixDistances[i + 1] = prefixDistances[i] + len
    }
    val totalLength = prefixDistances.last().coerceAtLeast(1.0e-6)
    val startCenter = centers.first()

    val ranked = blocks.map { pos ->
      val px = pos.x + 0.5
      val py = pos.y + 0.5
      val pz = pos.z + 0.5

      var bestLateralDistSq = Double.POSITIVE_INFINITY
      var bestProgressDistance = 0.0

      for (i in 0 until centers.size - 1) {
        val a = centers[i]
        val b = centers[i + 1]
        val segDx = b.x - a.x
        val segDy = b.y - a.y
        val segDz = b.z - a.z
        val lenSq = segDx * segDx + segDy * segDy + segDz * segDz
        if (lenSq <= 1.0e-6) continue

        val vx = px - a.x
        val vy = py - a.y
        val vz = pz - a.z
        val t = ((vx * segDx + vy * segDy + vz * segDz) / lenSq).coerceIn(0.0, 1.0)
        val cx = a.x + segDx * t
        val cy = a.y + segDy * t
        val cz = a.z + segDz * t
        val lx = px - cx
        val ly = py - cy
        val lz = pz - cz
        val lateralDistSq = lx * lx + ly * ly + lz * lz
        val progressDistance = prefixDistances[i] + segmentLengths[i] * t

        if (lateralDistSq < bestLateralDistSq) {
          bestLateralDistSq = lateralDistSq
          bestProgressDistance = progressDistance
        }
      }

      val progress = (bestProgressDistance / totalLength).coerceIn(0.0, 1.0)
      val startDx = px - startCenter.x
      val startDy = py - startCenter.y
      val startDz = pz - startCenter.z
      val startDistSq = startDx * startDx + startDy * startDy + startDz * startDz
      MineOrderEntry(pos, progress, bestLateralDistSq, startDistSq)
    }

    val orderedBlocks = ranked
      .sortedWith(
        compareBy<MineOrderEntry>(
          { it.progress },
          { it.lateralDistSq },
          { it.startDistSq }
        )
      )
      .map { it.pos }
    val anchorPriority = waypoints.filter { it in blocks }.distinct()
    if (anchorPriority.isEmpty()) return orderedBlocks
    return anchorPriority + orderedBlocks.filter { it !in anchorPriority }
  }

  private fun moveToward(
    level: net.minecraft.world.level.Level,
    player: Player,
    target: BlockPos
  ) {
    val approach = findApproach(level, player, target) ?: return
    PathfindingModule.ensureEnabledForAutomation("routes")
    val distSq = minePathTarget?.distSqr(approach)?.toDouble() ?: Double.POSITIVE_INFINITY
    if (!nativeActive() || distSq > 1.0) {
      if (level.gameTime - lastPathStartTick < 8L) {
        return
      }
      lastPathStartTick = level.gameTime
      NativePathfinder.setTarget(approach.x + 0.5, approach.y.toDouble(), approach.z + 0.5)
      minePathTarget = approach
    }
    NativePathfinder.tick()?.applyToPlayer()
  }

  private fun findApproach(
    level: net.minecraft.world.level.Level,
    player: Player,
    target: BlockPos
  ): BlockPos? {
    var best: BlockPos? = null
    var bestScore = Double.POSITIVE_INFINITY
    val origin = player.blockPosition()
    val offsets = arrayOf(
      intArrayOf(1, 0, 0), intArrayOf(-1, 0, 0), intArrayOf(0, 0, 1), intArrayOf(0, 0, -1),
      intArrayOf(1, -1, 0), intArrayOf(-1, -1, 0), intArrayOf(0, -1, 1), intArrayOf(0, -1, -1),
      intArrayOf(1, 1, 0), intArrayOf(-1, 1, 0), intArrayOf(0, 1, 1), intArrayOf(0, 1, -1),
      intArrayOf(1, 0, 1), intArrayOf(1, 0, -1), intArrayOf(-1, 0, 1), intArrayOf(-1, 0, -1),
      intArrayOf(0, 2, 0), intArrayOf(0, -2, 0),
    )
    for (off in offsets) {
      val pos = target.offset(off[0], off[1], off[2])
      if (!MinecraftPathingRules.isWalkable(level, pos)) continue
      val playerDistSq = origin.distSqr(pos).toDouble()
      val targetDistSq = pos.distSqr(target).toDouble()
      val score = playerDistSq + targetDistSq * 0.5
      if (score < bestScore) {
        bestScore = score
        best = pos
      }
    }
    if (best != null) return best
    return MinecraftPathingRules.resolveTarget(level, target)
  }

  private fun pruneMineBlocks(level: net.minecraft.world.level.Level, player: Player) {
    val iterator = mineBlocks.iterator()
    while (iterator.hasNext()) {
      val pos = iterator.next()
      when (mineMode) {
        RouteMineMode.ANCHOR_LIST -> {
          val expectedId = mineAnchorBlockIds[pos]
          val state = level.getBlockState(pos)
          if (
            state.isAir ||
            (expectedId != null && blockIdAt(level, pos) != expectedId) ||
            (expectedId == null && state.getDestroyProgress(player, level, pos) <= 0f)
          ) {
            iterator.remove()
          }
        }
        else -> {
          val id = mineBlockId ?: return
          if (blockIdAt(level, pos) != id) {
            iterator.remove()
          }
        }
      }
    }
  }

  private fun resolveMineBlockIdAt(level: net.minecraft.world.level.Level, pos: BlockPos): String? {
    val state = level.getBlockState(pos)
    if (state.isAir) return null
    val blockId = blockIdAt(level, pos)
    return blockId.takeUnless { MiningBlockRegistry.isBlacklisted(it) }
  }

  private fun hasMatchingOreNearAnchor(
    level: net.minecraft.world.level.Level,
    player: Player,
    anchor: BlockPos,
    blockId: String
  ): Boolean {
    val radius = 4
    for (dy in -radius..radius) {
      for (dx in -radius..radius) {
        for (dz in -radius..radius) {
          val pos = anchor.offset(dx, dy, dz)
          if (blockIdAt(level, pos) != blockId) continue
          if (level.getBlockState(pos).getDestroyProgress(player, level, pos) <= 0f) continue
          return true
        }
      }
    }
    return false
  }

  /**
   * Collect every distinct mineable block ID within the anchor scan radius. Used to
   * expand the mining macro's vein selection beyond a single ore type so that
   * clustered ores of different types near the anchor are all mined together
   * instead of the macro breaking one block and moving on.
   */
  private fun collectMineBlockIdsNearAnchor(
    level: net.minecraft.world.level.Level,
    player: Player,
    anchor: BlockPos
  ): LinkedHashSet<String> {
    val result = LinkedHashSet<String>()
    val r = MINE_ANCHOR_SCAN_RADIUS
    for (dy in -r..r) {
      for (dx in -r..r) {
        for (dz in -r..r) {
          val pos = anchor.offset(dx, dy, dz)
          val blockId = resolveMineBlockIdAt(level, pos) ?: continue
          if (!isRegisteredMineBlockId(blockId)) continue
          if (level.getBlockState(pos).getDestroyProgress(player, level, pos) <= 0f) continue
          result.add(blockId)
        }
      }
    }
    return result
  }

  /**
   * Build the "blockTypes" string passed to MiningMacroModule from a set of raw
   * block IDs. Each registered ID maps to its ore type; any unregistered ID falls
   * back to "Custom" so it's still included via automationCustomBlockIds.
   */
  private fun deriveMacroTypeLabel(blockIds: Set<String>): String {
    if (blockIds.isEmpty()) return "Custom"
    val types = LinkedHashSet<String>()
    var hasUnregistered = false
    for (id in blockIds) {
      val type = MiningBlockRegistry.BLOCK_ID_TO_TYPE[id]
      if (type != null) types.add(type) else hasUnregistered = true
    }
    if (hasUnregistered || types.isEmpty()) types.add("Custom")
    return types.joinToString(", ")
  }

  private fun resolveMineBlockIdNearAnchor(
    level: net.minecraft.world.level.Level,
    player: Player,
    anchor: BlockPos,
    preferredBlockId: String? = null
  ): String? {
    val preferred =
      preferredBlockId
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !MiningBlockRegistry.isBlacklisted(it) }
    if (preferred != null && hasMatchingOreNearAnchor(level, player, anchor, preferred)) {
      return preferred
    }

    val directBlockId = resolveMineBlockIdAt(level, anchor)?.takeIf(::isRegisteredMineBlockId)
    if (directBlockId != null && hasMatchingOreNearAnchor(level, player, anchor, directBlockId)) {
      return directBlockId
    }

    var bestBlockId: String? = null
    var bestDistSq = Double.POSITIVE_INFINITY
    for (dy in -MINE_ANCHOR_SCAN_RADIUS..MINE_ANCHOR_SCAN_RADIUS) {
      for (dx in -MINE_ANCHOR_SCAN_RADIUS..MINE_ANCHOR_SCAN_RADIUS) {
        for (dz in -MINE_ANCHOR_SCAN_RADIUS..MINE_ANCHOR_SCAN_RADIUS) {
          val pos = anchor.offset(dx, dy, dz)
          val blockId = resolveMineBlockIdAt(level, pos) ?: continue
          if (!isRegisteredMineBlockId(blockId)) continue
          if (level.getBlockState(pos).getDestroyProgress(player, level, pos) <= 0f) continue
          val distSq = pos.distSqr(anchor).toDouble()
          if (distSq < bestDistSq) {
            bestDistSq = distSq
            bestBlockId = blockId
          }
        }
      }
    }
    return bestBlockId
  }

  private fun isRegisteredMineBlockId(blockId: String): Boolean =
    MiningBlockRegistry.BLOCK_ID_TO_TYPE.containsKey(blockId)

  private fun buildMineVein(
    level: net.minecraft.world.level.Level,
    waypoints: List<BlockPos>
  ): MineVein? {
    if (waypoints.isEmpty()) return null
    val start = waypoints.first()
    val minX = waypoints.minOf { it.x }
    val maxX = waypoints.maxOf { it.x }
    val minY = waypoints.minOf { it.y }
    val maxY = waypoints.maxOf { it.y }
    val minZ = waypoints.minOf { it.z }
    val maxZ = waypoints.maxOf { it.z }

    val seed = findMineSeed(level, start, minX, maxX, minY, maxY, minZ, maxZ) ?: return null
    val blockId = blockIdAt(level, seed)

    val blocks = LinkedHashSet<BlockPos>()
    val queue = ArrayDeque<BlockPos>()
    queue.add(seed)
    blocks.add(seed)

    while (queue.isNotEmpty() && blocks.size < MINE_MAX_BLOCKS) {
      val pos = queue.removeFirst()
      for (dir in Direction.values()) {
        val next = pos.relative(dir)
        if (abs(next.x - seed.x) > MINE_VEIN_SCAN_RADIUS) continue
        if (abs(next.y - seed.y) > MINE_VEIN_SCAN_RADIUS) continue
        if (abs(next.z - seed.z) > MINE_VEIN_SCAN_RADIUS) continue
        if (blocks.contains(next)) continue
        if (blockIdAt(level, next) != blockId) continue
        blocks.add(next)
        queue.add(next)
        if (blocks.size >= MINE_MAX_BLOCKS) break
      }
    }

    return MineVein(blockId, blocks)
  }

  private fun findMineSeed(
    level: net.minecraft.world.level.Level,
    start: BlockPos,
    minX: Int,
    maxX: Int,
    minY: Int,
    maxY: Int,
    minZ: Int,
    maxZ: Int
  ): BlockPos? {
    if (!level.getBlockState(start).isAir) {
      return start
    }
    for (y in minY..maxY) {
      for (x in minX..maxX) {
        for (z in minZ..maxZ) {
          val pos = BlockPos(x, y, z)
          if (!level.getBlockState(pos).isAir) {
            return pos
          }
        }
      }
    }
    return null
  }

  private fun hasLineOfSight(
    level: net.minecraft.world.level.Level,
    player: Player,
    target: BlockPos
  ): Boolean {
    val center = Vec3(target.x + 0.5, target.y + 0.5, target.z + 0.5)
    return hasLineOfSight(level, player, target, center)
  }

  private fun hasLineOfSight(
    level: net.minecraft.world.level.Level,
    player: Player,
    target: BlockPos,
    point: Vec3
  ): Boolean {
    val eye = player.eyePosition
    val hit = level.clip(
      net.minecraft.world.level.ClipContext(
        eye,
        point,
        net.minecraft.world.level.ClipContext.Block.OUTLINE,
        net.minecraft.world.level.ClipContext.Fluid.NONE,
        player
      )
    )
    return hit.type == net.minecraft.world.phys.HitResult.Type.BLOCK &&
      hit.blockPos == target
  }

  private fun hasMineLineOfSight(
    level: net.minecraft.world.level.Level,
    player: Player,
    target: BlockPos
  ): Boolean {
    val center = Vec3(target.x + 0.5, target.y + 0.5, target.z + 0.5)
    val points = listOf(
      center,
      Vec3(center.x + 0.24, center.y, center.z),
      Vec3(center.x - 0.24, center.y, center.z),
      Vec3(center.x, center.y + 0.24, center.z),
      Vec3(center.x, center.y - 0.24, center.z),
      Vec3(center.x, center.y, center.z + 0.24),
      Vec3(center.x, center.y, center.z - 0.24),
    )
    for (point in points) {
      if (hasLineOfSight(level, player, target, point)) {
        return true
      }
    }
    return false
  }

  private fun blockIdAt(level: net.minecraft.world.level.Level, pos: BlockPos): String {
    val state = level.getBlockState(pos)
    return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.block).toString()
  }

  private fun distanceToBlockSq(player: Player, pos: BlockPos): Double {
    val dx = (pos.x + 0.5) - player.x
    val dy = (pos.y + 0.5) - player.y
    val dz = (pos.z + 0.5) - player.z
    return dx * dx + dy * dy + dz * dz
  }

  private fun hasArrived(
    player: Player,
    target: BlockPos,
    cautious: Boolean = false
  ): Boolean {
    if (player.blockPosition() == target || player.blockPosition().below() == target) {
      return true
    }
    val dx = (target.x + 0.5) - player.x
    val dy = target.y - player.y
    val dz = (target.z + 0.5) - player.z
    val distSq = dx * dx + dy * dy + dz * dz
    if (distSq <= ARRIVAL_DISTANCE_SQ) {
      return true
    }
    if (!cautious) {
      return false
    }
    val horizontalDistSq = dx * dx + dz * dz
    return horizontalDistSq <= WALK_EDGE_ARRIVAL_DISTANCE_SQ && abs(dy) <= WALK_EDGE_ARRIVAL_VERTICAL
  }

  private fun handleWalkArrival(
    player: Player,
    level: net.minecraft.world.level.Level
  ): Boolean {
    if (!awaitingArrival) return false
    val arrivalTarget = currentWalkArrivalTarget() ?: return false
    val cautiousArrival = shouldUseCautiousWalkApproach(player, level, arrivalTarget)
    if (!hasArrived(player, arrivalTarget, cautiousArrival)) return false

    NativePathfinder.stop()
    MovementManager.setMovementLock(false)
    stopRouteRotation()
    setWalkKeys(false, false, false, false, false, false, false)

    if (walkCompletePointOnArrival) {
      completePoint(chainNextWalkImmediately = true)
    } else {
      action = RouteAction.NONE
      awaitingArrival = false
      lastTarget = null
      lastResolvedTarget = null
      walkCompletePointOnArrival = true
    }
    return true
  }

  private fun currentWalkArrivalTarget(): BlockPos? = lastResolvedTarget ?: lastTarget

  private fun beginSingleWalkPath(
    level: net.minecraft.world.level.Level,
    target: BlockPos,
    resolved: BlockPos,
    completePointOnArrival: Boolean
  ) {
    PathfindingModule.ensureEnabledForAutomation("routes")
    NativePathfinder.stop()
    MovementManager.setMovementLock(false)
    stopRouteRotation()
    NativePathfinder.setTarget(resolved.x + 0.5, resolved.y.toDouble(), resolved.z + 0.5)
    lastPathStartTick = level.gameTime
    action = RouteAction.WALK
    awaitingArrival = true
    walkCompletePointOnArrival = completePointOnArrival
    walkSegmentEndIndex = -1
    lastTarget = target
    lastResolvedTarget = resolved
  }

  private fun restartWalkAutonomousPath(level: net.minecraft.world.level.Level): Boolean {
    val currentPoint = routePoints.getOrNull(routeIndex)
    if (currentPoint?.type == RoutePointType.NORMAL && walkSegmentEndIndex >= routeIndex) {
      startWalkSegment(level)
      return true
    }

    val target = lastTarget ?: return false
    val resolved = lastResolvedTarget ?: resolveApproxTarget(target) ?: return false
    beginSingleWalkPath(level, target, resolved, walkCompletePointOnArrival)
    return true
  }

  private fun holdWalkAutonomousMovement() {
    MovementManager.setMovementLock(true)
    MovementManager.clearForcedMovement()
  }

  private fun applyWalkEdgeSafety(
    player: Player,
    level: net.minecraft.world.level.Level
  ) {
    val arrivalTarget = currentWalkArrivalTarget() ?: return
    if (!shouldUseCautiousWalkApproach(player, level, arrivalTarget)) return
    if (!MovementManager.hasForcedMovement) return

    MovementManager.setMovementLock(true)
    MovementManager.setForcedMovement(
      MovementManager.forcedForward,
      MovementManager.forcedBackward,
      MovementManager.forcedLeft,
      MovementManager.forcedRight,
      MovementManager.forcedJump,
      shift = true,
      sprint = false
    )
  }

  private fun syncWalkKeys() {
    val hasForcedMovement = MovementManager.hasForcedMovement
    setWalkKeys(
      forward = hasForcedMovement && MovementManager.forcedForward,
      backward = hasForcedMovement && MovementManager.forcedBackward,
      left = hasForcedMovement && MovementManager.forcedLeft,
      right = hasForcedMovement && MovementManager.forcedRight,
      jump = hasForcedMovement && MovementManager.forcedJump,
      shift = hasForcedMovement && MovementManager.forcedShift,
      sprint = hasForcedMovement && MovementManager.forcedSprint,
    )
  }

  private fun setWalkKeys(
    forward: Boolean,
    backward: Boolean,
    left: Boolean,
    right: Boolean,
    jump: Boolean,
    shift: Boolean,
    sprint: Boolean,
  ) {
    mc.options.keyUp?.setDown(forward)
    mc.options.keyDown?.setDown(backward)
    mc.options.keyLeft?.setDown(left)
    mc.options.keyRight?.setDown(right)
    mc.options.keyJump?.setDown(jump)
    mc.options.keyShift?.setDown(shift)
    mc.options.keySprint?.setDown(sprint)
  }

  private fun stopRouteRotation() {
    MovementManager.setLookLock(false)
    RotationExecutor.stopRotating()
  }

  private fun applyWalkCameraRotation(player: Player) {
    val targetRotation = resolveWalkTargetRotation(player) ?: run {
      MovementManager.setLookLock(false)
      RotationExecutor.stopIfUsing(routeFollowRotationStrategy)
      return
    }
    MovementManager.setLookLock(true)
    RotationExecutor.rotateTo(targetRotation, routeFollowRotationStrategy)
  }

  private fun resolveWalkLookPoint(player: Player): Vec3? {
    val lookHeight = RotationsModule.routeFollowLookHeight.value
    val nodes = NativePathfinder.cachedPathNodes
    if (nodes.isNotEmpty()) {
      val nearestIndex = nearestWalkNodeIndex(player, nodes)
      if (nearestIndex >= 0) {
        val lookahead = RotationsModule.routeFollowLookaheadNodes.value.toInt().coerceAtLeast(1)
        val startIndex = (nearestIndex + 1).coerceAtMost(nodes.lastIndex)
        val endIndex = (nearestIndex + lookahead).coerceAtMost(nodes.lastIndex)
        var sumX = 0.0
        var sumZ = 0.0
        var count = 0
        for (index in startIndex..endIndex) {
          val node = nodes[index]
          sumX += node.x
          sumZ += node.z
          count++
        }
        val refNode = nodes[endIndex]
        if (count > 0) {
          return Vec3(sumX / count + 0.5, max(refNode.y + lookHeight, player.eyePosition.y), sumZ / count + 0.5)
        }
      }
    }

    val target = currentWalkArrivalTarget() ?: return null
    return Vec3(target.x + 0.5, target.y + lookHeight, target.z + 0.5)
  }

  private fun resolveWalkTargetRotation(player: Player): Rotation? {
    val lookPoint = resolveWalkLookPoint(player) ?: return null
    return AngleUtils.getRotation(player.eyePosition, lookPoint)
  }

  private fun nearestWalkNodeIndex(player: Player, nodes: List<Vec3>): Int {
    var nearestIndex = -1
    var nearestDistSq = Double.POSITIVE_INFINITY
    val px = player.x
    val pz = player.z
    for (index in nodes.indices) {
      val node = nodes[index]
      val dx = node.x - px
      val dz = node.z - pz
      val distSq = dx * dx + dz * dz
      if (distSq < nearestDistSq) {
        nearestDistSq = distSq
        nearestIndex = index
      }
    }
    return nearestIndex
  }

  private fun shouldUseCautiousWalkApproach(
    player: Player,
    level: net.minecraft.world.level.Level,
    target: BlockPos
  ): Boolean {
    if (!isRiskyWalkTarget(level, target)) return false
    return horizontalDistanceToBlockCenterSq(player, target) <= WALK_EDGE_SLOW_DISTANCE_SQ
  }

  private fun isRiskyWalkTarget(
    level: net.minecraft.world.level.Level,
    target: BlockPos
  ): Boolean {
    for (dir in arrayOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
      val adjacent = target.relative(dir)
      if (!MinecraftPathingRules.isPassable(level, adjacent)) continue
      if (!MinecraftPathingRules.isPassable(level, adjacent.above())) continue
      if (!MinecraftPathingRules.isStandable(level, adjacent.below())) {
        return true
      }
    }
    return false
  }

  private fun horizontalDistanceToBlockCenterSq(player: Player, target: BlockPos): Double {
    val dx = (target.x + 0.5) - player.x
    val dz = (target.z + 0.5) - player.z
    return dx * dx + dz * dz
  }

  private fun applyWarpHeadRotation(player: Player, target: Vec3): Pair<Double, Double> {
    val targetRotation = AngleUtils.getRotation(target)
    val now = System.nanoTime()
    val dtSec =
      if (warpLookLastNs == 0L) {
        1.0 / 60.0
      } else {
        ((now - warpLookLastNs) / 1_000_000_000.0).coerceIn(1.0 / 240.0, 0.08)
      }
    warpLookLastNs = now

    val maxYawStep = WARP_LOOK_YAW_SPEED_DPS * dtSec
    val maxPitchStep = WARP_LOOK_PITCH_SPEED_DPS * dtSec

    val yawDelta = AngleUtils.getRotationDelta(player.yRot, targetRotation.yaw).toDouble()
    val pitchDelta = (targetRotation.pitch - player.xRot).toDouble()

    val yawStep = yawDelta.coerceIn(-maxYawStep, maxYawStep).toFloat()
    val pitchStep = pitchDelta.coerceIn(-maxPitchStep, maxPitchStep).toFloat()

    player.yRot = AngleUtils.normalizeAngle(player.yRot + yawStep)
    player.yHeadRot = player.yRot
    player.yBodyRot = player.yRot
    player.xRot = (player.xRot + pitchStep).coerceIn(-89.9f, 89.9f)

    val yawError = kotlin.math.abs(AngleUtils.getRotationDelta(player.yRot, targetRotation.yaw)).toDouble()
    val pitchError = kotlin.math.abs(targetRotation.pitch - player.xRot).toDouble()
    return yawError to pitchError
  }

  private fun resolveWarpAimPoint(
    level: net.minecraft.world.level.Level,
    player: Player,
    target: BlockPos
  ): Vec3 {
    val center = Vec3(target.x + 0.5, target.y + 0.5, target.z + 0.5)
    val eye = player.eyePosition
    val towardX = (eye.x - center.x).coerceIn(-0.28, 0.28)
    val towardZ = (eye.z - center.z).coerceIn(-0.28, 0.28)

    val candidates = listOf(
      center,
      Vec3(center.x, center.y + 0.26, center.z),
      Vec3(center.x, center.y - 0.20, center.z),
      Vec3(center.x + towardX, center.y, center.z),
      Vec3(center.x, center.y, center.z + towardZ),
      Vec3(center.x + towardX, center.y + 0.18, center.z + towardZ),
      Vec3(center.x + towardX, center.y - 0.12, center.z + towardZ),
      Vec3(center.x + 0.24, center.y, center.z),
      Vec3(center.x - 0.24, center.y, center.z),
      Vec3(center.x, center.y, center.z + 0.24),
      Vec3(center.x, center.y, center.z - 0.24),
    )

    for (candidate in candidates) {
      if (hasLineOfSight(level, player, target, candidate)) {
        return candidate
      }
    }
    return center
  }

  private fun startWarp(
    target: BlockPos,
    completePointOnArrival: Boolean = true,
    resumeAction: RouteAction = RouteAction.NONE
  ): Boolean {
    val player = mc.player ?: return false
    val level = mc.level ?: return false
    if (level.gameTime < warpCooldownUntil) return false
    if (wasJustWarpedToTarget(level, player, target)) return false
    if (!ensureEtherwarpHotbarSelected()) return false
    if (!EtherwarpLogic.holdingEtherwarpItem()) return false
    if (!ensureEtherwarpHotbarSelected()) return false

    if (nativeActive()) {
      nativeStop(null)
    }
    MovementManager.setMovementLock(false)
    MovementManager.clearForcedMovement()
    setWalkKeys(false, false, false, false, false, false, false)
    stopRouteRotation()
    mc.options.keyUse?.setDown(false)
    if (!shouldPreserveSneakForWarpStart()) {
      mc.options.keyShift?.setDown(false)
    }

    warpTarget = target
    warpStage = 0
    warpStageElapsedMs = 0.0
    warpStageLastNs = 0L
    warpLookLastNs = 0L
    pendingWarpUseRelease = false
    warpCompletePointOnArrival = completePointOnArrival
    warpResumeAction = resumeAction
    action = RouteAction.WARP
    return true
  }

  private fun handleWarp(
    player: Player,
    level: net.minecraft.world.level.Level
  ) {
    val target = warpTarget ?: run {
      resetWarp()
      return
    }
    MovementManager.clearForcedMovement()

    val warpAimPoint = resolveWarpAimPoint(level, player, target)
    advanceWarpFrameTime()

    when (warpStage) {
      0 -> {
        val (yawError, pitchError) = applyWarpHeadRotation(player, warpAimPoint)
        if (
          (yawError <= WARP_AIM_TOLERANCE && pitchError <= WARP_AIM_TOLERANCE) ||
          warpStageElapsedMs >= WARP_ALIGN_MS
        ) {
          mc.options.keyShift?.setDown(true)
          warpStage = 1
          resetWarpStageTimer()
          return
        }
      }
      1 -> {
        applyWarpHeadRotation(player, warpAimPoint)
        mc.options.keyShift?.setDown(true)
        if (!canWarpToTarget(level, player, target, warpAimPoint)) {
          if (warpStageElapsedMs >= WARP_STAGE1_TIMEOUT_MS) {
            mc.options.keyShift?.setDown(false)
            warpCooldownUntil = level.gameTime + WARP_RETRY_COOLDOWN_TICKS
            val resumeAction = warpResumeAction
            resetWarp()
            action = resumeAction
            return
          }
          return
        }
        if (warpStageElapsedMs >= WARP_SNEAK_MS) {
          val shiftKeyHeld = mc.options.keyShift?.isDown == true
          if (!shiftKeyHeld) {
            return
          }
          mc.options.keyUse?.setDown(false)
          MouseUtils.rightClick()
          pendingWarpUseRelease = true
          warpStage = 2
          resetWarpStageTimer()
          return
        }
        if (warpStageElapsedMs >= WARP_STAGE1_TIMEOUT_MS) {
          mc.options.keyShift?.setDown(false)
          warpCooldownUntil = level.gameTime + WARP_RETRY_COOLDOWN_TICKS
          val resumeAction = warpResumeAction
          resetWarp()
          action = resumeAction
        }
      }
      else -> {
        if (!pendingWarpUseRelease) {
          mc.options.keyUse?.setDown(false)
        }
        val landed = hasArrived(player, target)
        val chainNextWarp = landed && shouldChainEtherwarpAfterCurrentPoint()
        val postWarpMs = if (chainNextWarp) WARP_CHAIN_POST_MS else WARP_POST_MS
        val postWarpAim = if (landed) resolveNextRouteLookPoint(level) else null
        val frameAim =
          if (postWarpAim != null) {
            val t = (warpStageElapsedMs / postWarpMs).coerceIn(0.0, 1.0)
            blendVec3(warpAimPoint, postWarpAim, t)
          } else {
            warpAimPoint
          }
        applyWarpHeadRotation(player, frameAim)

        mc.options.keyShift?.setDown(true)
        if (warpStageElapsedMs >= postWarpMs) {
          warpCooldownUntil = level.gameTime + WARP_COOLDOWN_TICKS
          val arrived = hasArrived(player, target)
          val keepSneakChained = arrived && chainNextWarp && warpCompletePointOnArrival
          if (!keepSneakChained) {
            mc.options.keyShift?.setDown(false)
          }
          if (arrived) {
            lastSuccessfulWarpTarget = target
            lastSuccessfulWarpTick = level.gameTime
          }
          val completePointOnArrival = warpCompletePointOnArrival
          val resumeAction = warpResumeAction
          resetWarp(releaseSneak = !keepSneakChained)
          if (arrived) {
            if (completePointOnArrival) {
              completePoint()
            } else {
              action = resumeAction
            }
          } else {
            action = resumeAction
          }
          return
        }
      }
    }
  }

  private fun advanceWarpFrameTime() {
    val now = System.nanoTime()
    val dtMs =
      if (warpStageLastNs == 0L) {
        0.0
      } else {
        ((now - warpStageLastNs) / 1_000_000.0).coerceIn(0.0, 80.0)
      }
    warpStageLastNs = now
    warpStageElapsedMs += dtMs
  }

  private fun resetWarpStageTimer() {
    warpStageElapsedMs = 0.0
    warpStageLastNs = System.nanoTime()
  }

  private fun resolveNextRouteLookPoint(level: net.minecraft.world.level.Level): Vec3? {
    val nextIndex = routeIndex + 1
    if (nextIndex !in routePoints.indices) return null
    val nextPoint = routePoints[nextIndex]
    val lookBlock =
      if (nextPoint.type == RoutePointType.WARP) {
        resolveWarpPoint(level, nextPoint.pos) ?: nextPoint.pos
      } else {
        nextPoint.pos
      }
    return Vec3(lookBlock.x + 0.5, lookBlock.y + 0.6, lookBlock.z + 0.5)
  }

  private fun attemptRouteVisibleEtherwarpRecovery(
    level: net.minecraft.world.level.Level,
    player: Player
  ): Boolean {
    if (routePoints.isEmpty()) return false
    if (level.gameTime < warpCooldownUntil) return false
    if (!hasEtherwarpAvailable()) return false

    val target = findClosestVisibleRouteWarpTarget(level, player) ?: return false
    return startWarp(target)
  }

  private fun findClosestVisibleRouteWarpTarget(
    level: net.minecraft.world.level.Level,
    player: Player
  ): BlockPos? {
    var best: BlockPos? = null
    var bestDistSq = Double.POSITIVE_INFINITY
    val eye = player.eyePosition
    val startIndex = routeIndex.coerceIn(0, routePoints.lastIndex)

    for (i in startIndex until routePoints.size) {
      val point = routePoints[i]
      val candidate =
        when (point.type) {
          RoutePointType.WARP -> resolveWarpPoint(level, point.pos)
          else -> candidateWarpBlock(level, point.pos)
        } ?: continue

      if (isStandingOnWarpTarget(player, candidate)) continue
      if (wasJustWarpedToTarget(level, player, candidate)) continue
      if (!canWarpToTarget(level, player, candidate)) continue

      val center = Vec3(candidate.x + 0.5, candidate.y + 0.5, candidate.z + 0.5)
      val distSq = eye.distanceToSqr(center)
      if (distSq < bestDistSq) {
        bestDistSq = distSq
        best = candidate
      }
    }
    return best
  }

  private fun attemptMineEtherwarpRecovery(
    level: net.minecraft.world.level.Level,
    player: Player,
    target: BlockPos
  ): Boolean {
    if (level.gameTime < warpCooldownUntil) return true

    val mineTarget = findClosestVisibleMineWarpTarget(level, player, target)
    if (mineTarget != null && startWarp(
        mineTarget,
        completePointOnArrival = false,
        resumeAction = RouteAction.MINE
      )
    ) {
      return true
    }

    return false
  }

  private fun findClosestVisibleMineWarpTarget(
    level: net.minecraft.world.level.Level,
    player: Player,
    focus: BlockPos
  ): BlockPos? {
    if (mineBlocks.isEmpty()) return null

    val candidates = LinkedHashSet<BlockPos>()

    // Scan adjacent walkable tunnel spots around the focus ore block
    fun addAdjacentWalkableLandings(oreBlock: BlockPos) {
      for (dir in Direction.values()) {
        val adj = oreBlock.relative(dir)
        if (!MinecraftPathingRules.isWalkable(level, adj)) continue
        val floor = adj.below()
        if (level.getBlockState(floor).isAir) continue
        if (!MinecraftPathingRules.isPassable(level, adj)) continue
        if (!MinecraftPathingRules.isPassable(level, adj.above())) continue
        candidates.add(floor)
      }
    }

    addAdjacentWalkableLandings(focus)
    candidateWarpBlock(level, focus)?.let { candidates.add(it) }

    val ordered = if (mineOrderedBlocks.isNotEmpty()) mineOrderedBlocks else mineBlocks.toList()
    for (block in ordered) {
      if (!mineBlocks.contains(block)) continue
      addAdjacentWalkableLandings(block)
      if (candidates.size >= MINE_WARP_MAX_CANDIDATES) break
    }

    val eye = player.eyePosition
    var best: BlockPos? = null
    var bestScore = Double.POSITIVE_INFINITY
    for (candidate in candidates) {
      if (isStandingOnWarpTarget(player, candidate)) continue
      if (wasJustWarpedToTarget(level, player, candidate)) continue
      if (!canWarpToTarget(level, player, candidate)) continue
      val center = Vec3(candidate.x + 0.5, candidate.y + 0.5, candidate.z + 0.5)
      val eyeDistSq = eye.distanceToSqr(center)
      val focusDistSq = candidate.distSqr(focus).toDouble()
      val score = focusDistSq * 1.6 + eyeDistSq
      if (score < bestScore) {
        bestScore = score
        best = candidate
      }
    }
    return best
  }

  private fun blendVec3(from: Vec3, to: Vec3, t: Double): Vec3 {
    val clamped = t.coerceIn(0.0, 1.0)
    return Vec3(
      from.x + (to.x - from.x) * clamped,
      from.y + (to.y - from.y) * clamped,
      from.z + (to.z - from.z) * clamped
    )
  }

  private fun resolveWarpPoint(level: net.minecraft.world.level.Level, rawPoint: BlockPos): BlockPos? {
    val direct = candidateWarpBlock(level, rawPoint)
    if (direct != null && isWarpBlockViable(level, direct)) {
      return direct
    }

    var best: BlockPos? = null
    var bestDistSq = Double.POSITIVE_INFINITY
    for (dy in -WARP_RESOLVE_VERTICAL..WARP_RESOLVE_VERTICAL) {
      for (dx in -WARP_RESOLVE_RADIUS..WARP_RESOLVE_RADIUS) {
        for (dz in -WARP_RESOLVE_RADIUS..WARP_RESOLVE_RADIUS) {
          val probe = rawPoint.offset(dx, dy, dz)
          val candidate = candidateWarpBlock(level, probe) ?: continue
          if (!isWarpBlockViable(level, candidate)) continue
          val distSq = candidate.distSqr(rawPoint).toDouble()
          if (distSq < bestDistSq) {
            bestDistSq = distSq
            best = candidate
          }
        }
      }
    }
    return best
  }

  private fun candidateWarpBlock(level: net.minecraft.world.level.Level, pos: BlockPos): BlockPos? {
    return if (MinecraftPathingRules.isWalkable(level, pos)) {
      val support = pos.below()
      if (level.getBlockState(support).isAir) null else support
    } else {
      if (level.getBlockState(pos).isAir) null else pos
    }
  }

  private fun isWarpBlockViable(level: net.minecraft.world.level.Level, block: BlockPos): Boolean {
    if (level.getBlockState(block).isAir) return false
    if (!MinecraftPathingRules.isPassable(level, block.above())) return false
    if (!MinecraftPathingRules.isPassable(level, block.above(2))) return false
    return true
  }

  private fun canWarpToTarget(
    level: net.minecraft.world.level.Level,
    player: Player,
    target: BlockPos,
    aimPoint: Vec3? = null
  ): Boolean {
    if (!isWarpBlockViable(level, target)) return false
    if (!EtherwarpLogic.canEtherwarp()) return false
    val eye = player.eyePosition
    val point = aimPoint ?: resolveWarpAimPoint(level, player, target)
    val range = EtherwarpLogic.getEtherwarpRange().toDouble() + 0.5
    if (eye.distanceToSqr(point) > range * range) return false
    // Use etherwarp's own raycast - standard line-of-sight misses blocks that
    // etherwarp passes through (carpets, vines, flowers, etc.)
    val result = EtherwarpLogic.getEtherwarpResultSneaking()
    return result.succeeded && result.pos == target
  }

  private fun isStandingOnWarpTarget(player: Player, target: BlockPos): Boolean {
    return player.blockPosition().below() == target
  }

  private fun wasJustWarpedToTarget(
    level: net.minecraft.world.level.Level,
    player: Player,
    target: BlockPos
  ): Boolean {
    val lastTarget = lastSuccessfulWarpTarget ?: return false
    if (lastSuccessfulWarpTick < 0L) return false
    if (lastTarget != target) return false
    if (!isStandingOnWarpTarget(player, target)) return false
    return level.gameTime - lastSuccessfulWarpTick <= WARP_REPEAT_BLOCK_SUPPRESS_TICKS
  }

  private fun resetWarp(releaseSneak: Boolean = true) {
    mc.options.keyUse?.setDown(false)
    if (releaseSneak) {
      mc.options.keyShift?.setDown(false)
    }
    stopRouteRotation()
    pendingWarpUseRelease = false
    warpStage = 0
    warpTarget = null
    warpStageElapsedMs = 0.0
    warpStageLastNs = 0L
    warpLookLastNs = 0L
    warpCompletePointOnArrival = true
    warpResumeAction = RouteAction.NONE
  }

  private fun ensureEtherwarpHotbarSelected(): Boolean {
    val player = mc.player ?: return false
    val currentSlot = player.inventory.selectedSlot
    val currentStack = player.inventory.getItem(currentSlot)
    if (EtherwarpLogic.isEtherwarpStack(currentStack)) {
      return true
    }
    if (EtherwarpLogic.isEtherwarpStack(player.offhandItem)) {
      return true
    }
    val slot = EtherwarpLogic.findEtherwarpHotbarSlot()
    if (slot in 0..8) {
      if (warpRestoreSlot == -1) {
        warpRestoreSlot = currentSlot
      }
      InventoryUtils.holdHotbarSlot(slot)
      return true
    }
    return false
  }

  private fun hasEtherwarpAvailable(): Boolean {
    val player = mc.player ?: return false
    val currentStack = player.inventory.getItem(player.inventory.selectedSlot)
    return EtherwarpLogic.isEtherwarpStack(currentStack) ||
      EtherwarpLogic.isEtherwarpStack(player.offhandItem) ||
      EtherwarpLogic.findEtherwarpHotbarSlot() in 0..8
  }

  private fun notifyWarpFallback(routeLabel: String?, points: List<RoutePoint>) {
    if (points.none { it.type == RoutePointType.WARP } || hasEtherwarpAvailable()) return
    val routePrefix = routeLabel?.takeIf { it.isNotBlank() }?.let { "Route \"$it\"" } ?: "Route"
    ChatUtils.sendMessage("$routePrefix has warp points but no AOTV/Etherwarp item was found. Those points will be walked.")
  }

  private fun restoreEtherwarpSlot() {
    if (warpRestoreSlot in 0..8) {
      InventoryUtils.holdHotbarSlot(warpRestoreSlot)
    }
    warpRestoreSlot = -1
  }

  private fun shouldKeepEtherwarpForNextPoint(): Boolean {
    if (routeIndex !in routePoints.indices) return false
    return routePoints[routeIndex].type == RoutePointType.WARP && hasEtherwarpAvailable()
  }

  private fun shouldChainEtherwarpAfterCurrentPoint(): Boolean {
    val nextIndex = routeIndex + 1
    if (nextIndex !in routePoints.indices) return false
    return routePoints[nextIndex].type == RoutePointType.WARP && hasEtherwarpAvailable()
  }

  private fun shouldPreserveSneakForWarpStart(): Boolean {
    return mc.options.keyShift?.isDown == true && shouldKeepEtherwarpForNextPoint()
  }

  private const val MAX_WALK_RETRIES = 3
  private const val WALK_REPATH_GRACE_TICKS = 8L
  private const val ARRIVAL_DISTANCE_SQ = 1.8 * 1.8
  private const val WALK_EDGE_SLOW_DISTANCE_SQ = 2.15 * 2.15
  private const val WALK_EDGE_ARRIVAL_DISTANCE_SQ = 1.9 * 1.9
  private const val WALK_EDGE_ARRIVAL_VERTICAL = 1.35
  private const val APPROX_SCAN_RADIUS = 6
  private const val APPROX_SCAN_VERTICAL = 4
  private const val MINE_RANGE = 4.5
  private const val MINE_ANCHOR_SCAN_RADIUS = 4
  private const val MINE_REQUIRE_LOS = true
  private const val MINE_MAX_BLOCKS = 768
  private const val MINE_VEIN_SCAN_RADIUS = 18
  private const val MINE_WARP_MAX_CANDIDATES = 220
  private const val MINE_DRILL_WARN_INTERVAL_TICKS = 60L
  private const val WARP_AIM_TOLERANCE = 6.0
  private const val WARP_LOOK_YAW_SPEED_DPS = 360.0
  private const val WARP_LOOK_PITCH_SPEED_DPS = 300.0
  private const val WARP_ALIGN_MS = 170.0
  private const val WARP_SNEAK_MS = 85.0
  private const val WARP_POST_MS = 70.0
  private const val WARP_CHAIN_POST_MS = 28.0
  private const val WARP_COOLDOWN_TICKS = 1L
  private const val WARP_STAGE1_TIMEOUT_MS = 240.0
  private const val WARP_RETRY_COOLDOWN_TICKS = 4L
  private const val WARP_REPEAT_BLOCK_SUPPRESS_TICKS = 10L
  private const val WARP_RESOLVE_RADIUS = 2
  private const val WARP_RESOLVE_VERTICAL = 2

  private data class MineVein(
    val blockId: String,
    val blocks: MutableSet<BlockPos>
  )

  private data class MineOrderEntry(
    val pos: BlockPos,
    val progress: Double,
    val lateralDistSq: Double,
    val startDistSq: Double
  )

  private data class MineSegment(
    val startIndex: Int,
    val endIndex: Int,
    val anchors: List<BlockPos>,
    val waypoints: List<BlockPos>,
    val anchorBlockIds: Map<BlockPos, String?>,
    val legacyVein: Boolean
  )

  private data class RouteRenderNode(
    val pos: BlockPos,
    val color: Color,
    val label: String?
  )
}
