package org.cobalt.internal.mining

import java.awt.Color
import java.util.Locale
import java.util.UUID
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.network.protocol.game.ServerboundInteractPacket
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.StainedGlassBlock
import net.minecraft.world.level.block.StainedGlassPaneBlock
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec3
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.BlockChangeEvent
import org.cobalt.api.event.impl.client.ChatEvent
import org.cobalt.api.event.impl.client.PacketEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.event.impl.render.WorldRenderEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.ColorSetting
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.module.setting.impl.TextSetting
import org.cobalt.api.notification.NotificationManager
import org.cobalt.api.util.ChatUtils
import org.cobalt.api.util.getSkyblockId
import org.cobalt.api.util.getLoreLines
import org.cobalt.api.util.render.Render3D
import org.cobalt.mixin.client.TabOverlayAccessor
import org.cobalt.mixin.client.MultiPlayerGameModeAccessor

object NoFrillsMiningModule : Module("NoFrills Mining") {

  private enum class CorpseType(val helmetName: String, val keyId: String?) {
    LAPIS("Lapis Armor Helmet", null),
    TUNGSTEN("Mineral Helmet", "TUNGSTEN_KEY"),
    UMBER("Yog Helmet", "UMBER_KEY"),
    VANGUARD("Vanguard Helmet", "SKELETON_KEY"),
    NONE("", null),
  }

  private data class ToolData(
    val tool: ItemStack = ItemStack.EMPTY,
    val ability: String = "",
  ) {
    fun isValid(): Boolean = !tool.isEmpty && ability.isNotBlank()
  }

  private val mc = Minecraft.getInstance()

  private const val AREA_DWARVEN_MINES = "Dwarven Mines"
  private const val AREA_CRYSTAL_HOLLOWS = "Crystal Hollows"
  private const val AREA_MINESHAFT = "Mineshaft"
  private const val AREA_THE_END = "The End"

  private val AREA_NAMES = arrayOf(
    AREA_DWARVEN_MINES,
    AREA_CRYSTAL_HOLLOWS,
    AREA_MINESHAFT,
    AREA_THE_END,
  )

  private val enabled = CheckboxSetting(
    "Enabled",
    "Enable the imported NoFrills mining helpers.",
    false
  )

  private val abilityAlert = CheckboxSetting(
    "Ability Alert",
    "Alert when your mining tool ability comes off cooldown.",
    true
  )

  private val abilityCooldownOverride = SliderSetting(
    "Ability CD Override",
    "Override the lore-derived cooldown in seconds. Set to 0 to use the item lore.",
    0.0,
    0.0,
    120.0,
    step = 1.0,
  )

  private val corpseHighlight = CheckboxSetting(
    "Corpse Highlight",
    "Highlight corpses in Glacite Mineshafts.",
    true
  )

  private val hideOpenedCorpses = CheckboxSetting(
    "Hide Opened Corpses",
    "Hide corpses after you interact with them if you had the right key.",
    true
  )

  private val lapisCorpseColor = ColorSetting(
    "Lapis Color",
    "Render color for lapis corpses.",
    0xFF5555FF.toInt()
  )

  private val tungstenCorpseColor = ColorSetting(
    "Tungsten Color",
    "Render color for tungsten corpses.",
    0xFFAAAAAA.toInt()
  )

  private val umberCorpseColor = ColorSetting(
    "Umber Color",
    "Render color for umber corpses.",
    0xFFFFAA00.toInt()
  )

  private val vanguardCorpseColor = ColorSetting(
    "Vanguard Color",
    "Render color for vanguard corpses.",
    0xFFFF55FF.toInt()
  )

  private val ghostVision = CheckboxSetting(
    "Ghost Vision",
    "Highlight ghosts in the Dwarven Mines.",
    true
  )

  private val ghostColor = ColorSetting(
    "Ghost Color",
    "Render color for ghost ESP.",
    0xFF00C8C8.toInt()
  )

