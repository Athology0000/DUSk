package org.cobalt.internal.combat

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.awt.Color
import java.io.File
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.cobalt.api.util.render.Render3D
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
import org.cobalt.api.pathfinder.jni.NativePathfinder
import org.cobalt.api.pathfinder.jni.PathStatus
import org.cobalt.api.pathfinder.minecraft.MinecraftPathingRules
import org.cobalt.api.rotation.RotationExecutor
import org.cobalt.api.util.AngleUtils
import org.cobalt.api.util.ChatUtils
import org.cobalt.api.util.InventoryUtils
import org.cobalt.api.util.player.MovementManager
import org.cobalt.internal.etherwarp.EtherwarpLogic
import org.cobalt.internal.pathfinding.PathfindingModule
import org.cobalt.internal.routes.RoutePoint
import org.cobalt.internal.routes.RouteStore
import org.cobalt.internal.routes.RouteType
import org.cobalt.internal.routes.SavedRoute

enum class CombatPatrolPointType(val id: String) {
    WALK("walk"), WARP("warp"), KILL("kill");
    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: WALK
    }
}

data class CombatPatrolPoint(val x: Int, val y: Int, val z: Int, val type: CombatPatrolPointType)

object CombatPatrolModule : Module("Combat Patrol") {

    private val mc: Minecraft = Minecraft.getInstance()
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val patrolDir: File by lazy {
        File(mc.gameDirectory, "config/cobalt/combat_patrol")
    }

    internal val patrolPoints = mutableListOf<CombatPatrolPoint>()

    enum class PatrolState { IDLE, NAVIGATING, WARPING, COMBAT_INTERRUPT, AT_KILL_ZONE }

    var patrolState: PatrolState = PatrolState.IDLE
        private set
    var patrolOwnsPathfinder: Boolean = false
        private set

    private var patrolRunning = false
    private var automationRouteLoaded = false
    private var routeIndex = 0
    private var killZoneClearTicks = 0
    private var killZoneClearedThisTick = false

    // True once the pathfinder has been seen PLANNING or EXECUTING for the current leg.
    // Used together with proximity check to filter stale ARRIVED status.
    private var navPathActivated = false

    // Pending block position from the last right-click, waiting for type selection in PatrolPointPopup.
    private var pendingClickPos: BlockPos? = null

    // -- warp sub-machine state ---------------------------------------------
    private var warpStage = 0
    private var warpTargetPoint: CombatPatrolPoint? = null
    private var warpStageElapsedMs = 0.0
    private var warpStageLastNs = 0L
    private var warpLookLastNs = 0L
    private var warpCooldownUntil = 0L
    private var warpRestoreSlot = -1
    private var lastSuccessfulWarpTarget: BlockPos? = null
    private var lastSuccessfulWarpTick = -1L

    val enabled = CheckboxSetting("Enabled", "Enable the Combat Patrol module.", false)

    internal val routeName = TextSetting("Route Name", "Name used for save/load.", "default")
    private val pointsInfo = InfoSetting("Points", "Number of recorded points.", InfoType.INFO)
    private val statusInfo = InfoSetting("Status", "Current patrol state.", InfoType.INFO)
    private val recordOnRightClick = CheckboxSetting("Record on Right Click", "Append a point when you right-click a block.", false)
    val loopRoute = CheckboxSetting("Loop Route", "Wrap back to first point at end.", true)
    val startFromNearest = CheckboxSetting("Start From Nearest", "Begin at the closest point to your position.", true)
    val pointType = ModeSetting("Point Type", "Type used when adding points.", 0, arrayOf("Walk", "Warp", "Kill"))
    val killZoneRadius = SliderSetting("Kill Zone Radius", "Mob search radius around a Kill point (blocks).", 16.0, 4.0, 64.0)
    val killZoneDwellTicks = SliderSetting("Kill Zone Dwell Ticks", "Zero-mob ticks required before advancing past a Kill point.", 60.0, 10.0, 200.0, step = 1.0)
    val aotvSlot = ModeSetting("AOTV Slot", "Hotbar slot (1-9) holding your AOTV item.", 0, arrayOf("1","2","3","4","5","6","7","8","9"))

