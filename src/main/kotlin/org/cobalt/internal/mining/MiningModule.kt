package org.cobalt.internal.mining

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.regex.Pattern
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.BlockChangeEvent
import org.cobalt.api.event.impl.client.ChatEvent
import org.cobalt.api.event.impl.client.PacketEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.ActionSetting
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.ModeSetting
import org.cobalt.api.module.setting.impl.RangeSetting
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.module.setting.impl.TextSetting
import org.cobalt.api.notification.NotificationManager
import org.cobalt.api.util.InventoryUtils
import org.cobalt.api.util.getLoreLines
import org.cobalt.internal.rotation.RotationsModule
import org.cobalt.internal.ui.panel.panels.UIModuleList

object MiningModule : Module("Mining") {

  private val mc: Minecraft = Minecraft.getInstance()

  val enabled = CheckboxSetting(
    "Enabled",
    "Enable mining stats tracking.",
    false
  )

  val blockStrength = SliderSetting(
    "Block Strength",
    "Fallback block strength used when the current block type is unknown.",
    10.0,
    1.0,
    6000.0
  )

  val blockType = ModeSetting(
    "Block Type",
    "Select a known block hardness/threshold profile to calculate ticks.",
    0,
    MiningBlockRegistry.BLOCK_TYPES
  )

  val detectedBlockText = TextSetting(
    "Detected Block",
    "Last detected block type (from crosshair).",
    "Unknown"
  )

  val pingDelay = RangeSetting(
    "Ping Delay",
    "Random extra ticks added to look time.",
    Pair(0.6, 1.4),
    0.0,
    4.0,
  )

  val miningSpeedText = TextSetting(
    "Mining Speed",
    "Last captured mining speed from /stats.",
    "0"
  )

  val hotmMultiplierText = TextSetting(
    "HOTM Mult",
    "Derived HOTM multiplier (currently precision miner only).",
    "1.00"
  )

  val miningGems = CheckboxSetting(
    "Mining Gems",
    "Apply gem-only perks (Professional).",
    false
  )

  val precisionActive = CheckboxSetting(
    "Precision Active",
    "Precision Miner particles are active (+30% mining speed).",
    false
  )

  val speedBoostActive = CheckboxSetting(
    "Speed Boost Active",
    "Mining Speed Boost active (+250% mining speed).",
    false
  )

  val autoActivateSpeedBoost = CheckboxSetting(
    "Auto Speed Boost",
    "Automatically right-click to activate Mining Speed Boost when it comes off cooldown.",
    false
  )

  val frontLoadedActive = CheckboxSetting(
    "Front Loaded Active",
    "Front Loaded active (+250 mining speed).",
    false
  )

  val skymallActive = CheckboxSetting(
    "Skymall Active",
    "Skymall active (+100 mining speed).",
    false
  )

  val miningUmberTungsten = CheckboxSetting(
    "Mining Umber/Tungsten",
    "Apply Strong Arm bonus only while mining Umber or Tungsten.",
    false
  )

  val pingText = TextSetting(
    "Ping",
    "Current ping (ms).",
    "0"
  )

  val lookTicksText = TextSetting(
    "Look Calc",
    "Computed total ticks to look at a block.",
    "0"
  )

  val lookCountdownText = TextSetting(
    "Look Left",
    "Remaining ticks until the next mining target, including ping delay.",
    "0"
  )

  val nukerEnabled = CheckboxSetting(
    "Nuker Active",
    "Mine nearby blocks using a recovered Jasper-style nuker pass.",
    false
  )

  val powderChestCollector = CheckboxSetting(
    "Powder Chest Aura",
    "Right-click nearby powder chests while the nuker is active.",
    false
  )

  val nukerRange = SliderSetting(
    "Nuker Range",
    "Horizontal and vertical range used by the nearby block nuker.",
    4.0,
    1.0,
    8.0,
    step = 1.0,
  )

  val nukerCooldownMs = SliderSetting(
    "Nuker Cooldown MS",
    "Delay between recovered nuker bursts.",
    100.0,
    10.0,
    500.0,
    step = 5.0,
  )

  val nukerBlocksPerTick = SliderSetting(
    "Nuker Blocks/Tick",
    "Maximum nearby blocks to start breaking each burst.",
    1.0,
    1.0,
    8.0,
    step = 1.0,
  )

  val nukerTargetMode = ModeSetting(
    "Nuker Target Mode",
    "Recovered nuker target filter.",
    0,
    arrayOf("Exposed Only", "Exposed Or Soft", "Custom")
  )

  val nukerToolMode = ModeSetting(
    "Nuker Tool Mode",
    "Tool family required before the nuker fires.",
    0,
    arrayOf("Stone", "Soft", "Custom")
  )

  val nukerCustomMatchers = TextSetting(
    "Nuker Custom Matchers",
    "Comma/newline-separated block ids or raw ids. Examples: minecraft:stone, 1, 1:5.",
    ""
  )

