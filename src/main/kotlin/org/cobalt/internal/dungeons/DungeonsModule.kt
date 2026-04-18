package org.cobalt.internal.dungeons

import java.awt.Color
import java.util.Locale
import kotlin.math.roundToInt
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.scores.DisplaySlot
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.ChatEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.event.impl.render.WorldRenderEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.ActionSetting
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.ColorSetting
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.module.setting.impl.TextSetting
import org.cobalt.api.util.ChatUtils
import org.cobalt.api.util.InventoryUtils
import org.cobalt.api.util.render.Render3D
import org.cobalt.bridge.module.IBonzoStaffHelper
import org.cobalt.internal.helper.ClientGlowEspManager

object DungeonsModule : Module("Dungeons"), IBonzoStaffHelper {

  private val mc: Minecraft = Minecraft.getInstance()

  private val bonzoEnabled = CheckboxSetting(
    "Bonzo Helper",
    "S-tap before Bonzo Staff explosion to catch boost.",
    false
  )

  private val explosionDelay = SliderSetting(
    "Explosion Delay",
    "Ticks until explosion (adjust for ping).",
    8.0,
    1.0,
    20.0
  )

  private val sTapDuration = SliderSetting(
    "S-Tap Duration",
    "How long to press S before explosion.",
    4.0,
    1.0,
    10.0
  )

  private val adaptiveTiming = CheckboxSetting(
    "Adaptive Timing",
    "Auto-learn optimal timing from attempts.",
    true
  )

  private val experimentalMode = CheckboxSetting(
    "Experimental Mode",
    "Cancel velocity until explosion (requires mana).",
    false
  )

  private val statsText = TextSetting(
    "Bonzo Stats",
    "Success rate and average boost.",
    "No data"
  )

  private val suggestionText = TextSetting(
    "Bonzo Suggestion",
    "Timing feedback after attempts.",
    ""
  )

  private val resetStats = ActionSetting(
    "Reset Bonzo Stats",
    "Reset Bonzo Staff helper stats.",
    "Reset"
  ) {
    resetStats()
  }

  private val autoSuperboomEnabled = CheckboxSetting(
    "Auto Superboom",
    "Auto-swap to Superboom TNT in Dungeons only.",
    false
  )

  private val switchDelayTicks = SliderSetting(
    "Superboom Switch Delay",
    "Ticks to wait after switching slot (3-15).",
    4.0,
    3.0,
    15.0
  )

  private val returnDelayTicks = SliderSetting(
    "Superboom Return Delay",
    "Ticks before switching back (5-25).",
    8.0,
    5.0,
    25.0
  )

  private val requireTargetBlock = CheckboxSetting(
    "Superboom Target Blocks",
    "Only trigger on dungeon blocks.",
    true
  )

  private val detectCrackedBricks = CheckboxSetting(
    "Cracked Bricks",
    "Detect cracked stone bricks (weak walls).",
    true
  )

  private val detectSlabs = CheckboxSetting(
    "Smooth Stone Slabs",
    "Detect smooth stone slabs (crypt lids).",
    true
  )

  private val detectStairs = CheckboxSetting(
    "Stone Brick Stairs",
    "Detect stone brick stairs (crypts).",
    true
  )

  private val allowNormalTnt = CheckboxSetting(
    "Allow Normal TNT",
    "Allow normal TNT (testing).",
    true
  )

  private val superboomDebug = CheckboxSetting(
    "Superboom Debug",
    "Show debug messages in chat.",
    false
  )

  private val witherKeyEspEnabled = CheckboxSetting(
    "Wither Key ESP",
    "Highlight dropped Wither Keys in dungeons.",
    true
  )

  private val witherKeyTracer = CheckboxSetting(
    "Wither Key Tracer",
    "Draw a tracer to dropped Wither Keys.",
    true
  )

  private val witherKeyColor = ColorSetting(
    "Wither Key Color",
    "ESP color used for dropped Wither Keys.",
    0xFF4DE2C5.toInt()
  )

  private val witherKeyLabel = CheckboxSetting(
    "Wither Key Label",
    "Show 'Wither Key' text above dropped Wither Key items.",
    true
  )

  private val mobEspEnabled = CheckboxSetting(
    "Mob ESP",
    "Apply a vanilla glow outline to dungeon mobs.",
    false
  )

  private val mobEspColor = ColorSetting(
    "Mob ESP Color",
    "Glow color used for dungeon mobs.",
    0xFFFF5555.toInt()
  )