    private val addPointAction = ActionSetting("Add Point", "Record your current position.", "Add") { addPointFromPlayer() }
    private val removeLastAction = ActionSetting("Remove Last", "Remove the last recorded point.", "Remove") {
        if (patrolPoints.isNotEmpty()) { patrolPoints.removeAt(patrolPoints.lastIndex); updatePointsInfo() }
    }
    private val clearRouteAction = ActionSetting("Clear Route", "Remove all patrol points.", "Clear") {
        patrolPoints.clear(); updatePointsInfo()
    }
    private val saveRouteAction = ActionSetting("Save Route", "Save route to disk.", "Save") { saveRoute() }
    private val loadRouteAction = ActionSetting("Load Route", "Load route from disk.", "Load") { loadRoute() }
    private val startPatrolAction = ActionSetting("Start Patrol", "Start the patrol.", "Start") { startPatrol() }
    private val stopPatrolAction = ActionSetting("Stop Patrol", "Stop the patrol.", "Stop") { stopPatrol() }

    init {
        addSetting(
            enabled,
            routeName, pointsInfo, statusInfo,
            recordOnRightClick, loopRoute, startFromNearest, pointType,
            killZoneRadius, killZoneDwellTicks, aotvSlot,
            addPointAction, removeLastAction, clearRouteAction,
            saveRouteAction, loadRouteAction,
            startPatrolAction, stopPatrolAction,
        )
        statusInfo.value = "Idle"
        org.cobalt.api.event.EventBus.register(this)
    }

    @SubscribeEvent
    fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
        updatePointsInfo()
        if (!enabled.value) {
            if (patrolRunning) stopPatrol("Patrol module disabled.")
            return
        }
        if (patrolPoints.isEmpty()) {
            if (patrolRunning) stopPatrol("No patrol points.")
            return
        }
        if (!patrolRunning) return

