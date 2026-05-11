package org.cobalt.internal.combat

import java.util.UUID
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import java.awt.Color
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.ChatEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.event.impl.render.WorldRenderEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.ModuleCategory
import org.cobalt.api.module.setting.impl.ActionSetting
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.ColorSetting
import org.cobalt.api.module.setting.impl.InfoSetting
import org.cobalt.api.module.setting.impl.InfoType
import org.cobalt.api.module.setting.impl.ModeSetting
import org.cobalt.api.module.setting.impl.KeyBindSetting
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.module.setting.impl.TextSetting
import org.cobalt.api.util.helper.KeyBind
import org.cobalt.api.notification.NotificationManager
import org.cobalt.api.rotation.EasingType
import org.cobalt.api.rotation.RotationExecutor
import org.cobalt.api.rotation.strategy.TimedEaseStrategy
import org.cobalt.api.util.AngleUtils
import org.cobalt.api.util.ChatUtils
import org.cobalt.api.util.InventoryUtils
import org.cobalt.api.util.MouseUtils
import org.cobalt.api.util.getLoreLines
import org.cobalt.api.util.helper.Rotation
import org.cobalt.api.util.render.Render3D
import org.cobalt.api.pathfinder.minecraft.MinecraftPathingRules
import org.cobalt.internal.helper.Config
import org.cobalt.api.pathfinder.jni.MovementProfile
import org.cobalt.api.pathfinder.jni.NativePathfinder
import org.cobalt.api.pathfinder.jni.PathStatus
import org.cobalt.api.util.player.MovementManager
import org.cobalt.api.util.player.MovementOwner
import org.cobalt.internal.pathfinding.PathfindingModule
import org.cobalt.internal.mining.RoutesModule
import org.cobalt.internal.routes.RouteStore
import org.cobalt.internal.routes.RouteType
import org.cobalt.internal.combat.slayer.BlazeSlayerSettings
import org.cobalt.internal.combat.slayer.EndermanSlayerSettings
import org.cobalt.internal.combat.slayer.SlayerLocationSettings
import org.cobalt.internal.combat.slayer.SlayerQuestSignals
import org.cobalt.internal.combat.slayer.SpiderSlayerPhase
import org.cobalt.internal.combat.slayer.SpiderSlayerSettings
import org.cobalt.internal.combat.slayer.VampireSlayerSettings
import org.cobalt.internal.combat.slayer.WolfSlayerSettings
import org.cobalt.internal.combat.slayer.ZombieSlayerSettings
import org.cobalt.internal.etherwarp.EtherwarpLogic

object CombatMacroModule : Module("Combat Macro") {

  override val category = ModuleCategory.COMBAT

  private val mc: Minecraft = Minecraft.getInstance()
  private val rotationStrategy = CombatRotationStrategy()
  private val builtInBlacklistedNames = CombatTargetDenylist.builtInNames
  private val targetMatchNamesCache = HashMap<Int, List<String>>(64)
  private val spiderMeleeWeapon get() = SpiderSlayerSettings.meleeWeapon
  private val spiderBowWeapon get() = SpiderSlayerSettings.bowWeapon
  private val spiderAutoDetectSword get() = SpiderSlayerSettings.autoDetectSword
  private val spiderAutoDetectBow get() = SpiderSlayerSettings.autoDetectBow
  private val spiderPhaseBowCombat get() = SpiderSlayerSettings.hatchlingsPhase
  private val spiderHyperionBeforeShooting get() = SpiderSlayerSettings.hyperionBeforeShooting
  private val spiderEtherwarpEnabled get() = SpiderSlayerSettings.etherwarpEnabled
  private val spiderEtherwarpMinDist get() = SpiderSlayerSettings.etherwarpMinDist
  private val spiderITEnabled get() = SpiderSlayerSettings.instantTransmission
  private val slayerLocation get() = ZombieSlayerSettings.location
  private val zombieDynamicCombat get() = ZombieSlayerSettings.dynamicCombat
  private val zombieSpawnWeapon get() = ZombieSlayerSettings.spawnWeapon
  private val zombieDynamicSword get() = ZombieSlayerSettings.dynamicSword
  private val wolfLocation get() = WolfSlayerSettings.location
  private val wolfIgnorePups get() = WolfSlayerSettings.ignorePups
  private val spiderLocation get() = SpiderSlayerSettings.location
  private val endermanLocation get() = EndermanSlayerSettings.location
  private val endermanVoidWarp get() = EndermanSlayerSettings.voidWarp
  private val endermanDynamicCombat get() = EndermanSlayerSettings.dynamicCombat
  private val emanSpawnWeapon get() = EndermanSlayerSettings.spawnWeapon
  private val emanSpawnBowWeapon get() = EndermanSlayerSettings.spawnBowWeapon
  private val emanSpawnSwordWeapon get() = EndermanSlayerSettings.spawnSwordWeapon
  private val emanDynamicSwapRange get() = EndermanSlayerSettings.dynamicSwapRange
  private val emanHitPhaseStyle get() = EndermanSlayerSettings.hitPhaseStyle
  private val emanHitPhaseBowWeapon get() = EndermanSlayerSettings.hitPhaseBowWeapon
  private val emanHitPhaseSwordWeapon get() = EndermanSlayerSettings.hitPhaseSwordWeapon
  private val emanHitPhaseSneakWhenHitting get() = EndermanSlayerSettings.hitPhaseSneakWhenHitting
  private val emanBossWeapon get() = EndermanSlayerSettings.bossWeapon
  private val vampireLocation get() = VampireSlayerSettings.location
  private val blazeLocation get() = BlazeSlayerSettings.location

  private val enabled = CheckboxSetting(
    "Enabled",
    "Pathfind to a target and attack until stopped.",
    false
  )

  private val toggleKeybind = KeyBindSetting(
    "Toggle Keybind",
    "Key to start/stop the combat macro.",
    KeyBind(-1)
  )

  private val slayerToggleKeybind = KeyBindSetting(
    "Slayer Toggle Keybind",
    "Key to start/stop the slayer macro.",
    KeyBind(-1)
  )

  private val info = InfoSetting(
    "Target",
    "Set a target name (partial match). Leave blank to target nearest mob.",
    InfoType.INFO
  )

  private val targetName = TextSetting(
    "Target Name",
    "Entity name to target (partial match).",
    ""
  )

  private val cryptZombieSlayer = CheckboxSetting(
    "Crypt Zombie Slayer",
    "Crypt Slayer flow: farm zombies/ghouls until Slayer boss is detected in tab, then focus boss.",
    false
  )

  private val slayerStatus = TextSetting(
    "Slayer Status",
    "Current Crypt Slayer state.",
    "Off"
  )

  private val slayerType = ModeSetting(
    "Slayer Type",
    "Which slayer quest type to run.",
    0,
    arrayOf("Zombie", "Wolf", "Spider", "Enderman", "Vampire", "Blaze")
  )

  private val loadoutSlayerType = ModeSetting(
    "Loadout Slayer Type",
    "Choose which slayer loadout to edit in the visual loadout tab.",
    2,
    arrayOf("Zombie", "Wolf", "Spider", "Enderman", "Vampire", "Blaze")
  )

  private val slayerTier = SliderSetting(
    "Slayer Tier",
    "Quest tier to buy (1 = cheapest, 5 = hardest).",
    1.0,
    1.0,
    5.0,
    step = 1.0
  )

  private val slayerAutoWarp = CheckboxSetting(
    "Auto Warp",
    "Warp to the farming area after buying a new quest.",
    true
  )

  private val slayerBossNotification = CheckboxSetting(
    "Boss Spawn Notification",
    "Show a HUD warning when the slayer boss spawns.",
    true
  )

  private val slayerBossEsp = CheckboxSetting(
    "Boss / MiniBoss ESP",
    "Highlight slayer bosses and minibosses while the slayer macro is active.",
    true
  )

  private val slayerEspTargets = ModeSetting(
    "ESP Targets",
    "Choose whether the slayer ESP highlights bosses, minibosses, or both.",
    2,
    arrayOf("Boss", "MiniBoss", "Both")
  )

  // Per-type walkback route backing settings (persisted; not rendered directly — shown via action buttons below)
  private val slayerEspStyle = ModeSetting(
    "ESP Style",
    "Glow uses the vanilla-style mob outline. Box keeps the older layered box ESP.",
    0,
    arrayOf("Glow", "Box")
  )

  private val slayerOwnBossOnly = CheckboxSetting(
    "Own Boss Only",
    "Only target and highlight slayer bosses whose Spawned by owner matches you.",
    true
  )

  private val slayerHighlightMiniBosses = CheckboxSetting(
    "Highlight MiniBosses",
    "Highlight slayer minibosses in addition to the main boss.",
    true
  )

  private val slayerBossEspColor = ColorSetting(
    "Boss Color",
    "Outline color used for the slayer boss.",
    0xFFFFFFFF.toInt()
  )

  private val slayerMiniBossEspColor = ColorSetting(
    "MiniBoss Color",
    "Outline color used for standard slayer minibosses.",
    0xFFFFFFFF.toInt()
  )

  private val slayerHighTierMiniEspColor = ColorSetting(
    "High Tier Mini Color",
    "Outline color used for higher-tier slayer minibosses.",
    0xFFFFD966.toInt()
  )

  private val slayerBlazeAttunementColors = CheckboxSetting(
    "Blaze Attunement Color",
    "Color Blaze Slayer outlines by attunement instead of the generic ESP colors.",
    true
  )

  private fun walkbackRouteTypeName(type: Int): String = when (type) {
    0 -> "Zombie"
    1 -> "Wolf"
    2 -> "Spider"
    3 -> "Enderman"
    4 -> "Vampire"
    5 -> "Blaze"
    else -> "Zombie"
  }

  private fun slotKeyForType(type: Int): String = when (type) {
    0 -> "patrol:zombie"
    1 -> "patrol:wolf"
    2 -> "patrol:spider"
    3 -> "patrol:enderman"
    4 -> "patrol:vampire"
    5 -> "patrol:blaze"
    else -> "patrol:zombie"
  }

  private fun currentSlotKey(): String = slotKeyForType(slayerType.value)

  private fun isAssignedPatrolTravelRoute(routeName: String): Boolean {
    val trimmed = routeName.trim()
    if (trimmed.isEmpty()) return false
    return RouteStore
      .listByType(RouteType.PATROL)
      .any { it.name.equals(trimmed, ignoreCase = true) }
  }

  fun getSelectedSlayerType(): Int = slayerType.value

  fun getSlayerTypeName(type: Int): String =
    slayerType.options.getOrElse(type) { "Slayer" }

  fun isSlayerMacroActiveFor(type: Int): Boolean =
    enabled.value && cryptZombieSlayer.value && slayerType.value == type

  fun startSlayerMacro(type: Int) {
    val safeType = type.coerceIn(slayerType.options.indices)
    if (cryptZombieSlayer.value && slayerType.value != safeType) {
      stopMacro()
    }
    slayerType.value = safeType
    cryptZombieSlayer.value = true
    automationBypassWhitelist = false
    whitelistOnly.value = false
    enabled.value = true
  }

  fun stopSlayerMacro(type: Int? = null) {
    if (type != null && slayerType.value != type) return
    cryptZombieSlayer.value = false
    enabled.value = false
    stopMacro()
  }

  fun getWalkbackRouteLabelForType(type: Int): String = "${walkbackRouteTypeName(type)} Patrol Route"

  fun getWalkbackRouteNameForType(type: Int): String = RouteStore.getSlotRoute(slotKeyForType(type)) ?: "None"

  fun setWalkbackRouteNameForType(type: Int, routeName: String) {
    RouteStore.setSlotRoute(slotKeyForType(type), routeName.ifBlank { null })
  }

  private val searchRange = SliderSetting(
    "Search Range",
    "Max distance to search for targets.",
    32.0,
    8.0,
    96.0
  )

  private val minCps = SliderSetting(
    "Min CPS",
    "Minimum clicks per second.",
    6.0,
    1.0,
    20.0
  )

  private val maxCps = SliderSetting(
    "Max CPS",
    "Maximum clicks per second.",
    10.0,
    1.0,
    20.0
  )

  private val attackRange = SliderSetting(
    "Attack Range",
    "Distance to start attacking.",
    3.2,
    2.0,
    6.0
  )

  private val chaseStopBuffer = SliderSetting(
    "Chase Stop Buffer",
    "Stop pathing this far before attack range to reduce movement jitter.",
    1.1,
    0.0,
    3.0
  )

  private val stayNearStart = CheckboxSetting(
    "Stay Near Start",
    "Keep combat inside the general area where macro was enabled.",
    true
  )

  private val startAreaRadius = SliderSetting(
    "Start Area Radius",
    "Max horizontal distance from start area for combat targets.",
    24.0,
    8.0,
    96.0
  )

  private val autoHeal = CheckboxSetting(
    "Auto Heal",
    "Auto-use a healing item when health is low.",
    true
  )

  private val autoWandOfAtonement = CheckboxSetting(
    "Wand Of Atonement",
    "Use Wand of Atonement for auto-heal. Auto-enables when the item is detected in hotbar.",
    false
  )

  private val alwaysUseWandOfAtonement = CheckboxSetting(
    "Always Use Wand Of Atonement",
    "Use Wand of Atonement every 7 seconds while the combat macro is running.",
    false
  )

  private val autoZombieSword = CheckboxSetting(
    "Zombie Sword",
    "Use Zombie Sword variants for auto-heal. Auto-enables when the item is detected in hotbar.",
    false
  )

  private val autoHyperion = CheckboxSetting(
    "Hyperion",
    "Use Hyperion and other Wither blades for auto-heal. Auto-enables when one is detected in hotbar.",
    false
  )

  private val autoOverflux = CheckboxSetting(
    "Overflux",
    "Use Overflux when the Slayer boss spawns. Auto-enables when the item is detected in hotbar.",
    false
  )

  private val autoRagnarok = CheckboxSetting(
    "Ragnarok",
    "Use Ragnarok when the Slayer boss spawns. Auto-enables when the item is detected in hotbar.",
    false
  )

  private val autoReaperScythe = CheckboxSetting(
    "Reaper Scythe",
    "Use Reaper Scythe during Enderman Slayer hit-shield phases. Auto-enables when detected for Enderman Slayer.",
    false
  )

  private val oneTapMode = CheckboxSetting(
    "One Tap Mode",
    "Keep moving through mobs - don't stop at each kill. Switch immediately when target dies.",
    false
  )

  private val combatMode = ModeSetting(
    "Combat Mode",
    "Melee: left-click. Bow: right-click ranged with drop aim. Mage: right-click projectile with lead aim.",
    0,
    arrayOf("Melee", "Bow", "Mage")
  )

  private val sepDefaultSlayerWeapons = InfoSetting("Default Roles", "", InfoType.SEPARATOR)

  private val slayerMeleeWeapon = TextSetting(
    "Slayer Melee Weapon",
    "Comma-separated hotbar keywords in priority order for default slayer melee combat.",
    "sword, blade, scythe, katana, saber, cleaver, axe, rapier, claymore, halberd, dagger, falchion"
  )

  private val slayerBowWeapon = TextSetting(
    "Slayer Bow Weapon",
    "Comma-separated hotbar keywords in priority order for default slayer bow combat.",
    "terminator, juju, shortbow, bow"
  )

  private val slayerMageWeapon = TextSetting(
    "Slayer Mage Weapon",
    "Comma-separated hotbar keywords in priority order for default slayer mage combat.",
    "staff, wand, sceptre, scepter, scythe"
  )

  private val loadoutPowerOrbChoice = ModeSetting(
    "Loadout Power Orb Choice",
    "Choose which power orb item the visual loadout should use.",
    0,
    arrayOf("Overflux Power Orb", "Plasmaflux Power Orb", "SOS Flare", "Mana Flux Power Orb")
  )

  private val swordKeepDistance = SliderSetting(
    "Sword Keep Distance",
    "Preferred stand-off distance in melee mode. Lower = closer. 0 = close in fully.",
    1.8,
    0.0,
    6.0,
    step = 0.1
  )

  private val combatDistance = SliderSetting(
    "Combat Distance",
    "Target distance to maintain from the mob.",
    4.0,
    1.0,
    20.0,
    0.5,
  )

  private val distanceTolerance = SliderSetting(
    "Distance Tolerance",
    "Acceptable deviation from Combat Distance before correcting position.",
    1.0,
    0.5,
    5.0,
    0.5,
  )

  private val slayerSwordKeepDistance = SliderSetting(
    "Slayer Sword Keep Distance",
    "Preferred stand-off distance in melee mode while the Slayer macro is active. Lower = closer. 0 = close in fully.",
    0.0,
    0.0,
    6.0,
    step = 0.1
  )

  private val bowMinRange = SliderSetting(
    "Bow Min Range",
    "Preferred stand-off distance in bow mode. 0 = use Attack Range.",
    8.0,
    0.0,
    20.0
  )

  private val bowElevationPerBlock = SliderSetting(
    "Bow Elevation /block",
    "Degrees to aim upward per block of distance to compensate for arrow drop. Tune to your bow.",
    0.15,
    0.0,
    1.0,
    step = 0.01
  )

  private val mageElevationPerBlock = SliderSetting(
    "Mage Elevation /block",
    "Degrees to aim upward per block of distance for slower projectiles. 0 for instant/no-drop weapons.",
    0.0,
    0.0,
    1.0,
    step = 0.01
  )

  private val autoSwordOfBadHealth = CheckboxSetting(
    "Sword Of Bad Health",
    "Use Sword of Bad Health during the Slayer boss fight. Auto-enables when the item is detected in hotbar.",
    false
  )

  private val slayerLoadoutBuilder = SlayerLoadoutSetting(
    loadoutSlayerType = loadoutSlayerType,
    spiderAutoDetectSword = spiderAutoDetectSword,
    spiderAutoDetectBow = spiderAutoDetectBow,
    spiderMeleeWeapon = spiderMeleeWeapon,
    spiderBowWeapon = spiderBowWeapon,
    powerOrbChoice = loadoutPowerOrbChoice,
    autoOverflux = autoOverflux,
    autoWandOfAtonement = autoWandOfAtonement,
    autoRagnarok = autoRagnarok,
    autoSwordOfBadHealth = autoSwordOfBadHealth,
    autoZombieSword = autoZombieSword,
    autoHyperion = autoHyperion
  )

  private val healAtHealth = SliderSetting(
    "Heal At Health",
    "Use heal item at or below this health.",
    10.0,
    1.0,
    20.0
  )

  private val stuckTicksSetting = SliderSetting(
    "Stuck Ticks",
    "Ticks without movement before warp hub.",
    80.0,
    20.0,
    200.0
  )

  private val warpOnStuck = CheckboxSetting(
    "Warp Hub On Stuck",
    "Warp to hub if stuck while macro is active.",
    true
  )

  private val requireLos = CheckboxSetting(
    "Require LOS",
    "Only attack when you have line of sight.",
    true
  )


  private val aimTolerance = SliderSetting(
    "Aim Tolerance",
    "Max yaw/pitch error before attacking.",
    15.0,
    4.0,
    45.0
  )

  private val minAttackCooldown = SliderSetting(
    "Min Attack Cooldown",
    "Minimum vanilla cooldown needed before an attack.",
    0.2,
    0.0,
    1.0
  )

  private val stuckRepathTries = SliderSetting(
    "Stuck Repath Tries",
    "Repath attempts before optional hub warp.",
    2.0,
    0.0,
    8.0
  )

  private val autoLearnLastKill = CheckboxSetting(
    "Auto Learn Last Kill",
    "Set last killed enemy as target and whitelist entry.",
    true
  )

  private val whitelistOnly = CheckboxSetting(
    "Whitelist Only",
    "Only target entities matching learned whitelist names.",
    true
  )

  private val learnedWhitelistText = TextSetting(
    "Learned Whitelist",
    "Auto-learned target names from kills.",
    "EMPTY"
  )

  private val lastKillText = TextSetting(
    "Last Kill",
    "Most recently learned enemy name.",
    "-"
  )

  val isActive: Boolean get() = enabled.value
  val isRunning: Boolean get() = enabled.value
  val isSlayerHudVisible: Boolean get() = cryptZombieSlayer.value || slayerModeEnabled
  val modeDisplay: String get() = combatModeDisplayName(currentHudCombatMode())
  val slayerDisplay: String get() = if (cryptZombieSlayer.value) slayerStatus.value else "Disabled"
  val slayerQuestLevelDisplay: String
    get() = if (!isSlayerHudVisible) "--" else trackedSlayerQuestLabel()
  val slayerQuestStateDisplay: String
    get() = if (!isSlayerHudVisible) "--" else trackedSlayerQuestHudStageLabel()
  val slayerKillsLeftDisplay: String
    get() = if (!isSlayerHudVisible) "--" else (currentSlayerKillsLeft()?.toString() ?: "--")
  val slayerKillsPerHourDisplay: String
    get() = if (!isSlayerHudVisible) "--" else formatSessionRateDisplay(slayerSessionMobKills)
  val slayerQuestsCompletedDisplay: String
    get() = if (!isSlayerHudVisible) "--" else slayerSessionQuestCompletions.toString()
  val slayerQuestsPerHourDisplay: String
    get() = if (!isSlayerHudVisible) "--" else formatSessionRateDisplay(slayerSessionQuestCompletions)
  val slayerQuestsFailedDisplay: String
    get() = if (!isSlayerHudVisible) "--" else slayerSessionQuestFailures.toString()
  val slayerQuestFailsPerHourDisplay: String
    get() = if (!isSlayerHudVisible) "--" else formatSessionRateDisplay(slayerSessionQuestFailures)
  val statusDisplay: String
    get() =
      when {
        !enabled.value -> "Off"
        cryptZombieSlayer.value -> slayerStatus.value
        currentTargetId != null && startedPath && nativeActive() -> "Pathing To Target"
        currentTargetId != null -> "Engaging Target"
        startedPath && nativeActive() -> "Pathing"
        else -> "Searching"
      }
  val targetDisplay: String
    get() {
      val activeTargetName = resolveCurrentTargetName()
      if (!activeTargetName.isNullOrBlank()) {
        return activeTargetName
      }
      if (cryptZombieSlayer.value) {
        if (slayerBossActive) return "Slayer Boss"
        val activeName = resolveCurrentTargetName()
        if (!activeName.isNullOrBlank()) {
          val norm = normalizeNameForMatch(activeName)
          if (isSlayerPriorityMobName(norm)) return "\u2B50 $activeName"
        }
        return when (slayerType.value) {
          0 -> "Zombie / Ghoul"
          1 -> "Wolf"
          2 -> "Spider"
          3 -> "Voidling"
          4 -> "Vampire"
          5 -> "Blaze"
          else -> "Mob"
        }
      }
      val filter = targetName.value.trim()
      return if (filter.isNotEmpty()) filter else "Nearest Mob"
    }
  val targetHealthDisplay: String
    get() {
      val target = resolveCurrentTarget() ?: return "-- / --"
      return "${formatHudHealth(target.health)} / ${formatHudHealth(target.maxHealth)}"
    }
  val targetHealthRatio: Float
    get() {
      val target = resolveCurrentTarget() ?: return 0f
      val maxHealth = target.maxHealth.coerceAtLeast(1f)
      return (target.health.coerceAtLeast(0f) / maxHealth).coerceIn(0f, 1f)
    }

  private fun currentHudCombatMode(): Int {
    val target = resolveCurrentTarget()
    if (target != null) return effectiveCombatMode(target)
    if (!cryptZombieSlayer.value) return combatMode.value
    return fallbackSlayerCombatModeForHud()
  }

  private fun fallbackSlayerCombatModeForHud(): Int {
    if (shouldUseSpiderPhaseBowCombat() && slayerBossActive && isSpiderBossHatchlingsPhaseActive()) {
      return 1
    }
    if (shouldUseEndermanDynamicCombat()) {
      val boss = resolveNearestSlayerBoss()
      if (boss != null) {
        return if (isEndermanBossHitPhase(boss)) effectiveEndermanHitPhaseCombatMode() else 0
      }
      return when (emanSpawnWeapon.value) {
        EMAN_SPAWN_WEAPON_ENDERMAN_SWORD -> 0
        else -> 1
      }
    }
    return combatMode.value
  }

  private fun combatModeDisplayName(mode: Int): String =
    when (mode) {
      0 -> "Melee"
      1 -> "Bow"
      2 -> "Mage"
      else -> "Unknown"
    }

  fun startForAutomation(mobName: String) {
    cryptZombieSlayer.value = false
    targetName.value = mobName.trim()
    automationBypassWhitelist = true
    whitelistOnly.value = false
    mc.player?.takeIf { prefersDrillForTargetName(targetName.value) }?.let { player ->
      ensureWalkerDrillSelected(player, mc.level?.gameTime ?: 0L, targetName.value)
    }
    enabled.value = true
  }

  fun stopForAutomation() {
    automationBypassWhitelist = false
    enabled.value = false
  }

  fun matchesAutomationTarget(living: LivingEntity, rawTargetName: String): Boolean {
    val filter = normalizeNameForMatch(rawTargetName)
    if (filter.isBlank()) return false
    return targetMatchNames(living).any { name -> name.contains(filter) }
  }