  internal val scrapeAll = ActionSetting(
    "Scrape All",
    "Equip drill, scrape /stats, then auto-scrape /hotm after.",
    "Scrape"
  ) {
    val drillSlot = InventoryUtils.findItemInHotbar("drill")
    if (drillSlot >= 0) InventoryUtils.holdHotbarSlot(drillSlot)
    pendingStatsScrape = true
    pendingStatsTick = mc.level?.gameTime ?: 0L
    pendingScrapeAll = true
    pendingHotmAfterStats = false
    sendCommand("/stats")
  }

  private val exportHotm = ActionSetting(
    "Export HOTM",
    "Export the current Heart of the Mountain perks to a text file.",
    "Export"
  ) {
    val screen = mc.screen as? AbstractContainerScreen<*>
    if (screen == null) {
      notify("Open /hotm first.")
      return@ActionSetting
    }
    val perks = parseHotmPerks(screen)
    if (perks.isEmpty()) {
      notify("No HOTM perks found on this screen.")
      return@ActionSetting
    }
    hotmPerks = perks
    exportCombinedStats()
    updateHotmMultiplier()
    notify("Exported HOTM perks (${perks.size}).")
  }

  private var miningSpeed: Double = 0.0
  private var hotmPerks: Map<String, Int> = emptyMap()
  private var miningStats: Map<String, String> = emptyMap()
  private var hotmMultiplier: Double = 1.0
  private var pendingStatsScrape = false
  private var pendingStatsTick = 0L
  private var pendingHotmScrape = false
  private var pendingHotmTick = 0L
  private var pendingScrapeAll = false
  private var pendingHotmAfterStats = false
  private var lastHotmSignature: String? = null
  private var lastHotmParseTick = 0L
  private var miningSpeedBoostBuffActive = false
  val isMiningSpeedBoostActive: Boolean get() = miningSpeedBoostBuffActive
  private var pendingSpeedBoostUse = false
  private var pendingSpeedBoostRelease = false
  private var detectedBlockPos: BlockPos? = null
  private var detectedBlockId: String? = null
  private var sampledLookTargetPos: BlockPos? = null
  private var sampledLookTargetId: String? = null
  private var sampledPingDelayTicks: Double? = null

  init {
    MiningPrecisionTracker.ensureInitialized()

    val side = UIModuleList.SIDE_GROUP
    blockStrength.uiGroup      = side
    pingDelay.uiGroup          = side
    miningSpeedText.uiGroup    = side
    hotmMultiplierText.uiGroup = side
    miningGems.uiGroup         = side
    precisionActive.uiGroup    = side
    speedBoostActive.uiGroup          = side
    autoActivateSpeedBoost.uiGroup    = side
    frontLoadedActive.uiGroup         = side
    skymallActive.uiGroup      = side
    miningUmberTungsten.uiGroup = side
    pingText.uiGroup           = side
    lookTicksText.uiGroup      = side
    lookCountdownText.uiGroup  = side
    nukerEnabled.uiGroup       = side
    powderChestCollector.uiGroup = side
    nukerRange.uiGroup         = side
    nukerCooldownMs.uiGroup    = side
    nukerBlocksPerTick.uiGroup = side
    nukerTargetMode.uiGroup    = side
    nukerToolMode.uiGroup      = side
    nukerCustomMatchers.uiGroup = side
    scrapeAll.uiGroup          = side

    addSetting(
      enabled,
      blockStrength,
      blockType,
      pingDelay,
      miningSpeedText,
      hotmMultiplierText,
      miningGems,
      precisionActive,
      speedBoostActive,
      autoActivateSpeedBoost,
      frontLoadedActive,
      skymallActive,
      miningUmberTungsten,
      pingText,
      lookTicksText,
      lookCountdownText,
      nukerEnabled,
      powderChestCollector,
      nukerRange,
      nukerCooldownMs,
      nukerBlocksPerTick,
      nukerTargetMode,
      nukerToolMode,
      nukerCustomMatchers,
      scrapeAll,
      exportHotm,
    )
    EventBus.register(this)
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
    if (mc.player == null || mc.level == null) {
      miningSpeedBoostBuffActive = false
      MiningNukerController.reset(clearCounts = true)
    }

    if (pendingSpeedBoostRelease) {
      mc.options.keyUse.setDown(false)
      pendingSpeedBoostRelease = false
    }

    if (pendingSpeedBoostUse) {
      pendingSpeedBoostUse = false
      val player = mc.player
      if (player != null && mc.screen == null && MiningMacroModule.isActive) {
        mc.options.keyUse.setDown(true)
        pendingSpeedBoostRelease = true
      }
    }

    val screen = mc.screen

    if (pendingHotmAfterStats && screen == null) {
      pendingHotmAfterStats = false
      pendingHotmScrape = true
      pendingHotmTick = mc.level?.gameTime ?: 0L
      sendCommand("/hotm")
    }

    if (screen is AbstractContainerScreen<*>) {
      if (pendingStatsScrape) {
        captureMiningSpeedFromStats(screen)
      }
      captureHotmPerks(screen)
    }

    val trackingActive = enabled.value || MiningMacroModule.isActive || nukerEnabled.value
    if (!trackingActive) {
      lookCountdownText.value = "0"
      return
    }

    val pingMs = getPingMs()
    pingText.value = pingMs.toString()
    updateBlockDetection()

    updateLookTicks()

    if (nukerEnabled.value && !MiningMacroModule.isActive) {
      MiningNukerController.tick(buildNukerConfig())
    } else if (!nukerEnabled.value) {
      MiningNukerController.reset()
    }
  }