  private val scathaMining = CheckboxSetting(
    "Scatha Mining",
    "Enable worm and scatha alerts plus cooldown tracking.",
    true
  )

  private val scathaSpawnAlert = CheckboxSetting(
    "Scatha Spawn Alert",
    "Alert when a worm or scatha spawns close to you.",
    true
  )

  private val scathaCooldownAlert = CheckboxSetting(
    "Scatha CD Alert",
    "Alert when the worm spawn cooldown ends.",
    true
  )

  private val endNodeHighlight = CheckboxSetting(
    "End Node Highlight",
    "Highlight end nodes when their witch particle packet appears.",
    true
  )

  private val endNodeColor = ColorSetting(
    "End Node Color",
    "Render color for ender nodes.",
    0xFF00FF00.toInt()
  )

  private val templeSkip = CheckboxSetting(
    "Temple Skip",
    "Highlight the Jungle Temple pearl skip spot once the entrance guardian is found.",
    true
  )

  private val templeSkipColor = ColorSetting(
    "Temple Color",
    "Render color for the temple skip markers.",
    0xFF7F00FF.toInt()
  )

  private val gemstoneDesyncFix = CheckboxSetting(
    "Gemstone Desync Fix",
    "Force neighboring gemstone panes to update after a gemstone breaks.",
    true
  )

  private val breakResetFix = CheckboxSetting(
    "Break Reset Fix",
    "Keep the held mining stack synced so inventory updates do not reset block breaking progress.",
    true
  )

  private val shaftAnnounce = CheckboxSetting(
    "Shaft Announce",
    "Announce the mineshaft id and corpse list after entering a Glacite Mineshaft.",
    true
  )

  private val shaftMessage = TextSetting(
    "Shaft Message",
    "Message template. Supported placeholders: {id}, {corpses}.",
    "/pc !ptme Entered Mineshaft: {id}. Corpses: {corpses}."
  )

  private val tooltipStripRegex = Regex("\u00A7[0-9A-FK-ORa-fk-or]")
  private val shaftIdRegex = Regex("""\b[A-Z0-9]{4}_[A-Z0-9]\b""")

  private val openedCorpseIds = mutableSetOf<Int>()
  private val seenWormIds = mutableSetOf<UUID>()
  private val endNodeHighlights = LinkedHashMap<BlockPos, Long>()

  private var currentArea: String? = null
  private var currentTool = ToolData()
  private var abilityCooldownTicks = 0
  private var scathaCooldownTicks = 0
  private var templeSkipSpot: BlockPos? = null
  private var enteringMineshaft = false
  private var shaftAnnounceTicks = -1

  init {
    addSetting(
      enabled,
      abilityAlert,
      abilityCooldownOverride,
      corpseHighlight,
      hideOpenedCorpses,
      lapisCorpseColor,
      tungstenCorpseColor,
      umberCorpseColor,
      vanguardCorpseColor,
      ghostVision,
      ghostColor,
      scathaMining,
      scathaSpawnAlert,
      scathaCooldownAlert,
      endNodeHighlight,
      endNodeColor,
      templeSkip,
      templeSkipColor,
      gemstoneDesyncFix,
      breakResetFix,
      shaftAnnounce,
      shaftMessage,
    )
    EventBus.register(this)
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
    val player = mc.player
    val level = mc.level

    if (player == null || level == null) {
      currentArea = null
      currentTool = ToolData()
      abilityCooldownTicks = 0
      scathaCooldownTicks = 0
      seenWormIds.clear()
      return
    }

    val previousArea = currentArea
    currentArea = resolveCurrentArea()
    if (previousArea != currentArea) {
      handleAreaTransition(previousArea, currentArea)
    }

    if (!enabled.value) {
      return
    }

    updateHeldMiningTool(player)
    tickAbilityAlert(player)
    tickScathaAlerts(player)
    updateTempleSkipSpot(level)
    pruneEndNodes(level)
    tickShaftAnnounce()
  }

