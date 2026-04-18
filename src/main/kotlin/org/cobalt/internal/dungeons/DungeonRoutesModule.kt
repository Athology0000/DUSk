package org.cobalt.internal.dungeons

import java.awt.Color
import kotlin.math.sqrt
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.BlockChangeEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.event.impl.render.WorldRenderContext
import org.cobalt.api.event.impl.render.WorldRenderEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.ColorSetting
import org.cobalt.api.module.setting.impl.KeyBindSetting
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.util.ChatUtils
import org.cobalt.api.util.helper.KeyBind
import org.cobalt.api.util.render.Render3D
import org.cobalt.internal.dungeons.map.DungeonRoom
import org.cobalt.internal.dungeons.map.DungeonScanState
import org.cobalt.internal.dungeons.map.LoadedRoute
import org.cobalt.internal.dungeons.map.RouteStep

object DungeonRoutesModule : Module("Dungeon Routes") {

  // ── Constants ──────────────────────────────────────────────────────────────

  private const val MAX_PATH_LENGTH = 28.0   // blocks beyond which the path is cut off
  private const val DASH_LENGTH     = 0.5    // blocks per dash
  private const val GAP_LENGTH      = 0.3    // blocks per gap
  private const val BAT_RANGE_SQ    = 256.0  // 16 blocks^2 — matches Hunch/Skyblocker

  // ── Behaviour ──────────────────────────────────────────────────────────────

  private val enabled = CheckboxSetting(
    "Enabled",
    "Render Hunch-style routes for the current dungeon room.",
    true,
  )
  private val autoStart = CheckboxSetting(
    "Auto Start",
    "Automatically start the route when entering a new room.",
    true,
  )
  private val restartOnComplete = CheckboxSetting(
    "Restart On Complete",
    "Restart the route after all secrets are reached.",
    false,
  )
  private val showChatMessages = CheckboxSetting(
    "Chat Messages",
    "Show chat notifications for route events.",
    true,
  )
  private val progressive = CheckboxSetting(
    "Progressive",
    "Only render the current step; auto-advance when you reach it.",
    true,
  )

  // ── Visibility ─────────────────────────────────────────────────────────────

  private val showPath      = CheckboxSetting("Show Path",        "Render dashed path lines and node boxes.",    true)
  private val showEtherwarps  = CheckboxSetting("Show Etherwarps",  "Render etherwarp spots.",                    true)
  private val showMines     = CheckboxSetting("Show Mines",       "Render mine / stonk spots.",                  true)
  private val showInteracts   = CheckboxSetting("Show Interacts",   "Render interact / TNT / lever spots.",        true)
  private val showEnderPearls = CheckboxSetting("Show Ender Pearls","Render ender pearl throw positions.",         true)
  private val showSecretTarget = CheckboxSetting("Show Secret Target","Render a box at the target secret.",        true)
  private val showSecretLabel  = CheckboxSetting(
    "Show Secret Label",
    "Show 'N/Total TYPE' label above the secret box.",
    true,
  )

  // ── Thresholds ─────────────────────────────────────────────────────────────

  private val advanceRadius = SliderSetting(
    "Advance Radius",
    "Proximity radius (blocks) for ITEM / EXITROUTE auto-advance.",
    2.0,
    1.0,
    6.0,
  )
  private val lineWidth = SliderSetting("Line Width", "Path line thickness.", 3.0, 1.0, 6.0)

  // ── Keybinds ───────────────────────────────────────────────────────────────

  private val startStopKey = KeyBindSetting("Start / Stop Key",   "Toggle route active state.",        KeyBind(-1))
  private val nextKey      = KeyBindSetting("Next Waypoint Key",  "Skip to the next route step.",      KeyBind(-1))
  private val prevKey      = KeyBindSetting("Prev Waypoint Key",  "Go back to the previous step.",     KeyBind(-1))

  // ── Marker colors ──────────────────────────────────────────────────────────