  @SubscribeEvent
  fun onChat(event: ChatEvent.Receive) {
    val message = event.message ?: return
    val stripped = stripFormatting(message).trim()
    when (stripped) {
      MINING_SPEED_BOOST_USED -> miningSpeedBoostBuffActive = true
      MINING_SPEED_BOOST_EXPIRED -> miningSpeedBoostBuffActive = false
      MINING_SPEED_BOOST_READY -> {
        miningSpeedBoostBuffActive = false
        if (autoActivateSpeedBoost.value) pendingSpeedBoostUse = true
      }
    }
    if (nukerEnabled.value) {
      MiningNukerController.onChatMessage(stripped)
    }
  }

  @SubscribeEvent
  fun onPacket(event: PacketEvent.Outgoing) {
    val command = when (val packet = event.packet) {
      is ServerboundChatCommandPacket -> packet.command()
      is ServerboundChatCommandSignedPacket -> packet.command()
      else -> return
    }

    if (command.trim().substringBefore(' ').equals("hotm", ignoreCase = true)) {
      pendingHotmScrape = true
      pendingHotmTick = mc.level?.gameTime ?: 0L
    }
  }

  private fun sendCommand(command: String) {
    val player = mc.player ?: return
    val trimmed = command.trim().removePrefix("/")
    player.connection?.sendCommand(trimmed)
  }

  private fun captureMiningSpeedFromStats(screen: AbstractContainerScreen<*>) {
    val title = screen.title.string.lowercase(Locale.ROOT)
    if (!title.contains("stat") && !title.contains("profile")) {
      return
    }
    val now = mc.level?.gameTime ?: 0L
    if (now - pendingStatsTick > STATS_SCRAPE_TIMEOUT_TICKS) {
      pendingStatsScrape = false
      notify("Failed to scrape Mining Speed (timeout).")
      return
    }
    val slot = findMiningStatsSlot(screen) ?: return
    trySetHoveredSlot(screen, slot)
    val stack = slot.item
    val lines = getTooltipLines(stack)
    if (lines.isEmpty()) return
    val stats = parseMiningStats(lines)
    if (stats.isNotEmpty()) {
      miningStats = stats
      exportCombinedStats()
    }

    val value = parseMiningSpeed(lines)
    if (value != null) {
      miningSpeed = value
      miningSpeedText.value = formatNumber(value)
      pendingStatsScrape = false
      notify("Captured Mining Speed: ${formatNumber(value)}")
      if (pendingScrapeAll) {
        pendingScrapeAll = false
        pendingHotmAfterStats = true
        mc.setScreen(null)
      }
    }
  }

  private fun captureHotmPerks(screen: AbstractContainerScreen<*>) {
    val title = screen.title.string.lowercase(Locale.ROOT)
    if (!title.contains("heart") && !title.contains("hotm")) {
      return
    }
    if (pendingHotmScrape) {
      val now = mc.level?.gameTime ?: 0L
      if (now - pendingHotmTick > HOTM_SCRAPE_TIMEOUT_TICKS) {
        pendingHotmScrape = false
        notify("Failed to scrape HOTM perks (timeout).")
        return
      }
    }
    val signature = "${title}:${screen.menu.containerId}:${screen.menu.slots.size}"
    val now = mc.level?.gameTime ?: 0L
    if (signature == lastHotmSignature && now - lastHotmParseTick < 10L) {
      return
    }
    lastHotmSignature = signature
    lastHotmParseTick = now

    val perks = parseHotmPerks(screen)
    if (perks.isEmpty()) return

    hotmPerks = perks
    exportCombinedStats()
    updateHotmMultiplier()
    applyHotmPerksToToggles()
    if (pendingHotmScrape) {
      pendingHotmScrape = false
      notify("Exported HOTM perks (${perks.size}).")
    }
  }

  private fun parseHotmPerks(screen: AbstractContainerScreen<*>): Map<String, Int> {
    val result = LinkedHashMap<String, Int>()
    for (slot in screen.menu.slots) {
      val stack = slot.item
      if (stack.isEmpty) continue
      val name = stripFormatting(stack.hoverName.string)
      if (name.isBlank()) continue

      val lore = getTooltipLines(stack)
      val level = parsePerkLevel(lore)
      if (level != null && level > 0) {
        result[name] = level
      }
    }
    return result
  }

  private fun parseMiningSpeed(lines: List<Component>): Double? {
    for (line in lines) {
      val raw = stripFormatting(line.string).trim()
      val statValue = parseMiningSpeedStatValue(raw)
      if (statValue != null) {
        return statValue
      }
    }
    return null
  }

