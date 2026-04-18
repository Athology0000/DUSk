package org.cobalt.internal.mining

import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.event.impl.render.WorldRenderEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.InfoSetting
import org.cobalt.api.module.setting.impl.InfoType
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.rotation.strategy.BezierTrackingRotationStrategy
import org.cobalt.api.rotation.RotationExecutor
import org.cobalt.api.util.AngleUtils
import org.cobalt.api.util.InventoryUtils
import org.cobalt.api.util.MouseUtils
import org.cobalt.api.util.helper.Rotation
import org.cobalt.internal.pathfinding.OverlayRenderEngine
import org.cobalt.internal.visual.DeployableHudModule

object AutoLanternModule : Module("Auto Lantern") {

  private enum class LanternPlacementPhase {
    TURN_TO_PLACE,
    WAIT_BEFORE_USE,
    WAIT_AFTER_USE,
    TURN_BACK,
  }

  private val mc: Minecraft = Minecraft.getInstance()

  private val enabled = CheckboxSetting(
    "Enabled",
    "Automatically use a lantern when the lantern buff is gone or you moved too far.",
    false
  )

  private val info = InfoSetting(
    "Auto Lantern",
    "Uses deployable lantern status first, with tab list fallback, then places a lantern from your hotbar when needed.",
    InfoType.INFO
  )

  private val requireMacro = CheckboxSetting(
    "Require Macro",
    "Only activate when Mining Macro is enabled.",
    true
  )

  private val cooldownTicks = SliderSetting(
    "Cooldown",
    "Minimum ticks to wait between lantern uses.",
    80.0,
    20.0,
    600.0
  )

  private val reUseDistance = SliderSetting(
    "Reuse Distance",
    "Distance from last use spot before reusing regardless of buff status.",
    14.0,
    4.0,
    48.0
  )

  private val preUseDelayTicks = SliderSetting(
    "Pre-Use Delay",
    "Ticks to wait after swapping to the lantern before using it.",
    3.0,
    0.0,
    10.0,
    1.0
  )

  private val postUseDelayTicks = SliderSetting(
    "Post-Use Delay",
    "Ticks to wait after using the lantern before restoring your slot.",
    3.0,
    0.0,
    10.0,
    1.0
  )

  init {
    addSetting(enabled, info, requireMacro, cooldownTicks, reUseDistance, preUseDelayTicks, postUseDelayTicks)
    EventBus.register(this)
  }

  private val LANTERN_NAMES = setOf(
    "mithril lantern",
    "titanium lantern",
    "glacite lantern",
    "will o wisp"
  )

  private var lastUseTick = -1L
  private var lastUsePos: BlockPos? = null
  private var lanternBuffActive = false

  private var pendingOriginalSlot = -1
  private var pendingLanternSlot = -1
  private var pendingPlaceRotation: Rotation? = null
  private var pendingReturnRotation: Rotation? = null
  private var pendingPlacementPhase: LanternPlacementPhase? = null
  private var pendingPlacementTicks = 0
  private val lanternPlaceStrategy = BezierTrackingRotationStrategy(
    maxYawStep = 17f,
    maxPitchStep = 11f,
    curveIn = 0.18f,
    curveOut = 0.9f,
    minScale = 0.24f,
    snapThreshold = 0.45f,
  )
  private val lanternReturnStrategy = BezierTrackingRotationStrategy(
    maxYawStep = 15f,
    maxPitchStep = 9f,
    curveIn = 0.18f,
    curveOut = 0.9f,
    minScale = 0.22f,
    snapThreshold = 0.45f,
  )

  private const val LANTERN_PLACE_PITCH = 82f
  private const val LANTERN_TURN_TIMEOUT_TICKS = 18
  private const val LANTERN_RETURN_TIMEOUT_TICKS = 16
  private const val LANTERN_ROTATION_SNAP_YAW = 4.5f
  private const val LANTERN_ROTATION_SNAP_PITCH = 3.5f

  fun isLanternBuffActive(): Boolean = lanternBuffActive
  fun isPlacementInProgress(): Boolean = pendingPlacementPhase != null || pendingLanternSlot >= 0