  @SubscribeEvent
  fun onChat(event: ChatEvent.Receive) {
    if (!enabled.value) return

    val raw = event.message ?: return
    val message = stripFormatting(raw)

    if (abilityAlert.value && isPickaxeAbilityUseMessage(message)) {
      armAbilityCooldownFromChat()
    }

    if (scathaMining.value && message == "You hear the sound of something approaching...") {
      scathaCooldownTicks = 620
    }

    if (shaftAnnounce.value && currentArea == AREA_DWARVEN_MINES && message == "Sending to Mineshaft...") {
      enteringMineshaft = true
      shaftAnnounceTicks = -1
    }
  }

  @SubscribeEvent
  fun onIncomingPacket(event: PacketEvent.Incoming) {
    if (!enabled.value) return

    when (val packet = event.packet) {
      is ClientboundLevelParticlesPacket -> {
        if (endNodeHighlight.value) {
          handleEndNodeParticle(packet)
        }
      }

      is ClientboundContainerSetSlotPacket -> {
        if (breakResetFix.value) {
          handleInventorySlotUpdate(packet)
        }
      }

      is ClientboundContainerSetContentPacket -> {
        if (breakResetFix.value) {
          handleInventoryContentUpdate(packet)
        }
      }
    }
  }

  @SubscribeEvent
  fun onOutgoingPacket(event: PacketEvent.Outgoing) {
    if (!enabled.value || !corpseHighlight.value || !hideOpenedCorpses.value) return
    if (currentArea != AREA_MINESHAFT) return
    if (event.packet !is ServerboundInteractPacket) return

    val stand = (mc.hitResult as? EntityHitResult)?.entity as? ArmorStand ?: return
    val corpseType = detectCorpseType(stand)
    if (corpseType == CorpseType.NONE || !hasKeyForCorpse(corpseType)) return
    openedCorpseIds += stand.id
  }

  @SubscribeEvent
  fun onBlockChange(event: BlockChangeEvent) {
    if (!enabled.value || !gemstoneDesyncFix.value) return
    if (!GEMSTONE_FIX_AREAS.contains(currentArea)) return
    if (!event.newBlock.isAir || !isGemstoneGlass(event.oldBlock)) return

    val level = mc.level ?: return
    event.newBlock.updateNeighbourShapes(level, event.pos, Block.UPDATE_ALL)
    event.newBlock.updateIndirectNeighbourShapes(level, event.pos, Block.UPDATE_ALL)

    for (direction in Direction.values()) {
      val neighborPos = event.pos.relative(direction)
      val neighborState = level.getBlockState(neighborPos)
      if (!isGemstoneGlass(neighborState)) continue
      level.sendBlockUpdated(neighborPos, neighborState, neighborState, Block.UPDATE_ALL)
    }
  }

  @SubscribeEvent
  fun onRender(event: WorldRenderEvent.Last) {
    if (!enabled.value) return

    val level = mc.level ?: return

    if (corpseHighlight.value && currentArea == AREA_MINESHAFT) {
      renderCorpseHighlights(level, event)
    }

    if (ghostVision.value && currentArea == AREA_DWARVEN_MINES) {
      renderGhostHighlights(level, event)
    }

    if (endNodeHighlight.value && currentArea == AREA_THE_END) {
      renderEndNodes(event)
    }

    if (templeSkip.value && currentArea == AREA_CRYSTAL_HOLLOWS) {
      renderTempleSkip(event)
    }
  }

  private fun updateHeldMiningTool(player: LocalPlayer) {
    val stack = player.mainHandItem
    currentTool =
      if (isMiningTool(stack)) {
        ToolData(stack.copy(), parseMiningAbility(stack))
      } else {
        ToolData()
      }
  }