  private fun parsePerkLevel(lines: List<Component>): Int? {
    for (line in lines) {
      val raw = stripFormatting(line.string)
      val levelMatch = LEVEL_PATTERN.matcher(raw)
      if (levelMatch.find()) {
        return levelMatch.group(1).toIntOrNull()
      }
      val romanMatch = ROMAN_PATTERN.matcher(raw)
      if (romanMatch.find()) {
        return romanToInt(romanMatch.group(1))
      }
    }
    return null
  }

  private fun applyHotmPerksToToggles() {
    if (getPerkLevel("precision miner") > 0)     precisionActive.value    = true
    if (getPerkLevel("mining speed boost") > 0)  speedBoostActive.value   = true
    if (getPerkLevel("front loaded") > 0)        frontLoadedActive.value  = true
    if (getPerkLevel("skymall") > 0)             skymallActive.value      = true
    if (getPerkLevel("professional") > 0) {
      miningGems.value = hasSelectedGemstones()
    }
    val selectedTypes = MiningMacroModule.getSelectedTypesInOrder()
    if (getPerkLevel("strong arm") > 0) {
      if (selectedTypes.any { it == "Umber" || it == "Tungsten" }) {
        miningUmberTungsten.value = true
      }
    }
  }

  private fun updateHotmMultiplier() {
    val base = miningSpeed
    if (base <= 0.0) {
      hotmMultiplier = 1.0
      hotmMultiplierText.value = "1.00"
      return
    }
    val effective = computeEffectiveMiningSpeed(base)
    val mult = effective / base
    hotmMultiplier = mult
    hotmMultiplierText.value = String.format(Locale.US, "%.2f", mult)
  }

  private fun updateLookTicks() {
    val total = getCalculatedLookTicks(includePingDelay = true)
    if (total <= 0.0) {
      lookTicksText.value = "0"
      lookCountdownText.value = "0"
      return
    }
    lookTicksText.value = String.format(Locale.US, "%.2f", total)
    val target = resolveLookTarget()
    val trackedTicks = MiningMacroModule.getTrackedTargetTicks(MiningMacroModule.tickGliding.value).toDouble()
    val remaining =
      if (target == null) {
        0.0
      } else if (trackedTicks > 0.0) {
        (total - trackedTicks).coerceAtLeast(0.0)
      } else {
        total
      }
    lookCountdownText.value = String.format(Locale.US, "%.2f", remaining)
  }

  fun getCalculatedLookTicks(includePingDelay: Boolean = true): Double {
    val effectiveSpeed = computeEffectiveMiningSpeed(miningSpeed)
    if (effectiveSpeed <= 0.0) {
      return 0.0
    }
    val baseTicks = resolveBaseMineTicks(effectiveSpeed).toDouble()
    return if (includePingDelay) baseTicks + computePingDelayTicks() else baseTicks
  }

  @SubscribeEvent
  fun onIncomingPacket(event: PacketEvent.Incoming) {
    if (!nukerEnabled.value || !powderChestCollector.value) return
    val packet = event.packet as? ClientboundLevelParticlesPacket ?: return
    MiningNukerController.onParticlePacket(packet)
  }

  @SubscribeEvent
  fun onBlockChange(event: BlockChangeEvent) {
    if (!nukerEnabled.value || !powderChestCollector.value) return
    MiningNukerController.onBlockChange(event.pos, event.oldBlock, event.newBlock)
  }

  private fun computePingDelayTicks(): Double {
    val target = resolveLookTarget()
    val pos = target?.first
    val id = target?.second
    if (pos == null || id.isNullOrEmpty()) {
      resetLookTickSample()
      return 0.0
    }
    val cached = sampledPingDelayTicks
    if (cached != null && pos == sampledLookTargetPos && id == sampledLookTargetId) {
      return cached
    }
    return RotationsModule.sample(pingDelay.value).also { sampled ->
      sampledLookTargetPos = pos
      sampledLookTargetId = id
      sampledPingDelayTicks = sampled
    }
  }

  private fun getPingMs(): Int {
    val player = mc.player ?: return 0
    val info = player.connection?.getPlayerInfo(player.uuid)
    return info?.latency ?: 0
  }