        when (patrolState) {
            PatrolState.NAVIGATING -> {
                val cmd = NativePathfinder.tick()
                if (cmd != null) cmd.applyToPlayer()
                else MovementManager.clearForcedMovement()  // PLANNING: clear stale flags (prevents jump spam)
                // Only mark activated once the pathfinder is genuinely executing
                // (tick() returns non-null exclusively for EXECUTING status).
                // PLANNING status does NOT count - the native side can transition
                // PLANNING -> ARRIVED in one native update without the player moving.
                if (cmd != null) navPathActivated = true

                val s = NativePathfinder.status
                if (s == PathStatus.ARRIVED || s == PathStatus.FAILED) {
                    val current = patrolPoints.getOrNull(routeIndex)
                    val player = mc.player
                    val distSq = if (player != null && current != null) {
                        val dx = player.x - (current.x + 0.5)
                        val dy = player.y - current.y.toDouble()
                        val dz = player.z - (current.z + 0.5)
                        dx * dx + dy * dy + dz * dz
                    } else Double.MAX_VALUE

                    // Accept arrival if:
                    //  - FAILED - always skip
                    //  - navPathActivated - player actually moved along this path leg
                    //  - distSq <= trivial threshold - player was already at the point
                    val confirmedArrival = s == PathStatus.FAILED
                        || navPathActivated
                        || distSq <= NAV_TRIVIAL_DIST_SQ

                    if (confirmedArrival) {
                        navPathActivated = false
                        if (s == PathStatus.FAILED) ChatUtils.sendMessage("Patrol: pathfinding failed at point ${routeIndex + 1}, skipping.")
                        if (current?.type == CombatPatrolPointType.KILL) {
                            NativePathfinder.stop()
                            patrolOwnsPathfinder = false
                            patrolState = PatrolState.AT_KILL_ZONE
                            killZoneClearTicks = 0
                        } else {
                            advanceAndNavigate()
                        }
                    } else {
                        // Stale ARRIVED from the previous path leg - the native side hasn't
                        // started planning our new target yet.  Re-issue setTarget so the
                        // engine resets to PLANNING; on the next tick tick() will eventually
                        // return non-null and navPathActivated will be set correctly.
                        if (current != null) {
                            val level = mc.level
                            if (level != null) {
                                val resolved = MinecraftPathingRules.resolveTarget(
                                    level, BlockPos(current.x, current.y, current.z)
                                ) ?: BlockPos(current.x, current.y, current.z)
                                NativePathfinder.setTarget(
                                    resolved.x + 0.5, resolved.y.toDouble(), resolved.z + 0.5
                                )
                            }
                        }
                    }
                }
            }
            PatrolState.WARPING -> {
                if (warpStageElapsedMs > WARP_TOTAL_TIMEOUT_MS) {
                    ChatUtils.sendMessage("Patrol: warp timed out at point ${routeIndex + 1}, skipping.")
                    cancelWarp()
                    advanceAndNavigate()
                }
            }
            PatrolState.AT_KILL_ZONE -> {
                if (killZoneClearedThisTick) {
                    killZoneClearTicks++
                    if (killZoneClearTicks >= killZoneDwellTicks.value.toInt()) {
                        killZoneClearTicks = 0
                        advanceAndNavigate()
                    }
                } else {
                    killZoneClearTicks = 0
                }
                killZoneClearedThisTick = false
            }
            PatrolState.COMBAT_INTERRUPT, PatrolState.IDLE -> { /* CombatMacroModule owns pathfinder */ }
        }
        statusInfo.value = patrolState.name
    }

    @SubscribeEvent
    fun onRender(event: WorldRenderEvent.Last) {
        // Warp sub-machine - frame-driven
        if (patrolRunning && patrolState == PatrolState.WARPING && mc.screen == null) {
            val player = mc.player
            val level = mc.level
            if (player != null && level != null) handleWarp(player, level)
        }
        // Route visualization - always shown while module is enabled
        if (enabled.value && patrolPoints.isNotEmpty()) renderRoute(event)
    }

    private fun renderRoute(event: WorldRenderEvent.Last) {
        val points = patrolPoints
        for (i in points.indices) {
            val p = points[i]
            val isActive = patrolRunning && i == routeIndex
            val inflate = if (isActive) 0.07 else 0.02
            val box = AABB(
                p.x - inflate, p.y - inflate, p.z - inflate,
                p.x + 1.0 + inflate, p.y + 1.0 + inflate, p.z + 1.0 + inflate
            )
            val color = when (p.type) {
                CombatPatrolPointType.WALK -> if (isActive) Color(100, 220, 255, 255) else Color(70, 150, 255, 180)
                CombatPatrolPointType.WARP -> if (isActive) Color(220, 110, 255, 255) else Color(170, 70, 220, 180)
                CombatPatrolPointType.KILL -> if (isActive) Color(255, 80, 80, 255) else Color(210, 50, 50, 180)
            }
            Render3D.drawBox(event.context, box, color, esp = true)

            // Kill zone radius preview - wireframe sphere (3 rings)
            if (p.type == CombatPatrolPointType.KILL) {
                val zoneColor = if (isActive) Color(255, 80, 80, 200) else Color(210, 50, 50, 140)
                drawSphereRings(event, Vec3(p.x + 0.5, p.y + 0.5, p.z + 0.5), killZoneRadius.value, zoneColor)
            }

            // Line to next point (skip last->first when loop is off)
            if (i < points.size - 1 || loopRoute.value) {
                val next = points[(i + 1) % points.size]
                val lineColor = if (isActive) Color(255, 255, 255, 200) else Color(190, 190, 190, 120)
                Render3D.drawLine(
                    event.context,
                    Vec3(p.x + 0.5, p.y + 0.5, p.z + 0.5),
                    Vec3(next.x + 0.5, next.y + 0.5, next.z + 0.5),
                    lineColor, esp = true, thickness = 1.5f
                )
            }
        }
    }

    @SubscribeEvent
    fun onRightClick(event: MouseEvent.RightClick) {
        if (!enabled.value || !recordOnRightClick.value || automationRouteLoaded) return
        val hit = mc.hitResult
        if (hit is BlockHitResult && hit.type == HitResult.Type.BLOCK) {
            event.setCancelled(true)
            pendingClickPos = hit.blockPos
            applyPickedType(currentPointType())
        }
    }

    /** Called by PatrolPointPopup when the user picks a type. */
    fun applyPickedType(type: CombatPatrolPointType) {
        val pos = pendingClickPos ?: return
        pendingClickPos = null
        patrolPoints.add(CombatPatrolPoint(pos.x, pos.y, pos.z, type))
        ChatUtils.sendMessage("Patrol point added at ${pos.x} ${pos.y} ${pos.z} (${type.id}).")
        updatePointsInfo()
    }

    /** Called by PatrolPointPopup when the user cancels. */
    fun cancelPendingPick() {
        pendingClickPos = null
    }

    private fun currentPointType() = when (pointType.value) {
        1 -> CombatPatrolPointType.WARP
        2 -> CombatPatrolPointType.KILL
        else -> CombatPatrolPointType.WALK
    }

    private fun addPointFromPlayer() {
        val player = mc.player ?: return
        val pos = player.blockPosition()
        val type = currentPointType()
        patrolPoints.add(CombatPatrolPoint(pos.x, pos.y, pos.z, type))
        ChatUtils.sendMessage("Patrol point added (${patrolPoints.size} total, type=${type.id}).")
        updatePointsInfo()
    }

    private fun isValidName(name: String): Boolean {
        if (name == "." || name == "..") return false
        if (name.endsWith(".") || name.endsWith(" ")) return false
        return name.none { it in charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|') }
    }

    private fun routeFile(name: String) = File(patrolDir, "$name.json")

    internal fun saveRoute() {
        val name = routeName.value.trim()
        if (name.isEmpty()) { ChatUtils.sendMessage("Route name is empty."); return }
        if (!isValidName(name)) { ChatUtils.sendMessage("Invalid route name characters."); return }
        if (!patrolDir.exists()) patrolDir.mkdirs()
        val root = JsonObject()
        val arr = JsonArray()
        patrolPoints.forEach { p ->
            val o = JsonObject()
            o.addProperty("x", p.x); o.addProperty("y", p.y); o.addProperty("z", p.z)
            o.addProperty("type", p.type.id)
            arr.add(o)
        }
        root.add("points", arr)
        routeFile(name).writeText(gson.toJson(root))
        ChatUtils.sendMessage("Saved patrol route \"$name\" (${patrolPoints.size} points).")
    }

    internal fun loadRoute() {
        val name = routeName.value.trim()
        if (name.isEmpty()) { ChatUtils.sendMessage("Route name is empty."); return }
        if (!isValidName(name)) { ChatUtils.sendMessage("Invalid route name characters."); return }
        val file = routeFile(name)
        if (!file.exists()) { ChatUtils.sendMessage("Route \"$name\" not found."); return }
        val text = runCatching { file.readText() }.getOrNull()?.trim().orEmpty()
        if (text.isEmpty()) { ChatUtils.sendMessage("Route file is empty."); return }
        val parsed = runCatching { JsonParser.parseString(text) }.getOrNull() ?: run {
            ChatUtils.sendMessage("Route file is invalid JSON."); return
        }
        val arr = parsed.asJsonObject?.getAsJsonArray("points") ?: run {
            ChatUtils.sendMessage("Route file has no \"points\" array."); return
        }
        val loaded = mutableListOf<CombatPatrolPoint>()
        arr.forEach { el ->
            val o = el.asJsonObject
            val x = o["x"]?.asInt ?: return@forEach
            val y = o["y"]?.asInt ?: return@forEach
            val z = o["z"]?.asInt ?: return@forEach
            val t = CombatPatrolPointType.fromId(o["type"]?.asString)
            loaded.add(CombatPatrolPoint(x, y, z, t))
        }
        patrolPoints.clear()
        patrolPoints.addAll(loaded)
        automationRouteLoaded = false
        updatePointsInfo()
        ChatUtils.sendMessage("Loaded patrol route \"$name\" (${patrolPoints.size} points).")
    }

    fun loadSavedRoute(route: SavedRoute, automationManaged: Boolean = false): Boolean {
        if (route.type != RouteType.PATROL) return false
        val loaded = (route.travelRoute + route.loopOrArea).map(::toPatrolPoint)
        if (loaded.isEmpty()) return false
        if (patrolRunning) stopPatrol()
        patrolPoints.clear()
        patrolPoints.addAll(loaded)
        automationRouteLoaded = automationManaged
        routeName.value = route.name
        RouteStore.setLoaded(route)
        updatePointsInfo()
        return true
    }

    fun loadRouteByName(name: String): Boolean {
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) return false
        val route =
            RouteStore
                .listByType(RouteType.PATROL)
                .firstOrNull { it.name.equals(normalizedName, ignoreCase = true) }
                ?: return false
        return loadSavedRoute(route, automationManaged = false)
    }

    fun clearPatrolRoute(stopIfRunning: Boolean = true) {
        if (stopIfRunning) stopPatrol()
        patrolPoints.clear()
        automationRouteLoaded = false
        RouteStore.clearLoaded(RouteType.PATROL)
        updatePointsInfo()
    }

    internal fun updatePointsInfo() {
        pointsInfo.value = "${patrolPoints.size} points"
    }

    fun startPatrol(startNearestOverride: Boolean? = null) {
        if (patrolPoints.isEmpty()) { ChatUtils.sendMessage("No patrol points. Add some first."); return }
        if (!enabled.value) enabled.value = true
        PathfindingModule.ensureEnabledForAutomation("combat-patrol")
        routeIndex = if (startNearestOverride ?: startFromNearest.value) findNearestIndex() else 0
        patrolRunning = true
        killZoneClearTicks = 0
        navigateTo(patrolPoints[routeIndex])
        ChatUtils.sendMessage("Combat patrol started at point ${routeIndex + 1}/${patrolPoints.size}.")
    }

    fun stopPatrol(msg: String = "") {
        cancelWarp()
        if (patrolRunning && msg.isNotEmpty()) ChatUtils.sendMessage(msg)
        patrolRunning = false
        patrolOwnsPathfinder = false
        patrolState = PatrolState.IDLE
        killZoneClearTicks = 0
        killZoneClearedThisTick = false
        NativePathfinder.stop()
        MovementManager.setMovementLock(false)
        statusInfo.value = "Idle"
    }

    val isPatrolRunning: Boolean get() = patrolRunning

    fun hasPointWithin(player: Player, radius: Double): Boolean {
        if (patrolPoints.isEmpty()) return false
        val radiusSq = radius * radius
        return patrolPoints.any { point ->
            val dx = player.x - (point.x + 0.5)
            val dy = player.y - point.y.toDouble()
            val dz = player.z - (point.z + 0.5)
            dx * dx + dy * dy + dz * dz <= radiusSq
        }
    }

    private fun advanceAndNavigate() {
        routeIndex++
        if (routeIndex >= patrolPoints.size) {
            if (loopRoute.value) {
                routeIndex = 0
            } else {
                stopPatrol("Patrol complete.")
                return
            }
        }
        navigateTo(patrolPoints[routeIndex])
    }

    internal fun navigateTo(point: CombatPatrolPoint) {
        when (point.type) {
            CombatPatrolPointType.WALK, CombatPatrolPointType.KILL -> {
                val level = mc.level
                val resolved = if (level != null) {
                    MinecraftPathingRules.resolveTarget(level, BlockPos(point.x, point.y, point.z))
                        ?: BlockPos(point.x, point.y, point.z)
                } else BlockPos(point.x, point.y, point.z)
                NativePathfinder.setTarget(resolved.x + 0.5, resolved.y.toDouble(), resolved.z + 0.5)
                navPathActivated = false
                patrolOwnsPathfinder = true
                patrolState = PatrolState.NAVIGATING
            }
            CombatPatrolPointType.WARP -> {
                val target = BlockPos(point.x, point.y, point.z)
                val resolved = mc.level?.let { resolveWarpPoint(it, target) } ?: target
                startWarpPoint(point.copy(x = resolved.x, y = resolved.y, z = resolved.z))
            }
        }
    }

    private fun findNearestIndex(): Int {
        val player = mc.player ?: return 0
        val px = player.x; val py = player.y; val pz = player.z
        return patrolPoints.indices.minByOrNull { i ->
            val p = patrolPoints[i]
            val dx = p.x + 0.5 - px; val dy = p.y - py; val dz = p.z + 0.5 - pz
            dx * dx + dy * dy + dz * dz
        } ?: 0
    }

    /** Called by CombatMacroModule when a stray mob is found while patrol is navigating. */
    fun onCombatInterrupt() {
        if (patrolState != PatrolState.NAVIGATING && patrolState != PatrolState.WARPING) return
        NativePathfinder.stop()
        patrolOwnsPathfinder = false
        patrolState = PatrolState.COMBAT_INTERRUPT
    }

    /** Called by CombatMacroModule when no target remains (edge-triggered: only acts on COMBAT_INTERRUPT). */
    fun onCombatResume() {
        if (patrolState != PatrolState.COMBAT_INTERRUPT) return
        val point = patrolPoints.getOrNull(routeIndex) ?: run { stopPatrol("Route index out of bounds."); return }
        navigateTo(point)
    }

    /**
     * Called by CombatMacroModule each tick there are no mobs in the kill zone.
     *
     * NOTE: requires CombatMacroModule's TickEvent.Start handler to fire BEFORE this module's
     * handler in the same tick - ensured by registration order in Cobalt.kt
     * (CombatMacroModule is registered before CombatPatrolModule).
     */
    fun onKillZoneCleared() {
        if (patrolState != PatrolState.AT_KILL_ZONE) return
        killZoneClearedThisTick = true
    }

    val currentKillPoint: CombatPatrolPoint?
        get() = if (patrolState == PatrolState.AT_KILL_ZONE) patrolPoints.getOrNull(routeIndex) else null

    val killZoneRadiusValue: Double get() = killZoneRadius.value

    private fun toPatrolPoint(point: RoutePoint): CombatPatrolPoint =
        CombatPatrolPoint(
            point.x,
            point.y,
            point.z,
            when (point.type) {
                org.cobalt.internal.routes.RoutePointType.WARP -> CombatPatrolPointType.WARP
                org.cobalt.internal.routes.RoutePointType.KILL -> CombatPatrolPointType.KILL
                else -> CombatPatrolPointType.WALK
            }
        )

    // -- warp sub-machine ---------------------------------------------------

    private fun startWarpPoint(point: CombatPatrolPoint) {
        val player = mc.player ?: return
        val level = mc.level ?: return
        val target = BlockPos(point.x, point.y, point.z)

        if (level.gameTime < warpCooldownUntil) {
            // cooldown - fall back to walking
            NativePathfinder.setTarget(point.x + 0.5, point.y.toDouble(), point.z + 0.5)
            patrolOwnsPathfinder = true
            patrolState = PatrolState.NAVIGATING
            return
        }
        if (wasJustWarpedToTarget(level, player, target)) {
            advanceAndNavigate()
            return
        }

        // Ensure AOTV is selected (use configured slot)
        val slot = aotvSlot.value  // index 0-8
        if (!EtherwarpLogic.holdingEtherwarpItem()) {
            val currentSlot = player.inventory.selectedSlot
            InventoryUtils.holdHotbarSlot(slot)
            if (!EtherwarpLogic.holdingEtherwarpItem()) {
                // Item not in that slot - restore immediately and skip
                InventoryUtils.holdHotbarSlot(currentSlot)
                ChatUtils.sendMessage("Patrol: no AOTV in slot ${slot + 1}, skipping warp.")
                advanceAndNavigate()
                return
            }
            // Successfully switched - record slot to restore after warp
            warpRestoreSlot = currentSlot
        }

        NativePathfinder.stop()
        RotationExecutor.stopRotating()
        mc.options.keyUse.setDown(false)
        mc.options.keyShift.setDown(false)

        warpTargetPoint = point
        warpStage = 0
        warpStageElapsedMs = 0.0
        warpStageLastNs = 0L
        warpLookLastNs = 0L
        patrolOwnsPathfinder = true
        patrolState = PatrolState.WARPING
    }

    private fun cancelWarp() {
        mc.options.keyUse.setDown(false)
        mc.options.keyShift.setDown(false)
        RotationExecutor.stopRotating()
        if (warpRestoreSlot in 0..8) {
            InventoryUtils.holdHotbarSlot(warpRestoreSlot)
        }
        warpRestoreSlot = -1
        warpStage = 0
        warpTargetPoint = null
        warpStageElapsedMs = 0.0
        warpStageLastNs = 0L
        warpLookLastNs = 0L
        patrolOwnsPathfinder = false
    }

    private fun handleWarp(player: Player, level: net.minecraft.world.level.Level) {
        val pointData = warpTargetPoint ?: run { cancelWarp(); return }
        val target = BlockPos(pointData.x, pointData.y, pointData.z)
        val warpAimPoint = resolveWarpAimPoint(level, player, target)
        advanceWarpFrameTime()

        when (warpStage) {
            0 -> {
                val (yawError, pitchError) = applyWarpHeadRotation(player, warpAimPoint)
                if ((yawError <= WARP_AIM_TOLERANCE && pitchError <= WARP_AIM_TOLERANCE) ||
                    warpStageElapsedMs >= WARP_ALIGN_MS) {
                    mc.options.keyShift.setDown(true)
                    warpStage = 1
                    resetWarpStageTimer()
                }
            }
            1 -> {
                applyWarpHeadRotation(player, warpAimPoint)
                mc.options.keyShift.setDown(true)
                if (!canWarpToTarget(level, player, target, warpAimPoint)) {
                    if (warpStageElapsedMs >= WARP_STAGE1_TIMEOUT_MS) {
                        mc.options.keyShift.setDown(false)
                        warpCooldownUntil = level.gameTime + WARP_RETRY_COOLDOWN_TICKS
                        cancelWarp()
                        advanceAndNavigate()
                    }
                    return
                }
                if (warpStageElapsedMs >= WARP_SNEAK_MS) {
                    val shiftKeyHeld = mc.options.keyShift.isDown
                    val playerIsShifting = player.isShiftKeyDown
                    if (!shiftKeyHeld || !playerIsShifting) return
                    mc.options.keyUse.setDown(true)
                    warpStage = 2
                    resetWarpStageTimer()
                }
            }
            else -> {
                mc.options.keyUse.setDown(false)
                val landed = hasArrived(player, target)
                // Blend head toward next route point during post-warp dwell
                val nextIndex = routeIndex + 1
                val nextPoint = patrolPoints.getOrNull(if (nextIndex < patrolPoints.size) nextIndex else 0)
                val postWarpAim = nextPoint?.let { Vec3(it.x + 0.5, it.y + 0.6, it.z + 0.5) }
                val frameAim = if (postWarpAim != null) {
                    val t = (warpStageElapsedMs / WARP_POST_MS).coerceIn(0.0, 1.0)
                    blendVec3(warpAimPoint, postWarpAim, t)
                } else {
                    warpAimPoint
                }
                applyWarpHeadRotation(player, frameAim)
                mc.options.keyShift.setDown(true)
                if (warpStageElapsedMs >= WARP_POST_MS) {
                    mc.options.keyShift.setDown(false)
                    warpCooldownUntil = level.gameTime + WARP_COOLDOWN_TICKS
                    if (landed) {
                        lastSuccessfulWarpTarget = target
                        lastSuccessfulWarpTick = level.gameTime
                    }
                    cancelWarp()
                    advanceAndNavigate()
                }
            }
        }
    }

    private fun hasArrived(player: Player, target: BlockPos): Boolean {
        val distSq = player.blockPosition().distSqr(target)
        return distSq <= ARRIVAL_DISTANCE_SQ
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
                    val distSq = candidate.distSqr(rawPoint)
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

    private fun blendVec3(from: Vec3, to: Vec3, t: Double): Vec3 {
        val clamped = t.coerceIn(0.0, 1.0)
        return Vec3(
            from.x + (to.x - from.x) * clamped,
            from.y + (to.y - from.y) * clamped,
            from.z + (to.z - from.z) * clamped
        )
    }

    // -- render helpers -----------------------------------------------------

    private fun drawSphereRings(event: WorldRenderEvent.Last, center: Vec3, radius: Double, color: Color) {
        val segments = 40
        for (i in 0 until segments) {
            val a1 = i * TWO_PI / segments
            val a2 = (i + 1) * TWO_PI / segments
            val c1 = kotlin.math.cos(a1); val s1 = kotlin.math.sin(a1)
            val c2 = kotlin.math.cos(a2); val s2 = kotlin.math.sin(a2)
            // horizontal ring (XZ)
            Render3D.drawLine(event.context,
                Vec3(center.x + radius * c1, center.y, center.z + radius * s1),
                Vec3(center.x + radius * c2, center.y, center.z + radius * s2),
                color, esp = true, thickness = 1.2f)
            // vertical ring facing Z (XY)
            Render3D.drawLine(event.context,
                Vec3(center.x + radius * c1, center.y + radius * s1, center.z),
                Vec3(center.x + radius * c2, center.y + radius * s2, center.z),
                color, esp = true, thickness = 1.2f)
            // vertical ring facing X (YZ)
            Render3D.drawLine(event.context,
                Vec3(center.x, center.y + radius * c1, center.z + radius * s1),
                Vec3(center.x, center.y + radius * c2, center.z + radius * s2),
                color, esp = true, thickness = 1.2f)
        }
    }

    // -- warp constants -----------------------------------------------------
    private val TWO_PI = kotlin.math.PI * 2.0
    // Player must be within this many blocks (sq) for "already at destination" trivial arrival.
    // Kept deliberately small (2 blocks) so nearby-but-not-there points don't trigger false advance.
    private const val NAV_TRIVIAL_DIST_SQ = 2.0 * 2.0
    private const val WARP_AIM_TOLERANCE = 6.0
    private const val WARP_LOOK_YAW_SPEED_DPS = 360.0
    private const val WARP_LOOK_PITCH_SPEED_DPS = 300.0
    private const val WARP_ALIGN_MS = 170.0
    private const val WARP_SNEAK_MS = 85.0
    private const val WARP_POST_MS = 70.0
    private const val WARP_STAGE1_TIMEOUT_MS = 240.0
    private const val WARP_RETRY_COOLDOWN_TICKS = 4L
    private const val WARP_COOLDOWN_TICKS = 1L
    private const val WARP_TOTAL_TIMEOUT_MS = WARP_ALIGN_MS + WARP_STAGE1_TIMEOUT_MS + WARP_SNEAK_MS + WARP_POST_MS + 200.0
    private const val WARP_REPEAT_BLOCK_SUPPRESS_TICKS = 10L
    private const val WARP_RESOLVE_RADIUS = 2
    private const val WARP_RESOLVE_VERTICAL = 2
    private const val ARRIVAL_DISTANCE_SQ = 6.0 * 6.0
}