  private fun tickAbilityAlert(player: LocalPlayer) {
    if (!abilityAlert.value || !currentTool.isValid()) {
      abilityCooldownTicks = 0
      return
    }

    val widget = findAbilityWidgetLine(currentTool.ability)
    if (widget != null) {
      val status = widget.substringAfter(':', "").trim()
      if (abilityCooldownTicks > 1 && status.equals("Available", ignoreCase = true)) {
        abilityCooldownTicks = 1
      }
      if (abilityCooldownTicks == 0) {
        val seconds = parseDurationSeconds(status)
        if (seconds > 6) {
          abilityCooldownTicks = seconds * 20
        }
      }
    }

    if (abilityCooldownTicks > 0) {
      abilityCooldownTicks--
      if (abilityCooldownTicks == 0) {
        notifyOverlay(
          title = currentTool.ability.uppercase(Locale.ROOT),
          description = "Pickaxe ability ready.",
          sound = SoundEvents.NOTE_BLOCK_PLING.value(),
          pitch = 1.0f,
          player = player
        )
      }
    }
  }

  private fun armAbilityCooldownFromChat() {
    if (!currentTool.isValid()) return
    if (findAbilityWidgetLine(currentTool.ability) != null) return

    val overrideSeconds = abilityCooldownOverride.value.toInt()
    abilityCooldownTicks =
      when {
        overrideSeconds > 0 -> overrideSeconds * 20
        else -> extractLoreCooldownTicks(currentTool.tool)
      }
  }

  private fun tickScathaAlerts(player: LocalPlayer) {
    if (!scathaMining.value) {
      scathaCooldownTicks = 0
      seenWormIds.clear()
      return
    }

    if (currentArea != AREA_CRYSTAL_HOLLOWS) {
      seenWormIds.clear()
      return
    }

    val level = mc.level ?: return

    if (scathaSpawnAlert.value) {
      for (entity in level.entitiesForRendering()) {
        val type = detectWormType(entity) ?: continue
        if (!isWithinWormAlertRadius(player, entity)) continue
        if (!seenWormIds.add(entity.uuid)) continue
        when (type) {
          "scatha" -> notifyOverlay("Scatha", "Scatha spawned nearby.", SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, player)
          "worm" -> notifyOverlay("Worm", "Worm spawned nearby.", SoundEvents.NOTE_BLOCK_BASS.value(), 0.7f, player)
        }
      }
    }

    seenWormIds.removeIf { id -> level.entitiesForRendering().none { it.uuid == id && it.isAlive } }

    if (scathaCooldownTicks > 0) {
      scathaCooldownTicks--
      if (scathaCooldownTicks == 0 && scathaCooldownAlert.value) {
        notifyOverlay(
          title = "Cooldown Ended",
          description = "Worm spawn cooldown ended.",
          sound = SoundEvents.NOTE_BLOCK_HARP.value(),
          pitch = 0.9f,
          player = player
        )
        ChatUtils.sendMessage("Worm spawn cooldown ended.")
      }
    }
  }

  private fun handleEndNodeParticle(packet: ClientboundLevelParticlesPacket) {
    if (currentArea != AREA_THE_END) return
    if (packet.particle.type != ParticleTypes.WITCH) return
    if (!packet.isOverrideLimiter() || !packet.alwaysShow()) return
    if (packet.count != 2 || packet.maxSpeed != 0f) return

    val offsetMatches =
      packet.xDist == 0.25f || packet.yDist == 0.25f || packet.zDist == 0.25f
    if (!offsetMatches) return

    val level = mc.level ?: return
    val origin = BlockPos.containing(packet.x, packet.y, packet.z)
    val now = System.currentTimeMillis()
    for (direction in Direction.values()) {
      val candidate = origin.relative(direction)
      if (level.getBlockState(candidate).block == Blocks.PURPLE_TERRACOTTA) {
        endNodeHighlights[candidate.immutable()] = now
        break
      }
    }
  }