  private var lastTargetPos: BlockPos? = null
  private var lastMoveX = 0.0
  private var lastMoveY = 0.0
  private var lastMoveZ = 0.0
  private var stuckTicks = 0
  private var nextAttackNs = 0L
  private var startedPath = false
  private var backingAway = false
  private var currentTargetId: UUID? = null
  private var automationBypassWhitelist = false
  private var closeChaseActive = false
  private var closeChaseTargetId: UUID? = null
  private var lastPathStartTick = 0L
  private var killCandidateId: UUID? = null
  private var killCandidateName: String? = null
  private var killCandidateWasSlayerMob = false
  private var killCandidateWasSlayerBoss = false
  private var killCandidateExpiresTick = 0L
  private var killCandidateAttackTick = 0L
  private val learnedWhitelist = LinkedHashSet<String>()
  private var lastSyncedWhitelistRaw = ""
  private var drillWarnTick = 0L
  private var lastHealUseTick = 0L
  private var lastAlwaysWandUseTick = 0L
  private var stuckRepathCount = 0
  private var startAreaOrigin: BlockPos? = null
  private var pendingHealRelease = false
  private var pendingHealRestoreSlot = -1
  private var pendingOverfluxLookDownTicks = 0
  private var pendingOverfluxRecoverLookTicks = 0
  private var pendingOverfluxRestoreSlot = -1
  private var slayerBossActive = false
  private var slayerSessionStartMs = 0L
  private var slayerTrackedQuestActive = false
  private var slayerTrackedQuestType = -1
  private var slayerTrackedQuestTier = 0
  private var slayerTrackedQuestProgressKills = 0
  private var slayerTrackedQuestTargetKills = 0
  private var slayerTrackedQuestSawMobKill = false
  private var slayerTrackedQuestBossSeen = false
  private var slayerTrackedQuestCompletionPendingRestart = false
  private var slayerSessionMobKills = 0
  private var slayerSessionQuestCompletions = 0
  private var slayerSessionQuestFailures = 0
  private var slayerBossLastSeenTick = 0L
  private var slayerOverfluxUsedThisBoss = false
  private var slayerRagnarokUsedThisBoss = false
  private var slayerReaperScytheUsedThisBoss = false
  private var slayerPrimordialBeltUsedThisBoss = false
  private var slayerLastBadHealthUseTick = -1L
  private var slayerLastOverfluxUseTick = -1L
  private var slayerLastRagnarokUseTick = -1L
  private var slayerLastReaperScytheUseTick = -1L
  private var slayerLastTabScanTick = -1L
  private var slayerTabCache: List<String> = emptyList()
  private var slayerNeedsQuestRestart = false
  private var slayerPendingTierSelection = false
  private var slayerQuestReady = false
  private var slayerRagnarokUsedPreBoss = false
  private var slayerAutoDetected = false
  private var slayerDetectStartTick = -1L
  private var slayerNeedsQuestClaim = false
  private var slayerNeedsWalkback = false
  private var slayerWalkbackJustFarm = false  // true = walkback is walk-IN to farm area (skip quest restart)
  private var slayerWalkInDelayUntilTick = -1L  // wait for warp to finish before starting walkin route
  private var slayerWalkInJustFarm = true       // justFarm value to use when the walkin-delay expires
  private var slayerDeathRespawnPending = false  // player died; trigger walkback once they respawn
  private var lastKnownLevel: net.minecraft.client.multiplayer.ClientLevel? = null  // server-switch detection
  private var slayerBossLastPos: net.minecraft.world.phys.Vec3? = null
  // Cached per-tick: entity UUID → highlight type, used by renderSlayerHighlights to avoid O(N²) per frame.
  private var slayerHighlightCache: Map<UUID, SlayerHighlightState> = emptyMap()
  private val slayerGlowingEntities = mutableMapOf<UUID, String>()
  private var slayerClaimStartTick = -1L
  private var slayerClaimLastClickTick = -1L
  private var slayerLastBatphoneAttemptTick = -1L
  private var slayerLastBatphoneUseTick = -1L
  private var slayerLastGuiActionTick = -1L
  private var slayerWarnNoBatphoneTick = -1L
  private var slayerModeEnabled = false
  private var slayerQuestStateGraceUntilTick = -1L
  private var spiderHyperionBeforeShootUsed = false
  private var pendingSpiderHyperionLookDownTicks = 0
  private var pendingSpiderHyperionRestoreSlot = -1
  private var wolfPupsPhaseUntilTick = -1L
  /** True once the player has physically been confirmed inside the crypt since the last warp-hub.
   *  The outside-crypt recovery check only fires when this is true, preventing the startup spam
   *  loop where the player warps to hub and the check immediately re-triggers before they arrive. */
  private var slayerEnteredCrypt = false
  /** True once the player has been confirmed near a patrol point since the last macro start or walkback.
   *  Used to detect area-exit (teleport/push) and trigger walkback for non-crypt slayer types. */
  private var enteredFarmingArea = false
  private var wandWasInHotbar = false
  private var hyperionWasInHotbar = false
  private var zombieSwordWasInHotbar = false
  private var overfluxWasInHotbar = false
  private var ragnarokWasInHotbar = false
  private var reaperScytheWasInHotbar = false
  private var badHealthWasInHotbar = false
  private var emanHitPhaseBossWeaponPrimed = false
  private var emanHitPhaseSneakApplied = false
  private var emanReaperScytheUsedThisHitPhase = false
  private var emanLaserPhaseActive = false
  private var emanLaserPhaseStartTick = -1L
  private var slayerRagnarokUsedForLaserPhase = false
  private val overfluxLookDownStrategy = TimedEaseStrategy(EasingType.LINEAR, EasingType.LINEAR, 220L)
  private val spiderHyperionLookDownStrategy = TimedEaseStrategy(EasingType.LINEAR, EasingType.LINEAR, 180L)
  // Spider combat etherwarp state
  private var spiderWarpStage = -1            // -1 = idle, 0 = rotating, 1 = sneak+verify, 2 = release
  private var spiderWarpAimPoint: net.minecraft.world.phys.Vec3? = null
  private var spiderWarpRestoreSlot = -1
  private var spiderWarpCooldownUntilTick = -1L
  private var spiderWarpStageTick = -1L

  init {
    CombatMacroUi.register(
      this,
      CombatMacroUiSettings(
        enabled,
        toggleKeybind,
        info,
        targetName,
        cryptZombieSlayer,
        slayerToggleKeybind,
        slayerStatus,
        slayerType,
        loadoutSlayerType,
        slayerTier,
        slayerAutoWarp,
        slayerBossNotification,
        slayerBossEsp,
        slayerEspTargets,
        slayerEspStyle,
        slayerOwnBossOnly,
        slayerHighlightMiniBosses,
        slayerBossEspColor,
        slayerMiniBossEspColor,
        slayerHighTierMiniEspColor,
        slayerBlazeAttunementColors,
        searchRange,
        minCps,
        maxCps,
        attackRange,
        chaseStopBuffer,
        stayNearStart,
        startAreaRadius,
        oneTapMode,
        combatMode,
        sepDefaultSlayerWeapons,
        slayerMeleeWeapon,
        slayerBowWeapon,
        slayerMageWeapon,
        autoReaperScythe,
        swordKeepDistance,
        combatDistance,
        distanceTolerance,
        slayerSwordKeepDistance,
        bowMinRange,
        bowElevationPerBlock,
        mageElevationPerBlock,
        autoHeal,
        autoWandOfAtonement,
        alwaysUseWandOfAtonement,
        autoZombieSword,
        autoHyperion,
        autoOverflux,
        autoRagnarok,
        autoSwordOfBadHealth,
        slayerLoadoutBuilder,
        loadoutPowerOrbChoice,
        healAtHealth,
        stuckTicksSetting,
        warpOnStuck,
        requireLos,
        aimTolerance,
        minAttackCooldown,
        stuckRepathTries,
        autoLearnLastKill,
        whitelistOnly,
        learnedWhitelistText,
        lastKillText,
      )
    )
    EventBus.register(this)
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
    targetMatchNamesCache.clear()
    if (toggleKeybind.value.isPressed()) {
      enabled.value = !enabled.value
    }
    if (slayerToggleKeybind.value.isPressed()) {
      cryptZombieSlayer.value = !cryptZombieSlayer.value
    }

    // Server-switch detection: if level instance changes while macro is active, stop pathfinding
    // and queue a walkback so the player returns to the farming area on reconnect.
    val currentLevel = mc.level
    if (currentLevel !== lastKnownLevel) {
      clearSlayerGlowState(lastKnownLevel)
      if (lastKnownLevel != null && (enabled.value || slayerModeEnabled)) {
        val wasSlayerActive = slayerModeEnabled
        val hasWalkbackRoute = !RouteStore.getSlotRoute(currentSlotKey()).isNullOrBlank()
        stopMacro()
        enteredFarmingArea = false
        if (wasSlayerActive && hasWalkbackRoute) {
          slayerNeedsWalkback = true
          slayerWalkbackJustFarm = false
          ChatUtils.sendMessage("Combat macro: server change detected, will walk back on reconnect.")
        } else {
          ChatUtils.sendMessage("Combat macro stopped: server change detected.")
        }
      }
      lastKnownLevel = currentLevel
    }

    // Release macro-managed keys before reevaluating combat state this tick.
    releaseEmanHitPhaseSneak()

    // Release heal key and restore slot from previous heal use
    if (pendingHealRelease) {
      mc.options.keyUse?.setDown(false)
      pendingHealRelease = false
    }
    if (pendingHealRestoreSlot >= 0) {
      InventoryUtils.holdHotbarSlot(pendingHealRestoreSlot)
      pendingHealRestoreSlot = -1
    }

    val player = mc.player ?: run {
      clearPendingOverfluxPlacement()
      clearPendingSpiderHyperionBeforeShooting()
      return
    }
    if (pendingOverfluxLookDownTicks > 0) {
      pendingOverfluxLookDownTicks--
      MovementManager.clearForcedMovement()
      RotationExecutor.rotateTo(Rotation(player.yRot, OVERFLUX_PLACE_PITCH), overfluxLookDownStrategy)
      if (pendingOverfluxLookDownTicks <= 0) {
        RotationExecutor.stopRotating()
        mc.options.keyUse?.setDown(true)
        pendingHealRelease = true
        pendingHealRestoreSlot = pendingOverfluxRestoreSlot
        pendingOverfluxRecoverLookTicks = OVERFLUX_RECOVER_LOOK_TICKS
        pendingOverfluxRestoreSlot = -1
      }
      return
    }
    if (pendingSpiderHyperionLookDownTicks > 0) {
      if (!shouldUseSpiderHyperionBeforeShooting() || !isSpiderBossHatchlingsPhaseActive()) {
        clearPendingSpiderHyperionBeforeShooting()
      } else {
        pendingSpiderHyperionLookDownTicks--
        MovementManager.clearForcedMovement()
        RotationExecutor.rotateTo(Rotation(player.yRot, SPIDER_HYPERION_BEFORE_SHOOT_PITCH), spiderHyperionLookDownStrategy)
        if (pendingSpiderHyperionLookDownTicks <= 0) {
          RotationExecutor.stopRotating()
          mc.options.keyUse?.setDown(true)
          pendingHealRelease = true
          pendingHealRestoreSlot = pendingSpiderHyperionRestoreSlot
          pendingSpiderHyperionRestoreSlot = -1
          spiderHyperionBeforeShootUsed = true
        }
        return
      }
    }
    if (pendingOverfluxRecoverLookTicks > 0) {
      pendingOverfluxRecoverLookTicks--
      applyOverfluxRecoverLook(player)
    }
    syncAutoItemToggles(player)
    if (startedPath && !nativeActive()) {
      startedPath = false
      lastTargetPos = null
    }
    val suppressMaddoxMovement = shouldSuppressMovementForMaddox(mc.level?.gameTime ?: -1L)
    if (suppressMaddoxMovement) {
      holdStillForMaddoxCall()
    }
    if (startedPath && nativeActive() && !CombatPatrolModule.patrolOwnsPathfinder && !slayerNeedsWalkback && !suppressMaddoxMovement) {
      val cmd = NativePathfinder.tick()
      if (cmd != null) cmd.applyToPlayer()
      else {
        when (NativePathfinder.status) {
          PathStatus.IDLE, PathStatus.ARRIVED, PathStatus.FAILED -> MovementManager.clearForcedMovement()
          else -> Unit
        }
      }
    }
    syncLearnedWhitelistFromSetting()
    if (cryptZombieSlayer.value && !slayerModeEnabled && enabled.value) {
      val spiderLoadoutError = spiderSlayerLoadoutError(player)
      if (spiderLoadoutError != null) {
        cryptZombieSlayer.value = false
        enabled.value = false
        stopMacro()
        ChatUtils.sendMessage(spiderLoadoutError)
        return
      }
      slayerModeEnabled = true
      beginSlayerSession()
      beginSlayerQuestDetection(mc.level?.gameTime ?: -1L)
      slayerRagnarokUsedPreBoss = false
      slayerEnteredCrypt = false
      enteredFarmingArea = false
      slayerLastBatphoneAttemptTick = -1L
      slayerLastBatphoneUseTick = -1L
      slayerLastGuiActionTick = -1L
      // If not already in the farming area, navigate there via walkback route.
      if (slayerLocation.value == 1) {
        val initPos = mc.player?.blockPosition()
        val alreadyInCrypt = initPos != null && CRYPT_PATROL_WAYPOINTS.any { wp ->
          val dx = wp.x - initPos.x; val dz = wp.z - initPos.z
          dx * dx + dz * dz < CRYPT_PROXIMITY_RANGE_SQ
        }
        if (!alreadyInCrypt) {
          slayerEnteredCrypt = false
          triggerWalkToFarmArea(justFarm = true)
        } else {
          slayerEnteredCrypt = true
        }
      } else if (!RouteStore.getSlotRoute(currentSlotKey()).isNullOrBlank()) {
        // Walk to farm if player is not already there.
        // If patrol points are configured, use them for proximity; if not, assume not at farm
        // (since we can't confirm location and have a route configured, use it).
        val initPos = mc.player?.blockPosition()
        val nearFarm = initPos != null
          && CombatPatrolModule.patrolPoints.isNotEmpty()
          && CombatPatrolModule.patrolPoints.any { p ->
            val dx = p.x - initPos.x; val dz = p.z - initPos.z
            dx * dx + dz * dz < GRAVEYARD_PROXIMITY_RANGE_SQ
          }
        if (!nearFarm) triggerWalkToFarmArea(justFarm = true)
      }
    }
    if (!cryptZombieSlayer.value) {
      slayerModeEnabled = false
    }
    if (!cryptZombieSlayer.value && !automationBypassWhitelist && !whitelistOnly.value) {
      whitelistOnly.value = true
    }
    if (cryptZombieSlayer.value && whitelistOnly.value) {
      whitelistOnly.value = false
    }
    if (!enabled.value) {
      clearSlayerGlowState(currentLevel)
      stopMacro()
      return
    }

    if (!cryptZombieSlayer.value || !slayerBossEsp.value || slayerEspStyle.value != SLAYER_ESP_STYLE_GLOW) {
      clearSlayerGlowState(currentLevel)
    }

    val level = mc.level ?: return
    PathfindingModule.ensureEnabledForAutomation("combat macro")
    updateKillTracking(level)
    if (cryptZombieSlayer.value) {
      if (!slayerAutoDetected) tryAutoDetectSlayerQuest(level)
      updateSlayerBossState(level.gameTime)
      if (slayerNeedsQuestClaim) {
        if (handleClaimSlayerQuest(player, level)) return
      }
      // Warp-then-walkback delay: issued by triggerWalkToFarmArea when a pre-warp is needed
      // (e.g. spider slayer warps to den before running the walkback route).
      if (slayerWalkInDelayUntilTick >= 0L) {
        if (level.gameTime < slayerWalkInDelayUntilTick) {
          slayerStatus.value = "Warping..."
          return
        }
        slayerWalkInDelayUntilTick = -1L
        triggerWalkToFarmArea(justFarm = slayerWalkInJustFarm, skipPreWarp = true)
      }
      if (slayerNeedsWalkback) {
        // Walkback owns the pathfinder - prevent CombatMacroModule from double-ticking it.
        startedPath = false
        lastTargetPos = null
        if (RoutesModule.isRunning) {
          // Boss spawned during walkback - interrupt immediately and engage.
          if (slayerBossActive) {
            slayerNeedsWalkback = false
            startAreaOrigin = null
            slayerStatus.value = "Boss! Engaging..."
            // fall through to combat resolution below
          } else {
            slayerStatus.value = if (slayerWalkbackJustFarm) "Walking to Farm..." else "Walking Back..."
            currentTargetId = null
          }
        } else {
          slayerNeedsWalkback = false
          startAreaOrigin = null
          // Set enteredFarmingArea based on actual proximity rather than always resetting to false.
          // Blindly clearing it here means the area-exit detection stays dead if the macro's
          // quest-restart logic blocks the per-tick proximity check for even a brief window
          // (e.g., while calling the batphone), leaving the player unrecovered if displaced.
          val walkbackEndPos = mc.player?.blockPosition()
          enteredFarmingArea = slayerLocation.value != 1
            && walkbackEndPos != null
            && CombatPatrolModule.patrolPoints.isNotEmpty()
            && CombatPatrolModule.patrolPoints.any { p ->
              val dx = p.x - walkbackEndPos.x; val dz = p.z - walkbackEndPos.z
              dx * dx + dz * dz < GRAVEYARD_PROXIMITY_RANGE_SQ
            }
          if (slayerWalkbackJustFarm) {
            slayerWalkbackJustFarm = false
            // Walkback complete - ensure quest detection runs for fresh quest
            if (shouldUseAutoSlayerForCurrentType() && slayerTrackedQuestCompletionPendingRestart) {
              beginAutoSlayerQuestDetection(mc.level?.gameTime ?: -1L)
            } else {
              beginSlayerQuestDetection(mc.level?.gameTime ?: -1L)
            }
          } else {
            queueSlayerQuestRestart(countFailure = false)
          }
        }
      }
      if (handleMaddoxGui(level.gameTime)) {
        return
      }
      if (tryRestartSlayerQuest(player, level.gameTime)) {
        return
      }
      // Use Ragnarok pre-emptively when quest is ready (boss spawns on next kill)
      if (slayerQuestReady && !slayerBossActive && !slayerRagnarokUsedPreBoss && autoRagnarok.value
        && level.gameTime - slayerLastRagnarokUseTick >= SLAYER_RAGNAROK_COOLDOWN_TICKS) {
        if (useHotbarUtilityItem(player, SLAYER_RAGNAROK_KEYWORDS)) {
          slayerRagnarokUsedPreBoss = true
          slayerRagnarokUsedThisBoss = true
          slayerLastRagnarokUseTick = level.gameTime
        }
      }
    } else {
      slayerStatus.value = "Off"
      clearSlayerBossState(false)
      slayerNeedsQuestRestart = false
      slayerPendingTierSelection = false
    }

    if (player.isDeadOrDying || player.health <= 0f) {
      if (slayerModeEnabled && !slayerDeathRespawnPending) {
        // Slayer: don't stop - queue walkback for when the player respawns.
        slayerDeathRespawnPending = true
        if (CombatPatrolModule.isPatrolRunning) CombatPatrolModule.stopPatrol()
        if (PathfindingModule.isPatrolActive) PathfindingModule.stopPatrol()

        slayerNeedsWalkback = false
        nativeStop()
        slayerStatus.value = "Died - respawning..."
      } else if (!cryptZombieSlayer.value) {
        stopMacro()
      }
      return
    }
    // Respawn after slayer death: trigger walkback so player returns to farm area.
    if (slayerDeathRespawnPending) {
      slayerDeathRespawnPending = false
      startAreaOrigin = null
      triggerWalkToFarmArea(justFarm = false)
    }

    if (stayNearStart.value && startAreaOrigin == null) {
      val pos = player.blockPosition()
      // For crypt location, only anchor the start area when the player is actually
      // inside the crypt - prevents hub spawn from being used as the anchor point
      // which would block the walk-in route.
      val shouldAnchor = !(cryptZombieSlayer.value && slayerLocation.value == 1) ||
        CRYPT_PATROL_WAYPOINTS.any { wp ->
          val dx = wp.x - pos.x; val dz = wp.z - pos.z
          dx * dx + dz * dz < CRYPT_PROXIMITY_RANGE_SQ
        }
      if (shouldAnchor) startAreaOrigin = pos
    }

    tryAutoHeal(player, level.gameTime)
    tryAlwaysUseWandOfAtonement(player, level.gameTime)
    if (!cryptZombieSlayer.value && prefersDrillForTargetName(targetName.value)) {
      ensureWalkerDrillSelected(player, level.gameTime, targetName.value)
    }
    // For crypt slayer: track when the player is confirmed inside the crypt, and recover
    // (warp hub + walk-in) only if they previously entered and then unexpectedly left.
    // The slayerEnteredCrypt guard prevents the startup spam loop where the player warps to
    // hub and the outside-crypt check immediately re-fires before they have walked back in.
    if (cryptZombieSlayer.value && slayerLocation.value == 1 && !slayerBossActive
      && slayerWalkInDelayUntilTick < 0L && !slayerNeedsWalkback) {
      val inCrypt = CRYPT_PATROL_WAYPOINTS.any { wp ->
        val dx = wp.x - player.x; val dz = wp.z - player.z
        dx * dx + dz * dz < CRYPT_PROXIMITY_RANGE_SQ
      }
      if (inCrypt) {
        slayerEnteredCrypt = true   // confirmed inside - enable recovery trigger
      } else if (slayerEnteredCrypt) {
        // Was inside the crypt but now isn't - navigate back via walkback route.
        ChatUtils.sendMessage("Combat macro: outside crypt, walking back.")
        startAreaOrigin = null
        slayerEnteredCrypt = false
        triggerWalkToFarmArea(justFarm = false)
        return
      }
      // If !inCrypt && !slayerEnteredCrypt: still making initial walk-in, do nothing here
    }
    // For non-crypt slayer types: track area entry/exit using patrol points.
    if (cryptZombieSlayer.value && slayerLocation.value != 1
      && CombatPatrolModule.patrolPoints.isNotEmpty()
      && !RouteStore.getSlotRoute(currentSlotKey()).isNullOrBlank()) {
      val pos = player.blockPosition()
      val nearAnyPoint = CombatPatrolModule.patrolPoints.any { p ->
        val dx = p.x - pos.x; val dz = p.z - pos.z
        dx * dx + dz * dz < GRAVEYARD_PROXIMITY_RANGE_SQ
      }
      if (nearAnyPoint) {
        enteredFarmingArea = true
      } else if (enteredFarmingArea && !slayerBossActive
        && true
        && !slayerNeedsWalkback && !slayerDeathRespawnPending) {
        ChatUtils.sendMessage("Combat macro: left farming area, walking back.")
        enteredFarmingArea = false
        startAreaOrigin = null
        triggerWalkToFarmArea(justFarm = false)
        return
      }
    }
    // Walkback owns pathfinding - yield before enforceStartArea can override it.
    if (slayerNeedsWalkback && !slayerBossActive) {
      currentTargetId = null
      return
    }
    // Don't enforce start area when the slayer boss is active - chase it wherever it goes
    if (!(cryptZombieSlayer.value && slayerBossActive) && enforceStartArea(player, level)) {
      return
    }
    if (cryptZombieSlayer.value && handleSpiderHyperionBeforeShooting(player)) {
      return
    }

    val target = if (cryptZombieSlayer.value) resolveSlayerTarget(player, level) else resolveTarget(player)
    if (cryptZombieSlayer.value) {
      tryUseSlayerSupportItems(player, target, level.gameTime)
    }
    if (target == null) {
      resetCloseChase()
      if (cryptZombieSlayer.value && shouldTargetOnlySpiderHatchlings(level.gameTime)) {
        if (startedPath && nativeActive()) {
          nativeStop()
        } else {
          MovementManager.clearForcedMovement()
        }
        applySpiderPhaseBossBackoff(player, level, null)
        startedPath = false
        lastTargetPos = null
        currentTargetId = null
        return
      }
      // Boss is active but not yet visible - walk toward its last known position so we can find it.
      if (cryptZombieSlayer.value && slayerBossActive) {
        val bossPos = slayerBossLastPos
        if (bossPos != null) {
          val bossBlockPos = BlockPos(bossPos.x.toInt(), bossPos.y.toInt(), bossPos.z.toInt())
          if (!nativeActive() || lastTargetPos != bossBlockPos) {
            if (level.gameTime - lastPathStartTick >= MIN_PATH_START_INTERVAL_TICKS) {
              lastPathStartTick = level.gameTime
              NativePathfinder.setTarget(bossBlockPos.x + 0.5, bossBlockPos.y.toDouble(), bossBlockPos.z + 0.5)
              val started = true
              if (started) {
                lastTargetPos = bossBlockPos
                startedPath = true
              }
            }
          }
          currentTargetId = null
          return
        }
      }
      // No target - hand off to CombatPatrolModule if patrol points are configured.
      if (CombatPatrolModule.patrolPoints.isNotEmpty() && !CombatPatrolModule.isPatrolRunning) {
        CombatPatrolModule.startPatrol()
      }
      if (CombatPatrolModule.isPatrolRunning) {
        when (CombatPatrolModule.patrolState) {
          CombatPatrolModule.PatrolState.COMBAT_INTERRUPT -> CombatPatrolModule.onCombatResume()
          CombatPatrolModule.PatrolState.AT_KILL_ZONE     -> CombatPatrolModule.onKillZoneCleared()
          else -> { /* NAVIGATING/WARPING - patrol owns movement */ }
        }
        if (startedPath && nativeActive() && !CombatPatrolModule.patrolOwnsPathfinder) nativeStop()
        startedPath = false; lastTargetPos = null; currentTargetId = null
        return
      }

      if (startedPath && nativeActive()) {
        nativeStop()
      } else {
        MovementManager.setMovementLock(false)
      }
      startedPath = false
      lastTargetPos = null
      currentTargetId = null
      return
    }

    val dist = player.distanceTo(target)
    val pathActiveNow = startedPath && nativeActive()
    val hasLos = player.hasLineOfSight(target)
    val activeCombatMode = effectiveCombatMode(target)
    val engageRange = combatEngageRange(activeCombatMode)
    val keepDistance = combatKeepDistance(activeCombatMode)
    val suppressAttack = cryptZombieSlayer.value && shouldSuppressSlayerAttack(target, level.gameTime)
    val inAttackRange =
      when (activeCombatMode) {
        0 -> dist <= attackRange.value
        else -> hasLos && dist <= engageRange
      }
    val inKeepDistanceZone =
      when (activeCombatMode) {
        0 -> dist <= keepDistance
        else -> hasLos && dist <= keepDistance
      }
    val tooClose = dist < (combatDistance.value - distanceTolerance.value).coerceAtLeast(0.5)
    val inCloseChaseRange = dist <= engageRange + chaseStopBuffer.value

    ensurePreferredWeapon(player, target, level.gameTime)
    val delayedByEmanPreSwap =
      if (cryptZombieSlayer.value) {
        ensureSlayerWeapon(player, target)
      } else {
        false
      }
    if (cryptZombieSlayer.value) {
      applyEmanHitPhaseSneak(target, inAttackRange || inKeepDistanceZone)
    }

    val aimRotation = if (activeCombatMode != 0) rangedAimRotation(player, target, activeCombatMode) else AngleUtils.getRotation(target)
    val spiderHatchlingPhaseTarget =
      shouldUseSpiderPhaseBowCombat() &&
        isSpiderBossHatchlingsPhaseActive(level.gameTime) &&
        matchesSpiderPhaseTarget(target)
    val phaseMovementActive =
      if (cryptZombieSlayer.value) {
        handleSlayerPhaseMovement(player, target, dist.toDouble(), hasLos, activeCombatMode, level.gameTime)
      } else {
        false
      }
    if (inCloseChaseRange && (hasLos || inAttackRange)) {
      // Let native path rotation steer the player until it has fully arrived;
      // overriding it early makes the movement drift off-path and circle.
      if (!pathActiveNow || inAttackRange) {
        RotationExecutor.rotateTo(aimRotation, rotationStrategy)
      }
    } else if (!pathActiveNow && !inCloseChaseRange && dist > attackRange.value + chaseStopBuffer.value + ROTATION_STOP_HYSTERESIS) {
      RotationExecutor.stopRotating()
    }

    if (phaseMovementActive && shouldUseSpiderPhaseBowCombat() && isSpiderBossHatchlingsPhaseActive(level.gameTime)) {
      if (startedPath && nativeActive()) {
        nativeStop()
      }
      startedPath = false
      lastTargetPos = null
      return
    }

    if (inAttackRange || inKeepDistanceZone) {
      if (!oneTapMode.value && inKeepDistanceZone && startedPath && nativeActive()) {
        nativeStop()
      }
      if (!oneTapMode.value && inKeepDistanceZone) {
        startedPath = false
        lastTargetPos = null
      }
      stuckRepathCount = 0
      if (inAttackRange) {
        val keepRangedMomentum = activeCombatMode != 0 && oneTapMode.value && pathActiveNow
        if (!keepRangedMomentum && !phaseMovementActive) {
          MovementManager.clearForcedMovement()
        }
        if (!suppressAttack && !delayedByEmanPreSwap && !phaseMovementActive) {
          attemptAttack(player, target, activeCombatMode)
        }
      } else {
        // In range but no LOS - face target and hold still.
        RotationExecutor.rotateTo(aimRotation, rotationStrategy)
        holdStillNoStrafe()
      }
    } else if (spiderHatchlingPhaseTarget) {
      if (startedPath && nativeActive()) {
        nativeStop()
      }
      startedPath = false
      lastTargetPos = null
      val hatchlingAim = SpiderSlayerPhase.aimRotation(player, target)
      RotationExecutor.rotateTo(hatchlingAim, rotationStrategy)
      val boss = SpiderSlayerPhase.resolveTrackedBoss(level, resolveNearestSlayerBoss(), ::isSlayerBossEntity)
      val bossPos = boss?.position() ?: SpiderSlayerPhase.bossAnchor
      val tooCloseToBoss = bossPos != null &&
        SpiderSlayerPhase.distanceFromBoss(player, bossPos, ::horizontalDistSq) < SpiderSlayerPhase.MIN_BACKSTEP_DISTANCE
      if (tooCloseToBoss) {
        MovementManager.setForcedMovement(
          forward = false, backward = true,
          left = false, right = false,
          jump = false, shift = false, sprint = false
        )
      } else {
        holdStillNoStrafe()
        attemptAttack(player, target, 1)
      }
    } else if (slayerNeedsWalkback) {
      // Walkback owns movement - don't chase, just rotate toward target so we can attack when it comes in range.
      RotationExecutor.rotateTo(aimRotation, rotationStrategy)
    } else {
      // Target spotted - stop kill patrol so combat macro can take over pathfinding.
      if (PathfindingModule.isPatrolActive) PathfindingModule.stopPatrol()
      if (CombatPatrolModule.patrolState == CombatPatrolModule.PatrolState.NAVIGATING ||
          CombatPatrolModule.patrolState == CombatPatrolModule.PatrolState.WARPING) {
        CombatPatrolModule.onCombatInterrupt()
      }
      val targetPos = target.blockPosition()
      val last = lastTargetPos
      val targetMovedFar = last == null || last.distSqr(targetPos) > TARGET_REPATH_DISTANCE_SQ
      val pathDone = !pathActiveNow
      val directChaseZone = activeCombatMode == 0 && shouldUseDirectChase(target, hasLos, dist.toDouble())
      if (directChaseZone) {
        // Once LOS is established and the target is close enough, stop letting the
        // pathfinder keep re-solving around the mob and finish the approach with
        // strict forward-only chase.
        if (pathActiveNow) {
          nativeStop()
        }
        startedPath = false
        lastTargetPos = null
        handleSoftChaseMovement(player, target, dist.toDouble())
      } else if ((pathDone || targetMovedFar) && !spiderHatchlingPhaseTarget) {
        if (level.gameTime - lastPathStartTick >= MIN_PATH_START_INTERVAL_TICKS) {
          lastPathStartTick = level.gameTime
          val started = startCombatPathToTarget(level, targetPos, activeCombatMode)
          if (started) {
            lastTargetPos = targetPos
            startedPath = true
          } else {
            startedPath = false
            if (!nativeActive()) {
              lastTargetPos = null
            }
          }
        }
      }
    }

    if (tooClose && !phaseMovementActive) {
      if (startedPath && nativeActive()) nativeStop()
      startedPath = false
      MovementManager.setForcedMovement(
        owner = MovementOwner.COMBAT,
        forward = false,
        backward = true,
        left = false,
        right = false,
        jump = false,
        shift = false,
        sprint = false
      )
      backingAway = true
    } else if (backingAway) {
      MovementManager.releaseMovement(MovementOwner.COMBAT)
      backingAway = false
    }

    updateStuck(player, inAttackRange || inKeepDistanceZone, level)
  }