  fun tryStartLanternPlacement(slot: Int): Boolean {
    val player = mc.player ?: return false
    if (mc.screen != null) return false
    if (slot !in 0..8 || isPlacementInProgress()) return false

    pendingOriginalSlot = player.inventory.selectedSlot
    pendingLanternSlot = slot
    pendingPlaceRotation = Rotation(AngleUtils.normalizeAngle(player.yRot + 180f), LANTERN_PLACE_PITCH)
    pendingReturnRotation = Rotation(player.yRot, player.xRot)
    pendingPlacementPhase = LanternPlacementPhase.TURN_TO_PLACE
    pendingPlacementTicks = 0
    pendingPlaceRotation?.let { RotationExecutor.rotateTo(it, lanternPlaceStrategy) }
    return true
  }

  fun getLanternBuffStatus(): String =
    DeployableHudModule.getLanternStatusLabel()
      ?: if (lanternBuffActive) "Active" else "Inactive"

  fun noteLanternUse(levelTick: Long, pos: BlockPos) {
    lastUseTick = levelTick
    lastUsePos = pos.immutable()
    lanternBuffActive = true
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
    if (pendingPlacementPhase != null) {
      handlePlacementSequence()
      return
    }

    val player = mc.player
    if (player == null) {
      lanternBuffActive = false
      return
    }
    lanternBuffActive = resolveLanternBuffState(player)

    if (!enabled.value) {
      return
    }

    val level = mc.level ?: return
    if (mc.screen != null) return
    if (requireMacro.value && !MiningMacroModule.isActive) return
    if (lanternBuffActive) return
    if (level.gameTime - lastUseTick < cooldownTicks.value.toLong()) return

    val slot = findLanternSlot(player)
    if (slot !in 0..8) return

    tryStartLanternPlacement(slot)
  }

  private fun resolveLanternBuffState(player: Player): Boolean {
    if (DeployableHudModule.isLanternActive()) {
      return true
    }
    if (isNearLastPlacement(player)) {
      return true
    }
    return hasLanternBuffInTabList()
  }

  private fun isNearLastPlacement(player: Player): Boolean {
    val last = lastUsePos ?: return false
    val ref = Vec3(last.x + 0.5, player.y, last.z + 0.5)
    return player.position().distanceTo(ref) <= reUseDistance.value
  }

  private fun hasLanternBuffInTabList(): Boolean {
    val connection = mc.connection ?: return false
    return try {
      resolveTabEntries(connection).any { entry ->
        val raw = resolveEntryDisplayName(entry) ?: return@any false
        val stripped = ChatFormatting.stripFormatting(raw)?.lowercase(Locale.ROOT) ?: return@any false
        LANTERN_NAMES.any { stripped.contains(it) } || stripped.contains("will-o'-wisp")
      }
    } catch (_: Exception) {
      false
    }
  }

  private fun resolveTabEntries(connection: Any): List<Any> {
    val methodNames = listOf("listPlayerEntries", "getListedOnlinePlayers", "getOnlinePlayers")
    for (name in methodNames) {
      val method =
        connection.javaClass.methods.firstOrNull { m ->
          m.name == name && m.parameterCount == 0
        } ?: continue
      val result = runCatching { method.invoke(connection) }.getOrNull() ?: continue
      when (result) {
        is Collection<*> -> return result.filterNotNull()
        is Iterable<*> -> return result.filterNotNull()
      }
    }
    return emptyList()
  }

  private fun resolveEntryDisplayName(entry: Any): String? {
    val displayMethodNames = listOf("getTabListDisplayName", "tabListDisplayName", "getDisplayName", "displayName")
    for (name in displayMethodNames) {
      val method =
        entry.javaClass.methods.firstOrNull { m ->
          m.name == name && m.parameterCount == 0
        } ?: continue
      val value = runCatching { method.invoke(entry) }.getOrNull()
      val text = coerceText(value)
      if (!text.isNullOrBlank()) {
        return text
      }
    }

    val profileMethodNames = listOf("getProfile", "getGameProfile", "profile")
    for (name in profileMethodNames) {
      val method =
        entry.javaClass.methods.firstOrNull { m ->
          m.name == name && m.parameterCount == 0
        } ?: continue
      val profile = runCatching { method.invoke(entry) }.getOrNull() ?: continue
      val nameMethod =
        profile.javaClass.methods.firstOrNull { m ->
          m.name == "getName" && m.parameterCount == 0
        } ?: continue
      val profileName = runCatching { nameMethod.invoke(profile) as? String }.getOrNull()
      if (!profileName.isNullOrBlank()) {
        return profileName
      }
    }
    return null
  }