  private fun handleInventorySlotUpdate(packet: ClientboundContainerSetSlotPacket) {
    if (packet.containerId != 0) return
    val player = mc.player ?: return
    val selectedHotbarSlot = player.inventory.selectedSlot
    val expectedSlot = 36 + selectedHotbarSlot
    if (packet.slot != expectedSlot) return

    val gameMode = mc.gameMode as? MultiPlayerGameModeAccessor ?: return
    gameMode.setDestroyingItemCobalt(packet.item.copy())
  }

  private fun handleInventoryContentUpdate(packet: ClientboundContainerSetContentPacket) {
    if (packet.containerId != 0) return
    val player = mc.player ?: return
    val expectedSlot = 36 + player.inventory.selectedSlot
    val selectedStack = packet.items.getOrNull(expectedSlot) ?: return

    val gameMode = mc.gameMode as? MultiPlayerGameModeAccessor ?: return
    gameMode.setDestroyingItemCobalt(selectedStack.copy())
  }

  private fun renderCorpseHighlights(level: net.minecraft.client.multiplayer.ClientLevel, event: WorldRenderEvent.Last) {
    for (entity in level.entitiesForRendering()) {
      val stand = entity as? ArmorStand ?: continue
      if (stand.isInvisible || !stand.isAlive) continue
      if (hideOpenedCorpses.value && stand.id in openedCorpseIds) continue

      val corpseType = detectCorpseType(stand)
      if (corpseType == CorpseType.NONE) continue

      val outline = corpseColor(corpseType)
      val fill = withAlpha(outline, 96)
      val box = stand.boundingBox.inflate(0.25, 0.0, 0.25)
      Render3D.drawStyledBox(event.context, box, outline, fill, esp = true, lineWidth = 2.2f)
    }
  }

  private fun renderGhostHighlights(level: net.minecraft.client.multiplayer.ClientLevel, event: WorldRenderEvent.Last) {
    val outline = color(ghostColor.value)
    val fill = withAlpha(outline, 90)

    for (entity in level.entitiesForRendering()) {
      val creeper = entity as? Creeper ?: continue
      if (!creeper.isAlive || creeper.y >= 100.0) continue
      val box = creeper.boundingBox.inflate(0.1)
      Render3D.drawStyledBox(event.context, box, outline, fill, esp = true, lineWidth = 2.2f)
    }
  }

  private fun renderEndNodes(event: WorldRenderEvent.Last) {
    val level = mc.level ?: return
    val outline = color(endNodeColor.value)
    val fill = withAlpha(outline, 100)

    for (pos in endNodeHighlights.keys.toList()) {
      if (level.getBlockState(pos).block != Blocks.PURPLE_TERRACOTTA) {
        endNodeHighlights.remove(pos)
        continue
      }

      val box = blockBox(pos, 0.02)
      Render3D.drawStyledBox(event.context, box, outline, fill, esp = true, lineWidth = 2.2f)
    }
  }

  private fun renderTempleSkip(event: WorldRenderEvent.Last) {
    val spot = templeSkipSpot ?: return
    val outline = color(templeSkipColor.value)
    val fill = withAlpha(outline, 70)

    Render3D.drawStyledBox(event.context, blockBox(spot, 0.03), outline, fill, esp = true, lineWidth = 2.2f)

    val standPos = spot.below(8)
    Render3D.drawStyledBox(event.context, blockBox(standPos, 0.03), outline, fill, esp = true, lineWidth = 2.2f)

    val start = Vec3(spot.x + 0.5, spot.y + 0.5, spot.z + 0.5)
    val end = Vec3(standPos.x + 0.5, standPos.y + 0.5, standPos.z + 0.5)
    Render3D.drawLine(event.context, start, end, outline, esp = true, thickness = 1.8f)
  }