  @SubscribeEvent
  fun onRender(event: WorldRenderEvent.Last) {
    if (!enabled.value) return
    val level = mc.level ?: return

    if (cryptZombieSlayer.value && slayerBossEsp.value && slayerEspStyle.value == SLAYER_ESP_STYLE_BOX) {
      renderSlayerHighlights(event.context, level)
    }

    val targetId =
      if (cryptZombieSlayer.value && shouldUseSpiderPhaseBowCombat() && isSpiderBossHatchlingsPhaseActive()) {
        SpiderSlayerPhase.selectedTargetId ?: currentTargetId
      } else {
        currentTargetId
      } ?: return
    val target = level.entitiesForRendering().firstOrNull { it.uuid == targetId } as? LivingEntity ?: return
    if (!target.isAlive || target.health <= 0f) return

    val color =
      if (cryptZombieSlayer.value && shouldUseSpiderPhaseBowCombat() && isSpiderBossHatchlingsPhaseActive() && matchesSpiderPhaseTarget(target)) {
        SPIDER_HATCHLING_TARGET_COLOR
      } else {
        currentTargetRenderColor()
      }
    Render3D.drawBox(event.context, target.boundingBox.inflate(TARGET_BOX_INFLATE), color, true)
  }

  private fun resolveTarget(player: Player): LivingEntity? {
    val level = mc.level ?: return null
    // Kill zone mode: only target mobs near the current kill point.
    val killPoint = CombatPatrolModule.currentKillPoint
    if (killPoint != null) {
      val kx = killPoint.x + 0.5; val ky = killPoint.y.toDouble(); val kz = killPoint.z + 0.5
      val radiusSq = CombatPatrolModule.killZoneRadiusValue * CombatPatrolModule.killZoneRadiusValue
      val blacklisted = builtInBlacklistedNames
      val filter = targetName.value.trim().lowercase()
      var best: LivingEntity? = null
      var bestDist = Double.POSITIVE_INFINITY
      for (entity in level.entitiesForRendering()) {
        val living = entity as? LivingEntity ?: continue
        if (!isValidTarget(living, player, blacklisted, filter, true)) continue
        val dx = living.x - kx; val dy = living.y - ky; val dz = living.z - kz
        val distSq = dx * dx + dy * dy + dz * dz
        if (distSq > radiusSq) continue
        val playerDist = player.distanceToSqr(living)
        if (playerDist < bestDist) { best = living; bestDist = playerDist }
      }
      if (best != null) currentTargetId = best.uuid
      return best
    }
    val filter = targetName.value.trim().lowercase()
    val blacklisted = builtInBlacklistedNames
    // Patrol running (non-kill-zone): bypass stayNearStart - patrol route defines the area.
    val startOrigin =
      if (CombatPatrolModule.isPatrolRunning || !stayNearStart.value) {
        null
      } else {
        startAreaOrigin ?: player.blockPosition().also { startAreaOrigin = it }
      }
    val startAreaRangeSq = startAreaRadius.value * startAreaRadius.value

    val searchRangeSq = searchRange.value * searchRange.value
    var best: LivingEntity? = null
    var bestDist = Double.POSITIVE_INFINITY
    for (entity in level.entitiesForRendering()) {
      val living = entity as? LivingEntity ?: continue
      if (!isValidTarget(living, player, blacklisted, filter, true)) continue
      if (startOrigin != null) {
        val ox = living.x - (startOrigin.x + 0.5)
        val oz = living.z - (startOrigin.z + 0.5)
        if (ox * ox + oz * oz > startAreaRangeSq) continue
      }
      val dx = living.x - player.x
      val dy = living.y - player.y
      val dz = living.z - player.z
      val distSq = dx * dx + dy * dy + dz * dz
      if (distSq > searchRangeSq) continue
      if (distSq < bestDist) {
        best = living
        bestDist = distSq
      }
    }
    if (best != null) {
      currentTargetId = best.uuid
    }
    return best
  }

  private fun resolveSlayerTarget(player: Player, level: ClientLevel): LivingEntity? {
    val blacklisted = builtInBlacklistedNames
    // Kill zone mode: when patrol is AT_KILL_ZONE, anchor the mob search around the kill
    // point instead of startAreaOrigin. This matches how resolveTarget() works and prevents
    // the slayer from marking the kill zone "cleared" before any mobs there are targeted.
    val killPoint = CombatPatrolModule.currentKillPoint
    val patrolRunning = CombatPatrolModule.isPatrolRunning
    val startOrigin = when {
      // Kill zone: anchor search around the kill point
      killPoint != null -> BlockPos(killPoint.x, killPoint.y, killPoint.z)
      // Patrol navigating: don't constrain by startAreaOrigin - patrol route defines the area
      patrolRunning -> null
      stayNearStart.value -> startAreaOrigin ?: player.blockPosition().also { startAreaOrigin = it }
      else -> null
    }
    val startAreaRangeSq = when {
      killPoint != null -> CombatPatrolModule.killZoneRadiusValue.let { it * it }
      else -> startAreaRadius.value * startAreaRadius.value
    }
    // In kill zone mode the kill-point radius already constrains the search; skip the
    // player-distance searchRange filter so mobs at the kill zone are never missed.
    val searchRangeSq = if (killPoint != null) Double.POSITIVE_INFINITY else searchRange.value * searchRange.value
    var bestBoss: LivingEntity? = null
    var bestBossDist = Double.POSITIVE_INFINITY
    var bestPriorityMob: LivingEntity? = null
    var bestPriorityMobDist = Double.POSITIVE_INFINITY
    var bestFarmMob: LivingEntity? = null
    var bestFarmMobDist = Double.POSITIVE_INFINITY
    var bestBossHighlightState: SlayerHighlightState? = null

    val newHighlightCache = mutableMapOf<UUID, SlayerHighlightState>()
    for (entity in level.entitiesForRendering()) {
      val living = entity as? LivingEntity ?: continue
      if (!isValidTarget(living, player, blacklisted, "", true, ignoreWhitelist = true)) continue

      // Compute names once — avoids 3 separate findAttachedArmorStandNames scans per entity.
      val names = targetMatchNames(living)
      val isBoss = names.any(::isSlayerBossName) &&
        (!cryptZombieSlayer.value || !slayerOwnBossOnly.value || isSlayerBossOwnedByPlayer(living))
      val isPriority = names.any(::isSlayerPriorityMobName)
      val isFarmMob = isSlayerFarmMobNames(names)
      if (!isBoss && !isPriority && !isFarmMob) continue

      // Populate miniboss highlights here, but add the boss only after the best candidate is known.
      if (!isBoss && isPriority && slayerHighlightMiniBosses.value) {
        val type =
          if (names.any(::isHighTierSlayerPriorityMobName)) SlayerHighlightType.HIGH_TIER_MINIBOSS
          else SlayerHighlightType.MINIBOSS
        newHighlightCache[living.uuid] = buildSlayerHighlightState(names, type)
      }

      if (startOrigin != null && !isBoss) {
        // Boss ignores start area - chase it wherever it goes
        val ox = living.x - (startOrigin.x + 0.5)
        val oz = living.z - (startOrigin.z + 0.5)
        if (ox * ox + oz * oz > startAreaRangeSq) continue
      }

      val dx = living.x - player.x
      val dy = living.y - player.y
      val dz = living.z - player.z
      val distSq = dx * dx + dy * dy + dz * dz
      if (!isBoss && distSq > searchRangeSq) continue // boss ignores search range

      if (isBoss && distSq < bestBossDist) {
        bestBoss = living
        bestBossDist = distSq
        bestBossHighlightState = buildSlayerHighlightState(names, SlayerHighlightType.BOSS)
      }
      if (isPriority && distSq < bestPriorityMobDist) {
        bestPriorityMob = living
        bestPriorityMobDist = distSq
      }
      if (isFarmMob && distSq < bestFarmMobDist) {
        bestFarmMob = living
        bestFarmMobDist = distSq
      }
    }
    if (bestBoss != null) {
      newHighlightCache[bestBoss.uuid] =
        bestBossHighlightState ?: buildSlayerHighlightState(targetMatchNames(bestBoss), SlayerHighlightType.BOSS)
    }
    slayerHighlightCache = newHighlightCache
    if (slayerBossEsp.value && slayerEspStyle.value == SLAYER_ESP_STYLE_GLOW) {
      syncSlayerGlowState(level, newHighlightCache)
    } else {
      clearSlayerGlowState(level)
    }

    if (bestBoss != null) {
      onSlayerBossDetected(level.gameTime)
      slayerBossLastPos = bestBoss.position()
    } else if (slayerBossActive && level.gameTime - slayerBossLastSeenTick > SLAYER_BOSS_ENTITY_LOST_TICKS) {
      clearSlayerBossState()
    }

    updateSlayerBossPhaseState(level, bestBoss)
    val phaseTarget = if (slayerBossActive) resolveSlayerPhaseTarget(player, level, bestBoss) else null
    val spiderHatchlingOnlyPhase = shouldTargetOnlySpiderHatchlings(level.gameTime)

    // Priority: boss > priority farm mob (rare/bonus) > regular farm mob
    val selected = when {
      phaseTarget != null -> phaseTarget
      spiderHatchlingOnlyPhase -> null
      slayerBossActive -> bestBoss
      else -> bestBoss ?: bestPriorityMob ?: bestFarmMob
    }
    if (selected != null) {
      currentTargetId = selected.uuid
    }
    return selected
  }

  private fun updateSlayerBossState(nowTick: Long) {
    val tabLines = readTabListLines(nowTick)
    val scoreboardLines = readScoreboardSidebarLines(mc.level)
    for (line in scoreboardLines) {
      applyQuestDetection(line, "scoreboard", nowTick)
      applyQuestProgressDetection(line, nowTick)
    }
    for (line in tabLines) {
      applyQuestDetection(line, "tab", nowTick)
      applyQuestProgressDetection(line, nowTick)
    }
    val questLines = tabLines + scoreboardLines
    val hasBossTabLine = questLines.any { line -> SlayerQuestSignals.bossTabKeywords.any { keyword -> line.contains(keyword) } }
    val hasClearTabLine = questLines.any { line -> SlayerQuestSignals.bossClearKeywords.any { keyword -> line.contains(keyword) } }
    val hasQuestTabLine = questLines.any { line ->
      SlayerQuestSignals.hasActiveQuestSignal(
        line,
        slayerType.value,
        slayerTrackedQuestType,
        slayerTrackedQuestTier,
        hasTrackedSlayerQuestContext(),
      )
    }
    val questStateSettling = isSlayerQuestStateSettling(nowTick)

    if (hasBossTabLine) {
      onSlayerBossDetected(nowTick)
      confirmActiveSlayerQuest(nowTick)
      slayerQuestReady = false
    } else {
      if (hasClearTabLine) {
        if (slayerBossActive) {
          clearSlayerBossState()
        }
        if (!questStateSettling && !shouldLetAutoSlayerHandleRestart()) {
          queueSlayerQuestRestart()
        }
      } else if (hasQuestTabLine) {
        confirmActiveSlayerQuest(nowTick)
      }
      if (slayerBossActive && nowTick - slayerBossLastSeenTick > SLAYER_BOSS_TAB_LOST_TICKS) {
        clearSlayerBossState()
      }
    }

    slayerStatus.value =
      when {
        slayerBossActive && isSpiderBossHatchlingsPhaseActive(nowTick) -> "Boss Active - Hatchlings"
        slayerBossActive && isWolfBossPupsPhaseActive(nowTick) ->
          if (wolfIgnorePups.value) "Boss Active - Pups (Ignored)" else "Boss Active - Pups"
        slayerBossActive -> "Boss Active"
        slayerNeedsQuestClaim -> "Claiming Loot"
        slayerQuestReady -> "Quest Ready!"
        slayerNeedsQuestRestart -> "Restarting Quest"
        else -> "Farming ${when (slayerType.value) { 0 -> "Zombies"; 1 -> "Wolves"; 2 -> "Spiders"; 3 -> "Voidlings"; 4 -> "Vampires"; 5 -> "Blazes"; else -> "Mobs" }}"
      }
  }

  private fun onSlayerBossDetected(nowTick: Long) {
    val wasActive = slayerBossActive
    slayerBossActive = true
    slayerTrackedQuestBossSeen = true
    slayerBossLastSeenTick = nowTick
    slayerNeedsQuestRestart = false
    if (!wasActive) {
      slayerOverfluxUsedThisBoss = false
      slayerRagnarokUsedThisBoss = false
      slayerReaperScytheUsedThisBoss = false
      slayerPrimordialBeltUsedThisBoss = false
      slayerLastBadHealthUseTick = -1L
      slayerLastOverfluxUseTick = -1L
      slayerLastReaperScytheUseTick = -1L
      val bossName = slayerBossDisplayName()
      ChatUtils.sendMessage("Combat macro: $bossName spawned.")
      if (slayerBossNotification.value) {
        NotificationManager.queue("Slayer Boss Spawned", bossName, 3200L)
      }
    }
  }

  private fun clearSlayerBossState(announce: Boolean = true) {
    if (slayerBossActive && announce) {
      ChatUtils.sendMessage("Combat macro: Slayer boss no longer detected.")
    }
    clearPendingOverfluxPlacement()
    clearSpiderHatchlingsPhase()
    clearEndermanLaserPhase()
    slayerRagnarokUsedForLaserPhase = false
    wolfPupsPhaseUntilTick = -1L
    slayerBossActive = false
    slayerBossLastSeenTick = 0L
    slayerOverfluxUsedThisBoss = false
    slayerRagnarokUsedThisBoss = false
    slayerReaperScytheUsedThisBoss = false
    slayerPrimordialBeltUsedThisBoss = false
    slayerRagnarokUsedPreBoss = false
    slayerQuestReady = false
    slayerLastBadHealthUseTick = -1L
    slayerLastReaperScytheUseTick = -1L
    slayerBossLastPos = null
    emanHitPhaseBossWeaponPrimed = false
    emanReaperScytheUsedThisHitPhase = false
  }

  private fun tryUseSlayerSupportItems(player: Player, target: LivingEntity?, nowTick: Long) {
    if (!slayerBossActive) return
    if (pendingHealRelease || pendingHealRestoreSlot >= 0 || pendingOverfluxLookDownTicks > 0) return

    if (autoOverflux.value && !slayerOverfluxUsedThisBoss && nowTick - slayerLastOverfluxUseTick >= SLAYER_OVERFLUX_COOLDOWN_TICKS) {
      if (useHotbarOverflux(player)) {
        slayerOverfluxUsedThisBoss = true
        slayerLastOverfluxUseTick = nowTick
        return
      }
    }

    if (SlayerLocationSettings.shouldUsePrimordialBelt(slayerType.value) && !slayerPrimordialBeltUsedThisBoss) {
      if (useHotbarUtilityItem(player, SLAYER_PRIMORDIAL_BELT_KEYWORDS)) {
        slayerPrimordialBeltUsedThisBoss = true
        return
      }
    }

    // Laser phase: fire ragnarok at the start of each laser phase (separate from the general boss ragnarok).
    if (autoRagnarok.value && emanLaserPhaseActive && !slayerRagnarokUsedForLaserPhase &&
        nowTick - slayerLastRagnarokUseTick >= SLAYER_RAGNAROK_COOLDOWN_TICKS) {
      if (useHotbarUtilityItem(player, SLAYER_RAGNAROK_KEYWORDS)) {
        slayerRagnarokUsedForLaserPhase = true
        slayerLastRagnarokUseTick = nowTick
        return
      }
    }

    val bossTarget = target?.takeIf(::isSlayerBossEntity) ?: resolveNearestSlayerBoss()
    val isEndermanHitPhase = bossTarget != null && shouldUseEndermanDynamicCombat() && isEndermanBossHitPhase(bossTarget)
    if (!isEndermanHitPhase) {
      emanReaperScytheUsedThisHitPhase = false
    }

    if (autoReaperScythe.value &&
      isEndermanHitPhase &&
      !emanReaperScytheUsedThisHitPhase &&
      nowTick - slayerLastReaperScytheUseTick >= SLAYER_REAPER_SCYTHE_COOLDOWN_TICKS
    ) {
      if (tryUseReaperScytheOnBoss(player, bossTarget, nowTick)) {
        emanReaperScytheUsedThisHitPhase = true
        return
      }
    }

    if (autoRagnarok.value && !slayerRagnarokUsedThisBoss && nowTick - slayerLastRagnarokUseTick >= SLAYER_RAGNAROK_COOLDOWN_TICKS) {
      if (useHotbarUtilityItem(player, SLAYER_RAGNAROK_KEYWORDS)) {
        slayerRagnarokUsedThisBoss = true
        slayerLastRagnarokUseTick = nowTick
        return
      }
    }

    if (autoSwordOfBadHealth.value && nowTick - slayerLastBadHealthUseTick >= SLAYER_BAD_HEALTH_REUSE_TICKS) {
      if (useHotbarUtilityItem(player, SLAYER_BAD_HEALTH_KEYWORDS)) {
        slayerLastBadHealthUseTick = nowTick
      }
    }
  }

  private fun tryUseReaperScytheOnBoss(player: Player, bossTarget: LivingEntity?, nowTick: Long): Boolean {
    if (bossTarget == null || !player.hasLineOfSight(bossTarget)) return false
    val rotation = AngleUtils.getRotation(bossTarget)
    RotationExecutor.rotateTo(rotation, rotationStrategy)
    val yawError = abs(AngleUtils.getRotationDelta(player.yRot, rotation.yaw))
    val pitchError = abs(rotation.pitch - player.xRot)
    if (yawError > REAPER_SCYTHE_USE_AIM_TOLERANCE || pitchError > REAPER_SCYTHE_USE_AIM_TOLERANCE) {
      return false
    }
    if (!useHotbarUtilityItem(player, SLAYER_REAPER_SCYTHE_KEYWORDS)) return false
    slayerLastReaperScytheUseTick = nowTick
    return true
  }

  private fun tryRestartSlayerQuest(player: Player, nowTick: Long): Boolean {
    if (!slayerNeedsQuestRestart || slayerBossActive) return false
    holdStillForMaddoxCall()
    // Don't fire the batphone until quest detection has settled
    if (!slayerAutoDetected) return true
    if (isLikelyMaddoxScreen(nowTick)) return true
    if (mc.screen is AbstractContainerScreen<*>) return false
    if (pendingHealRelease || pendingHealRestoreSlot >= 0) return true
    if (slayerLastBatphoneAttemptTick >= 0L && nowTick - slayerLastBatphoneAttemptTick < SLAYER_BATPHONE_RETRY_TICKS) {
      return true
    }

    var batphoneSlot = findHotbarSlotByKeywords(player, SLAYER_BATPHONE_KEYWORDS)
    if (batphoneSlot !in 0..8) {
      val moved = moveBatphoneToHotbar(player)
      if (!moved) {
        slayerStatus.value = "Need Maddox Batphone"
        if (slayerWarnNoBatphoneTick < 0L || nowTick - slayerWarnNoBatphoneTick >= SLAYER_NO_BATPHONE_WARN_TICKS) {
          slayerWarnNoBatphoneTick = nowTick
          ChatUtils.sendMessage("Combat macro: Maddox Batphone not found in inventory/hotbar.")
        }
        slayerLastBatphoneAttemptTick = nowTick
        return true
      }
      batphoneSlot = findHotbarSlotByKeywords(player, SLAYER_BATPHONE_KEYWORDS)
    }

    if (batphoneSlot !in 0..8) {
      slayerLastBatphoneAttemptTick = nowTick
      return true
    }

    if (useHotbarUtilityItem(player, SLAYER_BATPHONE_KEYWORDS)) {
      slayerLastBatphoneAttemptTick = nowTick
      slayerLastBatphoneUseTick = nowTick
      slayerStatus.value = "Calling Maddox..."
      return true
    }

    return false
  }

  private fun handleMaddoxGui(nowTick: Long): Boolean {
    if (!slayerNeedsQuestRestart) return false
    if (!isLikelyMaddoxScreen(nowTick)) return false
    holdStillForMaddoxCall()
    if (nowTick - slayerLastGuiActionTick < SLAYER_GUI_ACTION_COOLDOWN_TICKS) return true

    val player = mc.player ?: return true
    val menu = player.containerMenu
    val action = findMaddoxMenuAction(menu.slots)
    if (action.slotIndex >= 0) {
      InventoryUtils.clickSlot(action.slotIndex)
      slayerLastGuiActionTick = nowTick
      slayerLastBatphoneAttemptTick = nowTick
      slayerPendingTierSelection = action.actionType == SlayerMaddoxActionType.TIER_SELECT
      if (action.actionType == SlayerMaddoxActionType.FINAL_PURCHASE) {
        player.closeContainer()
        beginSlayerQuestDetection(nowTick)
        startTrackedSlayerQuest(slayerType.value, slayerTier.value.toInt())
        slayerRagnarokUsedPreBoss = false
        startAreaOrigin = null
        if (cryptZombieSlayer.value && slayerLocation.value == 1) {
          // Crypt: check if already inside the farming area - if so skip the warp and walk-in.
          val alreadyInCrypt = CRYPT_PATROL_WAYPOINTS.any { wp ->
            val dx = wp.x - player.x; val dz = wp.z - player.z
            dx * dx + dz * dz < CRYPT_PROXIMITY_RANGE_SQ
          }
          if (alreadyInCrypt) {
            slayerEnteredCrypt = true
          } else {
            slayerEnteredCrypt = false
            triggerWalkToFarmArea(justFarm = true)
          }
        } else if (slayerAutoWarp.value) {
          val warp = slayerWarpCommand
          if (warp.isNotEmpty()) player.connection?.sendCommand(warp)
        }
      }
      slayerStatus.value = "Restarting Slayer..."
      return true
    }

    if (slayerLastBatphoneUseTick >= 0L && nowTick - slayerLastBatphoneUseTick >= SLAYER_MADDOX_GUI_TIMEOUT_TICKS) {
      player.closeContainer()
      slayerLastGuiActionTick = nowTick
      slayerLastBatphoneAttemptTick = nowTick
      slayerPendingTierSelection = false
      return true
    }

    return true
  }

  /** Called when the loot claim step finishes (success or timeout). Starts the walkback route
   *  or proceeds directly to quest restart. */
  private fun finishSlayerClaim() {
    slayerNeedsQuestClaim = false
    slayerBossLastPos = null
    val useAutoSlayer = shouldUseAutoSlayerForCurrentType()
    val routeName = RouteStore.getSlotRoute(currentSlotKey()).orEmpty().trim()
    if (!slayerAutoWarp.value && routeName.isNotBlank()) {
      if (startedPath && nativeActive()) nativeStop()
      startedPath = false
      lastTargetPos = null
      currentTargetId = null
      stuckTicks = 0
      stuckRepathCount = 0
      RotationExecutor.stopRotating()
      MovementManager.clearForcedMovement()
      val usePatrolTravelRoute = isAssignedPatrolTravelRoute(routeName)
      val started = RoutesModule.loadAndStartWalkback(
        routeName,
        reverse = if (usePatrolTravelRoute) true else slayerLocation.value != 1,
        patrolTravelOnly = usePatrolTravelRoute
      )
      if (started) {
        slayerNeedsWalkback = true
        slayerWalkbackJustFarm = useAutoSlayer
        return
      }
    }
    if (useAutoSlayer) {
      beginAutoSlayerQuestDetection(mc.level?.gameTime ?: -1L)
      return
    }
    queueSlayerQuestRestart(countFailure = false)
  }