  @Volatile
  private var shouldPressBackward = false

  @Volatile
  private var shouldCancelVelocity = false

  private var wasEnabled = false
  private var wasRightClicking = false
  private var waitingForExplosion = false
  private var ticksSinceClick = 0

  private var velocityBeforeStaff = 0.0
  private var maxVelocityAfterStaff = 0.0
  private var ticksAtMaxVelocity = 0

  private var successfulBoosts = 0
  private var totalAttempts = 0
  private var averageBoostStrength = 0.0

  private var superboomSequenceActive = false
  private var lastSuperboomTick = 0L
  private var originalSlot = -1
  private var lastSwapTick = -1L
  private var recentlySwapped = false
  private var slotSwitchScheduledTick = -1L
  private var returnSwitchScheduledTick = -1L
  private var targetSlot = -1
  private var inBoss = false
  private var lastBossCheckTick = 0L

  init {
    addSetting(
      bonzoEnabled,
      explosionDelay,
      sTapDuration,
      adaptiveTiming,
      experimentalMode,
      statsText,
      suggestionText,
      resetStats,
      autoSuperboomEnabled,
      switchDelayTicks,
      returnDelayTicks,
      requireTargetBlock,
      detectCrackedBricks,
      detectSlabs,
      detectStairs,
      allowNormalTnt,
      superboomDebug,
      witherKeyEspEnabled,
      witherKeyTracer,
      witherKeyColor,
      witherKeyLabel,
      mobEspEnabled,
      mobEspColor,
    )
    EventBus.register(this)
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
    val player = mc.player
    val level = mc.level

    tickBonzo(player)

    if (level == null || player == null) {
      ClientGlowEspManager.clear(DUNGEON_MOB_ESP_SCOPE)
      resetSuperboomState(true)
      return
    }

    tickSuperboom(level)

    if (mobEspEnabled.value && isInDungeon(level)) {
      syncDungeonMobEsp(level, player)
    } else {
      ClientGlowEspManager.clear(DUNGEON_MOB_ESP_SCOPE, level)
    }
  }

  @SubscribeEvent
  fun onChat(event: ChatEvent.Receive) {
    val message = event.message ?: return
    onChatMessage(message)
  }

  @SubscribeEvent
  fun onRender(event: WorldRenderEvent.Last) {
    val level = mc.level ?: return
    val player = mc.player ?: return
    if (!witherKeyEspEnabled.value || !isInDungeon(level)) return

    val stroke = Color(witherKeyColor.value, true)
    val outerStroke = stroke.brighter()
    val outerFill = Color(stroke.red, stroke.green, stroke.blue, 72)
    val innerFill = Color(stroke.red, stroke.green, stroke.blue, 118)

    for (entity in level.entitiesForRendering()) {
      val itemEntity = entity as? ItemEntity ?: continue
      if (!itemEntity.isAlive || !isWitherKeyItem(itemEntity.item)) continue

      val box = itemEntity.boundingBox.inflate(0.28, 0.18, 0.28)
      Render3D.drawStyledBox(
        event.context,
        box.inflate(0.12, 0.08, 0.12),
        outerStroke,
        outerFill,
        esp = true,
        lineWidth = 4.2f
      )
      Render3D.drawStyledBox(
        event.context,
        box,
        stroke,
        innerFill,
        esp = true,
        lineWidth = 2.2f
      )

      if (witherKeyTracer.value) {
        Render3D.drawLine(
          event.context,
          player.getEyePosition(1.0f),
          itemEntity.position().add(0.0, 0.12, 0.0),
          stroke,
          esp = true,
          thickness = 1.8f
        )
      }

      if (witherKeyLabel.value) {
        val labelPos = Vec3.atCenterOf(itemEntity.blockPosition()).add(0.0, 1.5, 0.0)
        Render3D.drawWorldLabel(event.context, labelPos, "Wither Key", stroke)
      }
    }
  }