  private fun updateTempleSkipSpot(level: net.minecraft.client.multiplayer.ClientLevel) {
    if (!templeSkip.value || currentArea != AREA_CRYSTAL_HOLLOWS || templeSkipSpot != null) return

    for (entity in level.entitiesForRendering()) {
      val normalizedName = normalize(entity.name.string)
      if (normalizedName != "kalhuiki door guardian") continue

      val ground = findGround(entity.blockPosition(), level, 4) ?: continue
      if (level.getBlockState(ground).block == Blocks.STONE_BRICKS) {
        templeSkipSpot = ground.offset(20, -45, -35).immutable()
        break
      }
    }
  }

  private fun pruneEndNodes(level: net.minecraft.client.multiplayer.ClientLevel) {
    if (currentArea != AREA_THE_END) {
      endNodeHighlights.clear()
      return
    }

    val now = System.currentTimeMillis()
    endNodeHighlights.entries.removeIf { (pos, lastSeen) ->
      now - lastSeen > 180_000L || level.getBlockState(pos).block != Blocks.PURPLE_TERRACOTTA
    }
  }

  private fun tickShaftAnnounce() {
    if (!shaftAnnounce.value) {
      enteringMineshaft = false
      shaftAnnounceTicks = -1
      return
    }

    if (enteringMineshaft && currentArea == AREA_MINESHAFT && shaftAnnounceTicks < 0) {
      shaftAnnounceTicks = 120
    }

    if (shaftAnnounceTicks > 0) {
      shaftAnnounceTicks--
    }

    if (shaftAnnounceTicks == 0 && currentArea == AREA_MINESHAFT) {
      announceMineshaft()
      enteringMineshaft = false
      shaftAnnounceTicks = -1
    }
  }

  private fun announceMineshaft() {
    val corpses =
      readTabListLines()
        .mapNotNull { line ->
          when {
            line.endsWith(": LOOTED", ignoreCase = true) || line.endsWith(": NOT LOOTED", ignoreCase = true) ->
              line.substringBefore(':').trim().takeIf { it.isNotEmpty() }
            else -> null
          }
        }
        .groupingBy { it }
        .eachCount()

    val corpseSummary =
      corpses.entries.joinToString(", ") { (name, count) ->
        "${count}x $name"
      }.ifEmpty { "None" }

    val shaftId = readScoreboardLines().firstNotNullOfOrNull { line ->
      shaftIdRegex.find(line)?.value
    } ?: "Unknown ID"

    sendServerMessage(
      shaftMessage.value
        .replace("{id}", shaftId)
        .replace("{corpses}", corpseSummary)
    )
  }

  private fun handleAreaTransition(previous: String?, next: String?) {
    if (previous == AREA_MINESHAFT && next != AREA_MINESHAFT) {
      openedCorpseIds.clear()
    }

    if (next != AREA_CRYSTAL_HOLLOWS) {
      seenWormIds.clear()
      templeSkipSpot = null
    }

    if (next != AREA_THE_END) {
      endNodeHighlights.clear()
    }

    if (enteringMineshaft && next == AREA_MINESHAFT) {
      shaftAnnounceTicks = 120
    }

    if (previous == AREA_DWARVEN_MINES && next != AREA_MINESHAFT && next != AREA_DWARVEN_MINES) {
      enteringMineshaft = false
      shaftAnnounceTicks = -1
    }
  }

  private fun resolveCurrentArea(): String? {
    val searchableLines = readTabListLines() + readScoreboardLines()
    for (line in searchableLines) {
      for (area in AREA_NAMES) {
        if (line.contains(area, ignoreCase = true)) {
          return area
        }
      }
    }
    return null
  }

  private fun readTabListLines(): List<String> {
    val lines = mutableListOf<String>()
    val overlay = mc.gui.tabList as? TabOverlayAccessor

    overlay?.header?.let { component ->
      appendSanitizedComponentLines(lines, component)
    }
    overlay?.footer?.let { component ->
      appendSanitizedComponentLines(lines, component)
    }

    mc.connection?.listedOnlinePlayers?.forEach { info ->
      val text = info.tabListDisplayName?.string ?: info.profile.name
      appendSanitizedLines(lines, text)
    }

    return lines
  }