  private fun coerceText(value: Any?): String? {
    if (value == null) return null
    if (value is String) return value
    val textMethod =
      value.javaClass.methods.firstOrNull { m ->
        m.name == "getString" && m.parameterCount == 0
      }
    if (textMethod != null) {
      val raw = runCatching { textMethod.invoke(value) }.getOrNull()
      if (raw is String) {
        return raw
      }
    }
    return value.toString()
  }

  private fun findLanternSlot(player: Player): Int {
    val inventory = player.inventory
    for (i in 0..8) {
      val stack = inventory.getItem(i)
      if (stack.isEmpty) continue
      val name = normalizeName(stack.hoverName.string)
      if (LANTERN_NAMES.any { name.contains(it) }) return i
    }
    return -1
  }

  private fun normalizeName(raw: String): String =
    (ChatFormatting.stripFormatting(raw) ?: raw).lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()

  private fun handlePlacementSequence() {
    val player = mc.player
    val level = mc.level
    if (player == null || level == null || mc.screen != null) {
      cancelPlacementSequence()
      return
    }

    when (pendingPlacementPhase) {
      LanternPlacementPhase.TURN_TO_PLACE -> {
        val target = pendingPlaceRotation ?: run {
          cancelPlacementSequence()
          return
        }
        pendingPlacementTicks++
        RotationExecutor.rotateTo(target, lanternPlaceStrategy)
        if (hasReachedRotation(player, target) || pendingPlacementTicks >= LANTERN_TURN_TIMEOUT_TICKS) {
          val slot = pendingLanternSlot
          if (slot !in 0..8) {
            cancelPlacementSequence()
            return
          }
          if (player.inventory.selectedSlot != slot) {
            InventoryUtils.holdHotbarSlot(slot)
          }
          pendingPlacementPhase = LanternPlacementPhase.WAIT_BEFORE_USE
          pendingPlacementTicks = 0
        }
      }

      LanternPlacementPhase.WAIT_BEFORE_USE -> {
        val slot = pendingLanternSlot
        if (slot !in 0..8) {
          cancelPlacementSequence()
          return
        }
        if (player.inventory.selectedSlot != slot) {
          InventoryUtils.holdHotbarSlot(slot)
        }
        if (pendingPlacementTicks < preUseDelayTicks.value.toInt()) {
          pendingPlacementTicks++
          return
        }
        useLantern(player, level)
        pendingPlacementPhase = LanternPlacementPhase.WAIT_AFTER_USE
        pendingPlacementTicks = 0
      }

      LanternPlacementPhase.WAIT_AFTER_USE -> {
        if (pendingPlacementTicks < postUseDelayTicks.value.toInt()) {
          pendingPlacementTicks++
          return
        }
        restorePendingSlot(player)
        val target = pendingReturnRotation
        if (target != null && !hasReachedRotation(player, target)) {
          pendingPlacementPhase = LanternPlacementPhase.TURN_BACK
          pendingPlacementTicks = 0
          RotationExecutor.rotateTo(target, lanternReturnStrategy)
        } else {
          finishPlacementSequence()
        }
      }

      LanternPlacementPhase.TURN_BACK -> {
        val target = pendingReturnRotation ?: run {
          finishPlacementSequence()
          return
        }
        pendingPlacementTicks++
        RotationExecutor.rotateTo(target, lanternReturnStrategy)
        if (hasReachedRotation(player, target) || pendingPlacementTicks >= LANTERN_RETURN_TIMEOUT_TICKS) {
          finishPlacementSequence()
        }
      }

      null -> return
    }
  }