  private fun exportCombinedStats() {
    val dir = mc.gameDirectory ?: return
    val outDir = File(dir, "config/cobalt")
    if (!outDir.exists()) {
      outDir.mkdirs()
    }
    val file = File(outDir, "hotm-perks.txt")
    val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
      .withZone(ZoneId.systemDefault())
      .format(Instant.now())

    file.bufferedWriter().use { writer ->
      writer.appendLine("# HOTM + Mining Stats Export @ $stamp")
      writer.appendLine("")
      writer.appendLine("[HOTM Perks]")
      if (hotmPerks.isEmpty()) {
        writer.appendLine("None")
      } else {
        hotmPerks.forEach { (name, level) ->
          writer.appendLine("$name: $level")
        }
      }
      writer.appendLine("")
      writer.appendLine("[Mining Stats]")
      if (miningStats.isEmpty() && miningSpeed <= 0.0) {
        writer.appendLine("None")
      } else {
        val merged = LinkedHashMap<String, String>(miningStats)
        if (miningSpeed > 0.0 && !merged.keys.any { it.equals("Mining Speed", ignoreCase = true) }) {
          merged["Mining Speed"] = formatNumber(miningSpeed)
        }
        merged.forEach { (name, value) ->
          writer.appendLine("$name: $value")
        }
      }
      writer.appendLine("")
      writer.appendLine("[Derived Mining Speed]")
      val derived = computeDerivedMiningSpeed()
      writer.appendLine("Base Speed: ${formatNumber(derived.baseSpeed)}")
      writer.appendLine("HOTM Passive: +${formatNumber(derived.passiveBonus)}")
      writer.appendLine("Strong Arm: +${formatNumber(derived.strongArmBonus)} (active=${miningUmberTungsten.value})")
      writer.appendLine("Gem Bonus (Professional): +${formatNumber(derived.professionalBonus)} (active=${hasSelectedGemstones()})")
      writer.appendLine("Front Loaded: +${formatNumber(derived.frontLoadedBonus)} (active=${frontLoadedActive.value})")
      writer.appendLine("Skymall: +${formatNumber(derived.skymallBonus)} (active=${skymallActive.value})")
      writer.appendLine("Precision Miner: x${String.format(Locale.US, "%.2f", derived.precisionMultiplier)} (active=${precisionActive.value})")
      writer.appendLine("Speed Boost: +${formatNumber(derived.speedBoostBonus)} (active=${speedBoostActive.value})")
      writer.appendLine("Effective Speed: ${formatNumber(derived.effectiveSpeed)}")
      val blockLabel = blockType.options.getOrNull(blockType.value) ?: "Custom"
      writer.appendLine("Block Type: $blockLabel")
      writer.appendLine("Block Strength: ${formatNumber(resolveBlockStrength())}")
      writer.appendLine("")
      writer.appendLine("[Warm Heart]")
      writer.appendLine("Warm Heart Level: ${derived.warmHeartLevel}")
      writer.appendLine("Cold Reduction: ${String.format(Locale.US, "%.2f", derived.warmHeartCold)}")
    }
  }

  private fun findMiningStatsSlot(screen: AbstractContainerScreen<*>): Slot? {
    for (slot in screen.menu.slots) {
      val stack = slot.item
      if (stack.isEmpty) continue
      val name = stripFormatting(stack.hoverName.string).lowercase(Locale.ROOT)
      if (name.contains("mining stats")) {
        return slot
      }
      val lore = getTooltipLines(stack)
      for (line in lore) {
        val raw = stripFormatting(line.string).trim()
        if (parseMiningSpeedStatValue(raw) != null) {
          return slot
        }
      }
    }
    return null
  }

  private fun trySetHoveredSlot(screen: AbstractContainerScreen<*>, slot: Slot) {
    val field = hoveredSlotField ?: resolveHoveredSlotField(screen).also { hoveredSlotField = it }
    if (field != null) {
      try {
        field.set(screen, slot)
      } catch (_: Exception) {
        // ignore
      }
    }
  }

  private fun resolveHoveredSlotField(screen: AbstractContainerScreen<*>): java.lang.reflect.Field? {
    val cls = screen.javaClass
    val byName = cls.declaredFields.firstOrNull {
      Slot::class.java.isAssignableFrom(it.type) && it.name.contains("hover", ignoreCase = true)
    }
    if (byName != null) {
      byName.isAccessible = true
      return byName
    }
    val byType = cls.declaredFields.firstOrNull { Slot::class.java.isAssignableFrom(it.type) }
    if (byType != null) {
      byType.isAccessible = true
      return byType
    }
    return null
  }

  private fun extractNumber(text: String): Double? {
    val match = NUMBER_PATTERN.matcher(text)
    if (!match.find()) return null
    val raw = match.group(1).replace(",", "")
    return raw.toDoubleOrNull()
  }