  private fun readScoreboardLines(): List<String> {
    val level = mc.level ?: return emptyList()
    val scoreboard = level.scoreboard
    val objective = scoreboard.getDisplayObjective(net.minecraft.world.scores.DisplaySlot.SIDEBAR) ?: return emptyList()

    return scoreboard.listPlayerScores(objective)
      .mapNotNull { score ->
        val ownerName = score.owner()
        val team = scoreboard.getPlayersTeam(ownerName)
        val raw = if (team != null) team.playerPrefix.string + ownerName + team.playerSuffix.string else ownerName
        stripFormatting(raw).takeIf { it.isNotBlank() }
      }
  }

  private fun appendSanitizedComponentLines(target: MutableList<String>, component: Component) {
    appendSanitizedLines(target, component.string)
  }

  private fun appendSanitizedLines(target: MutableList<String>, raw: String) {
    raw.lineSequence()
      .map(::stripFormatting)
      .filter { it.isNotBlank() }
      .forEach(target::add)
  }

  private fun isMiningTool(stack: ItemStack): Boolean {
    if (stack.isEmpty) return false

    val loreLines = stack.getLoreLines().map { stripFormatting(it.string) }
    if (loreLines.isEmpty()) return false
    if (loreLines.none { it.contains("Mining Speed", ignoreCase = true) }) return false

    val lastLine = loreLines.last().uppercase(Locale.ROOT)
    return lastLine.contains(" DRILL") || lastLine.contains(" PICKAXE") || lastLine.contains(" GAUNTLET")
  }

  private fun parseMiningAbility(stack: ItemStack): String {
    for (line in stack.getLoreLines()) {
      val stripped = stripFormatting(line.string)
      if (!stripped.contains("RIGHT CLICK", ignoreCase = true)) continue
      if (!stripped.contains(':')) continue

      val ability =
        stripped.substringAfter(':')
          .replace("RIGHT CLICK", "", ignoreCase = true)
          .trim()
      if (ability.isNotEmpty()) {
        return ability
      }
    }
    return ""
  }

  private fun extractLoreCooldownTicks(stack: ItemStack): Int {
    for (line in stack.getLoreLines().asReversed()) {
      val stripped = stripFormatting(line.string)
      if (!stripped.startsWith("Cooldown:", ignoreCase = true)) continue

      val seconds = parseDurationSeconds(stripped.substringAfter(':').trim())
      if (seconds > 0) {
        return seconds * 20
      }
    }
    return 0
  }

  private fun findAbilityWidgetLine(ability: String): String? {
    if (ability.isBlank()) return null
    return readTabListLines().firstOrNull { line ->
      line.contains(ability, ignoreCase = true) && line.contains(':')
    }
  }

  private fun isPickaxeAbilityUseMessage(message: String): Boolean {
    return message.startsWith("You used your ") && message.endsWith(" Pickaxe Ability!")
  }

  private fun parseDurationSeconds(value: String): Int {
    val stripped = value.trim().removeSuffix("s").trim()
    return stripped.toIntOrNull() ?: stripped.substringBefore(' ').toIntOrNull() ?: 0
  }

  private fun detectCorpseType(stand: ArmorStand): CorpseType {
    val helmet = stand.getItemBySlot(EquipmentSlot.HEAD)
    if (helmet.isEmpty) return CorpseType.NONE

    val name = stripFormatting(helmet.hoverName.string)
    return CorpseType.entries.firstOrNull { it != CorpseType.NONE && it.helmetName.equals(name, ignoreCase = true) }
      ?: CorpseType.NONE
  }

  private fun hasKeyForCorpse(type: CorpseType): Boolean {
    val player = mc.player ?: return false
    val keyId = type.keyId ?: return true
    val inventory = player.inventory
    for (index in 0 until 36) {
      val id = inventory.getItem(index).getSkyblockIdSafe()
      if (id == keyId) {
        return true
      }
    }
    return false
  }