  private fun tickBonzo(player: net.minecraft.client.player.LocalPlayer?) {
    val enabledNow = bonzoEnabled.value
    if (!enabledNow) {
      if (wasEnabled) {
        resetState()
      }
      wasEnabled = false
      return
    }
    if (!wasEnabled) {
      resetState()
    }
    wasEnabled = true

    if (player == null) return

    val isRightClicking = mc.options.keyUse.isDown
    val holdingStaff = isHoldingBonzoStaff(player)

    if (isRightClicking && !wasRightClicking && holdingStaff) {
      startAttempt(player)
    }
    wasRightClicking = isRightClicking

    if (!waitingForExplosion) {
      shouldPressBackward = false
      shouldCancelVelocity = false
      return
    }

    ticksSinceClick++
    val currentVelocity = player.deltaMovement.horizontalDistance()
    if (currentVelocity > maxVelocityAfterStaff) {
      maxVelocityAfterStaff = currentVelocity
      ticksAtMaxVelocity = ticksSinceClick
    }

    val delayTicks = explosionDelay.value.roundToInt().coerceIn(1, 20)
    val sTapTicks = sTapDuration.value.roundToInt().coerceIn(1, 10)

    if (experimentalMode.value && ticksSinceClick <= delayTicks) {
      shouldCancelVelocity = true
      shouldPressBackward = false
    } else {
      shouldCancelVelocity = false
      val startSTapAt = (delayTicks - sTapTicks).coerceAtLeast(1)
      shouldPressBackward = ticksSinceClick >= startSTapAt && ticksSinceClick < delayTicks
    }

    if (ticksSinceClick >= delayTicks + 5) {
      finishAttempt()
    }
  }

  private fun tickSuperboom(level: Level) {
    if (!autoSuperboomEnabled.value) {
      resetSuperboomState(superboomSequenceActive)
      return
    }

    val nowTick = level.gameTime
    if (lastSwapTick < nowTick) {
      recentlySwapped = false
    }

    if (nowTick - lastBossCheckTick >= 20L) {
      updateBossStatus(level)
      lastBossCheckTick = nowTick
    }

    if (slotSwitchScheduledTick >= 0 && nowTick >= slotSwitchScheduledTick) {
      executeSwitchToSlot(targetSlot, nowTick)
      slotSwitchScheduledTick = -1L
    }

    if (returnSwitchScheduledTick >= 0 && nowTick >= returnSwitchScheduledTick) {
      executeRestoreSlot(nowTick)
      returnSwitchScheduledTick = -1L
    }
  }

  private fun startAttempt(player: net.minecraft.client.player.LocalPlayer) {
    waitingForExplosion = true
    ticksSinceClick = 0
    shouldPressBackward = false
    shouldCancelVelocity = false
    totalAttempts++

    velocityBeforeStaff = player.deltaMovement.horizontalDistance()
    maxVelocityAfterStaff = velocityBeforeStaff
    ticksAtMaxVelocity = 0
  }

  private fun finishAttempt() {
    waitingForExplosion = false
    shouldPressBackward = false
    shouldCancelVelocity = false

    val boostGain = maxVelocityAfterStaff - velocityBeforeStaff
    val wasSuccessful = boostGain > 0.5
    if (wasSuccessful) {
      successfulBoosts++
      averageBoostStrength =
        (averageBoostStrength * (successfulBoosts - 1) + boostGain) / successfulBoosts
    }

    updateStatsText()
    updateSuggestionText(boostGain)
  }

  private fun updateStatsText() {
    if (totalAttempts <= 0) {
      statsText.value = "No data"
      return
    }
    val rate = if (totalAttempts > 0) successfulBoosts * 100.0 / totalAttempts else 0.0
    statsText.value =
      String.format(
        Locale.US,
        "%d/%d (%.1f%%) - Avg: %.2f",
        successfulBoosts,
        totalAttempts,
        rate,
        averageBoostStrength
      )
  }

  private fun updateSuggestionText(boostGain: Double) {
    if (!adaptiveTiming.value || totalAttempts < 3) {
      suggestionText.value = ""
      return
    }
    val delayTicks = explosionDelay.value.roundToInt().coerceIn(1, 20)
    suggestionText.value =
      when {
        ticksAtMaxVelocity < delayTicks - 1 ->
          "Explosion near tick $ticksAtMaxVelocity. Try delay ${ticksAtMaxVelocity + 1}."
        boostGain < 0.3 ->
          "Weak boost. Try increasing S-tap duration."
        else ->
          "Settings look good."
      }
  }

  private fun resetStats() {
    successfulBoosts = 0
    totalAttempts = 0
    averageBoostStrength = 0.0
    statsText.value = "No data"
    suggestionText.value = ""
  }

  private fun resetState() {
    shouldPressBackward = false
    shouldCancelVelocity = false
    wasRightClicking = false
    waitingForExplosion = false
    ticksSinceClick = 0
  }