  private val pathColor       = ColorSetting("Path Color",        "Primary route path color.",           0xFF49DCC7.toInt())
  private val etherwarpColor  = ColorSetting("Etherwarp Color",   "Etherwarp marker color.",             0xFF9E7CFF.toInt())
  private val mineColor       = ColorSetting("Mine Color",        "Mine / stonk marker color.",          0xFFFFA24F.toInt())
  private val interactColor   = ColorSetting("Interact Color",    "Interact / TNT / lever marker color.",0xFF7DE16B.toInt())
  private val enderPearlColor = ColorSetting("Ender Pearl Color", "Ender pearl throw marker color.",     0xFF3975C8.toInt())

  // ── Secret-type colors ─────────────────────────────────────────────────────

  private val chestColor        = ColorSetting("Chest Color",       "Color for CHEST secrets.",       0xFF02D5FA.toInt())
  private val itemColor         = ColorSetting("Item Color",        "Color for ITEM secrets.",        0xFF0240FA.toInt())
  private val batColor          = ColorSetting("Bat Color",         "Color for BAT secrets.",         0xFF8E4200.toInt())
  private val witherColor       = ColorSetting("Wither Color",      "Color for WITHER secrets.",      0xFF555555.toInt())
  private val exitColor         = ColorSetting("Exit Color",        "Color for EXITROUTE secrets.",   0xFF00FF80.toInt())
  private val fairyColor        = ColorSetting("Fairy Color",       "Color for FAIRYSOUL secrets.",   0xFFFF88FF.toInt())
  private val defaultSecretColor = ColorSetting("Secret Color",     "Color for all other secrets.",   0xFFFFE26A.toInt())

  // ── Runtime state ──────────────────────────────────────────────────────────

  private var activeSignature: String? = null
  private var activeStepIndex: Int = 0
  private var routeActive: Boolean = false
  // BAT detection: track whether a bat was near the secret last tick.
  // We advance only when bats disappear (died), not while they're alive.
  private var batSeenNearSecret: Boolean = false

  init {
    addSetting(
      enabled, autoStart, restartOnComplete, showChatMessages, progressive,
      showPath, showEtherwarps, showMines, showInteracts, showEnderPearls,
      showSecretTarget, showSecretLabel,
      advanceRadius, lineWidth,
      startStopKey, nextKey, prevKey,
      pathColor, etherwarpColor, mineColor, interactColor, enderPearlColor,
      chestColor, itemColor, batColor, witherColor, exitColor, fairyColor, defaultSecretColor,
    )
    EventBus.register(this)
  }