  private fun corpseColor(type: CorpseType): Color =
    when (type) {
      CorpseType.LAPIS -> color(lapisCorpseColor.value)
      CorpseType.TUNGSTEN -> color(tungstenCorpseColor.value)
      CorpseType.UMBER -> color(umberCorpseColor.value)
      CorpseType.VANGUARD -> color(vanguardCorpseColor.value)
      CorpseType.NONE -> Color.WHITE
    }

  private fun detectWormType(entity: Entity): String? {
    val raw = stripFormatting(entity.name.string)
    val name = raw.replace(Regex("\\s+[0-9.,]+(?:/[0-9.,]+)?\\s*[❤♥]?$"), "").trim()
    return when {
      name.startsWith("[Lv10] Scatha ", ignoreCase = true) -> "scatha"
      name.startsWith("[Lv5] Worm ", ignoreCase = true) -> "worm"
      else -> null
    }
  }

  private fun isWithinWormAlertRadius(player: LocalPlayer, entity: Entity): Boolean {
    val playerPos = player.blockPosition()
    val wormPos = entity.blockPosition()
    return kotlin.math.abs(wormPos.y - playerPos.y) <= 4 &&
      (kotlin.math.abs(wormPos.x - playerPos.x) <= 2 || kotlin.math.abs(wormPos.z - playerPos.z) <= 2)
  }

  private fun findGround(origin: BlockPos, level: net.minecraft.client.multiplayer.ClientLevel, maxDepth: Int): BlockPos? {
    for (offset in 0..maxDepth) {
      val candidate = origin.below(offset)
      if (!level.getBlockState(candidate).isAir) {
        return candidate.immutable()
      }
    }
    return null
  }

  private fun sendServerMessage(message: String) {
    val trimmed = message.trim()
    val player = mc.player ?: return
    if (trimmed.isEmpty()) return

    if (trimmed.startsWith("/")) {
      player.connection.sendCommand(trimmed.removePrefix("/"))
    } else {
      player.connection.sendChat(trimmed)
    }
  }

  private fun notifyOverlay(
    title: String,
    description: String,
    sound: SoundEvent,
    pitch: Float,
    player: LocalPlayer,
  ) {
    NotificationManager.queue(title, description, 3000L)
    player.displayClientMessage(Component.literal(title), true)
    mc.level?.playLocalSound(player.x, player.y, player.z, sound, SoundSource.PLAYERS, 1.0f, pitch, false)
  }

  private fun color(argb: Int): Color {
    return Color(argb, true)
  }

  private fun withAlpha(color: Color, alpha: Int): Color {
    return Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))
  }

  private fun blockBox(pos: BlockPos, inflate: Double): AABB {
    return AABB(
      pos.x.toDouble() - inflate,
      pos.y.toDouble() - inflate,
      pos.z.toDouble() - inflate,
      pos.x + 1.0 + inflate,
      pos.y + 1.0 + inflate,
      pos.z + 1.0 + inflate,
    )
  }

  private fun normalize(text: String): String {
    return stripFormatting(text).lowercase(Locale.ROOT)
  }

  private fun stripFormatting(text: String): String {
    return (ChatFormatting.stripFormatting(text) ?: tooltipStripRegex.replace(text, "")).trim()
  }

  private fun isGemstoneGlass(state: net.minecraft.world.level.block.state.BlockState): Boolean {
    val block = state.block
    return block is StainedGlassBlock || block is StainedGlassPaneBlock
  }

  private fun ItemStack.getSkyblockIdSafe(): String {
    return runCatching {
      this.getSkyblockId()
    }.getOrDefault("")
  }

  private val GEMSTONE_FIX_AREAS = setOf(
    AREA_DWARVEN_MINES,
    AREA_CRYSTAL_HOLLOWS,
    AREA_MINESHAFT,
    "Crimson Isle",
    "The Rift",
  )
}