  /**
   * Walks to the boss death position, right-clicks the loot bag entity, then transitions
   * to quest restart. Returns true while the claim step is still in progress.
   */
  private fun handleClaimSlayerQuest(player: LocalPlayer, level: ClientLevel): Boolean {
    val nowTick = level.gameTime

    // Timeout - give up and restart the quest anyway
    if (slayerClaimStartTick >= 0L && nowTick - slayerClaimStartTick > SLAYER_CLAIM_TIMEOUT_TICKS) {
      finishSlayerClaim()
      return false
    }

    val deathPos = slayerBossLastPos
    if (deathPos == null) {
      // No tracked position - skip straight to restart
      finishSlayerClaim()
      return false
    }

    // Search for a loot bag ArmorStand near the death position
    val lootEntity = level.entitiesForRendering()
      .filterIsInstance<ArmorStand>()
      .filter { entity ->
        val dx = entity.x - deathPos.x
        val dy = entity.y - deathPos.y
        val dz = entity.z - deathPos.z
        dx * dx + dy * dy + dz * dz < SLAYER_LOOT_SEARCH_RADIUS_SQ
      }
      .filter { entity ->
        val name = normalizeNameForMatch(entity.name.string)
        SLAYER_LOOT_KEYWORDS.any { name.contains(it) }
      }
      .minByOrNull { entity ->
        val dx = entity.x - player.x
        val dy = entity.y - player.y
        val dz = entity.z - player.z
        dx * dx + dy * dy + dz * dz
      }

    if (lootEntity == null) {
      // No loot bag found yet - walk to death pos and keep waiting
      val targetPos = BlockPos(deathPos.x.toInt(), deathPos.y.toInt(), deathPos.z.toInt())
      if (!nativeActive() || lastTargetPos != targetPos) {
        if (nowTick - lastPathStartTick >= MIN_PATH_START_INTERVAL_TICKS) {
          lastPathStartTick = nowTick
          NativePathfinder.setTarget(targetPos.x + 0.5, targetPos.y.toDouble(), targetPos.z + 0.5)
          val started = true
          if (started) {
            lastTargetPos = targetPos
            startedPath = true
          }
        }
      }
      return true
    }

    // Loot entity found - stop pathing, look at it and right-click
    if (startedPath && nativeActive()) {
      nativeStop()
      startedPath = false
    }

    val dx = lootEntity.x - player.x
    val dy = lootEntity.y - player.y
    val dz = lootEntity.z - player.z
    val distSq = dx * dx + dy * dy + dz * dz

    if (distSq > SLAYER_LOOT_CLAIM_RANGE_SQ) {
      val targetPos = lootEntity.blockPosition()
      if (!nativeActive()) {
        if (nowTick - lastPathStartTick >= MIN_PATH_START_INTERVAL_TICKS) {
          lastPathStartTick = nowTick
          NativePathfinder.setTarget(targetPos.x + 0.5, targetPos.y.toDouble(), targetPos.z + 0.5)
          lastTargetPos = targetPos
          startedPath = true
        }
      }
      return true
    }

    val rotation = AngleUtils.getRotation(lootEntity)
    RotationExecutor.rotateTo(rotation, rotationStrategy)

    if (nowTick - slayerClaimLastClickTick >= SLAYER_CLAIM_CLICK_INTERVAL_TICKS) {
      slayerClaimLastClickTick = nowTick
      MouseUtils.rightClick()
    }

    // After a short dwell at the loot bag, assume claimed and move on
    if (nowTick - slayerClaimStartTick > SLAYER_CLAIM_DWELL_TICKS) {
      finishSlayerClaim()
    }

    return true
  }

  private fun isLikelyMaddoxScreen(nowTick: Long): Boolean {
    val screen = mc.screen as? AbstractContainerScreen<*> ?: return false
    val title = normalizeNameForMatch(screen.title.string)
    if (SLAYER_MADDOX_SCREEN_KEYWORDS.any { keyword -> title.contains(keyword) }) return true
    return slayerLastBatphoneUseTick >= 0L && nowTick - slayerLastBatphoneUseTick <= SLAYER_MADDOX_GUI_TIMEOUT_TICKS
  }

  private fun shouldSuppressMovementForMaddox(nowTick: Long): Boolean {
    if (!cryptZombieSlayer.value || !slayerNeedsQuestRestart || slayerBossActive) return false
    if (nowTick < 0L) return true
    if (isLikelyMaddoxScreen(nowTick)) return true
    if (slayerLastBatphoneUseTick >= 0L && nowTick - slayerLastBatphoneUseTick <= SLAYER_MADDOX_GUI_TIMEOUT_TICKS) return true
    return true
  }

  private fun holdStillForMaddoxCall() {
    if (CombatPatrolModule.isPatrolRunning) {
      CombatPatrolModule.stopPatrol()
    }
    if (PathfindingModule.isPatrolActive) {
      PathfindingModule.stopPatrol()
    }
    if (startedPath && nativeActive()) {
      nativeStop()
      startedPath = false
      lastTargetPos = null
    }
    currentTargetId = null
    MovementManager.setMovementLock(true)
    MovementManager.setForcedMovement(
      forward = false,
      backward = false,
      left = false,
      right = false,
      jump = false,
      shift = false,
      sprint = false
    )
  }

  @SubscribeEvent
  fun onChatReceive(event: ChatEvent.Receive) {
    val raw = event.message ?: return
    val msg = ChatFormatting.stripFormatting(raw)?.lowercase(Locale.ROOT)?.trim() ?: return

    if (enabled.value) {
      // Player name mentioned in chat -> play system alert sound.
      val ign = mc.player?.gameProfile?.name?.lowercase(Locale.ROOT) ?: ""
      if (ign.isNotEmpty() && msg.contains(ign)) {
        java.awt.Toolkit.getDefaultToolkit().beep()
      }
      // Evacuated from hub or moved to different server -> walk back to farm area.
      if (msg.contains("evacuated from") || msg.contains("moved to a different server")) {
        triggerWalkToFarmArea(justFarm = false)
        return
      }
    }

    if (!cryptZombieSlayer.value) return

    if (SlayerQuestSignals.purchaseFailedKeywords.any { msg.contains(it) }) {
      handleSlayerQuestPurchaseFailure()
      return
    }

    if (SlayerQuestSignals.questReadyChatKeywords.any { msg.contains(it) }) {
      confirmActiveSlayerQuest(mc.level?.gameTime ?: -1L)
      slayerQuestReady = true
      ChatUtils.sendMessage("Combat macro: Quest ready - Ragnarok prepped for boss spawn.")
    }

    if (SlayerQuestSignals.bossSpawnedChatKeywords(slayerType.value).any { msg.contains(it) }) {
      val level = mc.level ?: return
      onSlayerBossDetected(level.gameTime)
      confirmActiveSlayerQuest(level.gameTime)
      slayerQuestReady = false
    }

    if (shouldUseSpiderPhaseBowCombat() &&
      msg.contains("broodfather's hatchlings") &&
      msg.contains("before it can be damaged again")
    ) {
      activateSpiderHatchlingsPhase(mc.level?.gameTime ?: -1L)
    }

    if (SlayerQuestSignals.bossKilledChatKeywords.any { msg.contains(it) }) {
      completeTrackedSlayerQuest()
      clearSlayerBossState(false)
      slayerNeedsQuestClaim = true
      slayerClaimStartTick = mc.level?.gameTime ?: -1L
    }
  }

  private fun tryAutoDetectSlayerQuest(level: ClientLevel) {
    val nowTick = level.gameTime
    if (isSlayerQuestStateSettling(nowTick)) return

    // Scan scoreboard sidebar
    for (line in readScoreboardSidebarLines(level)) {
      if (applyQuestDetection(line, "scoreboard", nowTick)) return
    }

    // Scan tab list
    val tabLines = readTabListLines(nowTick)
    for (line in tabLines) {
      if (applyQuestDetection(line, "tab", nowTick)) return
    }

    // Nothing found yet - only conclude "no quest" after the detection window expires
    if (slayerDetectStartTick >= 0L && nowTick - slayerDetectStartTick >= SLAYER_DETECT_WINDOW_TICKS) {
      slayerAutoDetected = true
      queueSlayerQuestRestart() // no quest found in window -> buy one
    }
    // else: still within window - farming continues normally, detection runs next tick
  }

  /** Returns true and updates state if the line matches any active slayer quest. */
  private fun applyQuestDetection(line: String, source: String, nowTick: Long): Boolean {
    val typeIdx = SlayerQuestSignals.detectType(line)
    if (typeIdx < 0) return false

    val tier = SlayerQuestSignals.parseTierFromLine(line)
    if (slayerType.value != typeIdx) {
      slayerType.value = typeIdx
      ChatUtils.sendMessage("Combat macro: Detected ${slayerType.options[typeIdx]} Slayer from $source.")
    }
    if (tier in 1..5) slayerTier.value = tier.toDouble()
    confirmActiveSlayerQuest(nowTick, typeIdx, tier)
    return true
  }

  private fun beginSlayerQuestDetection(nowTick: Long) {
    slayerNeedsQuestRestart = false
    slayerPendingTierSelection = false
    slayerQuestReady = false
    slayerAutoDetected = false
    slayerDetectStartTick = nowTick
    slayerQuestStateGraceUntilTick =
      if (nowTick >= 0L) nowTick + SLAYER_QUEST_STATE_GRACE_TICKS else -1L
  }

  private fun confirmActiveSlayerQuest(
    nowTick: Long,
    detectedType: Int = slayerType.value,
    detectedTier: Int = slayerTier.value.toInt(),
  ) {
    val resolvedType = detectedType.takeIf { it in slayerType.options.indices } ?: slayerType.value
    val resolvedTier = detectedTier.takeIf { it in 1..5 } ?: slayerTier.value.toInt().coerceIn(1, 5)
    slayerNeedsQuestRestart = false
    slayerAutoDetected = true
    slayerDetectStartTick = -1L
    if (!slayerTrackedQuestActive || slayerTrackedQuestCompletionPendingRestart) {
      startTrackedSlayerQuest(resolvedType, resolvedTier)
    } else {
      slayerTrackedQuestType = resolvedType
      slayerTrackedQuestTier = resolvedTier
    }
    if (nowTick >= 0L) {
      slayerQuestStateGraceUntilTick = nowTick + SLAYER_QUEST_STATE_GRACE_TICKS
    }
  }

  private fun isSlayerQuestStateSettling(nowTick: Long): Boolean {
    return slayerQuestStateGraceUntilTick >= 0L && nowTick < slayerQuestStateGraceUntilTick
  }

  private fun hasTrackedSlayerQuestContext(): Boolean {
    return slayerTrackedQuestActive && slayerTrackedQuestType in slayerType.options.indices
  }

  private fun applyQuestProgressDetection(line: String, nowTick: Long): Boolean {
    val progress =
      SlayerQuestSignals.parseProgress(
        line,
        slayerType.value,
        slayerTrackedQuestType,
        slayerTrackedQuestTier,
        hasTrackedSlayerQuestContext(),
      ) ?: return false
    val resolvedTier =
      progress.tier.takeIf { it in 1..5 }
        ?: slayerTrackedQuestTier.takeIf { slayerTrackedQuestActive && it in 1..5 }
        ?: slayerTier.value.toInt().coerceIn(1, 5)
    if (!slayerTrackedQuestActive || slayerTrackedQuestCompletionPendingRestart || slayerTrackedQuestType != progress.typeIndex) {
      startTrackedSlayerQuest(progress.typeIndex, resolvedTier)
    }
    confirmActiveSlayerQuest(nowTick, progress.typeIndex, resolvedTier)
    slayerTrackedQuestTargetKills = progress.target.coerceAtLeast(progress.progress)
    slayerTrackedQuestProgressKills = max(slayerTrackedQuestProgressKills, progress.progress)
    slayerTrackedQuestSawMobKill = slayerTrackedQuestSawMobKill || progress.progress > 0
    return true
  }

  private fun readScoreboardSidebarLines(level: ClientLevel?): List<String> {
    val liveLevel = level ?: return emptyList()
    val scoreboard = liveLevel.scoreboard
    val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return emptyList()
    return scoreboard.listPlayerScores(objective)
      .mapNotNull { score ->
        val ownerName = score.owner()
        val team = scoreboard.getPlayersTeam(ownerName)
        val raw = if (team != null) team.playerPrefix.string + ownerName + team.playerSuffix.string else ownerName
        ChatFormatting.stripFormatting(raw)?.lowercase(Locale.ROOT)?.trim()
      }
      .filter { it.isNotBlank() }
  }

  private fun moveBatphoneToHotbar(player: Player): Boolean {
    val sourceSlot = findFirstContainerSlotByKeywords(player.containerMenu.slots, SLAYER_BATPHONE_KEYWORDS) ?: return false
    if (sourceSlot in PLAYER_HOTBAR_MENU_SLOT_START..PLAYER_HOTBAR_MENU_SLOT_END) return true

    val targetHotbarSlot = findBestHotbarSwapSlot(player)
    InventoryUtils.swapSlotWithHotbar(sourceSlot, targetHotbarSlot)
    return true
  }

  private fun findFirstContainerSlotByKeywords(
    slots: List<net.minecraft.world.inventory.Slot>,
    keywords: Array<String>
  ): Int? {
    for (slot in slots) {
      if (!slot.hasItem()) continue
      val normalizedName = normalizeNameForMatch(slot.item.hoverName.string)
      if (keywords.any { keyword -> normalizedName.contains(keyword) }) {
        return slot.index
      }
    }
    return null
  }

  private fun findBestHotbarSwapSlot(player: Player): Int {
    val inventory = player.inventory
    for (i in 0..8) {
      if (inventory.getItem(i).isEmpty) {
        return i
      }
    }
    return inventory.selectedSlot.coerceIn(0, 8)
  }

  private enum class SlayerMaddoxActionType {
    NAVIGATION,
    TIER_SELECT,
    FINAL_PURCHASE
  }

  private data class SlayerMaddoxAction(
    val slotIndex: Int,
    val actionType: SlayerMaddoxActionType
  )

  /**
   * Returns the next Maddox GUI action to take.
   * Navigation opens the slayer page, tier-select changes the chosen level,
   * and final-purchase actually starts or confirms the quest.
   */
  private fun findMaddoxMenuAction(slots: List<net.minecraft.world.inventory.Slot>): SlayerMaddoxAction {
    val desiredTier = slayerTier.value.toInt().coerceIn(1, 5)
    var slayerNavigationSlot = -1
    var desiredTierSelectSlot = -1
    var typedRestartSlot = -1
    var typedConfirmSlot = -1
    var genericRestartSlot = -1
    var genericConfirmSlot = -1

    for (slot in slots) {
      if (!slot.hasItem()) continue
      val stack = slot.item
      val name = normalizeNameForMatch(stack.hoverName.string)
      val lore = stack.getLoreLines().joinToString(" ") { normalizeNameForMatch(it.string) }
      val text = "$name $lore"
      val nameTier = SlayerQuestSignals.parseTierFromMenuText(name, slayerType.value)
      val textTier =
        if (nameTier in 1..5) nameTier else SlayerQuestSignals.parseTierFromMenuText(text, slayerType.value)

      val hasRevenantKeyword = SlayerQuestSignals.maddoxQuestKeywords(slayerType.value).any { keyword -> text.contains(keyword) }
      val hasRestartKeyword = SlayerQuestSignals.maddoxRestartKeywords.any { keyword -> text.contains(keyword) }
      val hasConfirmKeyword = SlayerQuestSignals.maddoxConfirmKeywords.any { keyword -> text.contains(keyword) }

      if (hasConfirmKeyword && textTier == desiredTier) {
        return SlayerMaddoxAction(slot.index, SlayerMaddoxActionType.FINAL_PURCHASE)
      }
      if (hasRestartKeyword && hasRevenantKeyword && nameTier == desiredTier) {
        return SlayerMaddoxAction(slot.index, SlayerMaddoxActionType.FINAL_PURCHASE)
      }

      if (hasRevenantKeyword && nameTier == desiredTier && desiredTierSelectSlot == -1) {
        desiredTierSelectSlot = slot.index
      }
      if (hasConfirmKeyword && hasRevenantKeyword && typedConfirmSlot == -1) {
        typedConfirmSlot = slot.index
      }
      if (hasRevenantKeyword && nameTier !in 1..5 && slayerNavigationSlot == -1) {
        slayerNavigationSlot = slot.index
      }
      if (hasRestartKeyword && hasRevenantKeyword && typedRestartSlot == -1) {
        typedRestartSlot = slot.index
      }
      if (hasRestartKeyword && genericRestartSlot == -1) genericRestartSlot = slot.index
      if (hasConfirmKeyword && genericConfirmSlot == -1) genericConfirmSlot = slot.index
    }

    return if (slayerPendingTierSelection) {
      when {
        typedConfirmSlot >= 0 -> SlayerMaddoxAction(typedConfirmSlot, SlayerMaddoxActionType.FINAL_PURCHASE)
        typedRestartSlot >= 0 -> SlayerMaddoxAction(typedRestartSlot, SlayerMaddoxActionType.FINAL_PURCHASE)
        genericConfirmSlot >= 0 -> SlayerMaddoxAction(genericConfirmSlot, SlayerMaddoxActionType.FINAL_PURCHASE)
        genericRestartSlot >= 0 -> SlayerMaddoxAction(genericRestartSlot, SlayerMaddoxActionType.FINAL_PURCHASE)
        desiredTierSelectSlot >= 0 -> SlayerMaddoxAction(desiredTierSelectSlot, SlayerMaddoxActionType.TIER_SELECT)
        slayerNavigationSlot >= 0 -> SlayerMaddoxAction(slayerNavigationSlot, SlayerMaddoxActionType.NAVIGATION)
        else -> SlayerMaddoxAction(-1, SlayerMaddoxActionType.NAVIGATION)
      }
    } else {
      when {
        // Pick the requested tier first so the follow-up confirm/restart uses the right level.
        desiredTierSelectSlot >= 0 -> SlayerMaddoxAction(desiredTierSelectSlot, SlayerMaddoxActionType.TIER_SELECT)
        slayerNavigationSlot >= 0 -> SlayerMaddoxAction(slayerNavigationSlot, SlayerMaddoxActionType.NAVIGATION)
        typedConfirmSlot >= 0 -> SlayerMaddoxAction(typedConfirmSlot, SlayerMaddoxActionType.FINAL_PURCHASE)
        typedRestartSlot >= 0 -> SlayerMaddoxAction(typedRestartSlot, SlayerMaddoxActionType.FINAL_PURCHASE)
        genericConfirmSlot >= 0 -> SlayerMaddoxAction(genericConfirmSlot, SlayerMaddoxActionType.FINAL_PURCHASE)
        genericRestartSlot >= 0 -> SlayerMaddoxAction(genericRestartSlot, SlayerMaddoxActionType.FINAL_PURCHASE)
        else -> SlayerMaddoxAction(-1, SlayerMaddoxActionType.NAVIGATION)
      }
    }
  }

  private fun useHotbarUtilityItem(player: Player, keywords: Array<String>, teleportUse: Boolean = false): Boolean {
    if (pendingOverfluxLookDownTicks > 0) return false
    val slot = findHotbarSlotByKeywords(player, keywords)
    if (slot !in 0..8) return false
    if (teleportUse && !EtherwarpLogic.tryConsumeTeleportUseThisTick()) return false

    val previousSlot = player.inventory.selectedSlot
    if (previousSlot != slot) {
      InventoryUtils.holdHotbarSlot(slot)
    }

    mc.options.keyUse?.setDown(true)
    pendingHealRelease = true
    pendingHealRestoreSlot = if (previousSlot != slot) previousSlot else -1
    return true
  }

  private fun useHotbarOverflux(player: Player): Boolean {
    if (pendingOverfluxLookDownTicks > 0) return false
    val slot = findHotbarSlotByKeywords(player, configuredPowerOrbKeywords())
    if (slot !in 0..8) return false

    val previousSlot = player.inventory.selectedSlot
    if (previousSlot != slot) {
      InventoryUtils.holdHotbarSlot(slot)
    }

    pendingOverfluxLookDownTicks = OVERFLUX_LOOK_DOWN_TICKS
    pendingOverfluxRestoreSlot = if (previousSlot != slot) previousSlot else -1
    return true
  }

  private fun clearPendingOverfluxPlacement() {
    if (pendingOverfluxLookDownTicks > 0) {
      RotationExecutor.stopRotating()
    }
    pendingOverfluxLookDownTicks = 0
    pendingOverfluxRecoverLookTicks = 0
    pendingOverfluxRestoreSlot = -1
  }

  private fun clearPendingSpiderHyperionBeforeShooting() {
    if (pendingSpiderHyperionLookDownTicks > 0) {
      RotationExecutor.stopRotating()
    }
    pendingSpiderHyperionLookDownTicks = 0
    pendingSpiderHyperionRestoreSlot = -1
  }

  private fun applyOverfluxRecoverLook(player: Player) {
    val target = resolveCurrentTarget() ?: if (cryptZombieSlayer.value) resolveNearestSlayerBoss() else null
    if (target != null) {
      val activeCombatMode = effectiveCombatMode(target)
      val rotation =
        if (activeCombatMode != 0) {
          rangedAimRotation(player, target, activeCombatMode)
        } else {
          AngleUtils.getRotation(target)
        }
      RotationExecutor.rotateTo(rotation, rotationStrategy)
      return
    }

    if (cryptZombieSlayer.value) {
      val bossPos = slayerBossLastPos
      if (bossPos != null) {
        RotationExecutor.rotateTo(AngleUtils.getRotation(bossPos), rotationStrategy)
      }
    }
  }

  private fun findHotbarSlotByKeywords(
    player: Player,
    keywords: Array<String>,
    excludeSlayerUtility: Boolean = false
  ): Int {
    val inventory = player.inventory
    for (i in 0..8) {
      val stack = inventory.getItem(i)
      if (stack.isEmpty) continue
      val normalizedName = normalizeNameForMatch(stack.hoverName?.string.orEmpty())
      if (excludeSlayerUtility && SLAYER_NON_WEAPON_KEYWORDS.any { keyword -> normalizedName.contains(keyword) }) {
        continue
      }
      if (keywords.any { keyword -> normalizedName.contains(keyword) }) {
        return i
      }
    }
    return -1
  }

  private fun findHotbarSlotByKeywordPriority(
    player: Player,
    keywords: Array<String>,
    excludeSlayerUtility: Boolean = false
  ): Int {
    val normalizedKeywords = keywords
      .asSequence()
      .map(::normalizeNameForMatch)
      .filter { it.isNotBlank() }
      .distinct()
      .toList()
    if (normalizedKeywords.isEmpty()) return -1

    val inventory = player.inventory
    for (keyword in normalizedKeywords) {
      for (i in 0..8) {
        val stack = inventory.getItem(i)
        if (stack.isEmpty) continue
        val normalizedName = normalizeNameForMatch(stack.hoverName?.string.orEmpty())
        if (excludeSlayerUtility && SLAYER_NON_WEAPON_KEYWORDS.any { utility -> normalizedName.contains(utility) }) {
          continue
        }
        if (normalizedName.contains(keyword)) {
          return i
        }
      }
    }
    return -1
  }

  private fun isSpiderSlayerSelected(): Boolean = slayerType.value == 2

  private fun spiderSlayerLoadoutError(player: Player): String? {
    if (!cryptZombieSlayer.value || !isSpiderSlayerSelected()) return null

    val swordSlot = configuredSpiderMeleeWeaponSlot(player)
    val bowSlot = configuredSpiderBowWeaponSlot(player)

    return when {
      swordSlot !in 0..8 && bowSlot !in 0..8 ->
        "Combat macro stopped: Spider Slayer requires both a sword and a bow in your hotbar."
      swordSlot !in 0..8 ->
        "Combat macro stopped: Spider Slayer requires a sword in your hotbar."
      bowSlot !in 0..8 ->
        "Combat macro stopped: Spider Slayer requires a bow in your hotbar."
      else -> null
    }
  }

  private fun isSlayerBossName(normalizedName: String): Boolean {
    return SLAYER_BOSS_ENTITY_KEYWORDS.any { keyword -> normalizedName.contains(keyword) }
  }

  private fun isSlayerPriorityMobName(normalizedName: String): Boolean {
    return SLAYER_PRIORITY_MOB_KEYWORDS.any { keyword -> normalizedName.contains(keyword) }
  }

  private fun isHighTierSlayerPriorityMobName(normalizedName: String): Boolean {
    return SLAYER_HIGH_TIER_PRIORITY_MOB_KEYWORDS.any { keyword -> normalizedName.contains(keyword) }
  }

  private fun isSlayerFarmMobName(normalizedName: String): Boolean {
    if (slayerType.value == 3) {
      return isEndermanFarmMobLabel(normalizedName)
    }
    return SLAYER_FARM_MOB_KEYWORDS.any { keyword -> normalizedName.contains(keyword) }
  }

  private fun isSlayerFarmMobNames(normalizedNames: Collection<String>): Boolean {
    if (slayerType.value == 3) {
      return isEndermanFarmMobNames(normalizedNames)
    }
    return normalizedNames.any(::isSlayerFarmMobName)
  }

  private fun isEndermanFarmMobLabel(normalizedName: String): Boolean {
    val isBruiser = normalizedName.contains(ENDERMAN_HIDEOUT_FARM_MOB_KEYWORD)
    val isVoidling = normalizedName.contains(ENDERMAN_VOID_FARM_MOB_KEYWORD)
    val isEnderman = normalizedName.contains(ENDERMAN_END_FARM_MOB_KEYWORD)
    return when (endermanLocation.value) {
      0 -> isEnderman && !isBruiser && !isVoidling
      1 -> isBruiser
      2 -> isVoidling
      else -> false
    }
  }

  private fun isEndermanFarmMobNames(normalizedNames: Collection<String>): Boolean {
    val hasBruiser = normalizedNames.any { it.contains(ENDERMAN_HIDEOUT_FARM_MOB_KEYWORD) }
    val hasVoidling = normalizedNames.any { it.contains(ENDERMAN_VOID_FARM_MOB_KEYWORD) }
    val hasEnderman = normalizedNames.any { it.contains(ENDERMAN_END_FARM_MOB_KEYWORD) }
    return when (endermanLocation.value) {
      0 -> hasEnderman && !hasBruiser && !hasVoidling
      1 -> hasBruiser
      2 -> hasVoidling
      else -> false
    }
  }

  private fun readTabListLines(nowTick: Long): List<String> {
    if (slayerLastTabScanTick >= 0L && nowTick - slayerLastTabScanTick < SLAYER_TAB_SCAN_INTERVAL_TICKS) {
      return slayerTabCache
    }
    slayerLastTabScanTick = nowTick
    val connection = mc.connection ?: run {
      slayerTabCache = emptyList()
      return slayerTabCache
    }
    slayerTabCache =
      try {
        resolveTabEntries(connection)
          .mapNotNull { resolveEntryDisplayName(it) }
          .map { ChatFormatting.stripFormatting(it)?.lowercase(Locale.ROOT)?.trim() ?: "" }
          .filter { it.isNotBlank() }
      } catch (_: Exception) {
        emptyList()
      }
    return slayerTabCache
  }