  private fun resetSuperboomState(restoreSlot: Boolean) {
    if (restoreSlot && originalSlot in 0..8) {
      InventoryUtils.holdHotbarSlot(originalSlot)
    }
    superboomSequenceActive = false
    originalSlot = -1
    lastSwapTick = -1L
    recentlySwapped = false
    slotSwitchScheduledTick = -1L
    returnSwitchScheduledTick = -1L
    targetSlot = -1
    inBoss = false
    lastBossCheckTick = 0L
    lastSuperboomTick = 0L
  }

  private fun debugSuperboom(message: String) {
    if (!superboomDebug.value) return
    ChatUtils.sendDebug(message)
  }

  private fun onChatMessage(message: String) {
    if (!autoSuperboomEnabled.value) return
    if (message.contains("[BOSS]") && (
        message.contains("Maxor") ||
          message.contains("Storm") ||
          message.contains("Goldor") ||
          message.contains("Necron") ||
          message.contains("Wither King") ||
          message.contains("Sadan")
      )) {
      inBoss = true
      debugSuperboom("Boss detected via chat - disabled")
      return
    }

    if (message.contains("The Core entrance is opening!") ||
      message.contains("PUZZLE FAIL!") ||
      message.contains("PUZZLE COMPLETE!") ||
      message.contains("defeated") ||
      message.contains("EXTRA STATS")
    ) {
      inBoss = false
      debugSuperboom("Boss ended via chat - enabled")
    }
  }

  private fun updateBossStatus(level: Level) {
    val scoreboard = level.scoreboard ?: run {
      inBoss = false
      return
    }
    val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: run {
      inBoss = false
      return
    }
    var detected = false
    try {
      val scores = scoreboard.listPlayerScores(objective)
      for (score in scores) {
        val ownerName = score.owner()
        val team = scoreboard.getPlayersTeam(ownerName)
        val lineText =
          if (team != null) {
            team.playerPrefix.string + ownerName + team.playerSuffix.string
          } else {
            ownerName
          }
        val stripped = stripFormatting(lineText).lowercase(Locale.ROOT)
        if (
          stripped.contains("sadan") ||
          stripped.contains("maxor") ||
          stripped.contains("storm") ||
          stripped.contains("goldor") ||
          stripped.contains("necron") ||
          stripped.contains("wither king") ||
          stripped.contains("the watcher") ||
          (stripped.contains("boss") && (stripped.contains("room") || stripped.contains("fight")))
        ) {
          detected = true
          break
        }
      }
    } catch (_: Exception) {
    }
    if (detected != inBoss) {
      inBoss = detected
      debugSuperboom(if (inBoss) "Boss detected via scoreboard - disabled" else "Left boss - enabled")
    }
  }

  private fun stripFormatting(text: String): String {
    return ChatFormatting.stripFormatting(text) ?: text
  }

  internal fun witherKeyMapColor(): Int = witherKeyColor.value

  private fun isInDungeon(level: Level): Boolean {
    val scoreboard = level.scoreboard ?: return false
    val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return false

    val allText = StringBuilder()
    val display = objective.displayName.string
    if (display.isNotEmpty()) {
      allText.append(display).append(" ")
    }

    try {
      val scores = scoreboard.listPlayerScores(objective)
      for (score in scores) {
        val ownerName = score.owner()
        val team = scoreboard.getPlayersTeam(ownerName)
        val lineText =
          if (team != null) {
            team.playerPrefix.string + ownerName + team.playerSuffix.string
          } else {
            ownerName
          }
        allText.append(lineText).append(" ")
      }
    } catch (_: Exception) {
    }

    val fullText = stripFormatting(allText.toString())
    val lower = fullText.lowercase(Locale.ROOT)

    if (lower.contains("hub")) {
      return false
    }
    if (lower.contains("catacombs") || lower.contains("the catacombs")) {
      return true
    }
    if (lower.contains("(e)") || lower.contains("entrance")) {
      return true
    }
    for (i in 1..7) {
      if (lower.contains("(f$i)") || lower.contains("floor $i") || lower.contains("f$i")) {
        return true
      }
      if (lower.contains("(m$i)") || lower.contains("master $i") || lower.contains("m$i")) {
        return true
      }
    }
    return false
  }