  private fun parseMiningStats(lines: List<Component>): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    for (line in lines) {
      val raw = stripFormatting(line.string).trim()
      if (raw.isEmpty()) continue
      val match = STAT_PAIR_PATTERN.matcher(raw)
      if (match.find()) {
        val name = match.group(1).trim()
        val value = match.group(2).trim()
        if (name.isNotBlank() && value.isNotBlank()) {
          result[name] = value
          continue
        }
      }
      val statValue = parseMiningSpeedStatValue(raw)
      if (statValue != null) {
        result["Mining Speed"] = formatNumber(statValue)
      }
    }
    return result
  }

  private fun parseMiningSpeedStatValue(raw: String): Double? {
    val match = STAT_PAIR_PATTERN.matcher(raw)
    if (!match.find()) return null
    val name = normalizeStatName(match.group(1))
    if (!name.equals("Mining Speed", ignoreCase = true)) return null
    return match.group(2).replace(",", "").toDoubleOrNull()
  }

  private fun normalizeStatName(raw: String): String {
    val stripped = raw.trim()
    val cleaned = LEADING_DECORATION_PATTERN.matcher(stripped).replaceAll("")
    return cleaned.trim()
  }

  private fun updateBlockDetection() {
    val level = mc.level ?: return
    val hit = mc.hitResult
    if (hit !is BlockHitResult || hit.type != HitResult.Type.BLOCK) {
      detectedBlockPos = null
      detectedBlockId = null
      detectedBlockText.value = "Unknown"
      return
    }
    detectedBlockPos = hit.blockPos
    val state = level.getBlockState(hit.blockPos)
    val id = BuiltInRegistries.BLOCK.getKey(state.block).toString()
    detectedBlockId = id
    val label = MiningBlockRegistry.BLOCK_ID_TO_TYPE[id]
    if (label != null) {
      detectedBlockText.value = label
    } else {
      detectedBlockText.value = id.substringAfter(':')
    }
  }

  private fun resetLookTickSample() {
    sampledLookTargetPos = null
    sampledLookTargetId = null
    sampledPingDelayTicks = null
  }

  private fun resolveLookTarget(): Pair<BlockPos, String>? {
    val level = mc.level
    if (level != null) {
      MiningMacroModule.getTrackedTargetBlock()?.let { pos ->
        val id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).block).toString()
        return pos to id
      }
    }
    val pos = detectedBlockPos ?: return null
    val id = detectedBlockId ?: return null
    return pos to id
  }

  private data class ResolvedMiningTarget(
    val type: String?,
    val hardness: Double,
  )

  private fun resolveBlockStrength(): Double {
    return resolveMiningTarget().hardness
  }

  private fun resolveBaseMineTicks(effectiveSpeed: Double): Int {
    val resolved = resolveMiningTarget()
    return MiningBreakThresholds.getOptimalTicks(resolved.type, effectiveSpeed, resolved.hardness)
  }

  private fun resolveMiningTarget(): ResolvedMiningTarget {
    val level = mc.level
    val targetId =
      level?.let { activeLevel ->
        MiningMacroModule.getTrackedTargetBlock()?.let { pos ->
          BuiltInRegistries.BLOCK.getKey(activeLevel.getBlockState(pos).block).toString()
        }
      } ?: detectedBlockId
    val targetType = targetId?.let { MiningBlockRegistry.BLOCK_ID_TO_TYPE[it] }
    val targetHardness = targetType?.let { MiningBlockRegistry.BLOCK_HARDNESS[it] }
    if (targetHardness != null) {
      return ResolvedMiningTarget(targetType, targetHardness)
    }

    val selectedType = blockType.options.getOrNull(blockType.value)
    val knownHardness = selectedType?.let { MiningBlockRegistry.BLOCK_HARDNESS[it] }
    if (knownHardness != null) {
      return ResolvedMiningTarget(selectedType, knownHardness)
    }
    return ResolvedMiningTarget(null, blockStrength.value)
  }

  private fun computeEffectiveMiningSpeed(baseSpeed: Double): Double {
    if (baseSpeed <= 0.0) return 0.0
    val derived = computeDerivedMiningSpeed(baseSpeed)
    return derived.effectiveSpeed
  }

  private fun hasSelectedGemstones(): Boolean {
    val selectedTypes = MiningMacroModule.getSelectedTypesInOrder()
    if (selectedTypes.any { it.contains("Gemstone") }) {
      return true
    }
    val selectedType = blockType.options.getOrNull(blockType.value)
    return selectedType?.contains("Gemstone") == true
  }

  private fun isPrecisionBonusApplied(): Boolean {
    if (!precisionActive.value) return false
    if (!MiningMacroModule.isActive) return true
    return MiningMacroModule.isUsingPrecisionPoint()
  }

  private fun computeDerivedMiningSpeed(baseOverride: Double? = null): DerivedMiningSpeed {
    val base = baseOverride ?: miningSpeed
    val perkMiningSpeed = getPerkLevel("mining speed") * 20.0
    val speedyMineman = getPerkLevel("speedy mineman") * 40.0
    val strongArmLevel = getPerkLevel("strong arm")
    val strongArm = if (miningUmberTungsten.value) strongArmLevel * 5.0 else 0.0
    val passive = perkMiningSpeed + speedyMineman + strongArm

    val professionalLevel = getPerkLevel("professional")
    val professionalBonus =
      if (hasSelectedGemstones() && professionalLevel > 0) {
        55.0 + (professionalLevel - 1).coerceAtLeast(0) * 5.0
      } else {
        0.0
      }

    val frontLoadedBonus = if (frontLoadedActive.value) 250.0 else 0.0
    val skymallBonus = if (skymallActive.value) 100.0 else 0.0

    val baseTotal = base + passive + professionalBonus + frontLoadedBonus + skymallBonus

    val precisionLevel = getPerkLevel("precision miner")
    val precisionMultiplier =
      if (isPrecisionBonusApplied() && precisionLevel > 0) {
        1.0 + 0.3 * precisionLevel
      } else {
        1.0
      }
    val afterPrecision = baseTotal * precisionMultiplier

    val speedBoostBonus =
      if (speedBoostActive.value && getPerkLevel("mining speed boost") > 0 && miningSpeedBoostBuffActive) {
        afterPrecision * 2.5
      } else {
        0.0
      }

    val warmHeartLevel = getPerkLevel("warm heart")
    val warmHeartCold = warmHeartLevel * 0.4

    val effective = afterPrecision + speedBoostBonus
    return DerivedMiningSpeed(
      baseSpeed = base,
      passiveBonus = passive,
      strongArmBonus = strongArm,
      professionalBonus = professionalBonus,
      frontLoadedBonus = frontLoadedBonus,
      skymallBonus = skymallBonus,
      precisionMultiplier = precisionMultiplier,
      speedBoostBonus = speedBoostBonus,
      effectiveSpeed = effective,
      warmHeartLevel = warmHeartLevel,
      warmHeartCold = warmHeartCold
    )
  }

  private fun getPerkLevel(nameContains: String): Int {
    val target = nameContains.lowercase(Locale.ROOT)
    for ((name, level) in hotmPerks) {
      if (name.lowercase(Locale.ROOT).contains(target)) {
        return level
      }
    }
    return 0
  }

  private fun stripFormatting(text: String): String {
    return ChatFormatting.stripFormatting(text) ?: text
  }

  private fun formatNumber(value: Double): String {
    return if (value % 1.0 == 0.0) {
      value.toInt().toString()
    } else {
      String.format(Locale.US, "%.2f", value)
    }
  }

  private fun formatRange(value: Pair<Double, Double>): String {
    return "${formatNumber(value.first)}-${formatNumber(value.second)}"
  }

  private fun getTooltipLines(stack: ItemStack): List<Component> {
    val player = mc.player
    val level = mc.level
    return try {
      val methods = stack.javaClass.methods.filter { it.name == "getTooltipLines" }
      for (method in methods) {
        val params = method.parameterTypes
        if (params.size == 2 && TooltipFlag::class.java.isAssignableFrom(params[1])) {
          return method.invoke(stack, player, TooltipFlag.NORMAL) as? List<Component> ?: emptyList()
        }
        if (params.size == 3 && TooltipFlag::class.java.isAssignableFrom(params[2])) {
          val ctxParam = params[0]
          val ctx =
            when {
              level != null && ctxParam.isAssignableFrom(level.javaClass) -> level
              else -> buildTooltipContext(ctxParam, level)
            }
          return method.invoke(stack, ctx, player, TooltipFlag.NORMAL) as? List<Component> ?: emptyList()
        }
      }
      stack.getLoreLines()
    } catch (_: Exception) {
      stack.getLoreLines()
    }
  }

  private fun buildTooltipContext(ctxClass: Class<*>, level: net.minecraft.world.level.Level?): Any? {
    if (level != null) {
      val ofMethod = ctxClass.methods.firstOrNull { it.name == "of" && it.parameterTypes.size == 1 }
      if (ofMethod != null) {
        return try {
          ofMethod.invoke(null, level)
        } catch (_: Exception) {
          null
        }
      }
    }
    val emptyMethod = ctxClass.methods.firstOrNull { it.name == "empty" && it.parameterTypes.isEmpty() }
    if (emptyMethod != null) {
      return try {
        emptyMethod.invoke(null)
      } catch (_: Exception) {
        null
      }
    }
    return null
  }

  private fun notify(message: String) {
    NotificationManager.queue("Mining", message, 2000L)
  }

  private fun buildNukerConfig(): MiningNukerController.Config {
    return MiningNukerController.Config(
      range = nukerRange.value.toInt(),
      cooldownMs = nukerCooldownMs.value.toInt(),
      blocksPerTick = nukerBlocksPerTick.value.toInt(),
      targetMode =
        when (nukerTargetMode.value) {
          1 -> MiningNukerController.TargetMode.EXPOSED_OR_SOFT
          2 -> MiningNukerController.TargetMode.CUSTOM
          else -> MiningNukerController.TargetMode.EXPOSED_ONLY
        },
      toolMode =
        when (nukerToolMode.value) {
          1 -> MiningNukerController.ToolMode.SOFT
          2 -> MiningNukerController.ToolMode.CUSTOM
          else -> MiningNukerController.ToolMode.STONE
        },
      customMatchers = MiningNukerController.parseCustomMatchers(nukerCustomMatchers.value),
      powderChestCollector = powderChestCollector.value,
    )
  }

  private val NUMBER_PATTERN = Pattern.compile("([0-9][0-9,]*)")
  private val LEVEL_PATTERN = Pattern.compile("Level\\s+([0-9]+)")
  private val ROMAN_PATTERN = Pattern.compile("(?:Tier|Level)\\s+([IVX]+)", Pattern.CASE_INSENSITIVE)
  private val STAT_PAIR_PATTERN = Pattern.compile("^(.+?)\\s*[:\\s]+([0-9][0-9,]*(?:\\.[0-9]+)?)$")
  private val LEADING_DECORATION_PATTERN = Pattern.compile("^[^A-Za-z0-9]+")
  private const val MINING_SPEED_BOOST_USED = "You used your Mining Speed Boost Pickaxe Ability!"
  private const val MINING_SPEED_BOOST_EXPIRED = "Your Mining Speed Boost has expired!"
  private const val MINING_SPEED_BOOST_READY = "Mining Speed Boost is now available!"

  private fun romanToInt(roman: String): Int {
    var sum = 0
    var last = 0
    val chars = roman.uppercase(Locale.ROOT)
    for (i in chars.length - 1 downTo 0) {
      val value = when (chars[i]) {
        'I' -> 1
        'V' -> 5
        'X' -> 10
        else -> 0
      }
      if (value < last) sum -= value else sum += value
      last = value
    }
    return sum
  }

  private const val STATS_SCRAPE_TIMEOUT_TICKS = 60L
  private const val HOTM_SCRAPE_TIMEOUT_TICKS = 100L

  private var hoveredSlotField: java.lang.reflect.Field? = null

  private data class DerivedMiningSpeed(
    val baseSpeed: Double,
    val passiveBonus: Double,
    val strongArmBonus: Double,
    val professionalBonus: Double,
    val frontLoadedBonus: Double,
    val skymallBonus: Double,
    val precisionMultiplier: Double,
    val speedBoostBonus: Double,
    val effectiveSpeed: Double,
    val warmHeartLevel: Int,
    val warmHeartCold: Double
  )

  fun getMiningCategory(): String {
    val types = MiningMacroModule.getSelectedTypesInOrder()
    return when {
      types.isEmpty() -> "None"
      types.any { it.contains("Gemstone") } -> "Gemstone"
      types.any { it.startsWith("Mithril") || it == "Titanium" } -> "Mithril"
      types.any { it == "Umber" || it == "Tungsten" || it == "Glacite" } -> "Tunnel"
      types.any { it.startsWith("Pure") } -> "Pure Ore"
      else -> types.first()
    }
  }

  fun buildOverlayRows(): List<String> {
    val rows = mutableListOf(
      "Type:     ${getMiningCategory()}",
      "Block:    ${detectedBlockText.value}",
      "Hardness: ${formatNumber(resolveBlockStrength())}",
      "Speed:    ${miningSpeedText.value}",
      "Ping:     ${pingText.value} ms",
      "Delay:    ${formatRange(pingDelay.value)} ticks",
      "LookCalc: ${lookTicksText.value} ticks",
      "LookLeft: ${lookCountdownText.value} ticks",
    )
    if (nukerEnabled.value) {
      rows += "Nuker:    ${if (MiningMacroModule.isActive) "Paused" else "Active"}"
      if (powderChestCollector.value) {
        rows += "Powder:   ${if (MiningNukerController.hasQueuedPowderChest()) "Queued" else "Idle"}"
        rows += "Opened:   ${MiningNukerController.getPowderChestsCollected()}"
      }
    }
    rows += MiningPrecisionTracker.buildOverlayRows()
    return rows
  }

  fun getDetectedBlockPos(): BlockPos? = detectedBlockPos
  fun getDetectedBlockId(): String? = detectedBlockId
  fun isNukerActive(): Boolean = nukerEnabled.value && !MiningMacroModule.isActive
  fun getPowderChestCount(): Int = MiningNukerController.getPowderChestsCollected()

  fun getBuffStatuses(): List<Pair<String, Boolean>> = listOf(
    "Lantern Buff" to AutoLanternModule.isLanternBuffActive(),
    "Mining Speed Boost" to miningSpeedBoostBuffActive,
  )

  fun buildBuffStatusRows(): List<String> = listOf(
    "Lantern Buff: ${AutoLanternModule.getLanternBuffStatus()}",
    "Mining Speed Boost: ${if (miningSpeedBoostBuffActive) "Active" else "Inactive"}"
  )

  fun getActivePerks(): List<String> {
    val result = mutableListOf<String>()
    if (precisionActive.value) {
      val lvl = getPerkLevel("precision miner")
      if (lvl > 0) result.add("Precision Miner x${String.format(Locale.US, "%.1f", 1.0 + 0.3 * lvl)}")
    }
    if (speedBoostActive.value && getPerkLevel("mining speed boost") > 0) {
      result.add("Speed Boost x2.5")
    }
    if (frontLoadedActive.value) result.add("Front Loaded +250 MS")
    if (skymallActive.value) result.add("Skymall +100 MS")
    if (hasSelectedGemstones()) {
      val lvl = getPerkLevel("professional")
      if (lvl > 0) result.add("Professional +${55 + (lvl - 1).coerceAtLeast(0) * 5} MS")
    }
    if (miningUmberTungsten.value) {
      val lvl = getPerkLevel("strong arm")
      if (lvl > 0) result.add("Strong Arm +${lvl * 5} MS")
    }
    return result
  }
}