  // ── Tick ───────────────────────────────────────────────────────────────────

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.End) {
    if (!enabled.value) return
    DungeonScanState.tick()

    val player = Minecraft.getInstance().player ?: run { resetRoute(); return }
    val room   = DungeonScanState.currentRoom      ?: run { resetRoute(); return }
    val route  = DungeonScanState.resolveRoute(room) ?: run { resetRoute(); return }

    // ── Room / route change → handle auto-start ────────────────────────────

    val signature = buildSignature(room, route.rawKey)
    if (signature != activeSignature) {
      activeSignature = signature
      activeStepIndex = 0
      routeActive = autoStart.value
      if (routeActive && showChatMessages.value) {
        ChatUtils.sendMessage("Route started - ${route.steps.size} secrets")
      }
    }

    // ── Keybinds ───────────────────────────────────────────────────────────

    if (startStopKey.value.isPressed()) toggleRoute(route)

    if (routeActive) {
      if (nextKey.value.isPressed()) advance(route, sendChat = true)
      if (prevKey.value.isPressed() && activeStepIndex > 0) {
        activeStepIndex--
        if (showChatMessages.value) {
          val step = route.steps.getOrNull(activeStepIndex)
          ChatUtils.sendMessage("Waypoint ${activeStepIndex + 1}/${route.steps.size} ${step?.secretType?.uppercase().orEmpty()}")
        }
      }
    }

    // ── Auto-advance (progressive + active only) ───────────────────────────

    if (!progressive.value || !routeActive) return
    val step   = route.steps.getOrNull(activeStepIndex) ?: return
    val target = DungeonScanState.relativeToActual(room, step.secretPos) ?: return
    val targetCenter = Vec3.atCenterOf(target)

    when (step.secretType.lowercase()) {

      // ITEM / EXITROUTE — pure proximity
      "item", "exitroute" -> {
        val distSq = player.position().distanceToSqr(targetCenter)
        if (distSq <= advanceRadius.value * advanceRadius.value) {
          advance(route, sendChat = showChatMessages.value)
        }
      }

      // BAT — advance when a nearby bat disappears (died), not while it's alive.
      // Checking for a live bat fires on spawn; we need the death (entity leaving the list).
      "bat" -> {
        val level = Minecraft.getInstance().level ?: return
        val batsNearby = level.entitiesForRendering().any { entity ->
          entity is Bat && entity.position().distanceToSqr(targetCenter) <= BAT_RANGE_SQ
        }
        if (batSeenNearSecret && !batsNearby) {
          batSeenNearSecret = false
          advance(route, sendChat = showChatMessages.value)
        } else {
          batSeenNearSecret = batsNearby
        }
      }

      // All other types rely on block-change (onBlockChange below)
      else -> Unit
    }
  }

  // ── Block-change auto-advance ───────────────────────────────────────────────

  @SubscribeEvent
  fun onBlockChange(event: BlockChangeEvent) {
    if (!enabled.value || !routeActive || !progressive.value) return
    val room  = DungeonScanState.currentRoom         ?: return
    val route = DungeonScanState.resolveRoute(room)  ?: return
    val step  = route.steps.getOrNull(activeStepIndex) ?: return

    val secretActual = DungeonScanState.relativeToActual(room, step.secretPos) ?: return
    if (event.pos == secretActual) { advance(route, sendChat = showChatMessages.value); return }

    for (interactPos in step.interacts) {
      val actual = DungeonScanState.relativeToActual(room, interactPos) ?: continue
      if (event.pos == actual) { advance(route, sendChat = showChatMessages.value); return }
    }
  }

  // ── Render ─────────────────────────────────────────────────────────────────

  @SubscribeEvent
  fun onRender(event: WorldRenderEvent.Last) {
    if (!enabled.value) return
    val room  = DungeonScanState.currentRoom         ?: return
    val route = DungeonScanState.resolveRoute(room)  ?: return
    if (room.corner == null || room.direction == null) return

    val stepsToRender: List<IndexedValue<RouteStep>> = when {
      progressive.value && routeActive -> {
        val step = route.steps.getOrNull(activeStepIndex) ?: return
        listOf(IndexedValue(activeStepIndex, step))
      }
      !progressive.value -> route.steps.withIndex().toList()
      else -> return
    }

    for ((index, step) in stepsToRender) {
      renderStep(event.context, room, step, stepNumber = index + 1, totalSteps = route.steps.size)
    }
  }

  // ── Step rendering ─────────────────────────────────────────────────────────

  private fun renderStep(
    context: WorldRenderContext,
    room: DungeonRoom,
    step: RouteStep,
    stepNumber: Int,
    totalSteps: Int,
  ) {
    val target = if (showSecretTarget.value || showPath.value) {
      DungeonScanState.relativeToActual(room, step.secretPos)
    } else null

    if (showPath.value && target != null) {
      val pathNodes = step.pathLocations.mapNotNull { DungeonScanState.relativeToActual(room, it) }
      drawPath(context, pathNodes, Vec3.atCenterOf(target), color(pathColor.value))
    }

    if (showEtherwarps.value) {
      for (pos in step.etherwarps.mapNotNull { DungeonScanState.relativeToActual(room, it) }) {
        Render3D.drawStyledBox(context, blockBox(pos), color(etherwarpColor.value), colorAlpha(etherwarpColor.value, 80), true, 2.2f)
      }
    }

    if (showMines.value) {
      for (pos in step.mines.mapNotNull { DungeonScanState.relativeToActual(room, it) }) {
        Render3D.drawStyledBox(context, blockBox(pos), color(mineColor.value), colorAlpha(mineColor.value, 70), true, 1.9f)
      }
    }

    if (showInteracts.value) {
      for (pos in step.interacts.mapNotNull { DungeonScanState.relativeToActual(room, it) }) {
        Render3D.drawStyledBox(context, blockBox(pos), color(interactColor.value), colorAlpha(interactColor.value, 70), true, 2.0f)
      }
      for (pos in step.tnts.mapNotNull { DungeonScanState.relativeToActual(room, it) }) {
        Render3D.drawStyledBox(context, blockBox(pos), color(interactColor.value), colorAlpha(interactColor.value, 55), true, 2.0f)
      }
    }

    if (showEnderPearls.value) {
      for (pos in step.enderPearls) {
        Render3D.drawStyledBox(context, blockBox(pos), color(enderPearlColor.value), colorAlpha(enderPearlColor.value, 85), true, 2.4f)
      }
    }

    if (showSecretTarget.value && target != null) {
      val typeColor = secretTypeColor(step.secretType)
      Render3D.drawStyledBox(context, blockBox(target), typeColor, Color(typeColor.red, typeColor.green, typeColor.blue, 90), true, 2.6f)
      if (showSecretLabel.value) {
        val labelPos = Vec3.atCenterOf(target).add(0.0, 1.4, 0.0)
        Render3D.drawWorldLabel(context, labelPos, "$stepNumber/$totalSteps ${step.secretType.uppercase()}", typeColor)
      }
    }
  }

  // ── Route control ──────────────────────────────────────────────────────────

  private fun toggleRoute(route: LoadedRoute) {
    routeActive = !routeActive
    if (routeActive) {
      activeStepIndex = 0
      if (showChatMessages.value) ChatUtils.sendMessage("Route started - ${route.steps.size} secrets")
    } else {
      if (showChatMessages.value) ChatUtils.sendMessage("Route stopped.")
    }
  }

  private fun advance(route: LoadedRoute, sendChat: Boolean) {
    batSeenNearSecret = false
    val next = activeStepIndex + 1
    if (next > route.steps.lastIndex) {
      if (restartOnComplete.value) {
        activeStepIndex = 0
        if (sendChat) ChatUtils.sendMessage("Route restarted!")
      } else {
        routeActive = false
        if (sendChat) ChatUtils.sendMessage("Route completed!")
      }
    } else {
      activeStepIndex = next
      if (sendChat) {
        val step = route.steps.getOrNull(next)
        ChatUtils.sendMessage("Waypoint ${next + 1}/${route.steps.size} ${step?.secretType?.uppercase().orEmpty()}")
      }
    }
  }

  // ── Path drawing ───────────────────────────────────────────────────────────

  /**
   * Draws the route path from the player to the secret:
   *   player → first unreached node → … → secret (up to MAX_PATH_LENGTH blocks)
   *
   * Shows the PATH_NODE_BOXES nearest unreached node boxes.
   * Skips nodes the player has already passed (within advanceRadius).
   */
  private fun drawPath(
    context: WorldRenderContext,
    pathNodes: List<BlockPos>,
    secretCenter: Vec3,
    color: Color,
  ) {
    val player = Minecraft.getInstance().player ?: return
    val playerPos = player.position()
    val radSq = advanceRadius.value * advanceRadius.value

    // Find the first node not yet reached
    val firstUnreachedIdx = pathNodes.indexOfFirst { pos ->
      playerPos.distanceToSqr(Vec3.atCenterOf(pos)) > radSq
    }
    val unreachedNodes = if (firstUnreachedIdx >= 0) {
      pathNodes.subList(firstUnreachedIdx, pathNodes.size)
    } else {
      emptyList()
    }

    // Build the full visual path: player → unreached nodes → secret
    val rawPath = buildList<Vec3> {
      add(playerPos.add(0.0, 0.9, 0.0))
      unreachedNodes.forEach { pos -> add(Vec3.atCenterOf(pos).add(0.0, 0.15, 0.0)) }
      add(secretCenter.add(0.0, 0.5, 0.0))
    }

    // Clamp path to MAX_PATH_LENGTH from player
    val effectivePath = clampPath(rawPath, MAX_PATH_LENGTH)

    // Dashed line segments
    val lw = lineWidth.value.toFloat()
    for (i in 0 until effectivePath.lastIndex) {
      drawDashedLine(context, effectivePath[i], effectivePath[i + 1], color, lw)
    }
  }

  /** Truncates [path] so that cumulative distance never exceeds [maxLen]. */
  private fun clampPath(path: List<Vec3>, maxLen: Double): List<Vec3> {
    if (path.size < 2) return path
    val result = mutableListOf(path.first())
    var walked = 0.0
    for (i in 1 until path.size) {
      val prev = path[i - 1]
      val curr = path[i]
      val seg = sqrt(prev.distanceToSqr(curr))
      if (walked + seg >= maxLen) {
        // Interpolate to exactly maxLen
        val remaining = maxLen - walked
        val dir = curr.subtract(prev).normalize()
        result.add(prev.add(dir.scale(remaining)))
        break
      }
      walked += seg
      result.add(curr)
    }
    return result
  }

  /** Renders a dashed line between [start] and [end]. */
  private fun drawDashedLine(
    context: WorldRenderContext,
    start: Vec3,
    end: Vec3,
    color: Color,
    lw: Float,
  ) {
    val segVec = end.subtract(start)
    val totalLen = sqrt(segVec.lengthSqr())
    if (totalLen < 0.001) return
    val dir = segVec.normalize()
    var pos = 0.0
    var drawing = true

    while (pos < totalLen) {
      val chunkLen = if (drawing) DASH_LENGTH else GAP_LENGTH
      val nextPos = minOf(pos + chunkLen, totalLen)
      if (drawing) {
        val s = start.add(dir.scale(pos))
        val e = start.add(dir.scale(nextPos))
        Render3D.drawLine(context, s, e, color, esp = true, thickness = lw)
      }
      pos = nextPos
      drawing = !drawing
    }
  }

  // ── Box helpers ────────────────────────────────────────────────────────────

  private fun markerBox(pos: BlockPos, halfSize: Double, height: Double): AABB =
    AABB(
      pos.x + 0.5 - halfSize, pos.y + 0.03, pos.z + 0.5 - halfSize,
      pos.x + 0.5 + halfSize, pos.y + height, pos.z + 0.5 + halfSize,
    )

  private fun blockBox(pos: BlockPos): AABB =
    AABB(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble(), pos.x + 1.0, pos.y + 1.0, pos.z + 1.0)

  // ── Color helpers ──────────────────────────────────────────────────────────

  private fun secretTypeColor(type: String): Color = when (type.lowercase()) {
    "chest"      -> color(chestColor.value)
    "item"       -> color(itemColor.value)
    "bat"        -> color(batColor.value)
    "wither"     -> color(witherColor.value)
    "exitroute"  -> color(exitColor.value)
    "interact",
    "lever"      -> color(interactColor.value)
    "fairysoul"  -> color(fairyColor.value)
    else         -> color(defaultSecretColor.value)
  }

  private fun buildSignature(room: DungeonRoom, rawKey: String): String =
    listOf(rawKey, room.name.orEmpty(), room.corner?.x ?: 0, room.corner?.z ?: 0, room.rotation).joinToString(":")

  private fun resetRoute() {
    if (activeSignature == null) return
    activeSignature = null
    activeStepIndex = 0
    routeActive = false
    batSeenNearSecret = false
  }

  private fun color(argb: Int): Color = Color(argb, true)

  private fun colorAlpha(argb: Int, alpha: Int): Color {
    val c = Color(argb, true)
    return Color(c.red, c.green, c.blue, alpha.coerceIn(0, 255))
  }
}