  private fun getLookingAtBlock(player: net.minecraft.client.player.LocalPlayer, level: Level): BlockPos? {
    val range = 5.0
    val eyePos = player.getEyePosition(1.0f)
    val lookVec = player.getViewVector(1.0f)
    val traceEnd = eyePos.add(lookVec.scale(range))
    val result = level.clip(
      ClipContext(
        eyePos,
        traceEnd,
        ClipContext.Block.OUTLINE,
        ClipContext.Fluid.NONE,
        player
      )
    )
    if (result != null && result.type == HitResult.Type.BLOCK) {
      val pos = (result as BlockHitResult).blockPos
      val state = level.getBlockState(pos)
      if (!state.isAir) {
        return pos
      }
    }
    return null
  }

  private fun isTargetBlock(level: Level, pos: BlockPos): Boolean {
    val state = level.getBlockState(pos)
    val block = state.block
    if (block == Blocks.CRACKED_STONE_BRICKS && detectCrackedBricks.value) {
      return true
    }
    if (block == Blocks.SMOOTH_STONE_SLAB && detectSlabs.value) {
      return true
    }
    if (block == Blocks.STONE_BRICK_STAIRS && detectStairs.value) {
      return true
    }
    return false
  }

  private fun findSuperboomInHotbar(player: net.minecraft.client.player.LocalPlayer): Int {
    val inventory = player.inventory
    for (slot in 0..8) {
      val stack = inventory.getItem(slot)
      if (isSuperboomStack(stack)) {
        return slot
      }
    }
    return -1
  }

  private fun isSuperboomStack(stack: ItemStack): Boolean {
    if (stack.isEmpty) return false
    return try {
      val itemName = stack.item.toString().lowercase(Locale.ROOT)
      if (!itemName.contains("tnt")) return false

      val attributes = getExtraAttributes(stack)
      if (attributes != null && attributes.contains("id")) {
        val id = unwrapOptional<String>(attributes.getString("id")) ?: ""
        if (id == "SUPERBOOM_TNT" || id == "INFINITYBOOM_TNT") {
          return true
        }
        if (allowNormalTnt.value && id == "TNT") {
          return true
        }
      }

      val displayName = stack.hoverName.string.lowercase(Locale.ROOT)
      if (displayName.contains("superboom") || displayName.contains("infinityboom")) {
        return true
      }
      if (allowNormalTnt.value && displayName.contains("tnt")) {
        return true
      }
      false
    } catch (_: Exception) {
      false
    }
  }

  private fun runSuperboomSequence(
    player: net.minecraft.client.player.LocalPlayer,
    level: Level,
    superboomSlot: Int,
    nowTick: Long
  ) {
    if (superboomSequenceActive) return
    superboomSequenceActive = true
    originalSlot = player.inventory.selectedSlot

    if (originalSlot == superboomSlot) {
      debugSuperboom("Already holding TNT - no swap needed.")
      superboomSequenceActive = false
      return
    }

    targetSlot = superboomSlot
    val jitter = level.random.nextInt(2)
    slotSwitchScheduledTick = nowTick + 1L
    val switchDelay = switchDelayTicks.value.roundToInt().coerceIn(3, 15)
    val returnDelay = returnDelayTicks.value.roundToInt().coerceIn(5, 25)
    returnSwitchScheduledTick = nowTick + 1L + switchDelay + returnDelay + jitter
  }

  private fun executeSwitchToSlot(slot: Int, nowTick: Long) {
    if (slot !in 0..8) return
    if (recentlySwapped) {
      debugSuperboom("Swap blocked: recentlySwapped")
      return
    }
    InventoryUtils.holdHotbarSlot(slot)
    recentlySwapped = true
    lastSwapTick = nowTick
  }

  private fun executeRestoreSlot(nowTick: Long) {
    if (originalSlot !in 0..8) {
      superboomSequenceActive = false
      return
    }
    if (recentlySwapped) {
      returnSwitchScheduledTick = nowTick + 1L
      return
    }
    InventoryUtils.holdHotbarSlot(originalSlot)
    recentlySwapped = true
    lastSwapTick = nowTick
    originalSlot = -1
    superboomSequenceActive = false
  }

  private fun isHoldingBonzoStaff(player: net.minecraft.client.player.LocalPlayer): Boolean {
    val mainHand = player.mainHandItem
    val offHand = player.offhandItem
    return isBonzoStaffStack(mainHand) || isBonzoStaffStack(offHand)
  }