  private fun resolveTabEntries(connection: Any): List<Any> {
    for (name in listOf("listPlayerEntries", "getListedOnlinePlayers", "getOnlinePlayers")) {
      val method = connection.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 } ?: continue
      val result = runCatching { method.invoke(connection) }.getOrNull() ?: continue
      when (result) {
        is Collection<*> -> return result.filterNotNull()
        is Iterable<*> -> return result.filterNotNull()
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
      val method = entry.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 } ?: continue
      val profile = runCatching { method.invoke(entry) }.getOrNull() ?: continue
      val nameMethod = profile.javaClass.methods.firstOrNull { it.name == "getName" && it.parameterCount == 0 } ?: continue
      val profileName = runCatching { nameMethod.invoke(profile) as? String }.getOrNull()
      if (!profileName.isNullOrBlank()) return profileName
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

  private fun isValidTarget(
    living: LivingEntity,
    player: Player,
    blacklisted: Set<String>,
    nameFilter: String,
    requireRange: Boolean,
    ignoreWhitelist: Boolean = false
  ): Boolean {
    if (living is ArmorStand) return false
    if (living == player) return false
    if (!living.isAlive) return false
    if (living is Player && mc.connection?.getPlayerInfo(living.uuid) != null) return false
    val displayName = normalizedTargetDisplayName(living)
    if (blacklisted.contains(displayName)) return false
    if (nameFilter.isNotEmpty() && !displayName.contains(nameFilter)) return false
    if (!ignoreWhitelist && whitelistOnly.value && !automationBypassWhitelist) {
      // Only apply whitelist if there are user-learned entries beyond the default seed.
      // The default "ice walker" entry is always present and must not block all other mobs.
      val effectiveWhitelist = learnedWhitelist.filter { it != DEFAULT_WHITELIST_ENTRY && !blacklisted.contains(it) }
      if (effectiveWhitelist.isNotEmpty()) {
        val whitelistMatch = effectiveWhitelist.any { entry ->
          displayName.contains(entry) || entry.contains(displayName)
        }
        if (!whitelistMatch) return false
      }
    }
    return true
  }

  private fun enforceStartArea(
    player: Player,
    level: ClientLevel
  ): Boolean {
    if (!stayNearStart.value) return false
    // Don't fight the patrol: it defines the operational area, so overriding its
    // pathfinder here would cause false ARRIVED signals and premature point advances.
    if (CombatPatrolModule.isPatrolRunning) return false
    val origin = startAreaOrigin ?: return false
    val radius = startAreaRadius.value + START_AREA_RETURN_BUFFER
    val distSq = horizontalDistSq(player.x, player.z, origin.x + 0.5, origin.z + 0.5)
    if (distSq <= radius * radius) {
      return false
    }

    val pathAlreadyReturning = startedPath && lastTargetPos == origin && nativeActive()
    if (!pathAlreadyReturning && level.gameTime - lastPathStartTick >= MIN_PATH_START_INTERVAL_TICKS) {
      lastPathStartTick = level.gameTime
      NativePathfinder.setTarget(origin.x + 0.5, origin.y.toDouble(), origin.z + 0.5)
      if (true) {
        lastTargetPos = origin
        startedPath = true
      }
    }
    currentTargetId = null
    RotationExecutor.stopRotating()
    return true
  }

  private fun horizontalDistSq(ax: Double, az: Double, bx: Double, bz: Double): Double {
    val dx = ax - bx
    val dz = az - bz
    return dx * dx + dz * dz
  }

  private fun attemptAttack(player: Player, target: LivingEntity, activeCombatMode: Int) {
    when (activeCombatMode) {
      1    -> attemptBowAttack(player, target)
      2    -> attemptMageAttack(player, target)
      else -> attemptMeleeAttack(player, target)
    }
  }

  private fun attemptMeleeAttack(player: Player, target: LivingEntity) {
    if (!isAttackReady(player, target)) return
    val now = System.nanoTime()
    if (now < nextAttackNs) return
    val minRate = min(minCps.value, maxCps.value).coerceAtLeast(0.1)
    val maxRate = max(minCps.value, maxCps.value).coerceAtLeast(minRate)
    val cps = minRate + (Math.random() * (maxRate - minRate))
    val delayNs = (1_000_000_000.0 / cps).toLong()
    nextAttackNs = now + delayNs
    MouseUtils.leftClick()
    registerKillCandidate(target)
  }

  private fun attemptBowAttack(player: Player, target: LivingEntity) {
    if (shouldUseSpiderPhaseBowCombat() && isSpiderBossHatchlingsPhaseActive() && matchesSpiderPhaseTarget(target)) {
      val bowSlot = configuredSpiderBowWeaponSlot(player)
      if (bowSlot !in 0..8) return
      if (player.inventory.selectedSlot != bowSlot) {
        InventoryUtils.holdHotbarSlot(bowSlot)
        return
      }
    }
    if (!isRangedAttackReady(player, target, 1)) return
    val now = System.nanoTime()
    if (now < nextAttackNs) return
    val minRate = min(minCps.value, maxCps.value).coerceAtLeast(0.1)
    val maxRate = max(minCps.value, maxCps.value).coerceAtLeast(minRate)
    val cps = minRate + (Math.random() * (maxRate - minRate))
    nextAttackNs = now + (1_000_000_000.0 / cps).toLong()
    MouseUtils.rightClick()
    registerKillCandidate(target)
    registerSpiderHatchlingShot(target)
    if (oneTapMode.value) currentTargetId = null
  }

  private fun attemptMageAttack(player: Player, target: LivingEntity) {
    if (!isRangedAttackReady(player, target, 2)) return
    val now = System.nanoTime()
    if (now < nextAttackNs) return
    val minRate = min(minCps.value, maxCps.value).coerceAtLeast(0.1)
    val maxRate = max(minCps.value, maxCps.value).coerceAtLeast(minRate)
    val cps = minRate + (Math.random() * (maxRate - minRate))
    nextAttackNs = now + (1_000_000_000.0 / cps).toLong()
    MouseUtils.rightClick()
    registerKillCandidate(target)
    if (oneTapMode.value) currentTargetId = null
  }

  private fun isRangedAttackReady(player: Player, target: LivingEntity, activeCombatMode: Int): Boolean {
    val isHatchlingTarget = shouldUseSpiderPhaseBowCombat() &&
      isSpiderBossHatchlingsPhaseActive() &&
      matchesSpiderPhaseTarget(target)
    if (!isHatchlingTarget && !player.hasLineOfSight(target)) return false
    val aimed = rangedAimRotation(player, target, activeCombatMode)
    val yawError = abs(AngleUtils.getRotationDelta(player.yRot, aimed.yaw))
    val pitchError = abs(aimed.pitch - player.xRot)
    val tolerance = aimTolerance.value
    return yawError <= tolerance && pitchError <= tolerance
  }

  /**
   * Returns the aim rotation for ranged modes, including upward pitch correction for
   * projectile drop/lead. Falls back to standard rotation in melee mode.
   */
  private fun rangedAimRotation(
    player: Player,
    target: LivingEntity,
    activeCombatMode: Int = combatMode.value
  ): org.cobalt.api.util.helper.Rotation {
    val base = AngleUtils.getRotation(target)
    if (activeCombatMode == 1 && shouldUseSpiderPhaseBowCombat() && isSpiderBossHatchlingsPhaseActive() && matchesSpiderPhaseTarget(target)) {
      return SpiderSlayerPhase.aimRotation(player, target)
    }
    val elevPerBlock = when (activeCombatMode) {
      1    -> bowElevationPerBlock.value
      2    -> mageElevationPerBlock.value
      else -> return base
    }
    if (elevPerBlock <= 0.0) return base
    val dist = player.distanceTo(target).toFloat()
    val correction = (elevPerBlock * dist).toFloat().coerceIn(0f, 45f)
    return org.cobalt.api.util.helper.Rotation(base.yaw, base.pitch - correction)
  }

  private fun registerKillCandidate(target: LivingEntity) {
    val level = mc.level ?: return
    killCandidateId = target.uuid
    killCandidateName = resolveTargetDisplayName(target)
    killCandidateWasSlayerBoss = cryptZombieSlayer.value && isSlayerBossEntity(target)
    killCandidateWasSlayerMob =
      cryptZombieSlayer.value &&
        !killCandidateWasSlayerBoss &&
        (isSlayerPriorityMobEntity(target) || isSlayerFarmMobEntity(target))
    killCandidateAttackTick = level.gameTime
    killCandidateExpiresTick = level.gameTime + KILL_CANDIDATE_TTL_TICKS
  }

  private fun updateKillTracking(level: ClientLevel) {
    val candidateId = killCandidateId ?: return
    if (level.gameTime > killCandidateExpiresTick) {
      clearKillCandidate()
      return
    }

    val candidate = level.entitiesForRendering().firstOrNull { it.uuid == candidateId } as? LivingEntity
    if (candidate == null) {
      if (level.gameTime - killCandidateAttackTick >= KILL_DISAPPEAR_CONFIRM_TICKS) {
        onKillCandidateConfirmed(killCandidateName ?: "")
        clearKillCandidate()
      }
      return
    }
    if (candidate.isAlive && candidate.health > 0f) {
      return
    }

    onKillCandidateConfirmed(killCandidateName ?: resolveTargetDisplayName(candidate))
    clearKillCandidate()
  }

  private fun onKillCandidateConfirmed(resolvedName: String) {
    if (killCandidateWasSlayerMob) {
      slayerSessionMobKills++
      if (slayerTrackedQuestActive && !slayerBossActive) {
        slayerTrackedQuestSawMobKill = true
      }
    }
    if (autoLearnLastKill.value) {
      applyLearnedKillTarget(resolvedName)
    }
    if (oneTapMode.value) currentTargetId = null
  }

  private fun clearKillCandidate() {
    killCandidateId = null
    killCandidateName = null
    killCandidateWasSlayerMob = false
    killCandidateWasSlayerBoss = false
    killCandidateExpiresTick = 0L
    killCandidateAttackTick = 0L
  }

  private fun applyLearnedKillTarget(rawName: String) {
    val learnedName = sanitizeTargetName(rawName)
    if (learnedName.isBlank()) return
    var changed = false

    if (targetName.value != learnedName) {
      targetName.value = learnedName
      changed = true
    }
    if (lastKillText.value != learnedName) {
      lastKillText.value = learnedName
      changed = true
    }
    if (learnedWhitelist.add(learnedName.lowercase())) {
      changed = true
    }

    val serializedWhitelist = serializeWhitelist()
    if (learnedWhitelistText.value != serializedWhitelist) {
      learnedWhitelistText.value = serializedWhitelist
      changed = true
    }
    lastSyncedWhitelistRaw = learnedWhitelistText.value

    ChatUtils.sendMessage("Combat macro learned target: $learnedName")
    if (changed) {
      Config.saveModulesConfig()
    }
  }

  private fun sanitizeTargetName(raw: String): String {
    val noFormatting = raw.replace(SANITIZE_COLOR_CODES, "").trim()
    val noLevelPrefix = noFormatting.replace(SANITIZE_LEVEL_PREFIX, "")
    val noSymbolsPrefix = noLevelPrefix.replace(SANITIZE_SYMBOL_PREFIX, "")
    val noHpSuffix = noSymbolsPrefix.replace(SANITIZE_HP_SUFFIX, "")
    return noHpSuffix.replace(SANITIZE_WHITESPACE, " ").trim()
  }

  private fun normalizeNameForMatch(raw: String): String {
    return sanitizeTargetName(raw).lowercase()
  }

  private fun resolveTargetDisplayName(living: LivingEntity): String {
    val baseName = sanitizeTargetName(living.name.string)
    val standNames = findAttachedArmorStandNames(living)
    if (standNames.isEmpty()) return baseName
    val baseNormalized = normalizeNameForMatch(baseName)
    val preferred = standNames.maxByOrNull { name -> scoreAttachedDisplayName(normalizeNameForMatch(name), baseNormalized) }
      ?: return baseName
    return if (scoreAttachedDisplayName(normalizeNameForMatch(preferred), baseNormalized) > 0) preferred else baseName
  }

  private fun normalizedTargetDisplayName(living: LivingEntity): String =
    normalizeNameForMatch(resolveTargetDisplayName(living))

  private fun targetMatchNames(living: LivingEntity): List<String> {
    targetMatchNamesCache[living.id]?.let { return it }
    val names = LinkedHashSet<String>()
    val baseName = normalizeNameForMatch(sanitizeTargetName(living.name.string))
    if (baseName.isNotBlank()) {
      names.add(baseName)
    }
    for (standName in findAttachedArmorStandNames(living)) {
      val normalized = normalizeNameForMatch(standName)
      if (normalized.isNotBlank()) {
        names.add(normalized)
      }
    }
    val result = names.toList()
    targetMatchNamesCache[living.id] = result
    return result
  }

  private fun isSlayerBossEntity(living: LivingEntity): Boolean =
    targetMatchNames(living).any(::isSlayerBossName)

  /** Returns true only when the Slayer ownership hologram explicitly names the local player. */
  private fun isSlayerBossOwnedByPlayer(living: LivingEntity): Boolean {
    val playerName = mc.player?.gameProfile?.name ?: return false
    val ownerName = slayerBossOwnerName(living) ?: return false
    return ownerName.equals(playerName, ignoreCase = true)
  }

  private fun slayerBossOwnerName(living: LivingEntity): String? {
    val standNames = LinkedHashSet<String>()
    standNames.addAll(findAttachedArmorStandNames(living))

    val level = mc.level
    if (level != null) {
      for (offset in 1..SLAYER_OWNER_ENTITY_ID_SCAN_OFFSETS) {
        val stand = level.getEntity(living.id + offset) as? ArmorStand ?: continue
        if (horizontalDistSq(stand.x, stand.z, living.x, living.z) > SLAYER_OWNER_STAND_MAX_DIST_SQ) continue
        val name = sanitizeTargetName(stand.name.string)
        if (name.isNotBlank()) {
          standNames.add(name)
        }
      }
    }

    for (standName in standNames) {
      parseSlayerBossOwnerName(standName)?.let { return it }
    }
    return null
  }

  private fun parseSlayerBossOwnerName(rawName: String): String? {
    val match = SLAYER_BOSS_OWNER_PATTERN.find(rawName) ?: return null
    return sanitizeTargetName(match.groupValues[1]).ifBlank { null }
  }

  private fun isSlayerPriorityMobEntity(living: LivingEntity): Boolean =
    targetMatchNames(living).any(::isSlayerPriorityMobName)

  private fun isSlayerFarmMobEntity(living: LivingEntity): Boolean =
    isSlayerFarmMobNames(targetMatchNames(living))

  private fun matchesEntityLabel(living: LivingEntity, matcher: (String) -> Boolean): Boolean =
    targetMatchNames(living).any(matcher)

  private fun scoreAttachedDisplayName(normalizedName: String, baseNormalized: String): Int {
    if (normalizedName.isBlank()) return Int.MIN_VALUE
    var score = 0
    if (normalizedName == baseNormalized) score += 300
    if (baseNormalized.isNotBlank() && (normalizedName.contains(baseNormalized) || baseNormalized.contains(normalizedName))) {
      score += 120
    }
    if (isSlayerBossName(normalizedName)) score += 1200
    if (isSlayerPriorityMobName(normalizedName)) score += 900
    if (isSlayerFarmMobName(normalizedName)) score += 700
    if (normalizedName.any(Char::isLetter)) score += 80
    if (isTransientAttachedLabel(normalizedName)) score -= 600
    return score
  }

  private fun isTransientAttachedLabel(normalizedName: String): Boolean {
    return ENDERMAN_HITS_PATTERNS.any { pattern -> pattern.containsMatchIn(normalizedName) } ||
      ATTACHED_LABEL_TRANSIENT_KEYWORDS.any { keyword -> normalizedName.contains(keyword) }
  }

  private fun findAttachedArmorStandNames(
    living: LivingEntity,
    horizontalRangeSq: Double = ATTACHED_NAMEPLATE_HORIZONTAL_RANGE_SQ
  ): List<String> {
    val level = mc.level ?: return emptyList()
    return level.entitiesForRendering()
      .asSequence()
      .filterIsInstance<ArmorStand>()
      .filter { it.isAlive }
      .filter { stand ->
        stand.y >= living.y - 0.5 &&
          stand.y <= living.y + 3.5 &&
          horizontalDistSq(stand.x, stand.z, living.x, living.z) <= horizontalRangeSq
      }
      .map { stand -> stand to sanitizeTargetName(stand.name.string) }
      .filter { (_, name) -> name.isNotBlank() }
      .filter { (_, name) -> !builtInBlacklistedNames.contains(normalizeNameForMatch(name)) }
      .sortedBy { (stand, _) ->
        horizontalDistSq(stand.x, stand.z, living.x, living.z) + kotlin.math.abs(stand.y - living.y)
      }
      .map { (_, name) -> name }
      .toList()
  }

  private fun resolveCurrentTarget(): LivingEntity? {
    val level = mc.level ?: return null
    val targetId = currentTargetId ?: return null
    val liveTarget = level.entitiesForRendering().firstOrNull { it.uuid == targetId } as? LivingEntity ?: return null
    if (!liveTarget.isAlive || liveTarget.health <= 0f) return null
    return liveTarget
  }

  private fun resolveCurrentTargetName(): String? {
    return resolveCurrentTarget()?.let { target ->
      resolveTargetDisplayName(target).ifBlank { null }
    }
  }

  private fun formatHudHealth(value: Float): String {
    val rounded = (value * 10f).toInt() / 10f
    return if (rounded % 1f == 0f) {
      rounded.toInt().toString()
    } else {
      rounded.toString()
    }
  }

  private fun syncAutoItemToggles(player: Player) {
    var changed = false

    val wandInHotbar = findHotbarSlotByKeywords(player, SLAYER_WAND_OF_ATONEMENT_KEYWORDS) in 0..8
    if (wandInHotbar && !wandWasInHotbar && !autoWandOfAtonement.value) {
      autoWandOfAtonement.value = true
      changed = true
    }
    wandWasInHotbar = wandInHotbar

    val hyperionInHotbar = findHotbarSlotByKeywords(player, SLAYER_HYPERION_KEYWORDS) in 0..8
    if (hyperionInHotbar && !hyperionWasInHotbar && !autoHyperion.value) {
      autoHyperion.value = true
      changed = true
    }
    hyperionWasInHotbar = hyperionInHotbar

    val zombieSwordInHotbar = findHotbarSlotByKeywords(player, SLAYER_ZOMBIE_SWORD_KEYWORDS) in 0..8
    if (zombieSwordInHotbar && !zombieSwordWasInHotbar && !autoZombieSword.value) {
      autoZombieSword.value = true
      changed = true
    }
    zombieSwordWasInHotbar = zombieSwordInHotbar

    val selectedPowerOrbInHotbar = findHotbarSlotByKeywords(player, configuredPowerOrbKeywords()) in 0..8
    if (selectedPowerOrbInHotbar && !overfluxWasInHotbar && !autoOverflux.value) {
      autoOverflux.value = true
      changed = true
    }
    overfluxWasInHotbar = selectedPowerOrbInHotbar

    val ragnarokInHotbar = findHotbarSlotByKeywords(player, SLAYER_RAGNAROK_KEYWORDS) in 0..8
    if (ragnarokInHotbar && !ragnarokWasInHotbar && !autoRagnarok.value) {
      autoRagnarok.value = true
      changed = true
    }
    ragnarokWasInHotbar = ragnarokInHotbar

    val reaperScytheInHotbar = findHotbarSlotByKeywords(player, SLAYER_REAPER_SCYTHE_KEYWORDS) in 0..8
    if (slayerType.value == 3 && reaperScytheInHotbar && !reaperScytheWasInHotbar && !autoReaperScythe.value) {
      autoReaperScythe.value = true
      changed = true
    }
    reaperScytheWasInHotbar = reaperScytheInHotbar

    val badHealthInHotbar = findHotbarSlotByKeywords(player, SLAYER_BAD_HEALTH_KEYWORDS) in 0..8
    if (badHealthInHotbar && !badHealthWasInHotbar && !autoSwordOfBadHealth.value) {
      autoSwordOfBadHealth.value = true
      changed = true
    }
    badHealthWasInHotbar = badHealthInHotbar

    if (changed) {
      Config.saveModulesConfig()
    }
  }

  private fun syncLearnedWhitelistFromSetting() {
    val raw = learnedWhitelistText.value
    if (raw == lastSyncedWhitelistRaw) return

    learnedWhitelist.clear()
    deserializeWhitelist(raw).forEach { learnedWhitelist.add(it) }
    learnedWhitelist.add(DEFAULT_WHITELIST_ENTRY)

    val normalized = serializeWhitelist()
    lastSyncedWhitelistRaw = normalized
    if (learnedWhitelistText.value != normalized) {
      learnedWhitelistText.value = normalized
    }
  }

  private fun deserializeWhitelist(raw: String): List<String> {
    if (raw.isBlank() || raw.equals("EMPTY", ignoreCase = true)) return emptyList()
    return raw
      .split(",")
      .asSequence()
      .map { it.trim().removeSuffix("...") }
      .filter { it.isNotEmpty() }
      .map(::normalizeNameForMatch)
      .filter { it.isNotBlank() && it != "empty" }
      .distinct()
      .toList()
  }

  private fun serializeWhitelist(): String {
    if (learnedWhitelist.isEmpty()) return "EMPTY"
    return learnedWhitelist.joinToString(", ")
  }

  private fun effectiveCombatMode(target: LivingEntity?): Int {
    if (target == null) return combatMode.value
    if (shouldUseSpiderPhaseBowCombat() && slayerBossActive && isSpiderBossHatchlingsPhaseActive()) {
      if (isSlayerBossEntity(target) || matchesSpiderPhaseTarget(target)) return 1
    }
    if (shouldUseEndermanDynamicCombat()) {
      if (!isSlayerBossEntity(target)) return effectiveEndermanSpawnCombatMode(target)
      return if (isEndermanBossHitPhase(target)) effectiveEndermanHitPhaseCombatMode() else 0
    }
    return combatMode.value
  }

  private fun shouldUseZombieDynamicCombat(): Boolean {
    return cryptZombieSlayer.value && slayerType.value == 0 && zombieDynamicCombat.value
  }

  private fun shouldUseSpiderPhaseBowCombat(): Boolean {
    return cryptZombieSlayer.value && slayerType.value == 2 && spiderPhaseBowCombat.value
  }

  private fun shouldTargetOnlySpiderHatchlings(nowTick: Long = mc.level?.gameTime ?: -1L): Boolean {
    return shouldUseSpiderPhaseBowCombat() && slayerBossActive && isSpiderBossHatchlingsPhaseActive(nowTick)
  }

  private fun shouldUseSpiderHyperionBeforeShooting(): Boolean {
    return shouldUseSpiderPhaseBowCombat() && spiderHyperionBeforeShooting.value
  }

  private fun handleSpiderHyperionBeforeShooting(player: Player): Boolean {
    if (!shouldUseSpiderHyperionBeforeShooting()) {
      spiderHyperionBeforeShootUsed = false
      return false
    }
    if (!slayerBossActive || !isSpiderBossHatchlingsPhaseActive()) {
      spiderHyperionBeforeShootUsed = false
      return false
    }
    if (spiderHyperionBeforeShootUsed || pendingSpiderHyperionLookDownTicks > 0) return pendingSpiderHyperionLookDownTicks > 0
    if (pendingHealRelease || pendingHealRestoreSlot >= 0 || pendingOverfluxLookDownTicks > 0 || pendingOverfluxRecoverLookTicks > 0) {
      return true
    }

    val hyperionSlot = findHotbarSlotByKeywords(player, SLAYER_HYPERION_KEYWORDS)
    if (hyperionSlot !in 0..8) {
      spiderHyperionBeforeShootUsed = true
      ChatUtils.sendMessage("Combat macro: no Wither blade found for Spider hatchling Hyperion pre-shot.")
      return false
    }

    val previousSlot = player.inventory.selectedSlot
    if (previousSlot != hyperionSlot) {
      InventoryUtils.holdHotbarSlot(hyperionSlot)
    }
    pendingSpiderHyperionRestoreSlot = if (previousSlot != hyperionSlot) previousSlot else -1
    pendingSpiderHyperionLookDownTicks = SPIDER_HYPERION_BEFORE_SHOOT_LOOK_DOWN_TICKS
    MovementManager.clearForcedMovement()
    return true
  }

  private fun shouldUseWolfPupsLogic(): Boolean {
    return cryptZombieSlayer.value && slayerType.value == 1
  }

  private fun shouldUseEndermanDynamicCombat(): Boolean {
    return cryptZombieSlayer.value && slayerType.value == 3 && endermanDynamicCombat.value
  }

  private fun effectiveEndermanSpawnCombatMode(target: LivingEntity): Int {
    return when (emanSpawnWeapon.value) {
      EMAN_SPAWN_WEAPON_ENDERMAN_SWORD -> 0
      EMAN_SPAWN_WEAPON_DYNAMIC -> if (shouldUseDynamicEndermanSpawnMelee(target)) 0 else 1
      else -> 1
    }
  }

  private fun effectiveEndermanHitPhaseCombatMode(): Int {
    return when (emanHitPhaseStyle.value) {
      EMAN_HIT_PHASE_WEAPON_ENDERMAN_SWORD -> 0
      else -> 1
    }
  }

  private fun shouldUseDynamicEndermanSpawnMelee(target: LivingEntity): Boolean {
    if (isSlayerPriorityMobEntity(target)) return true
    val player = mc.player ?: return false
    return player.distanceTo(target).toDouble() <= emanDynamicSwapRange.value
  }


  private fun shouldSuppressSlayerAttack(target: LivingEntity, nowTick: Long): Boolean {
    if (!cryptZombieSlayer.value) return false
    if (!isSlayerBossEntity(target)) return false
    return isSpiderBossHatchlingsPhaseActive(nowTick) || isWolfBossPupsPhaseActive(nowTick)
  }

  private fun handleSlayerPhaseMovement(
    player: Player,
    target: LivingEntity,
    distanceToTarget: Double,
    hasLos: Boolean,
    activeCombatMode: Int,
    nowTick: Long
  ): Boolean {
    if (!cryptZombieSlayer.value) return false
    // Laser phase takes priority over hits phase movement.
    if (shouldUseEndermanDynamicCombat() && emanLaserPhaseActive && isSlayerBossEntity(target)) {
      return applyEndermanLaserPhaseMovement(player)
    }
    if (shouldUseEndermanDynamicCombat() && isSlayerBossEntity(target) && isEndermanBossHitPhase(target)) {
      if (effectiveEndermanHitPhaseCombatMode() == 0) return false
      return applyEmanHitsPhaseStandoffMovement(player, target, distanceToTarget)
    }
    if (shouldUseSpiderPhaseBowCombat() && activeCombatMode == 1 && isSpiderBossHatchlingsPhaseActive(nowTick)) {
      if (isSlayerBossEntity(target) || matchesSpiderPhaseTarget(target)) {
        return applySpiderPhaseStepBackMovement(player, target, distanceToTarget)
      }
    }
    if (shouldUseWolfPupsLogic() && wolfIgnorePups.value && isWolfBossPupsPhaseActive(nowTick)) {
      if (isSlayerBossEntity(target)) {
        MovementManager.clearForcedMovement()
        return true
      }
    }
    return false
  }

  private fun applySpiderPhaseStepBackMovement(
    player: Player,
    target: LivingEntity,
    distanceToTarget: Double
  ): Boolean {
    val level = mc.level ?: return false
    val boss = SpiderSlayerPhase.resolveTrackedBoss(level, resolveNearestSlayerBoss(), ::isSlayerBossEntity)
    val bossPos = boss?.position() ?: SpiderSlayerPhase.bossAnchor
    val distanceFromBoss =
      if (bossPos != null) {
        SpiderSlayerPhase.distanceFromBoss(player, bossPos, ::horizontalDistSq)
      } else {
        distanceToTarget
      }
    val desiredDistance = SpiderSlayerPhase.MIN_BACKSTEP_DISTANCE
    val rotation = if (matchesSpiderPhaseTarget(target)) SpiderSlayerPhase.aimRotation(player, target) else rangedAimRotation(player, target, 1)
    RotationExecutor.rotateTo(rotation, rotationStrategy)
    val yawError = abs(AngleUtils.getRotationDelta(player.yRot, rotation.yaw))
    val pitchError = abs(rotation.pitch - player.xRot)
    val aligned = yawError <= SpiderSlayerPhase.STEPBACK_YAW_TOLERANCE &&
      pitchError <= SpiderSlayerPhase.STEPBACK_PITCH_TOLERANCE
    val backingOff = distanceFromBoss < desiredDistance

    if (backingOff) {
      MovementManager.setForcedMovement(
        forward = false, backward = true,
        left = false, right = false,
        jump = false, shift = false, sprint = false
      )
      if (startedPath && nativeActive()) nativeStop()
      startedPath = false
      lastTargetPos = null
      return true
    }
    if (aligned) {
      MovementManager.clearForcedMovement()
    }
    return false
  }

  private fun applySpiderPhaseBossBackoff(
    player: Player,
    level: ClientLevel,
    target: LivingEntity?
  ): Boolean {
    if (!shouldUseSpiderPhaseBowCombat() || !isSpiderBossHatchlingsPhaseActive(level.gameTime)) return false
    val boss = SpiderSlayerPhase.resolveTrackedBoss(level, resolveNearestSlayerBoss(), ::isSlayerBossEntity)
    val bossPos = boss?.position() ?: SpiderSlayerPhase.bossAnchor ?: return false
    val rotation =
      if (target != null && matchesSpiderPhaseTarget(target)) {
        SpiderSlayerPhase.aimRotation(player, target)
      } else {
        Rotation(AngleUtils.getRotation(bossPos).yaw, SpiderSlayerPhase.HATCHLING_LOOK_UP_PITCH)
      }
    RotationExecutor.rotateTo(rotation, rotationStrategy)
    val distanceFromBoss = SpiderSlayerPhase.distanceFromBoss(player, bossPos, ::horizontalDistSq)
    if (distanceFromBoss < SpiderSlayerPhase.MIN_BACKSTEP_DISTANCE) {
      MovementManager.setForcedMovement(
        forward = false, backward = true,
        left = false, right = false,
        jump = false, shift = false, sprint = false
      )
    } else {
      MovementManager.clearForcedMovement()
    }
    return true
  }

  private fun applyEmanHitsPhaseStandoffMovement(
    player: Player,
    target: LivingEntity,
    distanceToTarget: Double
  ): Boolean {
    // Bow mode: maintain the configured bow engage range (e.g. 7 blocks for term/shortbow).
    // Sword mode: keep the original close-in standoff (attackRange + buffer).
    val hitPhaseCombatMode = effectiveEndermanHitPhaseCombatMode()
    val standoffMin: Double
    val standoffMax: Double
    if (hitPhaseCombatMode != 0) {
      val bowRange = combatEngageRange(1)
      standoffMin = (bowRange - EMAN_HITS_PHASE_STANDOFF_BUFFER).coerceAtLeast(EMAN_HITS_PHASE_MIN_STANDOFF)
      standoffMax = bowRange + EMAN_HITS_PHASE_STANDOFF_BUFFER
    } else {
      standoffMin = EMAN_HITS_PHASE_MIN_STANDOFF
      standoffMax = attackRange.value + EMAN_HITS_PHASE_STANDOFF_BUFFER
    }
    if (distanceToTarget > standoffMax + EMAN_HITS_PHASE_STANDOFF_HYSTERESIS) return false
    val rotation = AngleUtils.getRotation(target)
    RotationExecutor.rotateTo(rotation, rotationStrategy)
    val yawError = abs(AngleUtils.getRotationDelta(player.yRot, rotation.yaw))
    val aligned = yawError <= EMAN_HITS_PHASE_YAW_TOLERANCE
    if (aligned && distanceToTarget < standoffMin) {
      MovementManager.setForcedMovement(
        forward = false, backward = true,
        left = false, right = false,
        jump = false, shift = false, sprint = false
      )
    } else if (aligned && distanceToTarget > combatEngageRange(hitPhaseCombatMode)) {
      MovementManager.setForcedMovement(
        forward = true, backward = false,
        left = false, right = false,
        jump = false, shift = false, sprint = true
      )
    } else {
      MovementManager.clearForcedMovement()
    }
    return true
  }

  /** During the enderman laser phase: jump continuously to dodge beams, then try to AOTV onto any nearby beacons. */
  private fun applyEndermanLaserPhaseMovement(player: Player): Boolean {
    // Look straight up for AOTV upward use.
    val lookUpRotation = Rotation(player.yRot, -90f)
    RotationExecutor.rotateTo(lookUpRotation, rotationStrategy)

    val shouldAotvUp = player.onGround() &&
      findHotbarSlotByKeywords(player, EMAN_LASER_AOTV_KEYWORDS) in 0..8
    if (shouldAotvUp) {
      // Look up and use AOTV to teleport ~11 blocks upward.
      useHotbarUtilityItem(player, EMAN_LASER_AOTV_KEYWORDS, teleportUse = true)
    } else {
      // Fallback: jump continuously to dodge beams.
      MovementManager.setForcedMovement(
        forward = false, backward = false,
        left = false, right = false,
        jump = player.onGround(), shift = false, sprint = false
      )
    }

    // AOTV onto nearby beacon blocks to disable them.
    tryAotvOntoBeacon(player)

    return true
  }

  private fun tryAotvOntoBeacon(player: Player) {
    val level = mc.level ?: return
    val aotvSlot = findHotbarSlotByKeywords(player, EMAN_LASER_AOTV_KEYWORDS)
    if (aotvSlot !in 0..8) return

    val px = player.blockX
    val py = player.blockY
    val pz = player.blockZ

    for (dx in -EMAN_BEACON_SCAN_RADIUS..EMAN_BEACON_SCAN_RADIUS) {
      for (dz in -EMAN_BEACON_SCAN_RADIUS..EMAN_BEACON_SCAN_RADIUS) {
        for (dy in -EMAN_BEACON_SCAN_RADIUS..EMAN_BEACON_SCAN_RADIUS) {
          val pos = net.minecraft.core.BlockPos(px + dx, py + dy, pz + dz)
          val state = level.getBlockState(pos)
          if (state.`is`(net.minecraft.world.level.block.Blocks.BEACON)) {
            // Aim at the top of the beacon and use AOTV.
            val beaconTop = net.minecraft.world.phys.Vec3(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5)
            val rotation = AngleUtils.getRotation(player.eyePosition, beaconTop)
            RotationExecutor.rotateTo(rotation, rotationStrategy)
            useHotbarUtilityItem(player, EMAN_LASER_AOTV_KEYWORDS, teleportUse = true)
            return
          }
        }
      }
    }
  }

  private fun updateSlayerBossPhaseState(level: ClientLevel, boss: LivingEntity?) {
    updateSpiderHatchlingsPhaseState(level, boss)
    updateWolfPupsPhaseState(level, boss)
    updateEndermanLaserPhaseState(level, boss)
  }

  private fun updateEndermanLaserPhaseState(level: ClientLevel, boss: LivingEntity?) {
    if (!shouldUseEndermanDynamicCombat()) {
      if (emanLaserPhaseActive) clearEndermanLaserPhase()
      return
    }
    val detected = boss != null && isEndermanBossLaserPhase(boss)
    if (detected && !emanLaserPhaseActive) {
      emanLaserPhaseActive = true
      emanLaserPhaseStartTick = level.gameTime
      slayerRagnarokUsedForLaserPhase = false
    } else if (!detected && emanLaserPhaseActive) {
      clearEndermanLaserPhase()
    }
  }

  private fun clearEndermanLaserPhase() {
    emanLaserPhaseActive = false
    emanLaserPhaseStartTick = -1L
  }

  private fun isEndermanBossLaserPhase(target: LivingEntity): Boolean {
    if (!shouldUseEndermanDynamicCombat()) return false
    if (!isSlayerBossEntity(target)) return false
    return findAttachedArmorStandNames(target, ENDERMAN_HITS_HORIZONTAL_RANGE_SQ)
      .asSequence()
      .map(::normalizeNameForMatch)
      .any { name -> ENDERMAN_LASER_TEXT_KEYWORDS.any { kw -> name.contains(kw) } }
  }

  private fun resolveSlayerPhaseTarget(
    player: Player,
    level: ClientLevel,
    boss: LivingEntity?
  ): LivingEntity? {
    val wantsSpiderAdds = shouldUseSpiderPhaseBowCombat() && isSpiderBossHatchlingsPhaseActive(level.gameTime)
    val wantsWolfAdds = shouldUseWolfPupsLogic() && !wolfIgnorePups.value && isWolfBossPupsPhaseActive(level.gameTime)
    if (!wantsSpiderAdds && !wantsWolfAdds) return null

    val bossPos = boss?.position() ?: slayerBossLastPos ?: return null
    val blacklisted = builtInBlacklistedNames
    var best: LivingEntity? = null
    var bestPriority = Int.MIN_VALUE
    var bestDistSq = Double.POSITIVE_INFINITY

    for (entity in level.entitiesForRendering()) {
      val living = entity as? LivingEntity ?: continue
      if (living is ArmorStand) {
        // "shoot me" targets are armor stands — allow them only during spider hatchlings phase
        if (!wantsSpiderAdds || !living.isAlive) continue
      } else {
        if (!isValidTarget(living, player, blacklisted, "", true, ignoreWhitelist = true)) continue
      }
      if (isSlayerBossEntity(living)) continue

      val matchesPhaseTarget =
        when {
          wantsSpiderAdds -> isSpiderShootMeTarget(living)
          wantsWolfAdds -> matchesEntityLabel(living, ::isWolfPhaseAddName)
          else -> false
        }
      if (!matchesPhaseTarget) continue
      if (wantsSpiderAdds && !isSpiderHatchlingShotEligible(living)) continue
      if (horizontalDistSq(living.x, living.z, bossPos.x, bossPos.z) > SpiderSlayerPhase.PHASE_ADD_SEARCH_RADIUS_SQ) continue

      val dx = living.x - player.x
      val dy = living.y - player.y
      val dz = living.z - player.z
      val distSq = dx * dx + dy * dy + dz * dz
      val priority =
        when {
          wantsSpiderAdds && isSpiderShootMeTarget(living) -> 1
          else -> 0
        }
      if (priority > bestPriority || (priority == bestPriority && distSq < bestDistSq)) {
        best = living
        bestPriority = priority
        bestDistSq = distSq
      }
    }

    if (wantsSpiderAdds) {
      if (best != null) {
        SpiderSlayerPhase.registerSelectedTarget(best)
      } else if (SpiderSlayerPhase.shouldReleaseToBossOnNoTarget(level.gameTime)) {
        clearSpiderHatchlingsPhase()
      }
    }
    return best
  }

  private fun activateSpiderHatchlingsPhase(nowTick: Long) {
    if (!shouldUseSpiderPhaseBowCombat()) return
    clearPendingSpiderHyperionBeforeShooting()
    spiderHyperionBeforeShootUsed = false
    SpiderSlayerPhase.activate(nowTick, resolveNearestSlayerBoss(), slayerBossLastPos)
  }

  private fun clearSpiderHatchlingsPhase() {
    clearPendingSpiderHyperionBeforeShooting()
    SpiderSlayerPhase.clear()
    spiderHyperionBeforeShootUsed = false
  }

  private fun updateSpiderHatchlingsPhaseState(level: ClientLevel, boss: LivingEntity?) {
    SpiderSlayerPhase.update(
      level = level,
      boss = boss,
      enabled = shouldUseSpiderPhaseBowCombat(),
      slayerBossActive = slayerBossActive,
      isSlayerBoss = ::isSlayerBossEntity,
      matchesPhaseTarget = ::matchesSpiderPhaseTarget,
      horizontalDistSq = ::horizontalDistSq,
    )
    if (!SpiderSlayerPhase.isActive(level.gameTime, shouldUseSpiderPhaseBowCombat())) {
      spiderHyperionBeforeShootUsed = false
    }
  }

  private fun updateWolfPupsPhaseState(level: ClientLevel, boss: LivingEntity?) {
    if (!shouldUseWolfPupsLogic()) {
      wolfPupsPhaseUntilTick = -1L
      return
    }
    if (!slayerBossActive || boss == null) {
      wolfPupsPhaseUntilTick = -1L
      return
    }

    if (hasNearbyAttachedText(boss, SLAYER_WOLF_PUPS_TEXT_KEYWORDS)) {
      wolfPupsPhaseUntilTick = level.gameTime + SLAYER_WOLF_PUPS_PHASE_TICKS
    } else if (wolfPupsPhaseUntilTick >= 0L && level.gameTime >= wolfPupsPhaseUntilTick) {
      wolfPupsPhaseUntilTick = -1L
    }
  }

  private fun isSpiderBossHatchlingsPhaseActive(nowTick: Long = mc.level?.gameTime ?: -1L): Boolean {
    return SpiderSlayerPhase.isActive(nowTick, shouldUseSpiderPhaseBowCombat())
  }

  private fun isWolfBossPupsPhaseActive(nowTick: Long = mc.level?.gameTime ?: -1L): Boolean {
    if (!shouldUseWolfPupsLogic()) return false
    return wolfPupsPhaseUntilTick >= 0L && nowTick < wolfPupsPhaseUntilTick
  }

  private fun resolveNearestSlayerBoss(): LivingEntity? {
    val level = mc.level ?: return null
    val player = mc.player
    return level.entitiesForRendering()
      .asSequence()
      .filterIsInstance<LivingEntity>()
      .filter { it.isAlive && it.health > 0f }
      .filter(::isSlayerBossEntity)
      .minByOrNull { entity ->
        if (player != null) {
          val dx = entity.x - player.x
          val dy = entity.y - player.y
          val dz = entity.z - player.z
          dx * dx + dy * dy + dz * dz
        } else {
          0.0
        }
      }
  }

  private fun hasNearbyAttachedText(target: LivingEntity, keywords: Array<String>): Boolean {
    val level = mc.level ?: return false
    return level.entitiesForRendering()
      .asSequence()
      .filterIsInstance<ArmorStand>()
      .filter { it.isAlive }
      .filter { stand ->
        stand.y >= target.y - 0.5 &&
          stand.y <= target.y + 3.5 &&
          horizontalDistSq(stand.x, stand.z, target.x, target.z) <= ATTACHED_NAMEPLATE_HORIZONTAL_RANGE_SQ
      }
      .map { stand -> normalizeNameForMatch(stand.name.string) }
      .any { text -> keywords.any { keyword -> text.contains(keyword) } }
  }

  private fun isSpiderPhaseAddName(normalizedName: String): Boolean {
    return SpiderSlayerPhase.addNameMatches(normalizedName)
  }

  private fun isSpiderShootMeName(normalizedName: String): Boolean {
    return SpiderSlayerPhase.shootMeNameMatches(normalizedName)
  }

  private fun isSpiderShootMeTarget(living: LivingEntity): Boolean =
    matchesEntityLabel(living, ::isSpiderShootMeName)

  private fun matchesSpiderPhaseTarget(living: LivingEntity): Boolean =
    matchesEntityLabel(living, ::isSpiderPhaseTargetName)

  private fun isSpiderHatchlingShotEligible(living: LivingEntity): Boolean {
    return SpiderSlayerPhase.isShotEligible(living)
  }

  private fun registerSpiderHatchlingShot(target: LivingEntity) {
    if (!shouldUseSpiderPhaseBowCombat() || !isSpiderBossHatchlingsPhaseActive()) return
    if (!matchesSpiderPhaseTarget(target)) return
    if (SpiderSlayerPhase.registerShot(target)) {
      currentTargetId = null
    }
  }

  private fun isSpiderPhaseTargetName(normalizedName: String): Boolean {
    return isSpiderPhaseAddName(normalizedName) || isSpiderShootMeName(normalizedName)
  }

  private fun isWolfPhaseAddName(normalizedName: String): Boolean {
    return SLAYER_WOLF_PHASE_ADD_KEYWORDS.any { keyword -> normalizedName.contains(keyword) }
  }

  private fun isEndermanBossHitPhase(target: LivingEntity): Boolean {
    if (!shouldUseEndermanDynamicCombat()) return false
    if (!isSlayerBossEntity(target)) return false
    return findAttachedArmorStandNames(target, ENDERMAN_HITS_HORIZONTAL_RANGE_SQ)
      .asSequence()
      .map(::normalizeNameForMatch)
      .any { name ->
        ENDERMAN_HITS_PATTERNS.any { pattern -> pattern.containsMatchIn(name) } ||
          ENDERMAN_HITS_TEXT_KEYWORDS.any { keyword -> name.contains(keyword) }
      }
  }

  private fun keywordSettingValues(raw: String, fallback: Array<String>): Array<String> {
    val parsed = raw
      .split(',', ';', '\n')
      .map(::normalizeNameForMatch)
      .filter { it.isNotBlank() }
      .distinct()
    return if (parsed.isEmpty()) fallback else parsed.toTypedArray()
  }

  private fun configuredSlayerWeaponKeywords(activeCombatMode: Int): Array<String> {
    return when (activeCombatMode) {
      0 -> keywordSettingValues(slayerMeleeWeapon.value, SLAYER_MELEE_WEAPON_KEYWORDS)
      1 -> keywordSettingValues(slayerBowWeapon.value, SLAYER_BOW_WEAPON_KEYWORDS)
      2 -> keywordSettingValues(slayerMageWeapon.value, SLAYER_MAGE_WEAPON_KEYWORDS)
      else -> SLAYER_ANY_WEAPON_KEYWORDS
    }
  }

  private fun configuredZombieMeleeWeaponKeywords(): Array<String> =
    keywordSettingValues(zombieDynamicSword.value, SLAYER_ZOMBIE_DYNAMIC_SWORD_DEFAULT_KEYWORDS)

  private fun configuredSpiderMeleeWeaponKeywords(): Array<String> =
    keywordSettingValues(spiderMeleeWeapon.value, SLAYER_SPIDER_MELEE_WEAPON_DEFAULT_KEYWORDS)

  private fun configuredSpiderBowWeaponKeywords(): Array<String> =
    keywordSettingValues(spiderBowWeapon.value, SLAYER_BOW_WEAPON_KEYWORDS)

  private fun configuredSpiderMeleeWeaponSlot(player: Player): Int {
    return if (spiderAutoDetectSword.value) {
      findHotbarSlotByKeywords(player, SLAYER_MELEE_WEAPON_KEYWORDS, excludeSlayerUtility = true)
    } else {
      findHotbarSlotByKeywordPriority(player, configuredSpiderMeleeWeaponKeywords(), excludeSlayerUtility = true)
    }
  }

  private fun configuredSpiderBowWeaponSlot(player: Player): Int {
    return if (spiderAutoDetectBow.value) {
      findHotbarSlotByKeywords(player, SLAYER_BOW_WEAPON_KEYWORDS, excludeSlayerUtility = true)
    } else {
      findHotbarSlotByKeywordPriority(player, configuredSpiderBowWeaponKeywords(), excludeSlayerUtility = true)
    }
  }

  private fun configuredPowerOrbKeywords(): Array<String> {
    return when (loadoutPowerOrbChoice.value) {
      POWER_ORB_PLASMAFLUX -> SLAYER_PLASMAFLUX_KEYWORDS
      POWER_ORB_SOS_FLARE -> SLAYER_SOS_FLARE_KEYWORDS
      POWER_ORB_MANA_FLUX -> SLAYER_MANAFLUX_KEYWORDS
      else -> SLAYER_OVERFLUX_KEYWORDS
    }
  }

  private fun defaultEndermanSpawnBowWeaponKeywords(): Array<String> {
    return when (emanSpawnWeapon.value) {
      EMAN_SPAWN_WEAPON_TERMINATOR -> SLAYER_ENDERMAN_TERMINATOR_WEAPON_KEYWORDS
      EMAN_SPAWN_WEAPON_SHORTBOW -> SLAYER_ENDERMAN_SHORTBOW_WEAPON_KEYWORDS
      else -> SLAYER_ENDERMAN_DYNAMIC_BOW_WEAPON_KEYWORDS
    }
  }

  private fun configuredEndermanSpawnBowWeaponKeywords(): Array<String> =
    keywordSettingValues(emanSpawnBowWeapon.value, defaultEndermanSpawnBowWeaponKeywords())

  private fun configuredEndermanSpawnSwordWeaponKeywords(): Array<String> =
    keywordSettingValues(emanSpawnSwordWeapon.value, SLAYER_ENDERMAN_SPAWN_SWORD_DEFAULT_KEYWORDS)

  private fun defaultEndermanHitPhaseBowWeaponKeywords(): Array<String> {
    return when (emanHitPhaseStyle.value) {
      EMAN_HIT_PHASE_WEAPON_SHORTBOW -> SLAYER_ENDERMAN_SHORTBOW_WEAPON_KEYWORDS
      else -> SLAYER_ENDERMAN_TERMINATOR_WEAPON_KEYWORDS
    }
  }

  private fun configuredEndermanHitPhaseBowWeaponKeywords(): Array<String> =
    keywordSettingValues(emanHitPhaseBowWeapon.value, defaultEndermanHitPhaseBowWeaponKeywords())

  private fun configuredEndermanHitPhaseSwordWeaponKeywords(): Array<String> =
    keywordSettingValues(emanHitPhaseSwordWeapon.value, SLAYER_ENDERMAN_BOSS_WEAPON_DEFAULT_KEYWORDS)

  private fun preferredEndermanSpawnWeaponKeywords(target: LivingEntity?): Array<String> {
    return when (emanSpawnWeapon.value) {
      EMAN_SPAWN_WEAPON_TERMINATOR,
      EMAN_SPAWN_WEAPON_SHORTBOW ->
        configuredEndermanSpawnBowWeaponKeywords()
      EMAN_SPAWN_WEAPON_ENDERMAN_SWORD ->
        configuredEndermanSpawnSwordWeaponKeywords()
      EMAN_SPAWN_WEAPON_DYNAMIC ->
        if (target != null && shouldUseDynamicEndermanSpawnMelee(target)) {
          configuredEndermanSpawnSwordWeaponKeywords()
        } else {
          configuredEndermanSpawnBowWeaponKeywords()
        }
      else -> configuredEndermanSpawnBowWeaponKeywords()
    }
  }

  private fun preferredEndermanHitPhaseWeaponKeywords(): Array<String> {
    return when (emanHitPhaseStyle.value) {
      EMAN_HIT_PHASE_WEAPON_SHORTBOW,
      EMAN_HIT_PHASE_WEAPON_TERMINATOR -> configuredEndermanHitPhaseBowWeaponKeywords()
      EMAN_HIT_PHASE_WEAPON_ENDERMAN_SWORD ->
        configuredEndermanHitPhaseSwordWeaponKeywords()
      else -> configuredEndermanHitPhaseBowWeaponKeywords()
    }
  }

  private fun preferredSlayerWeaponKeywords(activeCombatMode: Int, target: LivingEntity?): Array<String> {
    return when {
      shouldUseZombieDynamicCombat() && slayerBossActive ->
        configuredZombieMeleeWeaponKeywords()
      shouldUseZombieDynamicCombat() ->
        keywordSettingValues(zombieSpawnWeapon.value, SLAYER_ZOMBIE_SPAWN_WEAPON_DEFAULT_KEYWORDS)
      shouldUseEndermanDynamicCombat() && target != null &&
        isSlayerBossEntity(target) && isEndermanBossHitPhase(target) ->
        preferredEndermanHitPhaseWeaponKeywords()
      shouldUseEndermanDynamicCombat() && target != null &&
        isSlayerBossEntity(target) ->
        keywordSettingValues(emanBossWeapon.value, SLAYER_ENDERMAN_BOSS_WEAPON_DEFAULT_KEYWORDS)
      shouldUseEndermanDynamicCombat() ->
        preferredEndermanSpawnWeaponKeywords(target)
      slayerType.value == 0 && activeCombatMode == 0 ->
        configuredZombieMeleeWeaponKeywords()
      slayerType.value == 2 && activeCombatMode == 0 ->
        configuredSpiderMeleeWeaponKeywords()
      slayerType.value == 2 && activeCombatMode == 1 ->
        configuredSpiderBowWeaponKeywords()
      slayerType.value == 3 && activeCombatMode == 0 && target != null && isSlayerBossEntity(target) ->
        keywordSettingValues(emanBossWeapon.value, SLAYER_ENDERMAN_BOSS_WEAPON_DEFAULT_KEYWORDS)
      slayerType.value == 3 && activeCombatMode == 0 ->
        configuredEndermanSpawnSwordWeaponKeywords()
      slayerType.value == 3 && activeCombatMode == 1 && target != null &&
        isSlayerBossEntity(target) && isEndermanBossHitPhase(target) ->
        configuredEndermanHitPhaseBowWeaponKeywords()
      slayerType.value == 3 && activeCombatMode == 1 ->
        configuredEndermanSpawnBowWeaponKeywords()
      activeCombatMode == 0 -> configuredSlayerWeaponKeywords(0)
      activeCombatMode == 1 -> configuredSlayerWeaponKeywords(1)
      activeCombatMode == 2 -> configuredSlayerWeaponKeywords(2)
      else -> SLAYER_ANY_WEAPON_KEYWORDS
    }
  }

  private fun findPreferredSlayerWeaponSlot(player: Player, activeCombatMode: Int, target: LivingEntity?): Int {
    if (isSpiderSlayerSelected()) {
      when (activeCombatMode) {
        0 -> configuredSpiderMeleeWeaponSlot(player).takeIf { it in 0..8 }?.let { return it }
        1 -> configuredSpiderBowWeaponSlot(player).takeIf { it in 0..8 }?.let { return it }
      }
    }
    val primaryKeywords = preferredSlayerWeaponKeywords(activeCombatMode, target)
    val primarySlot = findHotbarSlotByKeywordPriority(player, primaryKeywords, excludeSlayerUtility = true)
    if (primarySlot in 0..8) return primarySlot
    return findHotbarSlotByKeywordPriority(player, SLAYER_ANY_WEAPON_KEYWORDS, excludeSlayerUtility = true)
  }

  private fun isSlayerWeaponForMode(
    player: Player,
    slot: Int,
    normalizedName: String,
    activeCombatMode: Int,
    target: LivingEntity?
  ): Boolean {
    if (SLAYER_NON_WEAPON_KEYWORDS.any { keyword -> normalizedName.contains(keyword) }) return false
    val preferredSlot = findPreferredSlayerWeaponSlot(player, activeCombatMode, target)
    if (preferredSlot in 0..8) return preferredSlot == slot
    val keywords = preferredSlayerWeaponKeywords(activeCombatMode, target)
    return keywords.any { keyword -> normalizedName.contains(keyword) }
  }

  private fun ensurePreferredWeapon(player: Player, target: LivingEntity, nowTick: Long) {
    val name = normalizedTargetDisplayName(target)
    if (!prefersDrillForTargetName(name)) return
    ensureWalkerDrillSelected(player, nowTick, name)
  }

  private fun prefersDrillForTargetName(rawName: String): Boolean {
    val normalized = normalizeNameForMatch(rawName)
    return normalized.contains("ice walker") || normalized.contains("glacite walker")
  }

  private fun ensureWalkerDrillSelected(player: Player, nowTick: Long, rawTargetName: String) {
    val selected = player.inventory.getItem(player.inventory.selectedSlot)
    val selectedName = normalizeNameForMatch(selected.hoverName?.string.orEmpty())
    if (selectedName.contains("drill")) return

    val drillSlot = InventoryUtils.findItemInHotbar("drill")
    if (drillSlot in 0..8) {
      InventoryUtils.holdHotbarSlot(drillSlot)
      return
    }

    if (nowTick - drillWarnTick >= DRILL_WARN_INTERVAL_TICKS) {
      drillWarnTick = nowTick
      val label = normalizeNameForMatch(rawTargetName).ifBlank { "glacite walker" }
      ChatUtils.sendMessage("Combat macro: no drill found in hotbar for $label target.")
    }
  }

  /** Ensures a weapon is selected when in slayer mode. Switches away from utility items
   *  (batphone, overflux, etc.) to the nearest sword/weapon in the hotbar. */
  private fun ensureSlayerWeapon(player: Player, target: LivingEntity?): Boolean {
    if (pendingHealRelease || pendingHealRestoreSlot >= 0) return false
    val activeCombatMode = effectiveCombatMode(target)
    val currentSlot = player.inventory.selectedSlot
    if (shouldPrimeEmanHitPhaseBossWeapon(target)) {
      val bossSlot = findHotbarSlotByKeywords(
        player,
        keywordSettingValues(emanBossWeapon.value, SLAYER_ENDERMAN_BOSS_WEAPON_DEFAULT_KEYWORDS)
      )
      if (!emanHitPhaseBossWeaponPrimed) {
        emanHitPhaseBossWeaponPrimed = true
        if (bossSlot in 0..8 && currentSlot != bossSlot) {
          InventoryUtils.holdHotbarSlot(bossSlot)
          return true
        }
      }
    } else {
      emanHitPhaseBossWeaponPrimed = false
    }
    val preferredSlot = findPreferredSlayerWeaponSlot(player, activeCombatMode, target)
    val current = player.inventory.getItem(currentSlot)
    val currentName = normalizeNameForMatch(current.hoverName?.string.orEmpty())
    if (!current.isEmpty) {
      if (preferredSlot in 0..8) {
        if (preferredSlot != currentSlot) {
          InventoryUtils.holdHotbarSlot(preferredSlot)
        }
        return false
      }
      if (isSlayerWeaponForMode(player, currentSlot, currentName, activeCombatMode, target)) return false
      val isUtility = SLAYER_NON_WEAPON_KEYWORDS.any { currentName.contains(it) }
      if (!isUtility) return false
    }
    if (preferredSlot in 0..8 && preferredSlot != currentSlot) {
      InventoryUtils.holdHotbarSlot(preferredSlot)
    }
    return false
  }

  private fun shouldPrimeEmanHitPhaseBossWeapon(target: LivingEntity?): Boolean {
    return shouldUseEndermanDynamicCombat() &&
      target != null &&
      isSlayerBossEntity(target) &&
      isEndermanBossHitPhase(target) &&
      effectiveEndermanHitPhaseCombatMode() == 1
  }

  private fun applyEmanHitPhaseSneak(target: LivingEntity?, shouldAttack: Boolean) {
    val shouldSneak =
      emanHitPhaseSneakWhenHitting.value &&
        shouldUseEndermanDynamicCombat() &&
        shouldAttack &&
        target != null &&
        isSlayerBossEntity(target) &&
        isEndermanBossHitPhase(target)
    if (shouldSneak) {
      mc.options.keyShift?.setDown(true)
      emanHitPhaseSneakApplied = true
    }
  }

  private fun releaseEmanHitPhaseSneak() {
    if (!emanHitPhaseSneakApplied) return
    mc.options.keyShift?.setDown(false)
    emanHitPhaseSneakApplied = false
  }

  private fun tryAutoHeal(player: Player, nowTick: Long) {
    if (!autoHeal.value) return
    val effectiveHealth = player.health + player.absorptionAmount
    if (effectiveHealth > healAtHealth.value.toFloat()) return
    if (nowTick - lastHealUseTick < AUTO_HEAL_COOLDOWN_TICKS) return

    val healSlot = findHealHotbarSlot(player)
    if (healSlot !in 0..8) return

    lastHealUseTick = nowTick
    val previousSlot = player.inventory.selectedSlot

    if (previousSlot != healSlot) {
      InventoryUtils.holdHotbarSlot(healSlot)
    }

    mc.options.keyUse?.setDown(true)
    pendingHealRelease = true
    pendingHealRestoreSlot = if (previousSlot != healSlot) previousSlot else -1
  }

  private fun tryAlwaysUseWandOfAtonement(player: Player, nowTick: Long) {
    if (!alwaysUseWandOfAtonement.value) return
    if (pendingHealRelease || pendingHealRestoreSlot >= 0 || pendingOverfluxLookDownTicks > 0) return
    if (nowTick - lastAlwaysWandUseTick < ALWAYS_WAND_OF_ATONEMENT_INTERVAL_TICKS) return

    if (useHotbarUtilityItem(player, SLAYER_WAND_OF_ATONEMENT_KEYWORDS)) {
      lastAlwaysWandUseTick = nowTick
    }
  }

  private fun findHealHotbarSlot(player: Player): Int {
    if (autoWandOfAtonement.value) {
      val wandSlot = findHotbarSlotByKeywords(player, SLAYER_WAND_OF_ATONEMENT_KEYWORDS)
      if (wandSlot in 0..8) {
        return wandSlot
      }
    }

    if (autoHyperion.value) {
      val hyperionSlot = findHotbarSlotByKeywords(player, SLAYER_HYPERION_KEYWORDS)
      if (hyperionSlot in 0..8) {
        return hyperionSlot
      }
    }

    if (autoZombieSword.value) {
      val zombieSwordSlot = findHotbarSlotByKeywords(player, SLAYER_ZOMBIE_SWORD_KEYWORDS)
      if (zombieSwordSlot in 0..8) {
        return zombieSwordSlot
      }
    }

    return -1
  }

  private fun currentTargetRenderColor(): Color {
    val phase = (System.currentTimeMillis() % TARGET_BOX_CYCLE_MS).toDouble() / TARGET_BOX_CYCLE_MS.toDouble()
    val t = 0.5 - 0.5 * cos(phase * Math.PI * 2.0)
    val r = lerpChannel(CYAN_COLOR.red, PINK_COLOR.red, t)
    val g = lerpChannel(CYAN_COLOR.green, PINK_COLOR.green, t)
    val b = lerpChannel(CYAN_COLOR.blue, PINK_COLOR.blue, t)
    return Color(r, g, b, TARGET_BOX_ALPHA)
  }

  private fun lerpChannel(start: Int, end: Int, t: Double): Int {
    return (start + (end - start) * t).toInt().coerceIn(0, 255)
  }

  private fun shouldUseDirectChase(target: LivingEntity, hasLos: Boolean, distanceToTarget: Double): Boolean {
    val slayerGapBonus =
      when {
        cryptZombieSlayer.value && isSlayerBossEntity(target) -> SLAYER_BOSS_DIRECT_CHASE_GAP_BONUS
        cryptZombieSlayer.value && isSlayerPriorityMobEntity(target) -> SLAYER_MINIBOSS_DIRECT_CHASE_GAP_BONUS
        else -> 0.0
      }
    val handoffGap = SOFT_CHASE_HANDOFF_GAP + slayerGapBonus
    val stickyExitGap = SOFT_CHASE_STICKY_EXIT_GAP + slayerGapBonus
    val losGraceGap = SOFT_CHASE_LOS_GRACE_GAP + min(slayerGapBonus, SOFT_CHASE_MAX_GAP)

    val enterDirectChase = hasLos && distanceToTarget <= attackRange.value + handoffGap
    val stayDirectChase =
      closeChaseActive &&
        closeChaseTargetId == target.uuid &&
        distanceToTarget <= attackRange.value + stickyExitGap &&
        (hasLos || distanceToTarget <= attackRange.value + losGraceGap)

    val active = enterDirectChase || stayDirectChase
    closeChaseActive = active
    closeChaseTargetId = if (active) target.uuid else null
    return active
  }

  private fun resetCloseChase() {
    closeChaseActive = false
    closeChaseTargetId = null
  }

  private fun renderSlayerHighlights(context: org.cobalt.api.event.impl.render.WorldRenderContext, level: ClientLevel) {
    val cache = slayerHighlightCache
    if (cache.isEmpty()) return
    val espMode = slayerEspTargets.value
    // Use the tick-computed cache to avoid re-running the O(N²) armor-stand scan every frame.
    for (entity in level.entitiesForRendering()) {
      val living = entity as? LivingEntity ?: continue
      if (!living.isAlive || living.health <= 0f) continue
      val highlightState = cache[living.uuid] ?: continue
      if (!slayerHighlightMatchesMode(highlightState.type, espMode)) continue
      drawSlayerAura(context, living.boundingBox.inflate(TARGET_BOX_INFLATE), highlightState)
    }
  }

  private fun slayerHighlightMatchesMode(type: SlayerHighlightType, mode: Int): Boolean =
    when (mode) {
      0 -> type == SlayerHighlightType.BOSS
      1 -> type != SlayerHighlightType.BOSS
      else -> true
    }

  private fun drawSlayerAura(
    context: org.cobalt.api.event.impl.render.WorldRenderContext,
    box: net.minecraft.world.phys.AABB,
    state: SlayerHighlightState,
  ) {
    val palette = paletteForSlayerHighlight(state)

    Render3D.drawStyledBox(
      context,
      box.inflate(SLAYER_ESP_OUTER_INFLATE),
      palette.outerStroke,
      palette.outerFill,
      esp = true,
      lineWidth = 3.2f
    )
    Render3D.drawStyledBox(
      context,
      box.inflate(SLAYER_ESP_MID_INFLATE),
      palette.stroke,
      palette.fill,
      esp = true,
      lineWidth = 2.8f
    )
    Render3D.drawStyledBox(
      context,
      box,
      palette.stroke,
      palette.fill,
      esp = true,
      lineWidth = 2.2f
    )
  }

  private fun buildSlayerHighlightState(
    names: Collection<String>,
    type: SlayerHighlightType
  ): SlayerHighlightState {
    val attunement =
      if (slayerType.value == 5 && slayerBlazeAttunementColors.value) resolveBlazeAttunement(names) else null
    return SlayerHighlightState(type, attunement)
  }

  private fun paletteForSlayerHighlight(state: SlayerHighlightState): SlayerEspPalette {
    val baseArgb =
      when {
        state.attunement != null -> state.attunement.argb
        state.type == SlayerHighlightType.BOSS -> slayerBossEspColor.value
        state.type == SlayerHighlightType.HIGH_TIER_MINIBOSS -> slayerHighTierMiniEspColor.value
        else -> slayerMiniBossEspColor.value
      }
    return buildPalette(Color(baseArgb, true))
  }

  private fun buildPalette(base: Color): SlayerEspPalette {
    val strokeAlpha = base.alpha.coerceAtLeast(220)
    val stroke = Color(base.red, base.green, base.blue, strokeAlpha)
    val fill = Color(base.red, base.green, base.blue, 26)
    val outerBase = blendColors(base, Color.WHITE, 0.38f)
    val outerStroke = Color(outerBase.red, outerBase.green, outerBase.blue, 170)
    val outerFill = Color(outerBase.red, outerBase.green, outerBase.blue, 10)
    return SlayerEspPalette(
      stroke = stroke,
      fill = fill,
      outerStroke = outerStroke,
      outerFill = outerFill,
    )
  }

  private fun blendColors(first: Color, second: Color, amount: Float): Color {
    val blend = amount.coerceIn(0f, 1f)
    val inv = 1f - blend
    return Color(
      (first.red * inv + second.red * blend).toInt().coerceIn(0, 255),
      (first.green * inv + second.green * blend).toInt().coerceIn(0, 255),
      (first.blue * inv + second.blue * blend).toInt().coerceIn(0, 255),
      (first.alpha * inv + second.alpha * blend).toInt().coerceIn(0, 255),
    )
  }

  private fun syncSlayerGlowState(
    level: ClientLevel,
    cache: Map<UUID, SlayerHighlightState>
  ) {
    val scoreboard = level.scoreboard
    ensureSlayerGlowTeams(scoreboard)

    val activeIds = cache.keys
    val glowIterator = slayerGlowingEntities.iterator()
    while (glowIterator.hasNext()) {
      val entry = glowIterator.next()
      if (entry.key in activeIds) continue
      val liveEntity = level.entitiesForRendering().firstOrNull { it.uuid == entry.key } as? LivingEntity
      liveEntity?.setGlowingTag(false)
      scoreboard.removePlayerFromTeam(entry.value)
      glowIterator.remove()
    }

    for ((uuid, state) in cache) {
      if (!slayerHighlightMatchesMode(state.type, slayerEspTargets.value)) continue
      val living = level.entitiesForRendering().firstOrNull { it.uuid == uuid } as? LivingEntity ?: continue
      val scoreHolder = living.scoreboardName
      val teamName = slayerGlowTeamName(state)
      val team = ensureSlayerGlowTeam(scoreboard, teamName, slayerGlowFormatting(state))
      val currentTeam = scoreboard.getPlayersTeam(scoreHolder)
      if (currentTeam == null || currentTeam.name != team.name) {
        if (currentTeam != null && currentTeam.name.startsWith(SLAYER_GLOW_TEAM_PREFIX)) {
          scoreboard.removePlayerFromTeam(scoreHolder, currentTeam)
        }
        scoreboard.addPlayerToTeam(scoreHolder, team)
      }
      living.setGlowingTag(true)
      slayerGlowingEntities[uuid] = scoreHolder
    }
  }

  private fun clearSlayerGlowState(level: ClientLevel? = mc.level) {
    if (slayerGlowingEntities.isEmpty()) return
    val scoreboard = level?.scoreboard
    val loadedLivingById =
      level?.entitiesForRendering()
        ?.filterIsInstance<LivingEntity>()
        ?.associateBy { it.uuid }
        .orEmpty()

    for ((uuid, scoreHolder) in slayerGlowingEntities) {
      loadedLivingById[uuid]?.setGlowingTag(false)
      scoreboard?.removePlayerFromTeam(scoreHolder)
    }
    slayerGlowingEntities.clear()
  }

  private fun ensureSlayerGlowTeams(scoreboard: net.minecraft.world.scores.Scoreboard) {
    ensureSlayerGlowTeam(scoreboard, SLAYER_GLOW_TEAM_BOSS, slayerGlowFormatting(SlayerHighlightState(SlayerHighlightType.BOSS, null)))
    ensureSlayerGlowTeam(scoreboard, SLAYER_GLOW_TEAM_MINI, slayerGlowFormatting(SlayerHighlightState(SlayerHighlightType.MINIBOSS, null)))
    ensureSlayerGlowTeam(scoreboard, SLAYER_GLOW_TEAM_HIGH, slayerGlowFormatting(SlayerHighlightState(SlayerHighlightType.HIGH_TIER_MINIBOSS, null)))
    ensureSlayerGlowTeam(scoreboard, SLAYER_GLOW_TEAM_ASHEN, nearestChatFormatting(BlazeAttunement.ASHEN.argb))
    ensureSlayerGlowTeam(scoreboard, SLAYER_GLOW_TEAM_SPIRIT, nearestChatFormatting(BlazeAttunement.SPIRIT.argb))
    ensureSlayerGlowTeam(scoreboard, SLAYER_GLOW_TEAM_AURIC, nearestChatFormatting(BlazeAttunement.AURIC.argb))
    ensureSlayerGlowTeam(scoreboard, SLAYER_GLOW_TEAM_CRYSTAL, nearestChatFormatting(BlazeAttunement.CRYSTAL.argb))
  }

  private fun ensureSlayerGlowTeam(
    scoreboard: net.minecraft.world.scores.Scoreboard,
    teamName: String,
    color: ChatFormatting,
  ): net.minecraft.world.scores.PlayerTeam {
    val team = scoreboard.getPlayerTeam(teamName) ?: scoreboard.addPlayerTeam(teamName)
    team.setColor(color)
    return team
  }

  private fun slayerGlowFormatting(state: SlayerHighlightState): ChatFormatting {
    return when (state.attunement) {
      BlazeAttunement.ASHEN -> nearestChatFormatting(BlazeAttunement.ASHEN.argb)
      BlazeAttunement.SPIRIT -> nearestChatFormatting(BlazeAttunement.SPIRIT.argb)
      BlazeAttunement.AURIC -> nearestChatFormatting(BlazeAttunement.AURIC.argb)
      BlazeAttunement.CRYSTAL -> nearestChatFormatting(BlazeAttunement.CRYSTAL.argb)
      null ->
        when (state.type) {
          SlayerHighlightType.BOSS -> nearestChatFormatting(slayerBossEspColor.value)
          SlayerHighlightType.HIGH_TIER_MINIBOSS -> nearestChatFormatting(slayerHighTierMiniEspColor.value)
          SlayerHighlightType.MINIBOSS -> nearestChatFormatting(slayerMiniBossEspColor.value)
        }
    }
  }

  private fun slayerGlowTeamName(state: SlayerHighlightState): String =
    when (state.attunement) {
      BlazeAttunement.ASHEN -> SLAYER_GLOW_TEAM_ASHEN
      BlazeAttunement.SPIRIT -> SLAYER_GLOW_TEAM_SPIRIT
      BlazeAttunement.AURIC -> SLAYER_GLOW_TEAM_AURIC
      BlazeAttunement.CRYSTAL -> SLAYER_GLOW_TEAM_CRYSTAL
      null ->
        when (state.type) {
          SlayerHighlightType.BOSS -> SLAYER_GLOW_TEAM_BOSS
          SlayerHighlightType.MINIBOSS -> SLAYER_GLOW_TEAM_MINI
          SlayerHighlightType.HIGH_TIER_MINIBOSS -> SLAYER_GLOW_TEAM_HIGH
        }
    }

  private fun resolveBlazeAttunement(names: Collection<String>): BlazeAttunement? =
    when {
      names.any { it.contains("ashen") } -> BlazeAttunement.ASHEN
      names.any { it.contains("spirit") } -> BlazeAttunement.SPIRIT
      names.any { it.contains("auric") } -> BlazeAttunement.AURIC
      names.any { it.contains("crystal") } -> BlazeAttunement.CRYSTAL
      else -> null
    }

  private fun nearestChatFormatting(argb: Int): ChatFormatting {
    val source = Color(argb, true)
    return CHAT_FORMATTING_COLORS.minByOrNull { (_, candidate) ->
      val dr = source.red - candidate.red
      val dg = source.green - candidate.green
      val db = source.blue - candidate.blue
      dr * dr + dg * dg + db * db
    }?.first ?: ChatFormatting.WHITE
  }

  private fun slayerBossDisplayName(): String =
    when (slayerType.value) {
      0 -> if (slayerTier.value.toInt() >= 5) "Atoned Horror" else "Revenant Horror"
      1 -> "Sven Packmaster"
      2 -> "Tarantula Broodfather"
      3 -> "Voidgloom Seraph"
      4 -> "Riftstalker Bloodfiend"
      5 -> "Inferno Demonlord"
      else -> "Slayer Boss"
    }

  private fun holdStillNoStrafe() {
    MovementManager.setForcedMovement(
      forward = false, backward = false,
      left = false, right = false,
      jump = false, shift = false, sprint = false
    )
  }

  private fun handleSoftChaseMovement(player: Player, target: LivingEntity, distanceToTarget: Double) {
    val rotation = AngleUtils.getRotation(target)
    RotationExecutor.rotateTo(rotation, rotationStrategy)

    val yawError = abs(AngleUtils.getRotationDelta(player.yRot, rotation.yaw))
    val pitchError = abs(rotation.pitch - player.xRot)
    val aligned = yawError <= SOFT_CHASE_YAW_TOLERANCE && pitchError <= SOFT_CHASE_PITCH_TOLERANCE
    val shouldStepForward = distanceToTarget > combatKeepDistance(0) + SOFT_CHASE_STOP_BUFFER

    if (aligned && shouldStepForward) {
      MovementManager.setMovementLock(true)
      val shouldJump = player.onGround() && player.horizontalCollision
      MovementManager.setForcedMovement(
        forward = true, backward = false,
        left = false, right = false,
        jump = shouldJump, shift = false, sprint = true
      )
    } else {
      MovementManager.setMovementLock(false)
    }
  }

  private fun isAttackReady(player: Player, target: LivingEntity): Boolean {
    val cooldown = player.getAttackStrengthScale(0.0f)
    if (cooldown < minAttackCooldown.value.toFloat()) return false
    if (requireLos.value && !player.hasLineOfSight(target)) return false

    val rotation = AngleUtils.getRotation(target)
    val yawError = abs(AngleUtils.getRotationDelta(player.yRot, rotation.yaw))
    val pitchError = abs(rotation.pitch - player.xRot)
    val tolerance = aimTolerance.value
    if (yawError > tolerance || pitchError > tolerance) return false

    return true
  }

  private fun updateStuck(
    player: Player,
    inAttackRange: Boolean,
    level: ClientLevel
  ) {
    if (inAttackRange || !startedPath || !nativeActive()) {
      stuckTicks = 0
      stuckRepathCount = 0
      lastMoveX = player.x
      lastMoveY = player.y
      lastMoveZ = player.z
      return
    }

    val dx = player.x - lastMoveX
    val dy = player.y - lastMoveY
    val dz = player.z - lastMoveZ
    val moved = dx * dx + dy * dy + dz * dz > 0.0008
    if (moved) {
      stuckTicks = 0
      stuckRepathCount = 0
      lastMoveX = player.x
      lastMoveY = player.y
      lastMoveZ = player.z
      return
    }

    stuckTicks++
    if (stuckTicks < stuckTicksSetting.value.toInt()) {
      return
    }
    stuckTicks = 0

    val maxRepaths = stuckRepathTries.value.toInt().coerceAtLeast(0)
    if (stuckRepathCount < maxRepaths) {
      val target =
        currentTargetId?.let { id ->
          level.entitiesForRendering().firstOrNull { it.uuid == id } as? LivingEntity
        }
      if (target != null && target.isAlive && target.health > 0f) {
        nativeStop()
        val targetPos = target.blockPosition()
        val restarted = startCombatPathToTarget(level, targetPos, effectiveCombatMode(target))
        if (restarted) {
          startedPath = true
          lastTargetPos = targetPos
          lastPathStartTick = level.gameTime
          stuckRepathCount++
          return
        }
      }
    }

    stuckRepathCount = 0
    if (warpOnStuck.value) {
      if (cryptZombieSlayer.value) {
        ChatUtils.sendMessage("Combat macro stuck. Triggering walkback.")
        startAreaOrigin = null
        stuckTicks = 0
        slayerEnteredCrypt = false
        triggerWalkToFarmArea(justFarm = false)
      } else {
        nativeStop()
        (player as? LocalPlayer)?.connection?.sendCommand("warp hub")
        ChatUtils.sendMessage("Combat macro stuck. Warping to hub.")
        stopMacro()
      }
    }
  }

  private fun startCombatPathToTarget(
    level: ClientLevel,
    targetPos: BlockPos,
    activeCombatMode: Int = combatMode.value
  ): Boolean {
    val resolved = findNearestWalkableAround(level, targetPos, CHASE_RESOLVE_RADIUS, CHASE_RESOLVE_VERTICAL) ?: targetPos
    val radius = combatPathRadius(activeCombatMode)
    if (!nativeActive()) {
      MovementManager.clearForcedMovement()
      RotationExecutor.stopRotating()
    }
    NativePathfinder.setTargetWithRadius(resolved.x + 0.5, resolved.y.toDouble(), resolved.z + 0.5, radius)
    return true
  }

  private fun combatEngageRange(activeCombatMode: Int = combatMode.value): Double {
    return when (activeCombatMode) {
      1 -> max(attackRange.value, bowMinRange.value.coerceAtLeast(0.0))
      else -> attackRange.value
    }
  }

  private fun combatKeepDistance(activeCombatMode: Int = combatMode.value): Double {
    return when (activeCombatMode) {
      0 -> {
        val configured = configuredSwordKeepDistance().coerceAtLeast(0.0)
        val desired = if (configured <= 0.0) MELEE_CLOSE_IN_DISTANCE else configured
        min(attackRange.value, desired)
      }
      1 -> combatEngageRange(activeCombatMode)
      else -> attackRange.value
    }
  }

  private fun configuredSwordKeepDistance(): Double {
    return when {
      cryptZombieSlayer.value || slayerModeEnabled -> slayerSwordKeepDistance.value
      else -> swordKeepDistance.value
    }
  }

  private fun combatPathRadius(activeCombatMode: Int = combatMode.value): Double {
    return (combatKeepDistance(activeCombatMode) - COMBAT_PATH_RADIUS_BUFFER).coerceAtLeast(COMBAT_MIN_STANDOFF)
  }

  private fun beginSlayerSession() {
    slayerSessionStartMs = System.currentTimeMillis()
    slayerSessionMobKills = 0
    slayerSessionQuestCompletions = 0
    slayerSessionQuestFailures = 0
    clearTrackedSlayerQuestState(clearIdentity = true)
  }

  private fun startTrackedSlayerQuest(typeIdx: Int, tier: Int) {
    slayerTrackedQuestActive = true
    slayerTrackedQuestType = typeIdx.takeIf { it in slayerType.options.indices } ?: slayerType.value
    slayerTrackedQuestTier = tier.takeIf { it in 1..5 } ?: slayerTier.value.toInt().coerceIn(1, 5)
    slayerTrackedQuestProgressKills = 0
    slayerTrackedQuestTargetKills = 0
    slayerTrackedQuestSawMobKill = false
    slayerTrackedQuestBossSeen = false
    slayerTrackedQuestCompletionPendingRestart = false
  }

  private fun clearTrackedSlayerQuestState(clearIdentity: Boolean) {
    slayerTrackedQuestActive = false
    slayerTrackedQuestProgressKills = 0
    slayerTrackedQuestTargetKills = 0
    slayerTrackedQuestSawMobKill = false
    slayerTrackedQuestBossSeen = false
    slayerTrackedQuestCompletionPendingRestart = false
    if (clearIdentity) {
      slayerTrackedQuestType = -1
      slayerTrackedQuestTier = 0
    }
  }

  private fun completeTrackedSlayerQuest() {
    if (slayerTrackedQuestCompletionPendingRestart) return
    slayerSessionQuestCompletions++
    slayerTrackedQuestActive = false
    slayerTrackedQuestSawMobKill = true
    slayerTrackedQuestBossSeen = true
    slayerTrackedQuestCompletionPendingRestart = true
  }

  private fun shouldUseAutoSlayerForCurrentType(): Boolean =
    SlayerLocationSettings.shouldUseAutoSlayer(slayerType.value)

  private fun shouldLetAutoSlayerHandleRestart(): Boolean =
    shouldUseAutoSlayerForCurrentType() &&
      (slayerNeedsQuestClaim || slayerTrackedQuestCompletionPendingRestart)

  private fun beginAutoSlayerQuestDetection(nowTick: Long) {
    slayerTrackedQuestCompletionPendingRestart = false
    slayerTrackedQuestSawMobKill = false
    slayerTrackedQuestBossSeen = false
    slayerTrackedQuestProgressKills = 0
    slayerTrackedQuestTargetKills = 0
    beginSlayerQuestDetection(nowTick)
    slayerStatus.value = "Waiting Auto Slayer"
  }

  private fun queueSlayerQuestRestart(countFailure: Boolean = true) {
    if (countFailure) {
      markTrackedSlayerQuestFailedIfNeeded()
    }
    slayerNeedsQuestRestart = true
    slayerPendingTierSelection = false
  }

  private fun markTrackedSlayerQuestFailedIfNeeded() {
    if (!slayerTrackedQuestActive) return
    if (slayerTrackedQuestCompletionPendingRestart || slayerNeedsQuestClaim) return
    slayerSessionQuestFailures++
    clearTrackedSlayerQuestState(clearIdentity = false)
  }

  private fun handleSlayerQuestPurchaseFailure() {
    markTrackedSlayerQuestFailedIfNeeded()
    slayerNeedsQuestRestart = false
    slayerPendingTierSelection = false
    slayerQuestReady = false
    slayerAutoDetected = true
    slayerDetectStartTick = -1L
    mc.player?.closeContainer()
    cryptZombieSlayer.value = false
    enabled.value = false
    stopMacro()
    slayerStatus.value = "Need Coins"
    ChatUtils.sendMessage("Combat macro stopped: you cannot afford this Slayer quest.")
  }

  private fun trackedSlayerQuestLabel(): String {
    val typeIndex =
      when {
        slayerTrackedQuestType in slayerType.options.indices -> slayerTrackedQuestType
        slayerType.value in slayerType.options.indices -> slayerType.value
        else -> return "--"
      }
    val tier =
      when {
        slayerTrackedQuestTier in 1..5 -> slayerTrackedQuestTier
        else -> slayerTier.value.toInt().coerceIn(1, 5)
      }
    return "${slayerType.options[typeIndex]} ${romanTierLabel(tier)}"
  }

  private fun trackedSlayerQuestHudStageLabel(): String =
    when {
      slayerNeedsQuestClaim || slayerTrackedQuestCompletionPendingRestart || slayerBossActive || slayerQuestReady || slayerTrackedQuestBossSeen ->
        "Killing Boss"
      slayerTrackedQuestSawMobKill || slayerTrackedQuestProgressKills > 0 -> "Killing Mobs"
      else -> "Started"
    }

  private fun currentSlayerKillsLeft(): Int? {
    if (slayerNeedsQuestClaim || slayerTrackedQuestCompletionPendingRestart || slayerBossActive || slayerQuestReady || slayerTrackedQuestBossSeen) {
      return 0
    }
    val targetKills = slayerTrackedQuestTargetKills
    if (targetKills <= 0) return null
    return (targetKills - slayerTrackedQuestProgressKills).coerceAtLeast(0)
  }

  private fun formatSessionRateDisplay(count: Int): String {
    val sessionStart = slayerSessionStartMs
    if (sessionStart <= 0L || count <= 0) return "0/h"
    val elapsedMs = (System.currentTimeMillis() - sessionStart).coerceAtLeast(1000L)
    val perHour = count * 3_600_000.0 / elapsedMs.toDouble()
    return if (perHour >= 100.0 || abs(perHour - perHour.toInt()) < 0.05) {
      "${perHour.toInt()}/h"
    } else {
      String.format(Locale.ROOT, "%.1f/h", perHour)
    }
  }

  private fun romanTierLabel(tier: Int): String =
    when (tier.coerceIn(1, 5)) {
      1 -> "I"
      2 -> "II"
      3 -> "III"
      4 -> "IV"
      5 -> "V"
      else -> "I"
    }

  /**
   * Navigate back to the farming area using the configured walkback route.
   * [justFarm] = true  -> just walk in; don't restart the quest after arriving.
   * [justFarm] = false -> buy a new quest once walkback completes.
   */
  private fun triggerWalkToFarmArea(justFarm: Boolean, skipPreWarp: Boolean = false) {
    if (CombatPatrolModule.isPatrolRunning) CombatPatrolModule.stopPatrol()
    if (PathfindingModule.isPatrolActive) PathfindingModule.stopPatrol()
    nativeStop()
    startedPath = false
    lastTargetPos = null
    currentTargetId = null
    stuckTicks = 0
    stuckRepathCount = 0
    RotationExecutor.stopRotating()
    MovementManager.clearForcedMovement()
    val perTypeRoute = RouteStore.getSlotRoute(currentSlotKey()).orEmpty().trim()
    val routeName = when {
      slayerLocation.value == 1 -> CRYPT_WALKBACK_ROUTE_NAME
      perTypeRoute.isNotBlank() -> perTypeRoute
      else -> ""
    }
    if (routeName.isBlank()) {
      if (!justFarm) queueSlayerQuestRestart(countFailure = false)
      return
    }
    // Spider slayer: warp to den first so the walkback route starts from the right place.
    // skipPreWarp is true when called from the delay-expiry tick (warp already issued).
    if (!skipPreWarp && slayerType.value == 2) {
      val warpCmd = slayerWarpCommand
      if (warpCmd.isNotBlank()) {
        mc.player?.connection?.sendCommand(warpCmd)
        slayerWalkInDelayUntilTick = (mc.level?.gameTime ?: 0L) + SPIDER_WARP_DELAY_TICKS
        slayerWalkInJustFarm = justFarm
        return
      }
    }
    // PATROL assignments now act as directional travel routes: walk into the farm on the
    // forward travelRoute, then reverse that same travelRoute when leaving after a boss kill.
    val usePatrolTravelRoute = slayerLocation.value != 1 && isAssignedPatrolTravelRoute(routeName)
    val started = RoutesModule.loadAndStartWalkback(
      routeName,
      reverse = if (usePatrolTravelRoute) false else slayerLocation.value != 1,
      patrolTravelOnly = usePatrolTravelRoute
    )
    if (started) {
      slayerNeedsWalkback = true
      slayerWalkbackJustFarm = justFarm
    } else {
      ChatUtils.sendMessage("Combat macro: walkback route \"$routeName\" not found.")
      if (!justFarm) queueSlayerQuestRestart(countFailure = false)
    }
  }

  private fun findNearestWalkableAround(
    level: ClientLevel,
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

  private fun nativeActive(): Boolean =
    NativePathfinder.status.let { it != PathStatus.IDLE && it != PathStatus.ARRIVED && it != PathStatus.FAILED }

  private fun nativeStop() {
    NativePathfinder.stop()
    MovementManager.setMovementLock(false)
  }

  private fun stopMacro() {
    automationBypassWhitelist = false
    clearSlayerGlowState(mc.level ?: lastKnownLevel)
    if (PathfindingModule.isPatrolActive) PathfindingModule.stopPatrol()
    if (CombatPatrolModule.isPatrolRunning) CombatPatrolModule.stopPatrol()
    if (startedPath && nativeActive()) {
      nativeStop()
    } else {
      MovementManager.setMovementLock(false)
    }
    RotationExecutor.stopRotating()
    lastTargetPos = null
    stuckTicks = 0
    nextAttackNs = 0L
    startedPath = false
    backingAway = false
    currentTargetId = null
    resetCloseChase()
    lastPathStartTick = 0L
    clearKillCandidate()
    drillWarnTick = 0L
    lastHealUseTick = 0L
    lastAlwaysWandUseTick = 0L
    stuckRepathCount = 0
    startAreaOrigin = null
    enteredFarmingArea = false
    emanHitPhaseBossWeaponPrimed = false
    if (pendingHealRelease) {
      mc.options.keyUse?.setDown(false)
      pendingHealRelease = false
    }
    releaseEmanHitPhaseSneak()
    pendingHealRestoreSlot = -1
    clearPendingOverfluxPlacement()
    clearPendingSpiderHyperionBeforeShooting()
    spiderHyperionBeforeShootUsed = false
    slayerStatus.value = "Off"
    clearSlayerBossState(false)
    slayerLastTabScanTick = -1L
    slayerTabCache = emptyList()
    slayerNeedsQuestRestart = false
    slayerPendingTierSelection = false
    slayerQuestReady = false
    slayerRagnarokUsedPreBoss = false
    slayerAutoDetected = false
    slayerDetectStartTick = -1L
    slayerNeedsQuestClaim = false
    slayerNeedsWalkback = false
    slayerWalkbackJustFarm = false
    slayerDeathRespawnPending = false
    slayerWalkInDelayUntilTick = -1L
    slayerWalkInJustFarm = true
    slayerBossLastPos = null
    slayerClaimStartTick = -1L
    slayerClaimLastClickTick = -1L
    slayerLastBatphoneAttemptTick = -1L
    slayerLastBatphoneUseTick = -1L
    slayerLastGuiActionTick = -1L
    slayerWarnNoBatphoneTick = -1L
    slayerModeEnabled = false
    slayerQuestStateGraceUntilTick = -1L
    slayerEnteredCrypt = false
    slayerSessionStartMs = 0L
    slayerSessionMobKills = 0
    slayerSessionQuestCompletions = 0
    slayerSessionQuestFailures = 0
    clearTrackedSlayerQuestState(clearIdentity = true)
  }

  private const val KILL_CANDIDATE_TTL_TICKS = 80L
  private const val KILL_DISAPPEAR_CONFIRM_TICKS = 2L
  private const val DRILL_WARN_INTERVAL_TICKS = 60L
  private const val AUTO_HEAL_COOLDOWN_TICKS = 20L
  private const val ALWAYS_WAND_OF_ATONEMENT_INTERVAL_TICKS = 140L
  private const val SLAYER_TAB_SCAN_INTERVAL_TICKS = 5L
  private const val SLAYER_BOSS_TAB_LOST_TICKS = 30L
  private const val SLAYER_BOSS_ENTITY_LOST_TICKS = 20L
  private const val SLAYER_OVERFLUX_COOLDOWN_TICKS = 600L
  private const val OVERFLUX_LOOK_DOWN_TICKS = 4
  private const val OVERFLUX_RECOVER_LOOK_TICKS = 8
  private const val OVERFLUX_PLACE_PITCH = 80f
  private const val SPIDER_HYPERION_BEFORE_SHOOT_LOOK_DOWN_TICKS = 3
  private const val SPIDER_HYPERION_BEFORE_SHOOT_PITCH = 80f
  private const val SLAYER_RAGNAROK_COOLDOWN_TICKS = 400L
  private const val SLAYER_REAPER_SCYTHE_COOLDOWN_TICKS = 100L
  private const val REAPER_SCYTHE_USE_AIM_TOLERANCE = 12.0
  private const val SLAYER_BAD_HEALTH_REUSE_TICKS = 80L
  private const val SLAYER_BATPHONE_RETRY_TICKS = 80L
  private const val SPIDER_WARP_DELAY_TICKS = 60L  // ~3 s to let the warp teleport complete
  private const val SLAYER_MADDOX_GUI_TIMEOUT_TICKS = 80L
  private const val SLAYER_GUI_ACTION_COOLDOWN_TICKS = 5L
  private const val SLAYER_NO_BATPHONE_WARN_TICKS = 200L
  private const val SLAYER_DETECT_WINDOW_TICKS = 100L  // 5 s to scan before assuming no quest
  private const val SLAYER_QUEST_STATE_GRACE_TICKS = 40L
  private const val SLAYER_WOLF_PUPS_PHASE_TICKS = 60L
  private const val PLAYER_HOTBAR_MENU_SLOT_START = 36
  private const val PLAYER_HOTBAR_MENU_SLOT_END = 44
  private const val DEFAULT_WHITELIST_ENTRY = "ice walker"
  private const val MIN_PATH_START_INTERVAL_TICKS = 20L
  private const val TARGET_REPATH_DISTANCE_SQ = 100.0  // repath when mob moves ~10+ blocks
  private const val ROTATION_STOP_HYSTERESIS = 2.5     // don't stop rotating until clearly out of range
  private const val COMBAT_MIN_STANDOFF = 0.45
  private const val MELEE_CLOSE_IN_DISTANCE = 1.1
  private const val COMBAT_PATH_RADIUS_BUFFER = 0.18
  private const val SOFT_CHASE_HANDOFF_GAP = 3.5
  private const val SOFT_CHASE_STICKY_EXIT_GAP = 4.0
  private const val SOFT_CHASE_LOS_GRACE_GAP = 0.55
  private const val SOFT_CHASE_MAX_GAP = 0.45
  private const val SOFT_CHASE_STOP_BUFFER = 0.10
  private const val SOFT_CHASE_YAW_TOLERANCE = 7.5
  private const val SOFT_CHASE_PITCH_TOLERANCE = 18.0
  private const val SLAYER_BOSS_DIRECT_CHASE_GAP_BONUS = 0.85
  private const val SLAYER_MINIBOSS_DIRECT_CHASE_GAP_BONUS = 0.45
  private const val EMAN_HITS_PHASE_MIN_STANDOFF = 2.5
  private const val EMAN_HITS_PHASE_STANDOFF_BUFFER = 0.5
  private const val EMAN_HITS_PHASE_STANDOFF_HYSTERESIS = 0.35
  private const val EMAN_HITS_PHASE_YAW_TOLERANCE = 25.0
  private const val EMAN_BEACON_SCAN_RADIUS = 16
  private const val CRYPT_WALKBACK_ROUTE_NAME = "cryptwalkback"
  private const val SLAYER_WALKIN_WARP_DELAY_TICKS = 40L  // ticks to wait after warp hub before starting walkin route
  private const val CRYPT_PROXIMITY_RANGE_SQ = 1600.0    // 40-block radius - if within this of any patrol point, already in crypt
  private const val GRAVEYARD_PROXIMITY_RANGE_SQ = 900.0  // 30-block radius - if within this of any patrol point, already at graveyard
  private const val CHASE_RESOLVE_RADIUS = 3
  private const val CHASE_RESOLVE_VERTICAL = 2
  private const val START_AREA_RETURN_BUFFER = 2.0
  private const val TARGET_BOX_CYCLE_MS = 4000L
  private const val TARGET_BOX_ALPHA = 170
  private const val ATTACHED_NAMEPLATE_HORIZONTAL_RANGE_SQ = 2.25
  private const val SLAYER_OWNER_ENTITY_ID_SCAN_OFFSETS = 4
  private const val SLAYER_OWNER_STAND_MAX_DIST_SQ = 9.0 // 3-block radius max for owner stand ID scan
  private const val ENDERMAN_HITS_HORIZONTAL_RANGE_SQ = 9.0
  private const val TARGET_BOX_INFLATE = 0.08
  private const val SLAYER_ESP_MID_INFLATE = 0.22
  private const val SLAYER_ESP_OUTER_INFLATE = 0.38
  private const val BOW_REFIRE_DELAY_NS = 200_000_000L  // 200 ms between shots
  private val ENDERMAN_HITS_PATTERNS = arrayOf(
    Regex("\\b\\d+[,.]?\\d*\\s+hits?\\b"),
    Regex("\\bhits?\\s*[:x-]?\\s*\\d+[,.]?\\d*\\b"),
    Regex("\\b\\d+[,.]?\\d*\\s+hit shield\\b"),
  )
  private val ENDERMAN_HITS_TEXT_KEYWORDS = arrayOf("hits", "shield", "shielded", "immune")
  private val ENDERMAN_LASER_TEXT_KEYWORDS = arrayOf("aligning", "lasers", "laser", "align")
  private val EMAN_LASER_AOTV_KEYWORDS = arrayOf("aspect of the void", "aotv")
  private val ATTACHED_LABEL_TRANSIENT_KEYWORDS = arrayOf("hits", "hit shield", "shielded", "immune")
  private val SLAYER_BOSS_OWNER_PATTERN = Regex("\\bspawned by:\\s*([A-Za-z0-9_]{1,16})\\b", RegexOption.IGNORE_CASE)
  private val SANITIZE_COLOR_CODES   = Regex("§.")
  private val SANITIZE_LEVEL_PREFIX  = Regex("^\\[[^\\]]+\\]\\s*")
  private val SANITIZE_SYMBOL_PREFIX = Regex("^[^A-Za-z0-9]+")
  private val SANITIZE_HP_SUFFIX     = Regex("\\s+[0-9.,]+(?:/[0-9.,]+)?(?:[kKmMbB])?\\s*[❤]?$")
  private val SANITIZE_WHITESPACE    = Regex("\\s+")
  private val SLAYER_BOSS_ENTITY_KEYWORDS get() = when (slayerType.value) {
    0 -> arrayOf("revenant horror", "atoned horror")
    1 -> arrayOf("sven packmaster")
    2 -> arrayOf("tarantula broodfather", "conjoined brood")
    3 -> arrayOf("voidgloom seraph")
    4 -> arrayOf("riftstalker bloodfiend")
    5 -> arrayOf("inferno demonlord")
    else -> arrayOf()
  }
  // Priority mobs: rare/high-XP mobs that should be targeted over regular farm mobs.
  // Only applies at the relevant tier (zombie: tier 3+).
  private val SLAYER_PRIORITY_MOB_KEYWORDS get() = when {
    slayerType.value == 0 && slayerTier.value >= 3 -> arrayOf(
      "revenant sycophant", "revenant champion", "deformed revenant",
      "atoned champion", "atoned revenant"
    )
    slayerType.value == 1 && slayerTier.value >= 3 -> arrayOf(
      "pack enforcer", "sven follower", "sven alpha"
    )
    slayerType.value == 2 && slayerTier.value >= 3 -> arrayOf(
      "tarantula vermin", "tarantula beast", "mutant tarantula",
      "primordial jockey", "primordial viscount"
    )
    slayerType.value == 3 && slayerTier.value >= 3 -> arrayOf(
      "voidling devotee", "voidling radical", "voidcrazed maniac"
    )
    slayerType.value == 5 && slayerTier.value >= 3 -> arrayOf(
      "flare demon", "kindleheart demon", "burningsoul demon"
    )
    else -> arrayOf()
  }
  private val SLAYER_HIGH_TIER_PRIORITY_MOB_KEYWORDS get() = when {
    slayerType.value == 0 && slayerTier.value >= 3 -> arrayOf(
      "revenant champion", "deformed revenant", "atoned champion", "atoned revenant"
    )
    slayerType.value == 1 && slayerTier.value >= 3 -> arrayOf(
      "sven alpha"
    )
    slayerType.value == 2 && slayerTier.value >= 3 -> arrayOf(
      "mutant tarantula", "primordial jockey", "primordial viscount"
    )
    slayerType.value == 3 && slayerTier.value >= 3 -> arrayOf(
      "voidling radical", "voidcrazed maniac"
    )
    slayerType.value == 5 && slayerTier.value >= 3 -> arrayOf(
      "kindleheart demon", "burningsoul demon"
    )
    else -> arrayOf()
  }
  private val SLAYER_FARM_MOB_KEYWORDS get() = when (slayerType.value) {
    0 -> when (slayerLocation.value) {
      0 -> arrayOf("zombie") // Zombie Graveyard: regular zombies only
      1 -> arrayOf("crypt ghoul", "golden ghoul") // Zombie Crypt
      else -> arrayOf("zombie", "crypt ghoul", "golden ghoul")
    }
    1 -> arrayOf("pack wolf", "old wolf", "pit wolf", "zombie wolf", "wolf", "pup", "pups")
    2 -> arrayOf("dasher spider", "voracious spider", "weaver spider", "spider", "hatchling", "hatchlings", "broodling")
    3 -> when (endermanLocation.value) {
      0 -> arrayOf(ENDERMAN_END_FARM_MOB_KEYWORD)
      1 -> arrayOf(ENDERMAN_HIDEOUT_FARM_MOB_KEYWORD)
      2 -> arrayOf(ENDERMAN_VOID_FARM_MOB_KEYWORD)
      else -> arrayOf(ENDERMAN_END_FARM_MOB_KEYWORD)
    }
    4 -> arrayOf("bat", "vampiric bat", "bloodfiend")
    5 -> arrayOf("blaze", "smoldering blaze", "emerald slime")
    else -> arrayOf()
  }
  // Items that are NOT weapons - switch away from these when preparing to attack
  private val SLAYER_NON_WEAPON_KEYWORDS = arrayOf(
    "batphone", "overflux", "ragnarok", "ragnorak", "wand of atonement",
    "reaper scythe", "zombie sword", "sword of bad health", "drill", "pickaxe", "gauntlet"
  )
  private val SLAYER_MELEE_WEAPON_KEYWORDS = arrayOf(
    "sword", "blade", "scythe", "katana", "saber", "cleaver", "axe", "rapier", "claymore",
    "halberd", "dagger", "falchion"
  )
  private val SLAYER_BOW_WEAPON_KEYWORDS = arrayOf("terminator", "juju", "shortbow", "bow")
  private val SLAYER_MAGE_WEAPON_KEYWORDS = arrayOf("staff", "wand", "sceptre", "scepter", "scythe")
  private val SLAYER_ANY_WEAPON_KEYWORDS = arrayOf(
    *SLAYER_MELEE_WEAPON_KEYWORDS,
    *SLAYER_BOW_WEAPON_KEYWORDS,
    *SLAYER_MAGE_WEAPON_KEYWORDS
  )
  private val SLAYER_ZOMBIE_SPAWN_WEAPON_DEFAULT_KEYWORDS = arrayOf("halberd")
  private val SLAYER_ZOMBIE_DYNAMIC_SWORD_DEFAULT_KEYWORDS = arrayOf("falchion", "reaper", "shredded", "sword")
  private val SLAYER_SPIDER_MELEE_WEAPON_DEFAULT_KEYWORDS = arrayOf(
    "sting", "foil", "sword", "blade", "scythe", "katana", "saber", "cleaver", "axe",
    "rapier", "claymore", "halberd", "dagger", "falchion", "hyperion", "astraea",
    "scylla", "valkyrie", "necron blade"
  )
  private const val EMAN_SPAWN_WEAPON_TERMINATOR = 0
  private const val EMAN_SPAWN_WEAPON_SHORTBOW = 1
  private const val EMAN_SPAWN_WEAPON_ENDERMAN_SWORD = 2
  private const val EMAN_SPAWN_WEAPON_DYNAMIC = 3
  private const val EMAN_HIT_PHASE_WEAPON_SHORTBOW = 0
  private const val EMAN_HIT_PHASE_WEAPON_TERMINATOR = 1
  private const val EMAN_HIT_PHASE_WEAPON_ENDERMAN_SWORD = 2
  private const val ENDERMAN_END_FARM_MOB_KEYWORD = "enderman"
  private const val ENDERMAN_HIDEOUT_FARM_MOB_KEYWORD = "zealot bruiser"
  private const val ENDERMAN_VOID_FARM_MOB_KEYWORD = "voidling"
  private val SLAYER_ENDERMAN_TERMINATOR_WEAPON_KEYWORDS = arrayOf("terminator")
  private val SLAYER_ENDERMAN_SHORTBOW_WEAPON_KEYWORDS = arrayOf("juju", "shortbow", "bow")
  private val SLAYER_ENDERMAN_DYNAMIC_BOW_WEAPON_KEYWORDS = arrayOf("terminator", "juju", "shortbow", "bow")
  private val SLAYER_ENDERMAN_SPAWN_WEAPON_DEFAULT_KEYWORDS = arrayOf("terminator", "juju", "shortbow", "bow")
  private val SLAYER_ENDERMAN_SPAWN_SWORD_DEFAULT_KEYWORDS = arrayOf("atomsplit", "vorpal", "voidedge", "katana")
  private val SLAYER_ENDERMAN_BOSS_WEAPON_DEFAULT_KEYWORDS = arrayOf("atomsplit", "vorpal", "voidedge", "katana")
  private val SLAYER_WOLF_PHASE_ADD_KEYWORDS = arrayOf("wolf", "pup", "pups")
  private val SLAYER_WOLF_PUPS_TEXT_KEYWORDS = arrayOf("calling the pups")
  private val SLAYER_WAND_OF_ATONEMENT_KEYWORDS = arrayOf("wand of atonement")
  private val SLAYER_HYPERION_KEYWORDS = arrayOf("hyperion", "astraea", "scylla", "valkyrie", "necron blade")
  private val SLAYER_ZOMBIE_SWORD_KEYWORDS = arrayOf("zombie sword")
  private val SLAYER_OVERFLUX_KEYWORDS = arrayOf("overflux")
  private val SLAYER_PLASMAFLUX_KEYWORDS = arrayOf("plasmaflux")
  private val SLAYER_SOS_FLARE_KEYWORDS = arrayOf("sos flare", "flare")
  private val SLAYER_MANAFLUX_KEYWORDS = arrayOf("mana flux", "manaflux")
  private val SLAYER_RAGNAROK_KEYWORDS = arrayOf("ragnarok", "ragnorak")
  private val SLAYER_REAPER_SCYTHE_KEYWORDS = arrayOf("reaper scythe")
  private val SLAYER_BAD_HEALTH_KEYWORDS = arrayOf("sword of bad health")
  private val SLAYER_PRIMORDIAL_BELT_KEYWORDS = arrayOf("primordial belt")
  private const val POWER_ORB_OVERFLUX = 0
  private const val POWER_ORB_PLASMAFLUX = 1
  private const val POWER_ORB_SOS_FLARE = 2
  private const val POWER_ORB_MANA_FLUX = 3
  private val SLAYER_BATPHONE_KEYWORDS = arrayOf("maddox batphone", "batphone")
  private val SLAYER_MADDOX_SCREEN_KEYWORDS = arrayOf("maddox", "slayer")
  private val SLAYER_LOOT_KEYWORDS = arrayOf("loot", "bag", "drops")
  private const val SLAYER_LOOT_SEARCH_RADIUS_SQ = 36.0  // 6 block radius
  private const val SLAYER_LOOT_CLAIM_RANGE_SQ = 9.0     // 3 block range to right-click
  private const val SLAYER_CLAIM_TIMEOUT_TICKS = 200L    // 10 s before giving up
  private const val SLAYER_CLAIM_DWELL_TICKS = 60L       // 3 s at loot bag before moving on
  private const val SLAYER_CLAIM_CLICK_INTERVAL_TICKS = 5L
  private val slayerWarpCommand get(): String {
    if (!SlayerLocationSettings.shouldWarpToLocation(slayerType.value)) return ""
    return when (slayerType.value) {
      0 -> when (slayerLocation.value) {
        0 -> "warp crypts"  // Zombie Graveyard
        else -> ""          // Zombie Crypt: walkback route handles return
      }
      1 -> when (wolfLocation.value) {
        0 -> "warp park"    // Park
        1 -> "warp park"    // Caves (accessed via park)
        2 -> "warp hub"     // Castle
        else -> "warp park"
      }
      2 -> when (spiderLocation.value) {
        0 -> "warp spider"
        1 -> "warp arachne"
        2 -> "warp crimson"
        else -> "warp spider"
      }
      3 -> when (endermanLocation.value) {
        0 -> "warp end"
        1 -> "warp dragon"
        2 -> "warp void"
        else -> "warp end"
      }
      4 -> "warp rift"
      5 -> "warp blazing_fortress"
      else -> ""
    }
  }
}
