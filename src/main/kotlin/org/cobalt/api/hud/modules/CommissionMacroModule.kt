package org.cobalt.api.hud.modules

import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.ChatEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.InfoSetting
import org.cobalt.api.module.setting.impl.InfoType
import org.cobalt.api.module.setting.impl.ModeSetting
import org.cobalt.api.module.setting.impl.TextSetting
import org.cobalt.api.pathfinder.jni.NativePathfinder
import org.cobalt.api.pathfinder.jni.PathStatus
import org.cobalt.api.pathfinder.minecraft.MinecraftPathingRules
import org.cobalt.api.util.AngleUtils
import org.cobalt.api.util.ChatUtils
import org.cobalt.api.util.InventoryUtils
import org.cobalt.api.util.getLoreLines
import org.cobalt.api.util.player.MovementManager
import org.cobalt.internal.combat.CombatPatrolModule
import org.cobalt.internal.combat.CombatMacroModule
import org.cobalt.internal.etherwarp.EtherwarpLogic
import org.cobalt.internal.mining.MiningBlockRegistry
import org.cobalt.internal.mining.MiningMacroModule
import org.cobalt.internal.mining.MiningProfitTracker
import org.cobalt.internal.mining.RoutesModule
import org.cobalt.internal.pathfinding.PathfindingModule
import org.cobalt.internal.routes.RoutePickerSetting
import org.cobalt.internal.routes.RouteStore
import org.cobalt.internal.routes.RouteType
import org.cobalt.internal.routes.SavedRoute
import java.util.Locale
import kotlin.collections.orEmpty
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object CommissionMacroModule : Module("Commission Macro") {

  private val mc = Minecraft.getInstance()

  // ---- Settings ----

  private val enabled = CheckboxSetting(
      "Enabled",
      "Complete commissions in a loop. Mode is auto-detected from AOTV and Royal Pigeon availability.",
      false
  )

  private val info = InfoSetting(
      "How It Works",
      "If Royal Pigeon is available it is used for reading and claiming commissions. Otherwise the dropdown chooses whether emissary access starts from /warp forge or /warp dwarves. Mining commissions always /warp forge before route start, and route warp points are walked if no AOTV/Etherwarp is available.",
      InfoType.INFO
  )

  private val routeWarpSetting = ModeSetting(
      "Emissary Warp",
      "Warp used before heading to the emissary when Royal Pigeon is not available.",
      0,
      arrayOf("Warp Forge", "Warp Dwarves")
  )

  private val statusText  = TextSetting("Status", "Current macro state.", "Idle")
  private val modeText    = TextSetting("Mode", "Auto-detected commission mode.", "Unknown")
  private val commText    = TextSetting("Commission", "Detected commission.", "None")
  private val areaText    = TextSetting("Area", "Detected map area.", "Unknown")
  private val elizaTargetPos = BlockPos(-37, 201, -130)
  private val royalRoutePicker = RoutePickerSetting(
      "Royal Mines Route",
      "Route used for Royal Mines commissions.",
      RouteType.COMMISSION,
      "commission:royal",
  )
  private val cliffsideRoutePicker = RoutePickerSetting(
      "Cliffside Veins Route",
      "Route used for Cliffside Veins commissions.",
      RouteType.COMMISSION,
      "commission:cliffside",
  )
  private val lavaRoutePicker = RoutePickerSetting(
      "Lava Springs Route",
      "Route used for Lava Springs commissions.",
      RouteType.COMMISSION,
      "commission:lava",
  )
  private val rampRoutePicker = RoutePickerSetting(
      "Rampart's Quarry Route",
      "Route used for Rampart's Quarry commissions.",
      RouteType.COMMISSION,
      "commission:ramp",
  )
  private val upperRoutePicker = RoutePickerSetting(
      "Upper Mines Route",
      "Route used for Upper Mines commissions.",
      RouteType.COMMISSION,
      "commission:upper",
  )
  private val glacitePatrolRoutePicker = RoutePickerSetting(
      "Glacite Patrol Route",
      "PATROL route used for Glacite Walker commissions.",
      RouteType.PATROL,
      "commission:glacite_patrol",
  )

  // ---- Commission model ----

  private enum class CommissionType { MINING, COMBAT }

  private data class Commission(
    val label:   String,
    val type:    CommissionType,
    val target:  String,   // primary block-type name (mining) or mob name (combat)
    val mineTypes: String, // comma-separated block types to mine (may include variants)
    val current: Int,
    val max:     Int,
  ) {
    val isComplete get() = max > 0 && current >= max
  }

  data class CommissionHudRow(
    val label: String,
    val detail: String,
    val isTargeted: Boolean,
    val percent: Int,
  )

  private data class ParsedCommissionSelection(
    val commissions: List<Commission>,
    val selected: Commission?,
  )

  private enum class CommissionMode(
    val label: String,
    val usesWarps: Boolean,
    val usesPigeon: Boolean,
  ) {
    WALK_ONLY("Walk Only", false, false),
    WALK_PIGEON("Walk + Pigeon", false, true),
    AOTV_ELIZA("AOTV + Emissary", true, false),
    AOTV_PIGEON("AOTV + Pigeon", true, true),
  }

  enum class CommissionRouteZone(
    val label: String,
    val areaLabel: String,
  ) {
    ROYAL("Royal", "Royal Mines"),
    CLIFFSIDE("Cliffside", "Cliffside Veins"),
    LAVA("Lava", "Lava Springs"),
    RAMP("Ramp", "Rampart's Quarry"),
    UPPER("Upper", "Upper Mines"),
  }

  private data class EmissaryTarget(
      val walkPos: BlockPos,
      val interactionEntity: Entity?,
      val label: String,
      val walkNodes: List<BlockPos> = emptyList(),
  )

  // ---- State machine ----

  private enum class State {
    IDLE,
    OPEN_PIGEON,        // select + right-click Royal Pigeon item; wait for GUI
    WARP_TO_DWARVES,    // /warp dwarves sent; waiting for teleport
    WARP_TO_FORGE_EMISSARY, // /warp forge sent; waiting before following the forge emissary path
    WALK_TO_EMISSARY,   // pathfind to the selected emissary target
    OPEN_EMISSARY,      // face + right-click the selected emissary; wait for GUI
    READ_GUI,           // GUI open: scrape book lore for commissions
    CLAIM_GUI,          // GUI open: click the claim button on a complete commission
    WARP_TO_ROUTE_START, // selected route warp sent; waiting for teleport before route start
    MINING,             // MiningMacroModule (or CombatMacroModule) is active
    RETURN_TO_DWARVES,  // commission done; warp back to Eliza to claim
  }

  private enum class RouteWarpDestination(
    val command: String,
    val statusLabel: String,
    val areaLabel: String,
  ) {
    FORGE("warp forge", "forge", "Forge"),
    DWARVES("warp dwarves", "dwarves", "Dwarven Mines"),
  }

  private var state     = State.IDLE
  private var stateTick = 0L

  private var currentMode:       CommissionMode = CommissionMode.WALK_ONLY
  private var commission:        Commission? = null
  private var activeCommissions: List<Commission> = emptyList()
  private var activeMiningRouteName: String? = null
  private var pendingUseRelease  = false
  private var pendingSlotRestore = -1
  private var pigeonCooldownTicks = 0
  private var pendingForgeWarpPigeonLook = false
  private var forgeWarpPigeonTargetYaw: Float? = null
  private var forgeWarpPigeonLookStableTicks = 0
  private var openAttempts       = 0
  private var claimAttempts      = 0
  private var readAttempts       = 0
  private var lastEmissaryPathTarget: BlockPos? = null
  private var activeEmissaryWalkNodeIndex = -1
  private val forgeEmissaryWalkNodes = listOf(
      BlockPos(8, 148, -66),
      BlockPos(11, 148, -61),
      BlockPos(11, 148, -51),
      BlockPos(4, 146, -43),
      BlockPos(10, 144, -15),
      BlockPos(41, 135, 17),
      BlockPos(42, 134, 21),
  )
  private val greatIceWallGatePos = BlockPos(0, 128, 150)
  private val goblinBurrowsCenterPos = BlockPos(-100, 163, 150)

  // ---- Known areas and ore->zone mapping ----

  private val AREA_NAMES = listOf(
    "Dwarven Mines", "Crystal Hollows", "Glacite Tunnels",
    "Deep Caverns", "Spider's Den", "The End", "Crimson Isle"
  )

  // Maps ore keyword (in lore) to primary type label used for zone lookup
  private val ORE_TO_TYPE = linkedMapOf(
    "titanium"    to "Titanium",
    "mithril"     to "Mithril (Gray)",
    "ruby"        to "Ruby Gemstone",
    "amber"       to "Amber Gemstone",
    "amethyst"    to "Amethyst Gemstone",
    "jade"        to "Jade Gemstone",
    "sapphire"    to "Sapphire Gemstone",
    "opal"        to "Opal Gemstone",
    "topaz"       to "Topaz Gemstone",
    "jasper"      to "Jasper Gemstone",
    "onyx"        to "Onyx Gemstone",
    "aquamarine"  to "Aquamarine Gemstone",
    "citrine"     to "Citrine Gemstone",
    "peridot"     to "Peridot Gemstone",
    "umber"       to "Umber",
    "tungsten"    to "Tungsten",
    "glacite"     to "Glacite",
    "sulphur"     to "Sulphur",
    "coal"        to "Pure Coal",
    "iron"        to "Pure Iron",
    "gold"        to "Pure Gold",
    "lapis"       to "Pure Lapis",
    "redstone"    to "Pure Redstone",
    "emerald"     to "Pure Emerald",
    "diamond"     to "Pure Diamond",
    "quartz"      to "Pure Quartz",
  )

  // All block types to mine for a given ore keyword (expands mithril to all variants)
  private val ORE_TO_MINE_TYPES = linkedMapOf(
    "mithril"     to "Mithril (Gray), Mithril (Dark), Mithril (Hot)",
    "titanium"    to "Titanium",
    "ruby"        to "Ruby Gemstone",
    "amber"       to "Amber Gemstone",
    "amethyst"    to "Amethyst Gemstone",
    "jade"        to "Jade Gemstone",
    "sapphire"    to "Sapphire Gemstone",
    "opal"        to "Opal Gemstone",
    "topaz"       to "Topaz Gemstone",
    "jasper"      to "Jasper Gemstone",
    "onyx"        to "Onyx Gemstone",
    "aquamarine"  to "Aquamarine Gemstone",
    "citrine"     to "Citrine Gemstone",
    "peridot"     to "Peridot Gemstone",
    "umber"       to "Umber",
    "tungsten"    to "Tungsten",
    "glacite"     to "Glacite",
    "sulphur"     to "Sulphur",
    "coal"        to "Pure Coal",
    "iron"        to "Pure Iron",
    "gold"        to "Pure Gold",
    "lapis"       to "Pure Lapis",
    "redstone"    to "Pure Redstone",
    "emerald"     to "Pure Emerald",
    "diamond"     to "Pure Diamond",
    "quartz"      to "Pure Quartz",
  )

  private val ORE_TO_ZONE = mapOf(
    "Titanium"            to "Dwarven Mines",
    "Mithril (Gray)"      to "Dwarven Mines",
    "Pure Coal"           to "Dwarven Mines",
    "Pure Iron"           to "Dwarven Mines",
    "Pure Gold"           to "Dwarven Mines",
    "Pure Lapis"          to "Dwarven Mines",
    "Pure Redstone"       to "Dwarven Mines",
    "Pure Emerald"        to "Dwarven Mines",
    "Pure Diamond"        to "Dwarven Mines",
    "Pure Quartz"         to "Dwarven Mines",
    "Ruby Gemstone"       to "Crystal Hollows",
    "Amber Gemstone"      to "Crystal Hollows",
    "Amethyst Gemstone"   to "Crystal Hollows",
    "Jade Gemstone"       to "Crystal Hollows",
    "Sapphire Gemstone"   to "Crystal Hollows",
    "Opal Gemstone"       to "Crystal Hollows",
    "Topaz Gemstone"      to "Crystal Hollows",
    "Jasper Gemstone"     to "Crystal Hollows",
    "Umber"               to "Glacite Tunnels",
    "Tungsten"            to "Glacite Tunnels",
    "Glacite"             to "Glacite Tunnels",
    "Sulphur"             to "Glacite Tunnels",
    "Onyx Gemstone"       to "Glacite Tunnels",
    "Aquamarine Gemstone" to "Glacite Tunnels",
    "Citrine Gemstone"    to "Glacite Tunnels",
    "Peridot Gemstone"    to "Glacite Tunnels",
  )

  // Lower index = higher priority
  private val COMMISSION_PRIORITY = listOf(
    "Royal Mines Titanium",
    "Cliffside Veins Titanium",
    "Lava Springs Titanium",
    "Rampart's Quarry Titanium",
    "Upper Mines Titanium",
    "Royal Mines Mithril",
    "Cliffside Veins Mithril",
    "Lava Springs Mithril",
    "Rampart's Quarry Mithril",
    "Upper Mines Mithril",
    "Titanium Miner",
    "Mithril Miner",
    "Glacite Walker Slayer",
    "Goblin Slayer",
    "Goblin Raid",
    "Star Sentry Puncher",
  )

  private val MINING_OBJECTIVE_PREFIXES = listOf("mine ", "collect ")
  private val COMBAT_OBJECTIVE_PREFIXES = listOf("kill ", "slay ", "defeat ", "punch ", "damage ")
  private val COMMISSION_OBJECTIVE_PREFIXES = listOf(
    *MINING_OBJECTIVE_PREFIXES.toTypedArray(),
    *COMBAT_OBJECTIVE_PREFIXES.toTypedArray(),
    "participate ",
    "deposit ",
    "open ",
    "enter ",
    "loot ",
    "obtain ",
    "find ",
  )

  private val COMMISSION_PRIORITY_INDEX =
    COMMISSION_PRIORITY.withIndex().associate { (index, label) -> normalizeComparisonText(label) to index }

  private val MINING_AREA_ALIASES = linkedMapOf(
    "royal mines" to "Royal Mines",
    "cliffside veins" to "Cliffside Veins",
    "lava springs" to "Lava Springs",
    "rampart's quarry" to "Rampart's Quarry",
    "ramparts quarry" to "Rampart's Quarry",
    "upper mines" to "Upper Mines",
  )

  private val COMMISSION_ROUTE_ZONE_ALIASES = linkedMapOf(
    CommissionRouteZone.ROYAL to listOf("royal mines", "royal"),
    CommissionRouteZone.CLIFFSIDE to listOf("cliffside veins", "cliffside"),
    CommissionRouteZone.LAVA to listOf("lava springs", "lava"),
    CommissionRouteZone.RAMP to listOf("rampart's quarry", "ramparts quarry", "ramp"),
    CommissionRouteZone.UPPER to listOf("upper mines", "upper"),
  )
  private val GLACITE_WALKER_NAME_KEYWORDS = listOf("glacite walker", "ice walker")

  // ---- Public HUD ----

  val statusDisplay:      String  get() = statusText.value
  val modeDisplay:        String  get() = modeText.value
  val commissionDisplay:  String  get() = commText.value
  val currentZoneDisplay: String  get() = areaText.value
  val targetedCommissionName: String? get() = commission?.label
  fun getCommissionRows(): List<CommissionHudRow> =
    activeCommissions.map { active ->
      val percent =
        if (active.max > 0) ((active.current.toDouble() / active.max.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        else 0
      CommissionHudRow(
        label      = active.label,
        detail     = "$percent%",
        isTargeted = active == commission,
        percent    = percent,
      )
    }

  val targetZoneDisplay: String get() {
    val c = commission ?: return "Unknown"
    return if (c.type == CommissionType.MINING) ORE_TO_ZONE[c.target] ?: "Unknown" else "Combat Zone"
  }
  val isRunning: Boolean get() = enabled.value

  fun getAvailableRouteInfos(): List<RoutesModule.SavedRouteInfo> = RoutesModule.getSavedRouteInfos()

  fun getRouteZones(): List<CommissionRouteZone> = CommissionRouteZone.values().toList()

  fun getAssignedRouteName(zone: CommissionRouteZone): String? =
    RouteStore.getSlotRoute(slotKeyForZone(zone))

  fun isRouteAssigned(zone: CommissionRouteZone, name: String): Boolean =
    getAssignedRouteName(zone)?.equals(name.trim(), ignoreCase = false) == true

  fun assignRoute(zone: CommissionRouteZone, name: String?) {
    RouteStore.setSlotRoute(slotKeyForZone(zone), name?.trim()?.takeIf { it.isNotEmpty() })
  }

  fun clearRouteAssignments() {
    getRouteZones().forEach { zone -> RouteStore.clearSlotRoute(slotKeyForZone(zone)) }
  }

  fun getSelectedRouteNames(): List<String> = emptyList()

  fun isRouteSelected(name: String): Boolean = false

  fun toggleRouteSelection(name: String) {}

  fun setRouteSelected(name: String, selected: Boolean) {}

  fun clearRouteSelections() {}

  init {
    addSetting(
      enabled,
      info,
      routeWarpSetting,
      royalRoutePicker,
      cliffsideRoutePicker,
      lavaRoutePicker,
      rampRoutePicker,
      upperRoutePicker,
      glacitePatrolRoutePicker,
    )
    EventBus.register(this)
  }

  // ---- Tick ----

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
    if (pendingUseRelease) {
      mc.options.keyUse?.setDown(false)
      pendingUseRelease = false
    }
    if (pendingSlotRestore >= 0) {
      pressHotbarSlot(pendingSlotRestore)
      pendingSlotRestore = -1
    }

    if (!enabled.value) {
      if (state != State.IDLE) resetMacro()
      return
    }

    val player = mc.player ?: return
    if (pigeonCooldownTicks > 0) {
      pigeonCooldownTicks--
    }
    stateTick++

    when (state) {
      State.IDLE            -> {
        val mode = refreshMode(player)
        if (!ensureCommissionSourceAvailable(player, mode)) return
        ChatUtils.sendMessage("Commission Macro: starting in ${mode.label} mode.")
        transitionToLoopStart(mode)
      }
      State.OPEN_PIGEON     -> handleOpenPigeon(player)
      State.WARP_TO_DWARVES -> handleWarpToDwarves()
      State.WARP_TO_FORGE_EMISSARY -> handleWarpToForgeEmissary()
      State.WALK_TO_EMISSARY -> handleWalkToEmissary(player)
      State.OPEN_EMISSARY   -> handleOpenEmissary(player)
      State.READ_GUI        -> handleReadGui()
      State.CLAIM_GUI       -> handleClaimGui()
      State.WARP_TO_ROUTE_START -> handleWarpToRouteStart()
      State.MINING          -> handleMining()
      State.RETURN_TO_DWARVES -> handleReturnToDwarves()
    }
  }

  // ---- Chat ----

  @SubscribeEvent
  fun onChat(event: ChatEvent.Receive) {
    if (!enabled.value) return
    val msg = event.message?.lowercase() ?: return

    Regex("this ability is on cooldown for\\s+([0-9]+)s").find(msg)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { seconds ->
      if (state == State.OPEN_PIGEON) {
        pigeonCooldownTicks = maxOf(pigeonCooldownTicks, seconds * 20 + 10)
        openAttempts = maxOf(0, openAttempts - 1)
        setStatus("Waiting for Royal Pigeon cooldown... (${seconds}s)")
      }
    }

    if (state == State.MINING) {
      val isComplete = msg.contains("commission complete") ||
        msg.contains("completed a commission") ||
        msg.contains("you've completed") ||
        msg.contains("commission completed") ||
        (msg.contains("commission") && msg.contains("complete"))
      if (isComplete) {
        val player = mc.player
        val mode = refreshMode(player)
        val destination =
          when {
            mode.usesPigeon -> "Royal Pigeon"
            shouldUseForgeEmissaryRoute(mode) -> "emissary"
            mode.usesWarps -> "Emissary Eliza"
            else -> "emissary"
          }
        setStatus("Commission complete! Returning to $destination...")
        ChatUtils.sendMessage("Commission Macro: commission complete, returning to $destination.")
        stopWorkModule()
        transitionToTurnIn(mode)
      }
    }
  }

  // ---- State: OPEN_PIGEON ----

  private fun handleOpenPigeon(player: Player) {
    val mode = refreshMode(player)
    if (!mode.usesPigeon) {
      transitionToCommissionSource(mode, alreadyAtSource = false)
      return
    }

    if (mc.screen is AbstractContainerScreen<*>) {
      openAttempts = 0
      pendingForgeWarpPigeonLook = false
      forgeWarpPigeonTargetYaw = null
      forgeWarpPigeonLookStableTicks = 0
      transition(State.READ_GUI)
      return
    }

    if (pendingForgeWarpPigeonLook) {
      applyForgeWarpPigeonLook(player)
    }

    if (ensureSafeRoyalPigeonLook(player)) {
      setStatus("Aiming Royal Pigeon away from Fred...")
      return
    }

    if (pigeonCooldownTicks > 0) {
      setStatus("Waiting for Royal Pigeon cooldown... (${(pigeonCooldownTicks + 19) / 20}s)")
      return
    }

    if (stateTick % 10L != 0L) return

    val pigeonSlot = findPigeonHotbarSlot(player)
    if (pigeonSlot !in 0..8) {
      transitionToCommissionSource(mode, alreadyAtSource = false)
      return
    }

    if (openAttempts >= 6) {
      ChatUtils.sendMessage("Commission Macro: could not open Royal Pigeon GUI. Disabling.")
      enabled.value = false
      return
    }

    val restoreSlot = player.inventory.selectedSlot
    if (restoreSlot in 0..8 && restoreSlot != pigeonSlot) {
      pendingSlotRestore = restoreSlot
    }
    pressHotbarSlot(pigeonSlot)
    mc.options.keyUse?.setDown(false)
    mc.options.keyUse?.setDown(true)
    pendingUseRelease = true
    openAttempts++
    setStatus("Opening Royal Pigeon... (attempt $openAttempts)")
  }

  // ---- State: WARP_TO_DWARVES ----

  private fun handleWarpToDwarves() {
    val mode = refreshMode(mc.player)
    if (stateTick == 1L) {
      (mc.player as? LocalPlayer)?.connection?.sendCommand("warp dwarves")
      areaText.value = "Dwarven Mines"
      setStatus("Warping to dwarves...")
    }
    if (stateTick >= 100L) {
      transitionToCommissionSource(mode, alreadyAtSource = false, allowDwarvenWarp = false, allowForgeWarp = false)
    }
  }

  private fun handleWarpToForgeEmissary() {
    val mode = refreshMode(mc.player)
    if (stateTick == 1L) {
      (mc.player as? LocalPlayer)?.connection?.sendCommand("warp forge")
      areaText.value = "Forge"
      setStatus("Warping to forge emissary...")
    }
    if (stateTick >= 100L) {
      transitionToCommissionSource(mode, alreadyAtSource = false, allowDwarvenWarp = false, allowForgeWarp = false)
    }
  }

  // ---- State: WALK_TO_EMISSARY ----

  private fun handleWalkToEmissary(player: Player) {
    if (mc.screen is AbstractContainerScreen<*>) {
      stopEmissaryNavigation()
      openAttempts = 0
      transition(State.READ_GUI)
      return
    }

    val target = resolveEmissaryTarget(player, refreshMode(player))
    if (target == null) {
      disableForMissingEmissaryTarget()
      return
    }

    val emissary = target.interactionEntity
    if (emissary != null && player.distanceToSqr(emissary) <= 12.25) {
      stopEmissaryNavigation()
      transition(State.OPEN_EMISSARY)
      return
    }

    val walkTarget = resolveEmissaryNavigationTarget(player, target)
    val pathTarget = findWalkTargetNear(walkTarget)
    if (walkTarget == target.walkPos && player.blockPosition().distSqr(pathTarget) <= 2.25) {
      stopEmissaryNavigation()
      transition(State.OPEN_EMISSARY)
      return
    }

    if (lastEmissaryPathTarget != pathTarget || !nativePathActive() || stateTick % 40L == 1L) {
      startEmissaryNavigation(pathTarget)
    }

    val cmd = NativePathfinder.tick()
    if (cmd != null) {
      cmd.applyToPlayer()
      applyEmissaryWalkCameraRotation(player)
      ensureEmissaryWalkMovement(player, pathTarget)
    } else if (lastEmissaryPathTarget != null) {
      applyEmissaryWalkCameraRotation(player)
      applyEmissaryWalkFallbackMovement(player, pathTarget)
    }
    syncEmissaryWalkKeys()
    setStatus("Walking to ${target.label}...")
  }

  // ---- State: OPEN_EMISSARY ----

  private fun handleOpenEmissary(player: Player) {
    if (mc.screen is AbstractContainerScreen<*>) {
      openAttempts = 0
      transition(State.READ_GUI)
      return
    }

    val target = resolveEmissaryTarget(player, refreshMode(player))
    if (target == null) {
      disableForMissingEmissaryTarget()
      return
    }

    val emissary = target.interactionEntity
    if (emissary == null) {
      if (player.blockPosition().distSqr(target.walkPos) <= 25.0 && stateTick > 80L) {
        ChatUtils.sendMessage("Commission Macro: reached emissary target but could not find ${target.label}. Disabling.")
        enabled.value = false
        return
      }
      transition(State.WALK_TO_EMISSARY)
      return
    }
    if (player.distanceToSqr(emissary) > 12.25) {
      transition(State.WALK_TO_EMISSARY)
      return
    }

    stopEmissaryNavigation()
    faceEntity(emissary)

    if (stateTick == 1L) {
      setStatus("Facing ${target.label}...")
      return
    }
    if (stateTick % 4L != 0L) return
    if (openAttempts >= 8) {
      ChatUtils.sendMessage("Commission Macro: could not open ${target.label} after 8 attempts. Disabling.")
      enabled.value = false
      return
    }

    mc.options.keyUse?.setDown(false)
    mc.options.keyUse?.setDown(true)
    pendingUseRelease = true
    openAttempts++
    setStatus("Opening ${target.label}... (attempt $openAttempts)")
  }

  // ---- State: WARP_TO_ROUTE_START ----

  private fun handleWarpToRouteStart() {
    val player = mc.player
    val mode = refreshMode(player)
    if (stateTick == 1L) {
      (mc.player as? LocalPlayer)?.connection?.sendCommand("warp forge")
      areaText.value = "Forge"
      if (mode.usesPigeon) {
        pendingForgeWarpPigeonLook = true
        forgeWarpPigeonTargetYaw = null
        forgeWarpPigeonLookStableTicks = 0
      }
      setStatus("Warping to forge for route...")
    }
    if (player != null && mode.usesPigeon && pendingForgeWarpPigeonLook && stateTick in 15L..95L) {
      applyForgeWarpPigeonLook(player)
    }
    if (stateTick >= 100L) {
      pendingForgeWarpPigeonLook = false
      forgeWarpPigeonTargetYaw = null
      forgeWarpPigeonLookStableTicks = 0
      transition(State.MINING)
    }
  }

  // ---- State: READ_GUI ----

  private fun handleReadGui() {
    val player = mc.player
    val mode = refreshMode(player)
    val screen = mc.screen as? AbstractContainerScreen<*>
    if (screen == null) {
      transitionToCommissionSource(mode, alreadyAtSource = false)
      return
    }

    if (stateTick < 5) return
    if (stateTick % 5 != 0L) return

    val claimSlot = findClaimSlot(screen)
    if (claimSlot >= 0) {
      setStatus("Found claimable commission - claiming...")
      transition(State.CLAIM_GUI)
      return
    }

    val parsed = parseCommissionSelectionFromGui(screen)
    activeCommissions = parsed.commissions
    val selected = parsed.selected

    if (selected != null) {
      commission = selected
      commText.value = "${selected.type.name.lowercase().replaceFirstChar { it.uppercase() }}: ${selected.label} (${selected.current}/${selected.max})"

      // Print all available commissions to chat
      if (activeCommissions.isNotEmpty()) {
        ChatUtils.sendMessage("Commission Macro: available commissions:")
        activeCommissions.forEachIndexed { i, c ->
          val pct = if (c.max > 0) "${c.current}/${c.max}" else "?"
          val marker = if (c == selected) " \u25C4 selected" else ""
          ChatUtils.sendMessage("  ${i + 1}. ${c.label} ($pct)$marker")
        }
      }

      mc.player?.closeContainer()
      openAttempts = 0
      setStatus("Commission: ${selected.label}")
      if (selected.type == CommissionType.MINING) {
        transition(State.WARP_TO_ROUTE_START)
      } else {
        transition(State.MINING)
      }
      return
    }

    readAttempts++
    if (readAttempts >= 30) {
      mc.player?.closeContainer()
      val source = if (mode.usesPigeon) "Royal Pigeon" else "emissary GUI"
      ChatUtils.sendMessage("Commission Macro: could not read any commission from $source. Disabling.")
      enabled.value = false
    }
  }

  // ---- State: CLAIM_GUI ----

  private fun handleClaimGui() {
    val player = mc.player
    val mode = refreshMode(player)
    val screen = mc.screen as? AbstractContainerScreen<*>
    if (screen == null) {
      resetCommissionState()
      transitionToCommissionSource(mode, alreadyAtSource = false)
      return
    }

    if (stateTick < 4 || stateTick % 4L != 0L) return

    val claimSlot = findClaimSlot(screen)
    if (claimSlot >= 0) {
      if (claimAttempts >= 8) {
        mc.player?.closeContainer()
        ChatUtils.sendMessage("Commission Macro: claim slot did not clear after $claimAttempts attempts. Reopening commissions.")
        resetCommissionState()
        transitionToCommissionSource(mode, alreadyAtSource = false)
        return
      }
      InventoryUtils.clickSlot(claimSlot)
      claimAttempts++
      setStatus("Claiming commission (slot $claimSlot, attempt $claimAttempts)...")
      return
    }

    if (claimAttempts > 0) {
      ChatUtils.sendMessage("Commission Macro: claim step complete. Reading next commission in the same menu...")
      resetCommissionState()
      transition(State.READ_GUI)
      return
    }

    val parsed = parseCommissionSelectionFromGui(screen)
    if (parsed.selected != null || parsed.commissions.isNotEmpty()) {
      transition(State.READ_GUI)
      return
    }

    mc.player?.closeContainer()
    resetCommissionState()
    transitionToCommissionSource(mode, alreadyAtSource = false)
  }

  // ---- State: MINING ----

  private fun handleMining() {
    val player = mc.player
    val mode = refreshMode(player)
    val c = commission ?: run {
      transitionToCommissionSource(mode, alreadyAtSource = false)
      return
    }

    if (!workModuleIsActive(c)) {

      // Navigation route just finished — hand off to the mining/combat macro instead of
      // restarting the route from point 0, which caused an infinite restart loop where the
      // player was walked backward and mining never started.
      if (activeMiningRouteName != null && !RoutesModule.isRunning) {
        val routeCompletionAnchor = RoutesModule.getLastAutomationCompletionPos() ?: player?.blockPosition()
        activeMiningRouteName = null
        when (c.type) {
          CommissionType.MINING ->
            MiningMacroModule.startForAutomation(preferredMiningMacroMineTypes(c), anchor = routeCompletionAnchor)
          CommissionType.COMBAT -> startCombatWorkModule(c)
        }
        val action = if (c.type == CommissionType.MINING) "Mining" else "Combat"
        setStatus("$action: ${c.label}")
        ChatUtils.sendMessage("Commission Macro: route complete, starting ${action.lowercase()} - ${c.label}")
        return
      }

      if (player != null && handleGlaciteWalkerCombatStartup(player, c)) {
        return
      }
      if (player != null && handleGoblinSlayerCombatStartup(player, c)) {
        return
      }

      stopEmissaryNavigation()

      startWorkModule(c)
      val action = if (c.type == CommissionType.MINING) "Mining" else "Combat"
      setStatus("$action: ${c.label}")
      ChatUtils.sendMessage("Commission Macro: starting ${action.lowercase()} - ${c.label}")
    }
  }

  // ---- State: RETURN_TO_DWARVES ----

  private fun handleReturnToDwarves() {
    val mode = refreshMode(mc.player)
    if (mode.usesPigeon) {
      transitionToTurnIn(mode)
      return
    }
    if (stateTick == 1L) {
      (mc.player as? LocalPlayer)?.connection?.sendCommand("warp dwarves")
      areaText.value = "Dwarven Mines"
      setStatus("Returning to Emissary Eliza...")
    }
    if (stateTick >= 100L) {
      transitionToCommissionSource(mode, alreadyAtSource = false, allowDwarvenWarp = false)
    }
  }

  // ---- Helpers ----

  private fun refreshMode(player: Player?): CommissionMode {
    val resolved =
      when {
        player != null && findAotvHotbarSlot(player) in 0..8 && findPigeonHotbarSlot(player) in 0..8 -> CommissionMode.AOTV_PIGEON
        player != null && findAotvHotbarSlot(player) in 0..8 -> CommissionMode.AOTV_ELIZA
        player != null && findPigeonHotbarSlot(player) in 0..8 -> CommissionMode.WALK_PIGEON
        else -> CommissionMode.WALK_ONLY
      }
    currentMode = resolved
    modeText.value = resolved.label
    return resolved
  }

  private fun ensureCommissionSourceAvailable(player: Player, mode: CommissionMode): Boolean {
    if (mode.usesPigeon) return true
    if (resolveEmissaryTarget(player, mode) != null) return true
    if (shouldWarpToForgeForEmissary(mode, alreadyAtSource = false)) return true
    if (shouldWarpToDwarvesForEmissary(mode, alreadyAtSource = false)) return true
    disableForMissingEmissaryTarget(mode)
    return false
  }

  private fun transitionToLoopStart(mode: CommissionMode) {
    MiningProfitTracker.resetSession()
    transitionToCommissionSource(mode, alreadyAtSource = false, allowDwarvenWarp = true)
  }

  private fun transitionToTurnIn(mode: CommissionMode) {
    openAttempts = 0
    readAttempts = 0
    claimAttempts = 0
    when {
      mode.usesPigeon -> {
        setStatus("Opening Royal Pigeon...")
        transition(State.OPEN_PIGEON)
      }
      shouldWarpToForgeForEmissary(mode, alreadyAtSource = false) -> {
        setStatus("Returning to forge emissary...")
        transition(State.WARP_TO_FORGE_EMISSARY)
      }
      shouldWarpToDwarvesForEmissary(mode, alreadyAtSource = false) -> {
        setStatus("Returning to Emissary Eliza...")
        transition(State.RETURN_TO_DWARVES)
      }
      else -> {
        transitionToCommissionSource(mode, alreadyAtSource = false, allowDwarvenWarp = false, allowForgeWarp = false)
      }
    }
  }

  private fun isGlaciteWalkerCommission(c: Commission): Boolean =
    c.type == CommissionType.COMBAT &&
      GLACITE_WALKER_NAME_KEYWORDS.any { keyword -> normalizeComparisonText(c.target).contains(keyword) }

  private fun isGoblinSlayerCommission(c: Commission): Boolean {
    if (c.type != CommissionType.COMBAT) return false
    val normalizedLabel = normalizeComparisonText(c.label)
    val normalizedTarget = normalizeComparisonText(c.target)
    if (normalizedLabel.contains("golden goblin") || normalizedLabel.contains("goblin raid")) return false
    return normalizedLabel == "goblin slayer" || normalizedTarget == "goblin"
  }

  private fun combatAutomationTarget(c: Commission): String =
    if (isGlaciteWalkerCommission(c)) "glacite walker" else c.target

  private fun startCombatWorkModule(c: Commission, patrolPrepared: Boolean = false) {
    activeMiningRouteName = null
    if (!patrolPrepared) {
      prepareCombatPatrolForCommission(c)
    }
    mc.player?.takeIf { isGlaciteWalkerCommission(c) }?.let(::equipDrillForGlaciteWalkers)
    CombatMacroModule.startForAutomation(combatAutomationTarget(c))
  }

  private fun handleGlaciteWalkerCombatStartup(player: Player, c: Commission): Boolean {
    if (!isGlaciteWalkerCommission(c)) {
      return false
    }

    equipDrillForGlaciteWalkers(player)
    if (mc.screen is AbstractContainerScreen<*>) {
      mc.player?.closeContainer()
    }
    val patrolRouteName = tryLoadGlacitePatrolRoute()
    if (patrolRouteName != null) {
      val hasNearbyWalker = hasNearbyGlaciteWalker(player)
      val startNearest = hasNearbyWalker || CombatPatrolModule.hasPointWithin(player, 18.0)
      stopEmissaryNavigation()
      startCombatWorkModule(c, patrolPrepared = true)
      if (!hasNearbyWalker) {
        CombatPatrolModule.startPatrol(startNearestOverride = startNearest)
      }
      setStatus("Combat: ${c.label}")
      ChatUtils.sendMessage("Commission Macro: using patrol route \"$patrolRouteName\" for ${c.label}.")
      return true
    }
    if (hasNearbyGlaciteWalker(player) || player.distanceToSqr(greatIceWallGatePos.x + 0.5, greatIceWallGatePos.y.toDouble(), greatIceWallGatePos.z + 0.5) <= 196.0) {
      stopEmissaryNavigation()
      startCombatWorkModule(c)
      setStatus("Combat: ${c.label}")
      ChatUtils.sendMessage("Commission Macro: at Great Ice Wall, starting combat - ${c.label}")
      return true
    }

    tickCommissionNavigation(player, greatIceWallGatePos)
    setStatus("Walking to Great Ice Wall gate...")
    return true
  }

  private fun prepareCombatPatrolForCommission(c: Commission): String? {
    if (!isGlaciteWalkerCommission(c)) {
      CombatPatrolModule.clearPatrolRoute()
      return null
    }
    return tryLoadGlacitePatrolRoute() ?: run {
      CombatPatrolModule.clearPatrolRoute()
      return null
    }
  }

  private fun tryLoadGlacitePatrolRoute(): String? {
    val route = resolveGlacitePatrolRoute() ?: return null
    if (!CombatPatrolModule.loadSavedRoute(route, automationManaged = true)) return null
    return route.name
  }

  private fun resolveGlacitePatrolRoute(): SavedRoute? {
    val patrolRoutes = RouteStore.listByType(RouteType.PATROL)
    if (patrolRoutes.isEmpty()) return null

    val assignedRouteName = glacitePatrolRoutePicker.value.trim()
    if (assignedRouteName.isNotEmpty()) {
      findPatrolRouteByName(patrolRoutes, assignedRouteName)?.let { return it }
    }

    return patrolRoutes
      .map { route -> route to scoreGlacitePatrolRoute(route.name) }
      .filter { (_, score) -> score > 0 }
      .maxByOrNull { (_, score) -> score }
      ?.first
  }

  private fun findPatrolRouteByName(routes: List<SavedRoute>, routeName: String): SavedRoute? {
    val normalizedName = normalizeComparisonText(routeName)
    return routes.firstOrNull { route -> normalizeComparisonText(route.name) == normalizedName }
  }

  private fun scoreGlacitePatrolRoute(routeName: String): Int {
    val normalized = normalizeComparisonText(routeName)
    var score = 0
    if (normalized.contains("glacite walker")) score += 120
    if (normalized.contains("ice walker")) score += 110
    if (normalized.contains("glacite")) score += 80
    if (normalized.contains("great ice wall")) score += 70
    if (normalized.contains("ice wall")) score += 60
    if (normalized.contains("walker")) score += 20
    return score
  }

  private fun handleGoblinSlayerCombatStartup(player: Player, c: Commission): Boolean {
    if (!isGoblinSlayerCommission(c)) {
      return false
    }

    if (mc.screen is AbstractContainerScreen<*>) {
      mc.player?.closeContainer()
    }

    val goblinBurrowsDistanceSq = player.distanceToSqr(
      goblinBurrowsCenterPos.x + 0.5,
      goblinBurrowsCenterPos.y.toDouble(),
      goblinBurrowsCenterPos.z + 0.5
    )
    if (hasNearbyGoblin(player) || goblinBurrowsDistanceSq <= 36.0 * 36.0) {
      stopEmissaryNavigation()
      startCombatWorkModule(c)
      setStatus("Combat: ${c.label}")
      ChatUtils.sendMessage("Commission Macro: at Goblin Burrows, starting combat - ${c.label}")
      return true
    }

    val atGreatIceWall = hasNearbyGlaciteWalker(player) ||
      player.distanceToSqr(greatIceWallGatePos.x + 0.5, greatIceWallGatePos.y.toDouble(), greatIceWallGatePos.z + 0.5) <= 42.0 * 42.0
    if (atGreatIceWall) {
      tickCommissionNavigation(player, goblinBurrowsCenterPos)
      setStatus("Walking to Goblin Burrows...")
      return true
    }

    tickCommissionNavigation(player, greatIceWallGatePos)
    setStatus("Walking to Great Ice Wall...")
    return true
  }

  private fun equipDrillForGlaciteWalkers(player: Player) {
    val selectedName = normalizeName(player.inventory.getItem(player.inventory.selectedSlot).hoverName.string)
    if (selectedName.contains("drill")) {
      return
    }
    val drillSlot = InventoryUtils.findItemInHotbar("drill")
    if (drillSlot in 0..8) {
      InventoryUtils.holdHotbarSlot(drillSlot)
    }
  }

  private fun hasNearbyGlaciteWalker(player: Player): Boolean {
    val level = mc.level ?: return false
    return level.entitiesForRendering()
      .asSequence()
      .filterIsInstance<LivingEntity>()
      .filter { it != player && it !is ArmorStand && it.isAlive }
      .any { entity ->
        val name = normalizeName(entity.name.string)
        GLACITE_WALKER_NAME_KEYWORDS.any(name::contains) &&
          player.distanceToSqr(entity) <= 40.0 * 40.0
      }
  }

  private fun hasNearbyGoblin(player: Player): Boolean {
    val level = mc.level ?: return false
    return level.entitiesForRendering()
      .asSequence()
      .filterIsInstance<LivingEntity>()
      .filter { it != player && it !is ArmorStand && it.isAlive }
      .any { entity ->
        val name = normalizeName(entity.name.string)
        name == "goblin" && player.distanceToSqr(entity) <= 40.0 * 40.0
      }
  }

  private fun tickCommissionNavigation(player: Player, target: BlockPos) {
    val pathTarget = findWalkTargetNear(target)
    if (lastEmissaryPathTarget != pathTarget || !nativePathActive() || stateTick % 40L == 1L) {
      startEmissaryNavigation(pathTarget)
    }

    val cmd = NativePathfinder.tick()
    if (cmd != null) {
      cmd.applyToPlayer()
      applyEmissaryWalkCameraRotation(player)
      ensureEmissaryWalkMovement(player, pathTarget)
    } else if (lastEmissaryPathTarget != null) {
      applyEmissaryWalkCameraRotation(player)
      applyEmissaryWalkFallbackMovement(player, pathTarget)
    }
    syncEmissaryWalkKeys()
  }

  private fun transitionToCommissionSource(
    mode: CommissionMode,
    alreadyAtSource: Boolean,
    allowDwarvenWarp: Boolean = true,
    allowForgeWarp: Boolean = true,
  ) {
    openAttempts = 0
    readAttempts = 0
    claimAttempts = 0
    when {
      mode.usesPigeon -> {
        setStatus("Opening Royal Pigeon...")
        transition(State.OPEN_PIGEON)
      }
      allowForgeWarp && shouldWarpToForgeForEmissary(mode, alreadyAtSource) -> {
        setStatus("Warping to forge emissary...")
        transition(State.WARP_TO_FORGE_EMISSARY)
      }
      allowDwarvenWarp && shouldWarpToDwarvesForEmissary(mode, alreadyAtSource) -> {
        setStatus("Warping to dwarves...")
        transition(State.WARP_TO_DWARVES)
      }
      alreadyAtSource -> {
        setStatus("Opening ${configuredEmissaryDisplayName(mode)}...")
        transition(State.OPEN_EMISSARY)
      }
      else -> {
        setStatus("Walking to emissary...")
        transition(State.WALK_TO_EMISSARY)
      }
    }
  }

  private fun shouldWarpToDwarvesForEmissary(
    mode: CommissionMode,
    alreadyAtSource: Boolean
  ): Boolean {
    if (getEmissaryWarpDestination(mode) != RouteWarpDestination.DWARVES) return false
    if (mode.usesPigeon || alreadyAtSource) return false
    val player = mc.player ?: return true
    val target = resolveEmissaryTarget(player, mode) ?: return true
    target.interactionEntity?.let { entity ->
      if (player.distanceToSqr(entity) <= 36.0) return false
    }
    return player.blockPosition().distSqr(target.walkPos) > 36.0
  }

  private fun shouldWarpToForgeForEmissary(
    mode: CommissionMode,
    alreadyAtSource: Boolean
  ): Boolean {
    if (getEmissaryWarpDestination(mode) != RouteWarpDestination.FORGE) return false
    if (mode.usesPigeon || alreadyAtSource) return false
    val player = mc.player ?: return true
    val target = resolveEmissaryTarget(player, mode) ?: return true
    target.interactionEntity?.let { entity ->
      if (player.distanceToSqr(entity) <= 36.0) return false
    }
    return player.blockPosition().distSqr(target.walkPos) > 36.0
  }

  private fun getEmissaryWarpDestination(mode: CommissionMode): RouteWarpDestination? =
    when {
      mode.usesPigeon -> null
      else -> getRouteWarpDestination()
    }

  private fun configuredEmissaryDisplayName(mode: CommissionMode): String =
    if (shouldUseForgeEmissaryRoute(mode)) "emissary" else "Emissary Eliza"

  private fun getRouteWarpDestination(): RouteWarpDestination =
    when (routeWarpSetting.value) {
      1 -> RouteWarpDestination.DWARVES
      else -> RouteWarpDestination.FORGE
    }

  private fun getElizaTargetPos(): BlockPos = elizaTargetPos

  private fun disableForMissingEmissaryTarget(mode: CommissionMode = currentMode) {
    setStatus("Missing emissary target")
    val detail =
      if (shouldUseForgeEmissaryRoute(mode)) "the configured forge emissary path"
      else "hard-coded Eliza coords -37 201 -130"
    ChatUtils.sendMessage("Commission Macro: no emissary target found near $detail. Disabling.")
    enabled.value = false
  }

  private fun resolveEmissaryTarget(player: Player, mode: CommissionMode): EmissaryTarget? {
    if (shouldUseForgeEmissaryRoute(mode)) {
      val nearestLoaded =
        findNearestLoadedEmissaryInteractionEntity(player)
          ?.takeIf { entity ->
            forgeEmissaryWalkNodes.lastOrNull()?.let { end ->
              entity.blockPosition().distSqr(end) <= 256.0
            } ?: false
          }
      val walkPos =
        nearestLoaded?.blockPosition()?.let(::findWalkTargetNear)
          ?: forgeEmissaryWalkNodes.lastOrNull()?.let(::findWalkTargetNear)
          ?: return null
      val label = nearestLoaded?.let(::formatEmissaryLabel) ?: "Emissary"
      return EmissaryTarget(walkPos, nearestLoaded, label, forgeEmissaryWalkNodes)
    }

    if (!mode.usesWarps) {
      val nearestLoaded = findNearestLoadedEmissaryInteractionEntity(player)
      if (nearestLoaded != null) {
        return EmissaryTarget(findWalkTargetNear(nearestLoaded.blockPosition()), nearestLoaded, formatEmissaryLabel(nearestLoaded))
      }
    }

    val elizaPos = getElizaTargetPos()
    return EmissaryTarget(findWalkTargetNear(elizaPos), findEmissaryInteractionEntity(elizaPos), "Emissary Eliza")
  }

  private fun shouldUseForgeEmissaryRoute(mode: CommissionMode): Boolean =
    !mode.usesPigeon && getRouteWarpDestination() == RouteWarpDestination.FORGE

  private fun resolveEmissaryNavigationTarget(player: Player, target: EmissaryTarget): BlockPos {
    val finalNode = target.walkNodes.lastOrNull()
    target.interactionEntity?.let { entity ->
      val canDirectToEntity =
        finalNode == null ||
          player.blockPosition().distSqr(finalNode) <= 144.0 ||
          player.distanceToSqr(entity) <= 100.0
      if (canDirectToEntity) {
        return findWalkTargetNear(entity.blockPosition())
      }
    }

    if (target.walkNodes.isEmpty()) return target.walkPos

    val node =
      resolveNextEmissaryWalkNode(player, target.walkNodes)
        ?: return target.walkPos
    return findWalkTargetNear(node)
  }

  private fun resolveNextEmissaryWalkNode(player: Player, nodes: List<BlockPos>): BlockPos? {
    if (nodes.isEmpty()) return null

    val playerPos = player.blockPosition()
    if (activeEmissaryWalkNodeIndex !in nodes.indices) {
      activeEmissaryWalkNodeIndex =
        nodes.indices.minByOrNull { index ->
          playerPos.distSqr(nodes[index])
        } ?: 0
    }

    while (activeEmissaryWalkNodeIndex < nodes.lastIndex) {
      val currentNode = nodes[activeEmissaryWalkNodeIndex]
      if (playerPos.distSqr(currentNode) > 9.0) break
      activeEmissaryWalkNodeIndex++
    }

    return nodes[activeEmissaryWalkNodeIndex.coerceIn(0, nodes.lastIndex)]
  }

  private fun findEmissaryInteractionEntity(targetPos: BlockPos): Entity? {
    val level = mc.level ?: return null
    val player = mc.player ?: return null
    val anchor = level.entitiesForRendering()
      .asSequence()
      .filter { it != player }
      .filter { it.distanceToSqr(targetPos.x + 0.5, targetPos.y.toDouble(), targetPos.z + 0.5) <= 64.0 }
      .filter { entityNameMatchesEliza(it) }
      .sortedWith(compareBy<Entity>({ if (it is ArmorStand) 1 else 0 }, { player.distanceToSqr(it) }))
      .firstOrNull() ?: return null
    if (anchor !is ArmorStand) return anchor

    return level.entitiesForRendering()
      .asSequence()
      .filter { it != player && it !is ArmorStand && it.distanceToSqr(anchor) <= 16.0 }
      .sortedWith(compareBy<Entity>({ if (it is Player) 0 else 1 }, { anchor.distanceToSqr(it) }))
      .firstOrNull() ?: anchor
  }

  private fun findNearestLoadedEmissaryInteractionEntity(player: Player): Entity? {
    val level = mc.level ?: return null
    val anchors = level.entitiesForRendering()
      .asSequence()
      .filter { it != player }
      .filter { entityNameMatchesAnyEmissary(it) }
      .sortedBy { player.distanceToSqr(it) }
      .toList()
    if (anchors.isEmpty()) return null

    val anchor = anchors.first()
    if (anchor !is ArmorStand) return anchor

    return level.entitiesForRendering()
      .asSequence()
      .filter { it != player && it !is ArmorStand && it.distanceToSqr(anchor) <= 16.0 }
      .sortedWith(compareBy<Entity>({ if (it is Player) 0 else 1 }, { anchor.distanceToSqr(it) }))
      .firstOrNull() ?: anchor
  }

  private fun entityNameMatchesEliza(entity: Entity): Boolean {
    val name = normalizeName(entity.name.string)
    return name.contains("emissary eliza") || name == "eliza"
  }

  private fun entityNameMatchesAnyEmissary(entity: Entity): Boolean {
    val name = normalizeName(entity.name.string)
    return name.contains("emissary") || name == "eliza"
  }

  private fun formatEmissaryLabel(entity: Entity): String {
    val normalized = normalizeName(entity.name.string)
    return normalized
      .removePrefix("emissary ")
      .ifBlank { "emissary" }
      .let(::titleCase)
      .let { if (it.startsWith("Emissary ")) it else "Emissary $it" }
  }

  private fun findAotvHotbarSlot(player: Player): Int {
    for (i in 0..8) {
      val stack = player.inventory.getItem(i)
      if (stack.isEmpty) continue
      if (!EtherwarpLogic.isEtherwarpStack(stack)) continue

      val name = ChatFormatting.stripFormatting(stack.hoverName.string)?.lowercase(Locale.US) ?: continue
      if (name.contains("aspect of the void") || name.contains("warped aspect of the void")) return i
    }
    return -1
  }

  private fun findPigeonHotbarSlot(player: Player): Int {
    for (i in 0..8) {
      val stack = player.inventory.getItem(i)
      if (stack.isEmpty) continue
      val name = ChatFormatting.stripFormatting(stack.hoverName.string)?.lowercase(Locale.US) ?: continue
      if (name.contains("royal pigeon")) return i
    }
    return -1
  }

  private fun pressHotbarSlot(slot: Int) {
    if (slot !in 0..8) return
    mc.player?.inventory?.selectedSlot = slot
  }

  private fun normalizeName(raw: String): String {
    return ChatFormatting.stripFormatting(raw)
      ?.lowercase(Locale.US)
      ?.replace(Regex("\\s+"), " ")
      ?.trim()
      .orEmpty()
  }

  private fun findWalkTargetNear(base: BlockPos): BlockPos {
    val level = mc.level ?: return base
    if (MinecraftPathingRules.isWalkable(level, base)) return base

    for (radius in 1..3) {
      for (dy in -2..2) {
        for (dx in -radius..radius) {
          for (dz in -radius..radius) {
            val candidate = base.offset(dx, dy, dz)
            if (MinecraftPathingRules.isWalkable(level, candidate)) return candidate
          }
        }
      }
    }

    return base
  }

  private fun faceEntity(entity: Entity) {
    val player = mc.player ?: return
    val rotation = AngleUtils.getRotation(entity)
    applyTickRotation(player, rotation.yaw, rotation.pitch, maxYawStep = 18f, maxPitchStep = 14f)
  }

  private fun applyForgeWarpPigeonLook(player: Player) {
    val targetYaw = forgeWarpPigeonTargetYaw ?: AngleUtils.normalizeAngle(player.yRot + 30f).also {
      forgeWarpPigeonTargetYaw = it
    }
    applyTickRotation(player, targetYaw, -18f, maxYawStep = 12f, maxPitchStep = 7f)
  }

  private fun ensureSafeRoyalPigeonLook(player: Player): Boolean {
    val fred = findNearestLoadedFredInteractionEntity(player)
    if (fred == null || player.distanceToSqr(fred) > 8.0 * 8.0) {
      if (!pendingForgeWarpPigeonLook) {
        forgeWarpPigeonTargetYaw = null
      }
      forgeWarpPigeonLookStableTicks = 0
      return false
    }

    val fredYaw = AngleUtils.getRotation(fred).yaw
    val rightTurn = AngleUtils.normalizeAngle(fredYaw + 82f)
    val leftTurn = AngleUtils.normalizeAngle(fredYaw - 82f)
    val targetYaw = forgeWarpPigeonTargetYaw?.takeIf { stored ->
      val rightError = abs(AngleUtils.getRotationDelta(stored, rightTurn))
      val leftError = abs(AngleUtils.getRotationDelta(stored, leftTurn))
      minOf(rightError, leftError) <= 18f
    } ?: run {
      val rightCost = abs(AngleUtils.getRotationDelta(player.yRot, rightTurn))
      val leftCost = abs(AngleUtils.getRotationDelta(player.yRot, leftTurn))
      (if (rightCost <= leftCost) rightTurn else leftTurn).also { chosen ->
        forgeWarpPigeonTargetYaw = chosen
      }
    }

    applyTickRotation(player, targetYaw, -18f, maxYawStep = 12f, maxPitchStep = 7f)
    val yawReady = abs(AngleUtils.getRotationDelta(player.yRot, targetYaw)) <= 3.5f
    val pitchReady = abs(player.xRot - (-18f)) <= 2.5f
    forgeWarpPigeonLookStableTicks = if (yawReady && pitchReady) forgeWarpPigeonLookStableTicks + 1 else 0
    return forgeWarpPigeonLookStableTicks < 2
  }

  private fun findNearestLoadedFredInteractionEntity(player: Player): Entity? {
    val level = mc.level ?: return null
    val anchors = level.entitiesForRendering()
      .asSequence()
      .filter { it != player }
      .filter { entityNameMatchesFred(it) }
      .sortedBy { player.distanceToSqr(it) }
      .toList()
    if (anchors.isEmpty()) return null

    val anchor = anchors.first()
    if (anchor !is ArmorStand) return anchor

    return level.entitiesForRendering()
      .asSequence()
      .filter { it != player && it !is ArmorStand && it.distanceToSqr(anchor) <= 16.0 }
      .sortedWith(compareBy<Entity>({ if (it is Player) 0 else 1 }, { anchor.distanceToSqr(it) }))
      .firstOrNull() ?: anchor
  }

  private fun entityNameMatchesFred(entity: Entity): Boolean {
    val name = normalizeName(entity.name.string)
    return name == "fred" || name.endsWith(" fred") || name.contains(" fred ")
  }

  private fun applyTickRotation(player: Player, targetYaw: Float, targetPitch: Float, maxYawStep: Float, maxPitchStep: Float) {
    val yawStep = AngleUtils.getRotationDelta(player.yRot, targetYaw).coerceIn(-maxYawStep, maxYawStep)
    val pitchStep = (targetPitch - player.xRot).coerceIn(-maxPitchStep, maxPitchStep)
    val nextYaw = AngleUtils.normalizeAngle(player.yRot + yawStep)
    val nextPitch = (player.xRot + pitchStep).coerceIn(-90f, 90f)
    player.setYRot(nextYaw)
    player.setXRot(nextPitch)
    player.yHeadRot = nextYaw
    player.yBodyRot = nextYaw
  }

  private fun nativePathActive(): Boolean =
    NativePathfinder.status.let { it != PathStatus.IDLE && it != PathStatus.ARRIVED && it != PathStatus.FAILED }

  private fun startEmissaryNavigation(target: BlockPos) {
    PathfindingModule.ensureEnabledForAutomation("commission macro")
    NativePathfinder.stop()
    MovementManager.setLookLock(true)
    MovementManager.setMovementLock(false)
    NativePathfinder.setTarget(target.x + 0.5, target.y.toDouble(), target.z + 0.5)
    lastEmissaryPathTarget = target
  }

  private fun ensureEmissaryWalkMovement(player: Player, target: BlockPos) {
    if (
      MovementManager.forcedForward ||
      MovementManager.forcedBackward ||
      MovementManager.forcedLeft ||
      MovementManager.forcedRight ||
      MovementManager.forcedJump
    ) {
      return
    }
    applyEmissaryWalkFallbackMovement(player, target)
  }

  private fun applyEmissaryWalkFallbackMovement(player: Player, target: BlockPos) {
    val dx = (target.x + 0.5) - player.x
    val dz = (target.z + 0.5) - player.z
    val len = sqrt(dx * dx + dz * dz)
    if (len < 0.05) {
      MovementManager.clearForcedMovement()
      return
    }

    val nx = dx / len
    val nz = dz / len
    val yawRad = Math.toRadians(player.yRot.toDouble())
    val sinYaw = sin(yawRad)
    val cosYaw = cos(yawRad)
    val fwd = (-nx * sinYaw + nz * cosYaw).toFloat()
    val str = (nx * cosYaw + nz * sinYaw).toFloat()
    val threshold = 0.2f

    MovementManager.setMovementLock(true)
    MovementManager.setForcedMovement(
      forward = fwd > threshold,
      backward = fwd < -threshold,
      left = str < -threshold,
      right = str > threshold,
      jump = player.onGround() && player.horizontalCollision,
      shift = false,
      sprint = fwd > threshold
    )
    player.isSprinting = fwd > threshold
  }

  private fun applyEmissaryWalkCameraRotation(player: Player) {
    val lookPoint = resolveEmissaryWalkLookPoint(player) ?: return
    val rotation = AngleUtils.getRotation(player.eyePosition, lookPoint)
    MovementManager.setLookLock(true)
    player.setYRot(rotation.yaw)
    player.setXRot(rotation.pitch)
    player.yHeadRot = rotation.yaw
    player.yBodyRot = rotation.yaw
  }

  private fun resolveEmissaryWalkLookPoint(player: Player): Vec3? {
    val nodes = NativePathfinder.cachedPathNodes
    if (nodes.isNotEmpty()) {
      val nearestIndex = nearestEmissaryWalkNodeIndex(player, nodes)
      if (nearestIndex >= 0) {
        val guideIndex = (nearestIndex + 2).coerceAtMost(nodes.lastIndex)
        val guideNode = nodes[guideIndex]
        return Vec3(guideNode.x, maxOf(guideNode.y + 0.6, player.eyePosition.y), guideNode.z)
      }
    }

    val target = lastEmissaryPathTarget ?: return null
    return Vec3(target.x + 0.5, target.y + 0.6, target.z + 0.5)
  }

  private fun nearestEmissaryWalkNodeIndex(player: Player, nodes: List<Vec3>): Int {
    var nearestIndex = -1
    var nearestDistSq = Double.POSITIVE_INFINITY
    for (index in nodes.indices) {
      val node = nodes[index]
      val dx = node.x - player.x
      val dz = node.z - player.z
      val distSq = dx * dx + dz * dz
      if (distSq < nearestDistSq) {
        nearestDistSq = distSq
        nearestIndex = index
      }
    }
    return nearestIndex
  }

  private fun stopEmissaryNavigation() {
    if (nativePathActive()) {
      NativePathfinder.stop()
    }
    MovementManager.setLookLock(false)
    MovementManager.clearForcedMovement()
    MovementManager.setMovementLock(false)
    setEmissaryWalkKeys(false, false, false, false, false, false, false)
    lastEmissaryPathTarget = null
    activeEmissaryWalkNodeIndex = -1
  }

  private fun syncEmissaryWalkKeys() {
    val hasForcedMovement = MovementManager.hasForcedMovement
    setEmissaryWalkKeys(
      forward = hasForcedMovement && MovementManager.forcedForward,
      backward = hasForcedMovement && MovementManager.forcedBackward,
      left = hasForcedMovement && MovementManager.forcedLeft,
      right = hasForcedMovement && MovementManager.forcedRight,
      jump = hasForcedMovement && MovementManager.forcedJump,
      shift = hasForcedMovement && MovementManager.forcedShift,
      sprint = hasForcedMovement && MovementManager.forcedSprint,
    )
  }

  private fun setEmissaryWalkKeys(
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

  /** Parse active commissions from the open GUI, prioritising exact known commission names first. */
  private fun parseCommissionSelectionFromGui(screen: AbstractContainerScreen<*>): ParsedCommissionSelection {
    val commissions = mutableListOf<Commission>()
    for (slot in getCommissionCandidateSlots(screen)) {
      if (!slot.hasItem()) continue
      val item = slot.item
      val lines = buildGuiTextLines(item)
      if (lines.isEmpty()) continue
      val combined = lines.joinToString("\n")
      if (!looksLikeCommissionEntry(lines, combined)) continue
      if (isClaimCommissionText(combined)) continue

      val (current, max) = parseCommissionProgress(combined)
      if (max > 0 && current >= max) continue

      val miningKeyword = extractMiningKeyword(lines)
      val miningTarget  = miningKeyword?.let { ORE_TO_TYPE[it] }
      val mineTypes     = miningKeyword?.let { ORE_TO_MINE_TYPES[it] }
      val combatTarget  = extractCombatCommissionTarget(lines)
      val label         = buildCommissionLabel(item, lines, miningTarget, combatTarget)

      if (miningTarget != null && mineTypes != null) {
        commissions += Commission(label, CommissionType.MINING, miningTarget, mineTypes, current, max)
        continue
      }
      if (combatTarget != null) {
        commissions += Commission(label, CommissionType.COMBAT, combatTarget, combatTarget, current, max)
      }
    }

    // The commission GUI can expose the same commission in multiple slots.
    // Collapse duplicates before selecting a target or printing the list.
    val unique = commissions.distinctBy { commission ->
      listOf(
        commission.type.name,
        commission.label.lowercase(Locale.US),
        commission.target.lowercase(Locale.US),
        commission.mineTypes.lowercase(Locale.US),
        commission.current.toString(),
        commission.max.toString(),
      ).joinToString("|")
    }

    val sorted = unique.sortedWith(
      compareBy<Commission>(
        { if (isExcludedCommissionSelection(it)) 1 else 0 },
        { commissionSelectionRank(it) },
        { if (it.type == CommissionType.MINING) 0 else 1 },
        { normalizeComparisonText(it.label) }
      )
    )

    val selected = sorted.firstOrNull { !isExcludedCommissionSelection(it) }
    return ParsedCommissionSelection(sorted, selected)
  }

  private fun isExcludedCommissionSelection(commission: Commission): Boolean {
    val normalizedLabel = normalizeComparisonText(commission.label)
    val normalizedTarget = normalizeComparisonText(commission.target)
    return normalizedLabel.split(' ').contains("raid") || normalizedLabel.contains("treasure hoarder") ||
      normalizedTarget.contains("treasure hoarder")
  }

  private fun findClaimSlot(screen: AbstractContainerScreen<*>): Int {
    for (slot in getCommissionCandidateSlots(screen)) {
      if (!slot.hasItem()) continue
      val item = slot.item
      val lines = buildGuiTextLines(item)
      if (lines.isEmpty()) continue
      val combined = lines.joinToString("\n")
      if (isClaimableCommissionSlot(lines, combined)) return slot.index
    }
    return -1
  }

  private fun isClaimableCommissionSlot(lines: List<String>, combined: String): Boolean {
    if (!looksLikeCommissionEntry(lines, combined)) return false
    if (isClaimCommissionText(combined)) return true
    val (current, max) = parseCommissionProgress(combined)
    return max > 0 && current >= max
  }

  private fun getCommissionCandidateSlots(screen: AbstractContainerScreen<*>): List<Slot> {
    val containerSlots = screen.menu.slots.filterNot { it.container is Inventory }
    return if (containerSlots.isNotEmpty()) containerSlots else screen.menu.slots
  }

  private fun buildGuiTextLines(item: ItemStack): List<String> {
    val lines = mutableListOf<String>()
    val name = ChatFormatting.stripFormatting(item.hoverName.string)?.lowercase()?.trim().orEmpty()
    if (name.isNotBlank()) lines += name
    lines += item.getLoreLines()
      .mapNotNull { ChatFormatting.stripFormatting(it.string)?.lowercase()?.trim() }
      .filter { it.isNotBlank() }
    return lines
  }

  private fun buildCommissionLabel(
    item: ItemStack,
    lines: List<String>,
    miningTarget: String?,
    combatTarget: String?,
  ): String {
    findKnownCommissionLabel(lines)?.let { return it }
    val itemName = ChatFormatting.stripFormatting(item.hoverName.string)
      ?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    if (itemName.isNotBlank() && !itemName.matches(Regex("Commission\\s*#\\d+", RegexOption.IGNORE_CASE))) {
      canonicalizeKnownCommissionLabel(itemName)?.let { return it }
      return itemName
    }
    if (miningTarget != null) return buildMiningCommissionLabel(lines, miningTarget)
    if (combatTarget != null) return buildCombatCommissionLabel(lines, combatTarget)
    return itemName.ifBlank { "Unknown Commission" }
  }

  private fun findKnownCommissionLabel(lines: List<String>): String? =
    lines.firstNotNullOfOrNull(::canonicalizeKnownCommissionLabel)

  private fun buildMiningCommissionLabel(lines: List<String>, miningTarget: String): String {
    val area = extractMiningAreaLabel(lines)
    val suffix = miningCommissionSuffix(miningTarget)
    if (area != null) return "$area $suffix"
    return when (suffix) {
      "Titanium" -> "Titanium Miner"
      "Mithril" -> "Mithril Miner"
      else -> suffix
    }
  }

  private fun buildCombatCommissionLabel(lines: List<String>, combatTarget: String): String {
    val normalizedTarget = normalizeComparisonText(combatTarget)
    return when {
      normalizedTarget.contains("glacite walker") || normalizedTarget.contains("ice walker") -> "Glacite Walker Slayer"
      normalizedTarget.contains("star sentry") || normalizedTarget.contains("crystal sentry") -> "Star Sentry Puncher"
      normalizedTarget.contains("treasure hoarder") -> "Treasure Hoarder Puncher"
      normalizedTarget.contains("golden goblin") -> "Golden Goblin Slayer"
      lines.any { normalizeComparisonText(it).contains("goblin raid") } -> "Goblin Raid"
      normalizedTarget.contains("goblin") -> "Goblin Slayer"
      else -> {
        val objectiveLine = lines.firstOrNull {
          COMMISSION_OBJECTIVE_PREFIXES.any(it::startsWith)
        }.orEmpty()
        val area = objectiveLine.substringAfter(" in ", "").substringBefore(" progress")
          .replace(Regex("[^a-z0-9' -]"), " ").replace(Regex("\\s+"), " ").trim()
        val targetLabel = titleCase(combatTarget)
        if (area.isNotBlank() && area != objectiveLine) "${titleCase(area)} $targetLabel" else targetLabel
      }
    }
  }

  private fun canonicalizeKnownCommissionLabel(raw: String): String? {
    val normalized = normalizeComparisonText(raw)
    return COMMISSION_PRIORITY.firstOrNull { normalizeComparisonText(it) == normalized }
  }

  private fun extractMiningAreaLabel(lines: List<String>): String? {
    val objectiveLine = lines.firstOrNull { it.startsWith("mine ") }.orEmpty()
    val inlineArea = objectiveLine.substringAfter(" in ", "").substringBefore(" progress").trim()
    canonicalizeMiningArea(inlineArea)?.let { return it }

    for (line in lines) {
      canonicalizeMiningArea(line)?.let { return it }
    }
    return null
  }

  private fun canonicalizeMiningArea(raw: String): String? {
    val normalized = normalizeComparisonText(raw)
    if (normalized.isBlank()) return null
    return MINING_AREA_ALIASES.entries.firstOrNull { (alias, _) -> normalized.contains(alias) }?.value
  }

  private fun miningCommissionSuffix(miningTarget: String): String {
    val normalized = MiningBlockRegistry.normalizeType(miningTarget)
    return when {
      normalized == "Titanium" -> "Titanium"
      normalized.startsWith("Mithril") -> "Mithril"
      else -> normalized
    }
  }

  private fun commissionSelectionRank(commission: Commission): Int {
    val normalizedLabel = normalizeComparisonText(commission.label)
    COMMISSION_PRIORITY_INDEX[normalizedLabel]?.let { return it }

    return when (commission.type) {
      CommissionType.MINING -> when (miningCommissionSuffix(commission.target)) {
        "Titanium" -> COMMISSION_PRIORITY.size + 10
        "Mithril" -> COMMISSION_PRIORITY.size + 11
        "Glacite" -> COMMISSION_PRIORITY.size + 20
        "Umber" -> COMMISSION_PRIORITY.size + 21
        "Tungsten" -> COMMISSION_PRIORITY.size + 22
        else -> COMMISSION_PRIORITY.size + 40
      }
      CommissionType.COMBAT -> when {
        normalizedLabel.contains("glacite walker") -> COMMISSION_PRIORITY.size + 30
        normalizedLabel.contains("goblin") -> COMMISSION_PRIORITY.size + 31
        else -> COMMISSION_PRIORITY.size + 50
      }
    }
  }

  private fun titleCase(value: String): String =
    value.split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ") { word ->
      word.lowercase(Locale.US).replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
    }

  private fun normalizeComparisonText(value: String): String {
    return ChatFormatting.stripFormatting(value)
      ?.lowercase(Locale.US)
      ?.replace("&", " and ")
      ?.replace(Regex("[^a-z0-9' ]"), " ")
      ?.replace(Regex("\\s+"), " ")
      ?.trim()
      .orEmpty()
  }

  private fun looksLikeCommissionEntry(lines: List<String>, combined: String): Boolean {
    val firstLine = lines.firstOrNull().orEmpty()
    if (firstLine.contains("close") || firstLine.contains("back") || firstLine.contains("next page")) return false
    if (combined.contains("royal pigeon")) return false
    val normalizedFirstLine = normalizeComparisonText(firstLine)
    if (canonicalizeKnownCommissionLabel(firstLine) != null) return true
    if (normalizedFirstLine == "goblin raid" || normalizedFirstLine == "star sentry puncher") return true

    if (isClaimCommissionText(combined)) return true

    val hasObjectiveLine = lines.any { line -> COMMISSION_OBJECTIVE_PREFIXES.any(line::startsWith) }
    val hasProgressMarker =
      combined.contains(" progress") ||
        Regex("([0-9,]+)\\s*/\\s*([0-9,]+)").containsMatchIn(combined) ||
        Regex("([0-9]{1,3}(?:\\.[0-9]+)?)\\s*%").containsMatchIn(combined)

    return hasObjectiveLine && hasProgressMarker
  }

  private fun isClaimCommissionText(combined: String): Boolean {
    val normalized = normalizeComparisonText(combined)
    return normalized.contains("click to claim") ||
      normalized.contains("click here to claim") ||
      normalized.contains("claim reward") ||
      normalized.contains("claim rewards") ||
      normalized.contains("claim commission") ||
      normalized.contains("commission complete") ||
      normalized.contains("commission completed") ||
      (normalized.contains("claim") && normalized.contains("reward")) ||
      normalized.contains("completed")
  }

  private fun parseCommissionProgress(combined: String): Pair<Int, Int> {
    val ratioMatch = Regex("([0-9,]+)\\s*/\\s*([0-9,]+)").find(combined)
    if (ratioMatch != null) {
      val current = ratioMatch.groupValues[1].replace(",", "").toIntOrNull() ?: 0
      val max = ratioMatch.groupValues[2].replace(",", "").toIntOrNull() ?: 100
      return current to max.coerceAtLeast(1)
    }
    val percentMatch = Regex("([0-9]{1,3}(?:\\.[0-9]+)?)\\s*%").find(combined)
    if (percentMatch != null) {
      val percent = percentMatch.groupValues[1].toDoubleOrNull() ?: 0.0
      return percent.toInt().coerceIn(0, 100) to 100
    }
    if (isClaimCommissionText(combined)) return 100 to 100
    return 0 to 100
  }

  private fun extractMiningKeyword(lines: List<String>): String? {
    for (line in lines) {
      if (MINING_OBJECTIVE_PREFIXES.none(line::startsWith)) continue
      for (keyword in ORE_TO_TYPE.keys) {
        if (line.contains(keyword)) return keyword
      }
    }
    return null
  }

  private fun extractCombatCommissionTarget(lines: List<String>): String? {
    val objectiveRe = Regex("(?:kill|slay|defeat|punch|damage)\\s+(?:[0-9,]+\\s*/\\s*[0-9,]+\\s+)?(?:[0-9,]+\\s+)?(.+)")
    for (line in lines) {
      val match = objectiveRe.find(line) ?: continue
      val rawTarget = match.groupValues[1]
        .substringBefore(" progress").substringBefore(" in ")
        .replace(Regex("[^a-z0-9' -]"), " ").replace(Regex("\\s+"), " ").trim()
      if (rawTarget.isBlank()) continue
      return rawTarget.removeSuffix(" mobs").removeSuffix(" mob").let { target ->
        when {
          target.endsWith("ies") && target.length > 3 -> target.dropLast(3) + "y"
          target.endsWith("s") && !target.endsWith("ss") && target.length > 3 -> target.dropLast(1)
          else -> target
        }
      }
    }
    return null
  }

  private fun startWorkModule(c: Commission) {
    when (c.type) {
      CommissionType.MINING -> {
        val routeName = findMatchingRouteName(c)
        if (
          routeName != null &&
            RoutesModule.loadAndStartAutomationRoute(
              routeName,
              startNearest = false,
              automationSource = "commission automation",
            )
        ) {
          activeMiningRouteName = routeName
          ChatUtils.sendMessage("Commission Macro: using route \"$routeName\" for ${c.label}.")
          return
        }
        if (routeName != null) {
          ChatUtils.sendMessage("Commission Macro: route \"$routeName\" could not be started. Falling back to mining macro.")
        } else {
          ChatUtils.sendMessage("Commission Macro: no route assigned or matched for ${c.label}. Falling back to mining macro.")
        }
        activeMiningRouteName = null
        MiningMacroModule.startForAutomation(preferredMiningMacroMineTypes(c))
      }
      CommissionType.COMBAT -> {
        startCombatWorkModule(c)
      }
    }
  }

  private fun stopWorkModule() {
    when (commission?.type) {
      CommissionType.MINING -> {
        if (activeMiningRouteName != null && RoutesModule.isRunning) {
          RoutesModule.stopForAutomation()
        }
        activeMiningRouteName = null
        MiningMacroModule.stopForAutomation()
      }
      CommissionType.COMBAT -> {
        CombatMacroModule.stopForAutomation()
        if (commission?.let(::isGlaciteWalkerCommission) == true) {
          CombatPatrolModule.clearPatrolRoute()
        }
      }
      null -> {}
    }
  }

  private fun workModuleIsActive(c: Commission): Boolean = when (c.type) {
    CommissionType.MINING -> (activeMiningRouteName != null && RoutesModule.isRunning) || MiningMacroModule.isActive
    CommissionType.COMBAT -> CombatMacroModule.isActive
  }

  private fun findMatchingRouteName(c: Commission): String? {
    val availableRoutes = RoutesModule.getSavedRouteInfos()
    resolveAssignedRouteName(c, availableRoutes)?.let { return it }

    val availableRoutesByName = availableRoutes.associateBy { it.name }
    val selectedRoutes = resolveSelectableRoutes(c, availableRoutesByName)
    if (selectedRoutes.isEmpty()) return null

    val requiredTypes = c.mineTypes
      .split(',')
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .map(MiningBlockRegistry::normalizeType)
      .toSet()
    val preferredTypes = preferredRouteMineTypes(c, requiredTypes)
    val namedZoneRoutes =
      resolveCommissionRouteZone(c.label)?.let { zone ->
        selectedRoutes.filter { routeMatchesZone(it.name, zone) }
      }.orEmpty()
    val targetZone = ORE_TO_ZONE[c.target]
    val areaRoutes =
      if (targetZone != null) {
        selectedRoutes.filter { route -> route.mineTypes.any { type -> ORE_TO_ZONE[type] == targetZone } }
      } else {
        emptyList()
      }

    if (shouldUseRandomAssignedCommissionRoute(c)) {
      val randomPool = (areaRoutes.ifEmpty { selectedRoutes }).shuffled()
      return selectRouteByType(randomPool, preferredTypes)
        ?: randomPool.firstOrNull()?.name
    }

    // For zone-specific commissions ("Royal Mines Mithril"), namedZoneRoutes is populated and
    // selection is deterministic. For generic commissions like "Mithril Miner" (no zone
    // keyword), namedZoneRoutes is empty and we shuffle to randomly vary which route is used.
    val hasZone = namedZoneRoutes.isNotEmpty()
    val areaRoutePool    = if (hasZone) areaRoutes    else areaRoutes.shuffled()
    val selectedRoutePool = if (hasZone) selectedRoutes else selectedRoutes.shuffled()

    return selectRouteByCommissionName(namedZoneRoutes, c)
      ?: selectRouteByType(namedZoneRoutes, preferredTypes)
      ?: namedZoneRoutes.firstOrNull()?.name
      ?: selectRouteByCommissionName(areaRoutePool, c)
      ?: selectRouteByCommissionName(selectedRoutePool, c)
      ?: selectRouteByType(areaRoutePool, preferredTypes)
      ?: areaRoutePool.firstOrNull()?.name
      ?: selectRouteByType(selectedRoutePool, preferredTypes)
      ?: selectedRoutePool.firstOrNull()?.name
  }

  private fun resolveSelectableRoutes(
      commission: Commission,
      availableRoutesByName: Map<String, RoutesModule.SavedRouteInfo>,
  ): List<RoutesModule.SavedRouteInfo> {
    val selectedRoutes =
      getSelectedRouteNames()
        .mapNotNull { name -> findRouteInfoByName(availableRoutesByName, name) }
        .distinctBy { normalizeComparisonText(it.name) }
    if (selectedRoutes.isNotEmpty()) return selectedRoutes
    val assignedRoutes = getRouteZones()
      .mapNotNull(::getAssignedRouteName)
      .mapNotNull { name -> findRouteInfoByName(availableRoutesByName, name) }
      .distinctBy { normalizeComparisonText(it.name) }
    if (!shouldUseRandomAssignedCommissionRoute(commission)) return assignedRoutes
    val fallbackRoutes =
      availableRoutesByName.values
        .filter(::isRandomMiningCommissionRouteCandidate)
        .sortedBy { normalizeComparisonText(it.name) }
    return (assignedRoutes + fallbackRoutes)
      .distinctBy { normalizeComparisonText(it.name) }
  }

  private fun shouldUseRandomAssignedCommissionRoute(c: Commission): Boolean {
    if (c.type != CommissionType.MINING) return false
    if (resolveCommissionRouteZone(c.label) != null) return false
    return when (miningCommissionSuffix(c.target)) {
      "Titanium", "Mithril" -> true
      else -> false
    }
  }

  private fun isRandomMiningCommissionRouteCandidate(route: RoutesModule.SavedRouteInfo): Boolean {
    val normalizedName = normalizeComparisonText(route.name)
    val matchesKnownZone =
      COMMISSION_ROUTE_ZONE_ALIASES.values
        .asSequence()
        .flatten()
        .any { alias -> normalizedName.contains(alias) }
    if (matchesKnownZone) return true
    return route.mineTypes.any { type -> ORE_TO_ZONE[type] == "Dwarven Mines" }
  }

  private fun selectRouteByCommissionName(
      routes: List<RoutesModule.SavedRouteInfo>,
      commission: Commission,
  ): String? {
    val best =
      routes
        .map { route -> route to scoreRouteName(route.name, commission) }
        .filter { (_, score) -> score > 0 }
        .maxByOrNull { (_, score) -> score }
        ?: return null
    return best.first.name
  }

  private fun scoreRouteName(routeName: String, commission: Commission): Int {
    val normalizedRouteName = normalizeComparisonText(routeName)
    val normalizedLabel = normalizeComparisonText(commission.label)
    var score = 0

    if (normalizedRouteName.contains(normalizedLabel)) {
      score += 100
    }

    extractCommissionAreaKey(commission.label)?.let { areaKey ->
      if (normalizedRouteName.contains(areaKey)) {
        score += 40
      }
    }

    when (miningCommissionSuffix(commission.target)) {
      "Titanium" -> if (normalizedRouteName.contains("titanium")) score += 20
      "Mithril" -> if (normalizedRouteName.contains("mithril")) score += 20
      else -> {
        val targetKey = normalizeComparisonText(commission.target)
        if (targetKey.isNotBlank() && normalizedRouteName.contains(targetKey)) {
          score += 20
        }
      }
    }

    return score
  }

  private fun selectRouteByType(
      routes: List<RoutesModule.SavedRouteInfo>,
      preferredTypes: Set<String>,
  ): String? {
    return routes.firstOrNull { route -> route.mineTypes.any { it in preferredTypes } }?.name
  }

  private fun preferredRouteMineTypes(c: Commission, requiredTypes: Set<String>): Set<String> {
    val dwarvenMithrilTypes = setOf("Mithril (Gray)", "Mithril (Dark)", "Mithril (Hot)")
    return when (c.target) {
      "Titanium" -> linkedSetOf<String>().apply {
        addAll(dwarvenMithrilTypes)
        add("Titanium")
      }
      "Mithril (Gray)", "Mithril (Dark)", "Mithril (Hot)" -> dwarvenMithrilTypes
      else -> requiredTypes
    }
  }

  private fun preferredMiningMacroMineTypes(c: Commission): String {
    return when (c.target) {
      "Titanium", "Mithril (Gray)", "Mithril (Dark)", "Mithril (Hot)" ->
        "Mithril (Gray), Mithril (Dark), Mithril (Hot)"
      else -> c.mineTypes
    }
  }

  private fun extractCommissionAreaKey(label: String): String? {
    val normalized = normalizeComparisonText(label)
    return MINING_AREA_ALIASES.keys.firstOrNull { normalized.contains(it) }
  }

  private fun resolveCommissionRouteZone(label: String): CommissionRouteZone? {
    val normalized = normalizeComparisonText(label)
    return COMMISSION_ROUTE_ZONE_ALIASES.entries.firstOrNull { (_, aliases) ->
      aliases.any { alias -> normalized.contains(alias) }
    }?.key
  }

  private fun resolveAssignedRouteName(
      commission: Commission,
      availableRoutes: List<RoutesModule.SavedRouteInfo>,
  ): String? {
    if (commission.type != CommissionType.MINING) return null
    val zone = resolveCommissionRouteZone(commission.label) ?: return null
    val assignedName = getAssignedRouteName(zone) ?: return null
    return findRouteInfoByName(availableRoutes.associateBy { it.name }, assignedName)?.name
  }

  private fun routeMatchesZone(routeName: String, zone: CommissionRouteZone): Boolean {
    val normalized = normalizeComparisonText(routeName)
    return COMMISSION_ROUTE_ZONE_ALIASES[zone].orEmpty().any { alias -> normalized.contains(alias) }
  }

  private fun findRouteInfoByName(
      routesByName: Map<String, RoutesModule.SavedRouteInfo>,
      name: String,
  ): RoutesModule.SavedRouteInfo? {
    routesByName[name]?.let { return it }
    return routesByName.entries.firstOrNull { (routeName, _) ->
      routeName.equals(name, ignoreCase = true)
    }?.value
  }

  private fun slotKeyForZone(zone: CommissionRouteZone): String = when (zone) {
    CommissionRouteZone.ROYAL     -> "commission:royal"
    CommissionRouteZone.CLIFFSIDE -> "commission:cliffside"
    CommissionRouteZone.LAVA      -> "commission:lava"
    CommissionRouteZone.RAMP      -> "commission:ramp"
    CommissionRouteZone.UPPER     -> "commission:upper"
  }

  // ---- Tab list area detection ----

  private fun detectAreaFromTabList(): String? {
    val connection = mc.connection ?: return null
    val lines = try {
      resolveTabEntries(connection)
        .mapNotNull { resolveEntryDisplayName(it) }
        .map { ChatFormatting.stripFormatting(it)?.lowercase()?.trim() ?: "" }
        .filter { it.isNotBlank() }
    } catch (_: Exception) { emptyList() }
    for (line in lines) {
      for (area in AREA_NAMES) {
        if (line.contains(area, ignoreCase = true)) return area
      }
    }
    return null
  }

  private fun resolveTabEntries(connection: Any): List<Any> {
    for (name in listOf("listPlayerEntries", "getListedOnlinePlayers", "getOnlinePlayers")) {
      val method = connection.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 } ?: continue
      val result = runCatching { method.invoke(connection) }.getOrNull() ?: continue
      when (result) {
        is Collection<*> -> return result.filterNotNull()
        is Iterable<*>   -> return result.filterNotNull()
      }
    }
    return emptyList()
  }

  private fun resolveEntryDisplayName(entry: Any): String? {
    for (name in listOf("getTabListDisplayName", "tabListDisplayName", "getDisplayName", "displayName")) {
      val method = entry.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 } ?: continue
      val text = coerceText(runCatching { method.invoke(entry) }.getOrNull())
      if (!text.isNullOrBlank()) return text
    }
    for (name in listOf("getProfile", "getGameProfile", "profile")) {
      val method  = entry.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 } ?: continue
      val profile = runCatching { method.invoke(entry) }.getOrNull() ?: continue
      val nm      = profile.javaClass.methods.firstOrNull { it.name == "getName" && it.parameterCount == 0 } ?: continue
      val n       = runCatching { nm.invoke(profile) as? String }.getOrNull()
      if (!n.isNullOrBlank()) return n
    }
    return null
  }

  private fun coerceText(value: Any?): String? {
    if (value == null) return null
    if (value is String) return value
    val m = value.javaClass.methods.firstOrNull { it.name == "getString" && it.parameterCount == 0 }
    val raw = m?.let { runCatching { it.invoke(value) }.getOrNull() }
    return if (raw is String) raw else value.toString()
  }

  // ---- Lifecycle ----

  private fun transition(newState: State) {
    state     = newState
    stateTick = 0
  }

  private fun setStatus(msg: String) {
    statusText.value = msg
  }

  private fun resetCommissionState() {
    commission        = null
    activeCommissions = emptyList()
    activeMiningRouteName = null
    commText.value    = "None"
    openAttempts      = 0
    claimAttempts     = 0
    readAttempts      = 0
  }

  private fun resetMacro() {
    stopWorkModule()
    stopEmissaryNavigation()
    NativePathfinder.stop()
    MovementManager.setMovementLock(false)
    if (pendingUseRelease) { mc.options.keyUse?.setDown(false); pendingUseRelease = false }
    pigeonCooldownTicks = 0
    pendingForgeWarpPigeonLook = false
    forgeWarpPigeonTargetYaw = null
    forgeWarpPigeonLookStableTicks = 0
    state               = State.IDLE
    stateTick           = 0
    resetCommissionState()
    setStatus("Idle")
    areaText.value = "Unknown"
  }
}