  private fun isBonzoStaffStack(stack: ItemStack): Boolean {
    if (stack.isEmpty) return false
    return try {
      val itemName = stack.item.toString().lowercase(Locale.ROOT)
      if (!itemName.contains("blaze_rod")) return false

      val attributes = getExtraAttributes(stack)
      if (attributes != null && attributes.contains("id")) {
        val id = unwrapOptional<String>(attributes.getString("id")) ?: ""
        if (id == "BONZO_STAFF") {
          return true
        }
      }

      val displayName = stack.hoverName.string.lowercase(Locale.ROOT)
      if (displayName.contains("bonzo") && displayName.contains("staff")) {
        return true
      }

      // For testing: accept any blaze rod.
      true
    } catch (_: Exception) {
      false
    }
  }

  private fun getExtraAttributes(stack: ItemStack): CompoundTag? {
    return try {
      val customData = stack.get(DataComponents.CUSTOM_DATA)
      if (customData != null) {
        val nbt = unwrapOptional<CompoundTag>(customData.copyTag()) ?: return null
        if (nbt.contains("ExtraAttributes")) {
          return unwrapOptional<CompoundTag>(nbt.getCompound("ExtraAttributes"))
        }
        if (nbt.contains("extra_attributes")) {
          return unwrapOptional<CompoundTag>(nbt.getCompound("extra_attributes"))
        }
        if (nbt.contains("id")) {
          return nbt
        }
      }
      null
    } catch (_: Exception) {
      null
    }
  }

  private fun <T> unwrapOptional(value: Any?): T? {
    if (value == null) return null
    @Suppress("UNCHECKED_CAST")
    return when (value) {
      is java.util.Optional<*> -> value.orElse(null) as T?
      is java.util.OptionalInt -> if (value.isPresent) value.orElse(0) as T else null
      is java.util.OptionalLong -> if (value.isPresent) value.orElse(0L) as T else null
      is java.util.OptionalDouble -> if (value.isPresent) value.orElse(0.0) as T else null
      else -> value as? T
    }
  }

  @JvmStatic
  fun onLeftClick(): Boolean {
    if (!autoSuperboomEnabled.value) return false
    val player = mc.player ?: return false
    val level = mc.level ?: return false
    if (mc.screen != null) return false

    val heldItem = player.mainHandItem
    if (!heldItem.isEmpty && heldItem.hoverName.string.contains("Dungeonbreaker")) {
      debugSuperboom("Skipping - holding Dungeonbreaker")
      return false
    }

    if (!isInDungeon(level)) {
      debugSuperboom("Not in dungeon")
      return false
    }
    if (inBoss) {
      debugSuperboom("Disabled in boss")
      return false
    }
    if (superboomSequenceActive) {
      debugSuperboom("Sequence already active")
      return false
    }

    val nowTick = level.gameTime
    if (nowTick - lastSuperboomTick < 10L) {
      debugSuperboom("Cooldown active")
      return false
    }

    val targetBlock = getLookingAtBlock(player, level) ?: run {
      debugSuperboom("No block targeted")
      return false
    }
    if (requireTargetBlock.value && !isTargetBlock(level, targetBlock)) {
      debugSuperboom("Not a target block")
      return false
    }

    val superboomSlot = findSuperboomInHotbar(player)
    if (superboomSlot == -1) {
      debugSuperboom("No TNT found in hotbar")
      return false
    }

    lastSuperboomTick = nowTick
    runSuperboomSequence(player, level, superboomSlot, nowTick)
    return true
  }

  override fun isEnabled(): Boolean {
    return bonzoEnabled.value
  }

  override fun shouldPressBackward(): Boolean {
    return bonzoEnabled.value && shouldPressBackward
  }

  override fun shouldCancelVelocity(): Boolean {
    return bonzoEnabled.value && shouldCancelVelocity
  }

  private fun syncDungeonMobEsp(level: net.minecraft.client.multiplayer.ClientLevel, player: Player) {
    val targets =
      level.entitiesForRendering()
        .asSequence()
        .mapNotNull { it as? LivingEntity }
        .filter { shouldHighlightDungeonMob(it, player) }
        .map { ClientGlowEspManager.GlowTarget(it, mobEspColor.value) }
        .toList()

    ClientGlowEspManager.sync(DUNGEON_MOB_ESP_SCOPE, level, targets)
  }

  private fun shouldHighlightDungeonMob(entity: LivingEntity, player: Player): Boolean {
    if (!entity.isAlive || entity.health <= 0f) return false
    if (entity === player || entity is ArmorStand || entity is Player) return false
    return true
  }

  private const val DUNGEON_MOB_ESP_SCOPE = "dungeon_mob_esp"
}