  private fun hasReachedRotation(player: Player, target: Rotation): Boolean {
    val yawError = abs(AngleUtils.getRotationDelta(player.yRot, target.yaw))
    val pitchError = abs(target.pitch - player.xRot)
    return yawError <= LANTERN_ROTATION_SNAP_YAW && pitchError <= LANTERN_ROTATION_SNAP_PITCH
  }

  private fun finishPlacementSequence() {
    mc.player?.let(::restorePendingSlot)
    pendingPlacementPhase = null
    pendingPlacementTicks = 0
    pendingPlaceRotation = null
    pendingReturnRotation = null
    pendingOriginalSlot = -1
    pendingLanternSlot = -1
    RotationExecutor.stopRotating()
  }

  private fun cancelPlacementSequence() {
    mc.options.keyUse.setDown(false)
    finishPlacementSequence()
  }

  private fun restorePendingSlot(player: Player) {
    val originalSlot = pendingOriginalSlot
    if (originalSlot in 0..8 && player.inventory.selectedSlot != originalSlot) {
      InventoryUtils.holdHotbarSlot(originalSlot)
    }
    pendingOriginalSlot = -1
  }

  private fun useLantern(player: Player, level: net.minecraft.world.level.Level) {
    mc.options.keyUse.setDown(false)
    MouseUtils.rightClick()

    noteLanternUse(level.gameTime, player.blockPosition())
  }

  @SubscribeEvent
  fun onRender(event: WorldRenderEvent.Last) {
    if (!enabled.value && !MiningMacroModule.isActive) return
    if (!lanternBuffActive) return
    val pos = lastUsePos ?: return
    val level = mc.level ?: return

    val cx = pos.x + 0.5
    val cy = pos.y + 0.5
    val cz = pos.z + 0.5
    val radius = reUseDistance.value
    val glowRadius = radius + 0.18
    val segments = 72

    val now = System.currentTimeMillis()
    val pulse = (sin(now / 920.0 * Math.PI) * 0.5 + 0.5).toFloat()
    val glowPulse = (sin(now / 650.0 * Math.PI) * 0.5 + 0.5).toFloat()
    val baseAlpha = (92 + (pulse * 108).toInt()).coerceIn(0, 255)
    val rotAngle = (now % 3000L).toDouble() / 3000.0 * Math.PI * 2.0
    val arcHalfSpan = Math.PI / 6.0

    drawLanternRing(level, cx, cy, cz, glowRadius, segments, rotAngle, arcHalfSpan, baseAlpha, glowPulse, 0.9f, true)
    drawLanternRing(level, cx, cy, cz, radius, segments, rotAngle, arcHalfSpan, baseAlpha + 24, pulse, 1.25f, false)
  }

  private fun drawLanternRing(
    level: net.minecraft.world.level.Level,
    cx: Double,
    cy: Double,
    cz: Double,
    radius: Double,
    segments: Int,
    rotAngle: Double,
    arcHalfSpan: Double,
    baseAlpha: Int,
    pulse: Float,
    widthScale: Float,
    halo: Boolean,
  ) {
    var prevX = cx + cos(0.0) * radius
    var prevZ = cz + sin(0.0) * radius
    for (i in 1..segments) {
      val angle = (i.toDouble() / segments) * Math.PI * 2.0
      val nextX = cx + cos(angle) * radius
      val nextZ = cz + sin(angle) * radius

      val segMid = ((i - 0.5) / segments) * Math.PI * 2.0
      val diff = abs(((segMid - rotAngle + Math.PI * 3.0) % (Math.PI * 2.0)) - Math.PI)
      val inArc = diff < arcHalfSpan
      val alpha = if (inArc) 255 else (baseAlpha + (pulse * if (halo) 30f else 18f).toInt()).coerceIn(0, 255)
      val r = if (inArc) 166 else if (halo) 104 else 82
      val g = if (inArc) 244 else if (halo) 220 else 204
      val width = if (inArc) 2.8f * widthScale else 1.4f * widthScale
      OverlayRenderEngine.addLine(
        level, prevX, cy, prevZ, nextX, cy, nextZ,
        OverlayRenderEngine.Color(r, g, 255, alpha), width, 2, "lantern-radius", true
      )
      prevX = nextX
      prevZ = nextZ
    }
  }
}
