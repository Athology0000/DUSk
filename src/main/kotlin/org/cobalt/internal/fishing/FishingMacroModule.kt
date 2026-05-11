package org.cobalt.internal.fishing

import java.awt.Color
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.tags.FluidTags
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.FishingHook
import net.minecraft.world.item.AxeItem
import net.minecraft.world.item.FishingRodItem
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.ChatEvent
import org.cobalt.api.event.impl.client.PacketEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.event.impl.render.WorldRenderEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.ModuleCategory
import org.cobalt.api.module.setting.impl.ActionSetting
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.InfoSetting
import org.cobalt.api.module.setting.impl.InfoType
import org.cobalt.api.module.setting.impl.KeyBindSetting
import org.cobalt.api.module.setting.impl.ModeSetting
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.module.setting.impl.TextSetting
import org.cobalt.api.module.setting.inGroup
import org.cobalt.api.pathfinder.jni.NativePathfinder
import org.cobalt.api.rotation.RotationExecutor
import org.cobalt.api.rotation.strategy.BezierTrackingRotationStrategy
import org.cobalt.api.util.player.MovementManager
import org.cobalt.api.util.AngleUtils
import org.cobalt.api.util.ChatUtils
import org.cobalt.api.util.TickScheduler
import org.cobalt.api.util.InventoryUtils
import org.cobalt.api.util.MouseUtils
import org.cobalt.api.util.helper.KeyBind
import org.cobalt.api.util.helper.Rotation
import org.cobalt.api.util.render.Render3D
import org.cobalt.internal.etherwarp.EtherwarpLogic
import org.cobalt.internal.wardrobe.WardrobeModule
import org.cobalt.mixin.client.FishingHookAccessor

object FishingMacroModule : Module("Fishing Macro") {

  override val category = ModuleCategory.FARMING

  private enum class State {
    IDLE,
    CASTING,
    WAITING_BITE,
    REELING,
    RECAST_DELAY,
    MOVING_TO_HOTSPOT,
    ETHERWARPING_TO_HOTSPOT,
    SWITCHING_TO_WEAPON,
    KILLING,
    SWITCHING_BACK,
    MISSING_ROD,
    PAUSED,
  }

  private enum class CombatMethod(val label: String) {
    MELEE("Melee Left Click"),
    HYPERION("Hyperion Down Right Click"),
    SOUL_WHIP("Soul Whip / Flay Right Click"),
    HYPERION_DOWN("Hyperion Down Spam"),
  }

  enum class FishingType(val label: String) {
    NORMAL("Normal"),
    BARN("Barn"),
    TROPHY("Trophy"),
    WORM("Worm"),
    STRIDERSURFER("Stridersurfer"),
  }

  private fun fishingRuntime(gameTick: Long): FishingRuntime {
    return FishingRuntime(
      gameTick = gameTick,

      reactionDelayTicks = reactionDelayTicksSetting.value.toLong(),
      recastDelayTicks = recastDelayTicksSetting.value.toLong(),
      castSettleTicks = castSettleTicksSetting.value.toLong(),
      hookTimeoutTicks = hookTimeoutTicksSetting.value.toLong(),

      minimumTrophyWaitTicks = (minimumTrophyWaitTimeSetting.value * 20.0).toInt(),
      catchGoldenFish = catchGoldenFishSetting.value,
      goldenFishPredictionDistance = goldenFishPredictionDistanceSetting.value,

      sneakWhileFishing = sneakWhileFishingSetting.value,
      killSeaCreatures = killSeaCreaturesSetting.value,
    )
  }

  private fun activeFishingProfile(): FishingTypeProfile {
    return FishingTypeRegistry.get(fishingType())
  }
  private enum class SurfWeaponMode(val label: String) {
    SOUL_WHIP("Flay / Soul Whip Right Click"),
    AXE("Axe Left Click"),
  }

  private data class SurfAim(val rotation: Rotation, val target: Vec3?)

  private val mc = Minecraft.getInstance()

  private val enabledSetting = CheckboxSetting(
    "Enabled",
    "Automatically casts, waits for a bite, reels, and recasts your fishing rod.",
    false,
  )

  private val toggleFishingMacroSetting = KeyBindSetting(
    "Toggle Fishing Macro",
    "Key to toggle the fishing macro.",
    KeyBind(-1),
  )

  private val infoSetting = InfoSetting(
    "Fishing Macro",
    "Splash-based fishing loop with optional sea-creature combat after reel.",
    InfoType.INFO,
  )

  private val stateText = TextSetting(
    "State",
    "Current fishing macro state.",
    "Idle",
  )

  private val rodSlotSetting = SliderSetting(
    "Rod Slot",
    "Hotbar slot of the fishing rod (0 = auto-detect, 1-9 = fixed slot).",
    0.0,
    0.0,
    9.0,
    1.0,
  )

  private val fishingTypeSetting = ModeSetting(
    "Fishing Type",
    "Fishing behavior preset.",
    FishingType.NORMAL.ordinal,
    FishingType.entries.map { it.label }.toTypedArray(),
  )

  private val sneakWhileFishingSetting = CheckboxSetting(
    "Sneak While Fishing",
    "Hold sneak while the macro is casting or waiting on the bobber.",
    false,
  )

  private val catchGoldenFishSetting = CheckboxSetting(
    "Catch Golden Fish",
    "Enable golden fish helper controls for trophy fishing.",
    false,
  ).inGroup(TROPHY_GROUP)

  private val goldenFishPredictionDistanceSetting = SliderSetting(
    "Golden Fish Prediction Distance",
    "Distance between predicted golden fish position and hook.",
    0.8,
    0.1,
    3.0,
    0.1,
  ).inGroup(TROPHY_GROUP)

  private val minimumTrophyWaitTimeSetting = SliderSetting(
    "Minimum Wait Time",
    "Seconds before catching trophy fish.",
    0.0,
    0.0,
    20.0,
    0.5,
  ).inGroup(TROPHY_GROUP)

  private val reactionDelayTicksSetting = SliderSetting(
    "Reaction Delay",
    "Ticks to wait after the splash before reeling in.",
    3.0,
    0.0,
    12.0,
    1.0,
  )

  private val recastDelayTicksSetting = SliderSetting(
    "Recast Delay",
    "Ticks to wait after reeling before casting again.",
    12.0,
    2.0,
    40.0,
    1.0,
  )

  private val castSettleTicksSetting = SliderSetting(
    "Cast Settle",
    "Minimum ticks to wait after casting before checking for the bobber again.",
    8.0,
    2.0,
    20.0,
    1.0,
  )

  private val hookTimeoutTicksSetting = SliderSetting(
    "Hook Timeout",
    "Fail-safe ticks before recasting if no bite is confirmed.",
    700.0,
    300.0,
    1200.0,
    5.0,
  )

  private val splashRadiusSetting = SliderSetting(
    "Splash Radius",
    "Maximum distance from your bobber for a splash packet to count as your bite.",
    2.2,
    0.5,
    6.0,
    0.1,
  )

  private val autoSelectRodSetting = CheckboxSetting(
    "Auto Select Rod",
    "Keep your fishing rod selected while the macro is active outside combat states.",
    true,
  )

  private val killSeaCreaturesSetting = CheckboxSetting(
    "Kill Sea Creatures",
    "After reeling, swap to a weapon and attack nearby sea creatures before recasting.",
    false,
  ).inGroup(COMBAT_GROUP)

  private val combatMethodSetting = ModeSetting(
    "Combat Method",
    "How the fishing macro attacks detected sea creatures.",
    CombatMethod.MELEE.ordinal,
    CombatMethod.entries.map { it.label }.toTypedArray(),
  ).inGroup(COMBAT_GROUP)

  private val weaponSlotSetting = SliderSetting(
    "Weapon Slot",
    "Hotbar slot of your weapon (0 = remember the last non-rod slot, 1-9 = fixed slot).",
    0.0,
    0.0,
    9.0,
    1.0,
  ).inGroup(COMBAT_GROUP)

  private val reelSettleTicksSetting = SliderSetting(
    "Reel Settle",
    "Ticks to wait after reeling before deciding whether to enter combat or recast.",
    4.0,
    0.0,
    12.0,
    1.0,
  ).inGroup(COMBAT_GROUP)

  private val weaponSwapDelayTicksSetting = SliderSetting(
    "Weapon Swap Delay",
    "Ticks to wait after switching to or from your weapon slot.",
    5.0,
    0.0,
    20.0,
    1.0,
  ).inGroup(COMBAT_GROUP)

  private val targetSearchRangeSetting = SliderSetting(
    "Target Search Range",
    "Maximum range around your hook or player to look for sea-creature targets.",
    12.0,
    4.0,
    24.0,
    0.5,
  ).inGroup(COMBAT_GROUP)

  private val attackRangeSetting = SliderSetting(
    "Attack Range",
    "Maximum distance to click attacks while fighting a sea creature.",
    4.3,
    2.0,
    6.0,
    0.1,
  ).inGroup(COMBAT_GROUP)

  private val attackIntervalTicksSetting = SliderSetting(
    "Attack Interval",
    "Ticks between left-click attacks while fighting.",
    2.0,
    1.0,
    10.0,
    1.0,
  ).inGroup(COMBAT_GROUP)

  private val killTimeoutTicksSetting = SliderSetting(
    "Kill Timeout",
    "Maximum ticks to stay in the kill phase before swapping back to the rod.",
    80.0,
    20.0,
    400.0,
    5.0,
  ).inGroup(COMBAT_GROUP)

  private val combatRotationSpeedSetting = SliderSetting(
    "Combat Rotation Speed",
    "Maximum yaw step used while turning toward a sea creature.",
    14.0,
    2.0,
    45.0,
    1.0,
  ).inGroup(COMBAT_GROUP)

  private val targetKeywordsSetting = TextSetting(
    "Target Keywords",
    "Comma-separated keywords used to identify sea creatures by name or entity type.",
    DEFAULT_TARGET_KEYWORDS,
  ).inGroup(COMBAT_GROUP)

  private val soulWhipArcSetting = CheckboxSetting(
    "Soul Whip Arc",
    "Render a Soul Whip-style guide arc to your bobber or current sea creature.",
    true,
  ).inGroup(COMBAT_GROUP)

  private val hyperionTargetKeywordsSetting = TextSetting(
    "Hyperion Targets",
    "Sea creature names that should always use Hyperion-style look-down right-click combat.",
    DEFAULT_HYPERION_TARGET_KEYWORDS,
  ).inGroup(COMBAT_GROUP)

  private val hyperionDownClicksSetting = SliderSetting(
    "Hyperion Down Clicks",
    "Total number of right-clicks to fire before switching back to the rod.",
    5.0,
    1.0,
    20.0,
    1.0,
  ).inGroup(COMBAT_GROUP)

  private val hyperionDownMinRangeSetting = SliderSetting(
    "Hyperion Down Min Range",
    "How close the target must be (blocks) before Hyperion Down starts clicking.",
    8.0,
    2.0,
    20.0,
    0.5,
  ).inGroup(COMBAT_GROUP)

  private val lavaBurstSetting = CheckboxSetting(
    "Lava Burst",
    "On spawn-chat triggers, fire 2 Hyperion right-clicks, wait 1 s, then 2 more.",
    false,
  ).inGroup(COMBAT_GROUP)

  private val lavaBurstKeywordsSetting = TextSetting(
    "Lava Burst Keywords",
    "Comma-separated chat phrases that trigger the lava burst combo.",
    DEFAULT_LAVA_BURST_KEYWORDS,
  ).inGroup(COMBAT_GROUP)

  private val surfStriderThresholdSetting = SliderSetting(
    "Surfstrider Kill Count",
    "Number of live Surfstriders to hook before the macro starts killing them.",
    5.0,
    1.0,
    30.0,
    1.0,
  ).inGroup(SURF_GROUP)

  private val surfWeaponModeSetting = ModeSetting(
    "Surf Weapon",
    "Weapon behavior used once the Surfstrider count is reached.",
    SurfWeaponMode.SOUL_WHIP.ordinal,
    SurfWeaponMode.entries.map { it.label }.toTypedArray(),
  ).inGroup(SURF_GROUP)

  private val surfSpotXSetting = TextSetting(
    "Surf Spot X",
    "Saved fishing-spot X position the macro walks to before casting.",
    "-694.50",
  ).inGroup(SURF_GROUP)

  private val surfSpotYSetting = TextSetting(
    "Surf Spot Y",
    "Saved fishing-spot Y position.",
    "121.00",
  ).inGroup(SURF_GROUP)

  private val surfSpotZSetting = TextSetting(
    "Surf Spot Z",
    "Saved fishing-spot Z position.",
    "80.00",
  ).inGroup(SURF_GROUP)

  private val surfYawSetting = TextSetting(
    "Surf Cast Yaw",
    "Saved yaw used when casting for Surfstriders.",
    "",
  ).inGroup(SURF_GROUP)

  private val surfPitchSetting = TextSetting(
    "Surf Cast Pitch",
    "Saved pitch used when casting for Surfstriders.",
    "",
  ).inGroup(SURF_GROUP)

  private val surfLookBlockXSetting = TextSetting(
    "Surf Look Block X",
    "Saved block X that the Surf cast aims at.",
    "",
  ).inGroup(SURF_GROUP)

  private val surfLookBlockYSetting = TextSetting(
    "Surf Look Block Y",
    "Saved block Y that the Surf cast aims at.",
    "",
  ).inGroup(SURF_GROUP)

  private val surfLookBlockZSetting = TextSetting(
    "Surf Look Block Z",
    "Saved block Z that the Surf cast aims at.",
    "",
  ).inGroup(SURF_GROUP)

  private val saveSurfSpotSetting = ActionSetting(
    "Save Surf Spot",
    "Stores your current yaw, pitch, and the block you are looking at for Surf casting.",
    "Save Current Aim",
  ) {
    val player = mc.player ?: return@ActionSetting
    surfYawSetting.value = "%.2f".format(Locale.US, AngleUtils.normalizeAngle(player.yRot))
    surfPitchSetting.value = "%.2f".format(Locale.US, player.xRot.coerceIn(-89.9f, 89.9f))

    val lookedBlock = findLookedSurfBlock(player)
    if (lookedBlock == null) {
      ChatUtils.sendMessage("Saved Surf yaw/pitch, but no looked block was found.")
      return@ActionSetting
    }

    surfLookBlockXSetting.value = lookedBlock.x.toString()
    surfLookBlockYSetting.value = lookedBlock.y.toString()
    surfLookBlockZSetting.value = lookedBlock.z.toString()
    ChatUtils.sendMessage(
      "Saved Surf aim yaw=${surfYawSetting.value}, pitch=${surfPitchSetting.value}, block=${lookedBlock.x}, ${lookedBlock.y}, ${lookedBlock.z}.",
    )
  }.inGroup(SURF_GROUP)

  private val hotspotNavModeSetting = ModeSetting(
    "Hotspot Nav",
    "Navigate to a fishing hotspot before casting.",
    0,
    arrayOf("Off", "Center", "Bank"),
  ).inGroup(HOTSPOT_NAV_GROUP)

  private val useEtherwarpNavSetting = CheckboxSetting(
    "Use Etherwarp",
    "Etherwarp to the hotspot target instead of walking, when in range.",
    false,
  ).inGroup(HOTSPOT_NAV_GROUP)

  private val navTimeoutTicksSetting = SliderSetting(
    "Nav Timeout",
    "Ticks before aborting hotspot navigation and casting in place.",
    200.0, 40.0, 600.0, 10.0,
  ).inGroup(HOTSPOT_NAV_GROUP)

  private val navArrivalBlocksSetting = SliderSetting(
    "Arrival Distance",
    "Blocks from target counted as arrived.",
    2.0, 0.5, 5.0, 0.5,
  ).inGroup(HOTSPOT_NAV_GROUP)

  private val etherwarpNavTimeoutTicksSetting = SliderSetting(
    "Etherwarp Timeout",
    "Ticks to wait for etherwarp confirmation before falling back to walking.",
    60.0, 20.0, 200.0, 5.0,
  ).inGroup(HOTSPOT_NAV_GROUP)

  private val smoothnessSetting = SliderSetting(
    "Smoothness",
    "Preset that writes curve/snap values below. 0 = snappy, 1 = fluid.",
    0.5, 0.0, 1.0, 0.01,
  ).inGroup(ROTATION_SETTINGS_GROUP)

  private val curveInSetting = SliderSetting(
    "Curve In",
    "Bezier ease-in control point for combat rotation.",
    0.18, 0.0, 1.0, 0.01,
  ).inGroup(ROTATION_SETTINGS_GROUP)

  private val curveOutSetting = SliderSetting(
    "Curve Out",
    "Bezier ease-out control point for combat rotation.",
    0.92, 0.0, 1.0, 0.01,
  ).inGroup(ROTATION_SETTINGS_GROUP)

  private val minScaleSetting = SliderSetting(
    "Min Scale",
    "Minimum speed scale at the start of a rotation arc.",
    0.22, 0.0, 1.0, 0.01,
  ).inGroup(ROTATION_SETTINGS_GROUP)

  private val snapThresholdSetting = SliderSetting(
    "Snap Threshold",
    "Delta angle below which rotation snaps instantly (degrees).",
    0.35, 0.05, 5.0, 0.05,
  ).inGroup(ROTATION_SETTINGS_GROUP)

  private val rareLoadoutEnabledSetting = CheckboxSetting(
    "Rare Mob Loadout",
    "Swap wardrobe and run a pet command when a boss sea creature's HP drops below threshold.",
    false,
  ).inGroup(RARE_MOB_GROUP)

  private val rareMobKeywordsSetting = TextSetting(
    "Rare Mob Keywords",
    "Comma-separated keywords that mark a sea creature as a boss.",
    DEFAULT_RARE_MOB_KEYWORDS,
  ).inGroup(RARE_MOB_GROUP)

  private val rareWardrobeSetSetting = SliderSetting(
    "Rare Wardrobe Set",
    "Wardrobe set to equip when boss HP is low (0 = skip).",
    0.0, 0.0, 27.0, 1.0,
  ).inGroup(RARE_MOB_GROUP)

  private val rarePetCommandSetting = TextSetting(
    "Rare Pet Command",
    "Command to run when boss HP is low, e.g. 'pets' to open pets menu.",
    "",
  ).inGroup(RARE_MOB_GROUP)

  private val rareHpThresholdSetting = SliderSetting(
    "Boss HP Threshold",
    "Boss HP percentage below which the wardrobe and pet swap triggers.",
    30.0, 0.0, 100.0, 5.0,
  ).inGroup(RARE_MOB_GROUP)

  private val lockRotationSetting = CheckboxSetting(
    "Lock Rotation",
    "Lock camera to the cast direction while waiting for a bite, so the macro runs without needing focus.",
    true,
  ).inGroup(ROTATION_LOCK_GROUP)

  private val ungrabMouseSetting = CheckboxSetting(
    "Ungrab Mouse",
    "Free the OS cursor while rotation is locked so you can use other windows.",
    true,
  ).inGroup(ROTATION_LOCK_GROUP)

  private val showCastBoxSetting = CheckboxSetting(
    "Show Cast Box",
    "Render a small box at the water surface you aimed at when casting.",
    true,
  ).inGroup(ROTATION_LOCK_GROUP)

  private val showBobberBoxSetting = CheckboxSetting(
    "Show Bobber Box",
    "Render a small box around the bobber while waiting for a bite.",
    true,
  ).inGroup(ROTATION_LOCK_GROUP)

  private val combatRotationStrategy = BezierTrackingRotationStrategy(
    yawStepSampler = { combatRotationSpeedSetting.value.toFloat().coerceAtLeast(1f) },
    pitchStepSampler = { (combatRotationSpeedSetting.value * 0.78).toFloat().coerceAtLeast(1f) },
    curveInProvider = { curveInSetting.value.toFloat() },
    curveOutProvider = { curveOutSetting.value.toFloat() },
    minScaleProvider = { minScaleSetting.value.toFloat() },
    snapThresholdProvider = { snapThresholdSetting.value.toFloat() },
  )

  private val etherwarpNavRotationStrategy = BezierTrackingRotationStrategy(
    yawStepSampler = { 20f },
    pitchStepSampler = { 15f },
    curveInProvider = { curveInSetting.value.toFloat() },
    curveOutProvider = { curveOutSetting.value.toFloat() },
    minScaleProvider = { minScaleSetting.value.toFloat() },
    snapThresholdProvider = { snapThresholdSetting.value.toFloat() },
  )

  private var wasEnabled = false
  private var state = State.IDLE
  private var nextActionTick = 0L
  private var pendingReel = false
  private var lastMissingRodNoticeTick = -1L
  private var lastMissingWeaponNoticeTick = -1L
  private var rememberedWeaponSlot = -1
  private var combatAnchorPos: Vec3? = null
  private var killStartTick = 0L
  private var lastAttackTick = Long.MIN_VALUE
  private var macroSneakHeld = false
  private var castTargetPos: Vec3? = null
  private var hyperionDownClicksRemaining = 0
  private var navTargetPos: Vec3? = null
  private var navStartTick = 0L
  private var etherwarpNavTriggered = false
  private var etherwarpNavConfirmed = false
  private var lastSmoothnessApplied = -1.0
  private var rareLoadoutTriggered = false

  init {
    addSetting(
      enabledSetting,
      toggleFishingMacroSetting,
      infoSetting,
      stateText,
      rodSlotSetting,
      fishingTypeSetting,
      sneakWhileFishingSetting,
      catchGoldenFishSetting,
      goldenFishPredictionDistanceSetting,
      minimumTrophyWaitTimeSetting,
      reactionDelayTicksSetting,
      recastDelayTicksSetting,
      castSettleTicksSetting,
      hookTimeoutTicksSetting,
      splashRadiusSetting,
      autoSelectRodSetting,
      killSeaCreaturesSetting,
      combatMethodSetting,
      weaponSlotSetting,
      reelSettleTicksSetting,
      weaponSwapDelayTicksSetting,
      targetSearchRangeSetting,
      attackRangeSetting,
      attackIntervalTicksSetting,
      killTimeoutTicksSetting,
      combatRotationSpeedSetting,
      targetKeywordsSetting,
      soulWhipArcSetting,
      hyperionTargetKeywordsSetting,
      hyperionDownClicksSetting,
      hyperionDownMinRangeSetting,
      lavaBurstSetting,
      lavaBurstKeywordsSetting,
      surfStriderThresholdSetting,
      surfWeaponModeSetting,
      surfSpotXSetting,
      surfSpotYSetting,
      surfSpotZSetting,
      surfYawSetting,
      surfPitchSetting,
      surfLookBlockXSetting,
      surfLookBlockYSetting,
      surfLookBlockZSetting,
      saveSurfSpotSetting,
      hotspotNavModeSetting,
      useEtherwarpNavSetting,
      navTimeoutTicksSetting,
      navArrivalBlocksSetting,
      etherwarpNavTimeoutTicksSetting,
      smoothnessSetting,
      curveInSetting,
      curveOutSetting,
      minScaleSetting,
      snapThresholdSetting,
      rareLoadoutEnabledSetting,
      rareMobKeywordsSetting,
      rareWardrobeSetSetting,
      rarePetCommandSetting,
      rareHpThresholdSetting,
      lockRotationSetting,
      ungrabMouseSetting,
      showCastBoxSetting,
      showBobberBoxSetting,
    )
    EventBus.register(this)
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
    if (toggleFishingMacroSetting.value.isPressed()) {
      enabledSetting.value = !enabledSetting.value
    }

    if (!enabledSetting.value) {
      if (wasEnabled) {
        stopMacro()
      }
      releaseMacroSneak()
      wasEnabled = false
      return
    }

    val player = mc.player ?: run { stopMacro(); return }
    val level = mc.level ?: run { stopMacro(); return }

    if (!wasEnabled) {
      rememberedWeaponSlot = player.inventory.selectedSlot
    }
    wasEnabled = true
    syncSmoothnessSliders()

    if (mc.screen != null) {
      RotationExecutor.stopIfUsing(combatRotationStrategy)
      releaseMacroSneak()
      setState(State.PAUSED)
      return
    }

    val rodSlot = resolveActiveRodSlot(player)
    if (rodSlot !in 0..8) {
      setState(State.MISSING_ROD)
      if (lastMissingRodNoticeTick < 0L || level.gameTime - lastMissingRodNoticeTick >= MISSING_ROD_NOTICE_TICKS) {
        ChatUtils.sendMessage("Fishing Macro requires a fishing rod in your hotbar.")
        lastMissingRodNoticeTick = level.gameTime
      }
      releaseMacroSneak()
      return
    }
    lastMissingRodNoticeTick = -1L

    if (player.inventory.selectedSlot in 0..8 && player.inventory.selectedSlot != rodSlot) {
      rememberedWeaponSlot = player.inventory.selectedSlot
    }

    when (state) {
      State.MOVING_TO_HOTSPOT -> {
        handleMovingToHotspot(player, rodSlot, level.gameTime)
        return
      }
      State.ETHERWARPING_TO_HOTSPOT -> {
        handleEtherwarpingToHotspot(player, rodSlot, level.gameTime)
        return
      }
      State.REELING -> {
        handleReeling(player, rodSlot, level.gameTime)
        return
      }
      State.SWITCHING_TO_WEAPON -> {
        handleSwitchingToWeapon(player, rodSlot, level.gameTime)
        return
      }
      State.KILLING -> {
        handleKilling(player, rodSlot, level.gameTime)
        return
      }
      State.SWITCHING_BACK -> {
        handleSwitchingBack(player, rodSlot, level.gameTime)
        return
      }
      else -> Unit
    }

    if (autoSelectRodSetting.value && player.inventory.selectedSlot != rodSlot) {
      InventoryUtils.holdHotbarSlot(rodSlot)
    }
    updateMacroSneak()
    applyRotationLock()

    val hook = resolveOwnedHook(player)

    if (pendingReel) {
      handlePendingReel(hook, rodSlot, level.gameTime)
      return
    }

    if (hook != null) {
      if (hook.tickCount >= MIN_SPLASH_HOOK_AGE_TICKS && hasConfirmedBite(hook)) {
        if (!canReelForFishingType(hook)) {
          setState(State.WAITING_BITE)
          return
        }
        combatAnchorPos = hook.position()
        queueReel(hook, level.gameTime)
      } else if (hook.tickCount >= hookTimeoutTicks() && level.gameTime >= nextActionTick) {
        combatAnchorPos = combatAnchorPos ?: hook.position()
        startReeling(rodSlot, level.gameTime)
      } else {
        setState(State.WAITING_BITE)
      }
      return
    }

    if (level.gameTime < nextActionTick) {
      setState(if (state == State.CASTING) State.CASTING else State.RECAST_DELAY)
      return
    }

    if (isSurfMode()) {
      if (handleSurfNavigationBeforeCast(player, level.gameTime)) {
        return
      }
      if (tryStartSurfCast(player, rodSlot, level.gameTime)) {
        return
      }
    }

    if (hotspotNavModeSetting.value != 0) {
      val hotspotPos = FishingHotspotModule.nearestHotspotPos(player.position())
      if (hotspotPos != null) {
        val navTarget = computeNavTarget(player, hotspotPos)
        val arrivalSq = navArrivalBlocksSetting.value * navArrivalBlocksSetting.value
        if (navTarget != null && player.distanceToSqr(navTarget) > arrivalSq) {
          navTargetPos = navTarget
          navStartTick = level.gameTime
          if (useEtherwarpNavSetting.value) {
            val ewSlot = EtherwarpLogic.findEtherwarpHotbarSlot()
            val targetBlock = BlockPos.containing(navTarget)
            val ewResult = EtherwarpLogic.getEtherwarpResultTo(targetBlock)
            if (ewSlot >= 0 && ewResult.succeeded) {
              InventoryUtils.holdHotbarSlot(ewSlot)
              etherwarpNavTriggered = false
              etherwarpNavConfirmed = false
              setState(State.ETHERWARPING_TO_HOTSPOT)
              return
            }
          }
          NativePathfinder.setTargetWithRadius(navTarget.x, navTarget.y, navTarget.z, navArrivalBlocksSetting.value)
          setState(State.MOVING_TO_HOTSPOT)
          return
        }
      }
    }

    castTargetPos = computeCastTarget(player)
    enterRotationLock(player)
    useRod(rodSlot)
    nextActionTick = level.gameTime + castSettleTicksSetting.value.toLong()
    setState(State.CASTING)
  }

  @SubscribeEvent
  fun onPacket(event: PacketEvent.Incoming) {
    if (!enabledSetting.value) return

    if (state == State.ETHERWARPING_TO_HOTSPOT && etherwarpNavTriggered && event.packet is ClientboundPlayerPositionPacket) {
      etherwarpNavConfirmed = true
      return
    }

    if (state != State.WAITING_BITE && state != State.CASTING && state != State.RECAST_DELAY) return

    val packet = event.packet as? ClientboundSoundPacket ?: return
    if (packet.sound.value() != SoundEvents.FISHING_BOBBER_SPLASH) return

    val player = mc.player ?: return
    val hook = resolveOwnedHook(player) ?: return
    if (hook.tickCount < MIN_SPLASH_HOOK_AGE_TICKS) return
    if (!shouldAcceptSplashForActiveType(hook, mc.level?.gameTime ?: return)) return

    val dx = packet.x - hook.x
    val dy = packet.y - hook.y
    val dz = packet.z - hook.z
    val radiusSq = splashRadiusSetting.value * splashRadiusSetting.value
    if ((dx * dx) + (dy * dy) + (dz * dz) > radiusSq) return

    queueReel(hook, mc.level?.gameTime ?: return)
  }

  @SubscribeEvent
  fun onChat(event: ChatEvent.Receive) {
    if (!enabledSetting.value || !lavaBurstSetting.value) return
    val raw = normalizeText(event.message ?: return)
    val keywords = parseTargetKeywords(lavaBurstKeywordsSetting.value)
    if (keywords.none { raw.contains(it) }) return
    MouseUtils.rightClick()
    TickScheduler.schedule(1) { MouseUtils.rightClick() }
    TickScheduler.schedule(21) { MouseUtils.rightClick() }
    TickScheduler.schedule(22) { MouseUtils.rightClick() }
  }

  @SubscribeEvent
  fun onRender(event: WorldRenderEvent.Last) {
    if (!enabledSetting.value) return
    renderSoulWhipArc(event)
    renderCastBox(event)
    renderBobberBox(event)
  }

  private fun renderSoulWhipArc(event: WorldRenderEvent.Last) {
    if (!soulWhipArcSetting.value) return
    val player = mc.player ?: return
    val soulWhipArc = state == State.KILLING && activeCombatMethod() == CombatMethod.SOUL_WHIP
    val destination =
      if (soulWhipArc) {
        findActiveCombatTarget(player)?.position()?.add(0.0, 0.65, 0.0)
      } else {
        resolveOwnedHook(player)?.position()?.add(0.0, 0.12, 0.0)
      } ?: return
    val start =
      if (soulWhipArc) {
        player.position().add(0.0, player.eyeHeight.toDouble() * 0.72, 0.0)
      } else {
        player.position().add(0.0, player.eyeHeight.toDouble() * 0.42, 0.0)
      }
    drawFishingArc(event, start, destination, soulWhipArc)
  }

  private fun renderCastBox(event: WorldRenderEvent.Last) {
    if (!showCastBoxSetting.value) return
    val pos = castTargetPos ?: return
    val box = AABB(
      pos.x - CAST_BOX_HALF, pos.y - CAST_BOX_HALF, pos.z - CAST_BOX_HALF,
      pos.x + CAST_BOX_HALF, pos.y + CAST_BOX_HALF, pos.z + CAST_BOX_HALF,
    )
    Render3D.drawStyledBox(event.context, box, CAST_BOX_COLOR, esp = true)
  }

  private fun renderBobberBox(event: WorldRenderEvent.Last) {
    if (!showBobberBoxSetting.value) return
    if (state != State.WAITING_BITE && state != State.CASTING) return
    val player = mc.player ?: return
    val pos = resolveOwnedHook(player)?.position() ?: return
    val box = AABB(
      pos.x - BOBBER_BOX_HALF, pos.y - BOBBER_BOX_HALF, pos.z - BOBBER_BOX_HALF,
      pos.x + BOBBER_BOX_HALF, pos.y + BOBBER_BOX_HALF, pos.z + BOBBER_BOX_HALF,
    )
    Render3D.drawStyledBox(event.context, box, BOBBER_BOX_COLOR, esp = true)
  }

  private fun queueReel(hook: FishingHook, gameTick: Long) {
    pendingReel = true
    combatAnchorPos = hook.position()
    nextActionTick = gameTick + reactionDelayTicksSetting.value.toLong()
  }

  private fun handlePendingReel(hook: FishingHook?, rodSlot: Int, gameTick: Long) {
    if (hook == null) {
      pendingReel = false
      scheduleRecast(gameTick)
      return
    }

    if (gameTick < nextActionTick) {
      setState(State.WAITING_BITE)
      return
    }

    if (!hasConfirmedBite(hook)) {
      pendingReel = false
      setState(State.WAITING_BITE)
      return
    }

    combatAnchorPos = combatAnchorPos ?: hook.position()
    startReeling(rodSlot, gameTick)
  }

  private fun handleReeling(player: Player, rodSlot: Int, gameTick: Long) {
    if (gameTick < nextActionTick) {
      setState(State.REELING)
      return
    }

    if (tryStartCombat(player, rodSlot, gameTick)) {
      return
    }

    scheduleRecast(gameTick)
  }

  private fun handleSwitchingToWeapon(player: Player, rodSlot: Int, gameTick: Long) {
    val target = findActiveCombatTarget(player)
    if (target == null) {
      beginSwitchBack(rodSlot, gameTick)
      return
    }

    val weaponSlot = resolveActiveWeaponSlot(player, rodSlot)
    if (weaponSlot !in 0..8) {
      warnMissingWeapon(gameTick)
      beginSwitchBack(rodSlot, gameTick)
      return
    }

    if (player.inventory.selectedSlot != weaponSlot) {
      InventoryUtils.holdHotbarSlot(weaponSlot)
    }

    RotationExecutor.rotateTo(combatAimRotation(player, target, activeCombatMethod(target)), combatRotationStrategy)

    if (gameTick < nextActionTick) {
      setState(State.SWITCHING_TO_WEAPON)
      return
    }

    killStartTick = gameTick
    lastAttackTick = gameTick - attackIntervalTicksSetting.value.toLong()
    if (activeCombatMethod() == CombatMethod.HYPERION_DOWN) {
      hyperionDownClicksRemaining = hyperionDownClicksSetting.value.toInt()
    }
    setState(State.KILLING)
  }

  private fun handleKilling(player: Player, rodSlot: Int, gameTick: Long) {
    if (activeCombatMethod() == CombatMethod.HYPERION_DOWN) {
      tickHyperionDown(player, rodSlot, gameTick)
      return
    }

    val target = findActiveCombatTarget(player)
    if (target == null || gameTick - killStartTick >= killTimeoutTicksSetting.value.toLong()) {
      beginSwitchBack(rodSlot, gameTick)
      return
    }

    val method = activeCombatMethod(target)
    val rotation = combatAimRotation(player, target, method)
    RotationExecutor.rotateTo(rotation, combatRotationStrategy)

    val attackInterval = attackIntervalTicksSetting.value.toLong().coerceAtLeast(1L)
    if (gameTick - lastAttackTick >= attackInterval) {
      when (method) {
        CombatMethod.MELEE -> {
          if (isAimedAt(rotation)) {
            val attackRangeSq = attackRangeSetting.value * attackRangeSetting.value
            if (player.distanceToSqr(target) <= attackRangeSq) {
              MouseUtils.leftClick()
              lastAttackTick = gameTick
            }
          }
        }
        CombatMethod.SOUL_WHIP -> {
          val dx = target.x - player.x
          val dz = target.z - player.z
          val verticalDiff = target.y - player.y
          if (dx * dx + dz * dz <= SOUL_WHIP_HORIZONTAL_RANGE_SQ && verticalDiff <= SOUL_WHIP_VERTICAL_RANGE) {
            MouseUtils.rightClick()
            lastAttackTick = gameTick
          }
        }
        CombatMethod.HYPERION,
        CombatMethod.HYPERION_DOWN -> {
          if (isAimedAt(rotation)) {
            MouseUtils.rightClick()
            lastAttackTick = gameTick
          }
        }
      }
    }

    if (rareLoadoutEnabledSetting.value && !rareLoadoutTriggered && isRareMob(target)) {
      val maxHp = target.maxHealth
      if (maxHp > 0f && (target.health / maxHp * 100f) <= rareHpThresholdSetting.value) {
        triggerRareLoadout()
        rareLoadoutTriggered = true
      }
    }

    setState(State.KILLING)
  }

  private fun tickHyperionDown(player: Player, rodSlot: Int, gameTick: Long) {
    val target = findActiveCombatTarget(player, anchor = null)
    if (target == null || gameTick - killStartTick >= killTimeoutTicksSetting.value.toLong()) {
      beginSwitchBack(rodSlot, gameTick)
      return
    }

    RotationExecutor.rotateTo(Rotation(player.yRot, HYPERION_PITCH), combatRotationStrategy)

    val minRangeSq = hyperionDownMinRangeSetting.value * hyperionDownMinRangeSetting.value
    if (player.distanceToSqr(target) > minRangeSq) {
      setState(State.KILLING)
      return
    }

    if (hyperionDownClicksRemaining > 0 && gameTick > lastAttackTick) {
      MouseUtils.rightClick()
      lastAttackTick = gameTick
      hyperionDownClicksRemaining--
    }

    if (hyperionDownClicksRemaining <= 0) {
      beginSwitchBack(rodSlot, gameTick)
      return
    }

    setState(State.KILLING)
  }

  private fun handleSwitchingBack(player: Player, rodSlot: Int, gameTick: Long) {
    RotationExecutor.stopIfUsing(combatRotationStrategy)
    applyRotationLock()

    if (player.inventory.selectedSlot != rodSlot) {
      InventoryUtils.holdHotbarSlot(rodSlot)
    }

    if (gameTick < nextActionTick) {
      setState(State.SWITCHING_BACK)
      return
    }

    scheduleRecast(gameTick)
  }

  private fun tryStartCombat(player: Player, rodSlot: Int, gameTick: Long): Boolean {
    if (!killSeaCreaturesSetting.value) {
      combatAnchorPos = null
      return false
    }

    val target = findActiveCombatTarget(player) ?: run {
      combatAnchorPos = null
      return false
    }

    if (isSurfMode() && countSurfstriders(player) < surfStriderThresholdSetting.value.toInt().coerceIn(1, 30)) {
      return false
    }

    val weaponSlot = resolveActiveWeaponSlot(player, rodSlot)
    if (weaponSlot !in 0..8) {
      warnMissingWeapon(gameTick)
      combatAnchorPos = null
      return false
    }

    InventoryUtils.holdHotbarSlot(weaponSlot)
    RotationExecutor.rotateTo(combatAimRotation(player, target, activeCombatMethod(target)), combatRotationStrategy)
    nextActionTick = gameTick + weaponSwapDelayTicksSetting.value.toLong()
    setState(State.SWITCHING_TO_WEAPON)
    return true
  }

  private fun beginSwitchBack(rodSlot: Int, gameTick: Long) {
    RotationExecutor.stopIfUsing(combatRotationStrategy)
    InventoryUtils.holdHotbarSlot(rodSlot)
    combatAnchorPos = null
    killStartTick = 0L
    lastAttackTick = Long.MIN_VALUE
    hyperionDownClicksRemaining = 0
    rareLoadoutTriggered = false
    nextActionTick = gameTick + weaponSwapDelayTicksSetting.value.toLong()
    setState(State.SWITCHING_BACK)
  }

  private fun startReeling(rodSlot: Int, gameTick: Long) {
    releaseMacroSneak()
    useRod(rodSlot)
    pendingReel = false
    nextActionTick = gameTick + reelSettleTicksSetting.value.toLong()
    setState(State.REELING)
  }

  private fun scheduleRecast(gameTick: Long) {
    RotationExecutor.stopIfUsing(combatRotationStrategy)
    combatAnchorPos = null
    killStartTick = 0L
    lastAttackTick = Long.MIN_VALUE
    rareLoadoutTriggered = false
    nextActionTick = gameTick + recastDelayTicksSetting.value.toLong()
    setState(State.RECAST_DELAY)
  }

  private fun resolveRodSlot(player: Player): Int {
    val configured = rodSlotSetting.value.toInt() - 1
    if (configured in 0..8) {
      return if (isFishingRod(player, configured)) configured else -1
    }

    for (slot in 0..8) {
      if (isFishingRod(player, slot)) {
        return slot
      }
    }
    return -1
  }

  private fun resolveActiveRodSlot(player: Player): Int {
    if (!isSurfMode()) return resolveRodSlot(player)

    val configured = rodSlotSetting.value.toInt() - 1
    if (configured in 0..8) {
      return if (isFishingRod(player, configured)) configured else -1
    }

    for (slot in 0..8) {
      if (!isFishingRod(player, slot)) continue
      val name = normalizeText(player.inventory.getItem(slot).hoverName.string)
      if (SURF_LAVA_ROD_KEYWORDS.any { name.contains(it) }) return slot
    }

    return resolveRodSlot(player)
  }

  private fun resolveWeaponSlot(player: Player, rodSlot: Int): Int {
    val configured = weaponSlotSetting.value.toInt() - 1

    if (configured in 0..8) {
      return if (
        configured != rodSlot &&
        !player.inventory.getItem(configured).isEmpty
      ) {
        configured
      } else {
        -1
      }
    }

    if (combatMethod() == CombatMethod.SOUL_WHIP) {
      for (slot in 0..8) {
        if (slot == rodSlot) continue
        if (isUniversalSoulWhipWeapon(player, slot)) return slot
      }
    }

    if (
      rememberedWeaponSlot in 0..8 &&
      rememberedWeaponSlot != rodSlot &&
      !player.inventory.getItem(rememberedWeaponSlot).isEmpty
    ) {
      return rememberedWeaponSlot
    }

    val selected = player.inventory.selectedSlot
    return if (
      selected in 0..8 &&
      selected != rodSlot &&
      !player.inventory.getItem(selected).isEmpty
    ) {
      selected
    } else {
      -1
    }
  }

  private fun resolveActiveWeaponSlot(player: Player, rodSlot: Int): Int {
    val configured = weaponSlotSetting.value.toInt() - 1

    if (configured in 0..8) {
      return if (
        configured != rodSlot &&
        !player.inventory.getItem(configured).isEmpty
      ) {
        configured
      } else {
        -1
      }
    }

    val method = activeCombatMethod()
    if (method == CombatMethod.SOUL_WHIP) {
      for (slot in 0..8) {
        if (slot == rodSlot) continue
        if (isUniversalSoulWhipWeapon(player, slot)) return slot
      }
    }

    if (isSurfMode()) {
      val mode = SurfWeaponMode.entries.getOrElse(surfWeaponModeSetting.value) { SurfWeaponMode.SOUL_WHIP }
      val preferred = when (mode) {
        SurfWeaponMode.SOUL_WHIP -> findHotbarSlot(player, UNIVERSAL_SOUL_WHIP_WEAPON_KEYWORDS) { false }
        SurfWeaponMode.AXE -> findHotbarSlot(player, SURF_AXE_KEYWORDS) { it.item is AxeItem }
      }
      if (preferred in 0..8 && preferred != rodSlot) return preferred
    }

    return resolveWeaponSlot(player, rodSlot)
  }

  private fun findHotbarSlot(
    player: Player,
    keywords: List<String>,
    itemPredicate: (net.minecraft.world.item.ItemStack) -> Boolean,
  ): Int {
    for (slot in 0..8) {
      val stack = player.inventory.getItem(slot)
      if (stack.isEmpty) continue
      val name = normalizeText(stack.hoverName.string)
      if (itemPredicate(stack) || keywords.any { name.contains(it) }) {
        return slot
      }
    }
    return -1
  }

  private fun isUniversalSoulWhipWeapon(player: Player, slot: Int): Boolean {
    if (slot !in 0..8) return false

    val stack = player.inventory.getItem(slot)
    if (stack.isEmpty) return false

    val name = normalizeText(stack.hoverName.string)
    return UNIVERSAL_SOUL_WHIP_WEAPON_KEYWORDS.any { keyword -> name.contains(keyword) }
  }

  private fun findSeaCreatureTarget(player: Player, anchor: Vec3? = combatAnchorPos): Mob? {
    val level = mc.level ?: return null
    val keywords = parseTargetKeywords(targetKeywordsSetting.value)
    val searchRangeSq = targetSearchRangeSetting.value * targetSearchRangeSetting.value

    return level.entitiesForRendering()
      .asSequence()
      .mapNotNull { it as? Mob }
      .filter { mob ->
        if (!mob.isAlive || mob.isInvisible) {
          return@filter false
        }

        val playerDistanceSq = player.distanceToSqr(mob)
        val anchorDistanceSq = anchor?.let { mob.position().distanceToSqr(it) } ?: Double.MAX_VALUE
        if (playerDistanceSq > searchRangeSq && anchorDistanceSq > searchRangeSq) {
          return@filter false
        }

        val matchesKeyword = matchesTargetKeyword(mob, keywords)
        matchesKeyword || anchorDistanceSq <= CLOSE_HOOK_FALLBACK_RANGE_SQ
      }
      .minByOrNull { mob ->
        val playerDistanceSq = player.distanceToSqr(mob)
        val anchorDistanceSq = anchor?.let { mob.position().distanceToSqr(it) } ?: playerDistanceSq
        val anchorBias = if (matchesTargetKeyword(mob, keywords)) 0.0 else CLOSE_HOOK_NON_KEYWORD_PENALTY
        (anchorDistanceSq * 0.65) + (playerDistanceSq * 0.35) + anchorBias
      }
  }

  private fun findActiveCombatTarget(player: Player, anchor: Vec3? = combatAnchorPos): Mob? {
    return if (isSurfMode()) findSurfstriderTarget(player, anchor) else findSeaCreatureTarget(player, anchor)
  }

  private fun findSurfstriderTarget(player: Player, anchor: Vec3? = combatAnchorPos): Mob? {
    val level = mc.level ?: return null
    val searchRangeSq = targetSearchRangeSetting.value * targetSearchRangeSetting.value

    return level.entitiesForRendering()
      .asSequence()
      .mapNotNull { it as? Mob }
      .filter { mob ->
        if (!mob.isAlive || mob.isInvisible) return@filter false
        if (!matchesSurfstrider(mob)) return@filter false

        val playerDistanceSq = player.distanceToSqr(mob)
        val anchorDistanceSq = anchor?.let { mob.position().distanceToSqr(it) } ?: Double.MAX_VALUE
        playerDistanceSq <= searchRangeSq || anchorDistanceSq <= searchRangeSq
      }
      .minByOrNull { mob ->
        val playerDistanceSq = player.distanceToSqr(mob)
        val anchorDistanceSq = anchor?.let { mob.position().distanceToSqr(it) } ?: playerDistanceSq
        (anchorDistanceSq * 0.65) + (playerDistanceSq * 0.35)
      }
  }

  private fun countSurfstriders(player: Player): Int {
    val level = mc.level ?: return 0
    val searchRangeSq = targetSearchRangeSetting.value * targetSearchRangeSetting.value
    return level.entitiesForRendering()
      .asSequence()
      .mapNotNull { it as? Mob }
      .count { mob ->
        mob.isAlive &&
          !mob.isInvisible &&
          player.distanceToSqr(mob) <= searchRangeSq &&
          matchesSurfstrider(mob)
      }
  }

  private fun matchesSurfstrider(mob: Mob): Boolean {
    val names = targetMatchNames(mob)
    return SURFSTRIDER_TARGET_KEYWORDS.any { keyword -> names.any { it.contains(keyword) } }
  }

  private fun matchesTargetKeyword(mob: Mob, keywords: List<String>): Boolean {
    if (keywords.isEmpty()) return true

    val names = targetMatchNames(mob)
    return keywords.any { keyword -> names.any { name -> name.contains(keyword) } }
  }

  private fun isHyperionTarget(mob: Mob): Boolean {
    val keywords = parseTargetKeywords(hyperionTargetKeywordsSetting.value)
    if (keywords.isEmpty()) return false

    val names = targetMatchNames(mob)
    return keywords.any { keyword -> names.any { name -> name.contains(keyword) } }
  }

  private fun targetMatchNames(mob: Mob): List<String> {
    val names = ArrayList<String>(4)
    names += normalizeText(mob.displayName.string)
    names += normalizeText(mob.type.descriptionId)

    val level = mc.level ?: return names
    level.entitiesForRendering()
      .asSequence()
      .mapNotNull { it as? ArmorStand }
      .filter { stand -> stand.isAlive && stand.position().distanceToSqr(mob.position()) <= TARGET_LABEL_RANGE_SQ }
      .map { stand -> stand.customName?.string ?: stand.name.string }
      .mapTo(names) { normalizeText(it) }

    return names.distinct()
  }

  private fun parseTargetKeywords(raw: String): List<String> {
    return raw.split(',')
      .map { normalizeText(it) }
      .filter { it.isNotBlank() }
      .distinct()
  }

  private fun normalizeText(text: String): String {
    return (ChatFormatting.stripFormatting(text) ?: text)
      .lowercase(Locale.US)
      .trim()
  }

  private fun isAimedAt(rotation: Rotation): Boolean {
    val player = mc.player ?: return false
    val yawDelta = abs(AngleUtils.getRotationDelta(player.yRot, rotation.yaw))
    val pitchDelta = abs(rotation.pitch - player.xRot)
    return yawDelta <= ATTACK_YAW_TOLERANCE && pitchDelta <= ATTACK_PITCH_TOLERANCE
  }

  private fun combatAimRotation(player: Player, target: Mob, method: CombatMethod): Rotation {
    return when (method) {
      CombatMethod.HYPERION,
      CombatMethod.HYPERION_DOWN -> Rotation(player.yRot, HYPERION_PITCH)
      CombatMethod.MELEE,
      CombatMethod.SOUL_WHIP -> AngleUtils.getRotation(target)
    }
  }

  private fun combatMethod(target: Mob? = null): CombatMethod {
    val base = CombatMethod.entries.getOrElse(combatMethodSetting.value) { CombatMethod.MELEE }
    if (base == CombatMethod.HYPERION_DOWN) return base
    if (target != null && isHyperionTarget(target)) return CombatMethod.HYPERION
    return base
  }

  private fun activeCombatMethod(target: Mob? = null): CombatMethod {
    if (isSurfMode()) {
      return when (SurfWeaponMode.entries.getOrElse(surfWeaponModeSetting.value) { SurfWeaponMode.SOUL_WHIP }) {
        SurfWeaponMode.SOUL_WHIP -> CombatMethod.SOUL_WHIP
        SurfWeaponMode.AXE -> CombatMethod.MELEE
      }
    }

    return combatMethod(target)
  }

  private fun canReelForActiveType(hook: FishingHook, gameTick: Long): Boolean {
    val runtime = fishingRuntime(gameTick)
    return activeFishingProfile().canReel(hook, runtime)
  }

  private fun shouldAcceptSplashForActiveType(hook: FishingHook, gameTick: Long): Boolean {
    val runtime = fishingRuntime(gameTick)
    return activeFishingProfile().shouldAcceptSplash(hook, runtime)
  }

  private fun canReelForFishingType(hook: FishingHook): Boolean {
    val gameTick = mc.level?.gameTime ?: return false
    return canReelForActiveType(hook, gameTick)
  }

  private fun fishingType(): FishingType {
    return FishingType.entries.getOrElse(fishingTypeSetting.value) { FishingType.NORMAL }
  }

  private fun isSurfMode(): Boolean = fishingType() == FishingType.STRIDERSURFER

  private fun updateMacroSneak() {
    val shouldSneak =
      sneakWhileFishingSetting.value &&
        state != State.REELING &&
        state != State.SWITCHING_TO_WEAPON &&
        state != State.KILLING &&
        state != State.SWITCHING_BACK

    if (shouldSneak) {
      mc.options.keyShift.setDown(true)
      macroSneakHeld = true
    } else {
      releaseMacroSneak()
    }
  }

  private fun releaseMacroSneak() {
    if (!macroSneakHeld) return
    mc.options.keyShift.setDown(false)
    macroSneakHeld = false
  }

  private fun drawFishingArc(event: WorldRenderEvent.Last, start: Vec3, end: Vec3, soulWhipArc: Boolean) {
    val midpoint = start.add(end).scale(0.5).add(0.0, arcHeight(start, end, soulWhipArc), 0.0)
    var previous = start
    val segments = if (soulWhipArc) SOUL_WHIP_ARC_SEGMENTS else BOBBER_ARC_SEGMENTS
    val color = if (soulWhipArc) SOUL_WHIP_ARC_COLOR else BOBBER_ARC_COLOR
    val thickness = if (soulWhipArc) SOUL_WHIP_ARC_THICKNESS else BOBBER_ARC_THICKNESS

    for (step in 1..segments) {
      val t = step.toDouble() / segments
      val point = quadraticBezier(start, midpoint, end, t)
      Render3D.drawLine(event.context, previous, point, color, esp = true, thickness = thickness)
      previous = point
    }
  }

  private fun quadraticBezier(start: Vec3, control: Vec3, end: Vec3, t: Double): Vec3 {
    val inverse = 1.0 - t
    return start.scale(inverse * inverse)
      .add(control.scale(2.0 * inverse * t))
      .add(end.scale(t * t))
  }

  private fun arcHeight(start: Vec3, end: Vec3, soulWhipArc: Boolean): Double {
    val distance = start.distanceTo(end)
    return if (soulWhipArc) {
      (distance * 0.28).coerceIn(1.2, 5.0)
    } else {
      (distance * 0.08).coerceIn(0.15, 1.1)
    }
  }

  private fun warnMissingWeapon(gameTick: Long) {
    if (lastMissingWeaponNoticeTick >= 0L && gameTick - lastMissingWeaponNoticeTick < MISSING_WEAPON_NOTICE_TICKS) {
      return
    }
    ChatUtils.sendMessage("Fishing Macro combat needs a valid non-rod weapon slot.")
    lastMissingWeaponNoticeTick = gameTick
  }

  private fun isFishingRod(player: Player, slot: Int): Boolean {
    if (slot !in 0..8) return false

    val stack = player.inventory.getItem(slot)
    if (stack.isEmpty) return false

    val name = normalizeText(stack.hoverName.string)

    // Soul Whip / Flay are weapons, never rods.
    if (UNIVERSAL_SOUL_WHIP_WEAPON_KEYWORDS.any { keyword -> name.contains(keyword) }) {
      return false
    }

    // Vanilla/fallback rod item check.
    if (stack.item is FishingRodItem) return true

    return when (fishingType()) {
      FishingType.STRIDERSURFER,
      FishingType.WORM -> VALID_LAVA_ROD_NAMES.any { rodName -> name.contains(rodName) }

      FishingType.NORMAL,
      FishingType.BARN,
      FishingType.TROPHY -> VALID_WATER_ROD_NAMES.any { rodName -> name.contains(rodName) }
    }
  }

  private fun handleSurfNavigationBeforeCast(player: Player, gameTick: Long): Boolean {
    val spot = surfSpot() ?: return false
    val arrivalSq = navArrivalBlocksSetting.value * navArrivalBlocksSetting.value
    if (player.distanceToSqr(spot) <= arrivalSq) return false

    navTargetPos = spot
    navStartTick = gameTick
    NativePathfinder.setTargetWithRadius(spot.x, spot.y, spot.z, navArrivalBlocksSetting.value)
    setState(State.MOVING_TO_HOTSPOT)
    return true
  }

  private fun tryStartSurfCast(player: Player, rodSlot: Int, gameTick: Long): Boolean {
    val savedAim = surfAim()
    val target = savedAim?.target ?: findClosestLavaTarget(player)
    val rotation = savedAim?.rotation ?: target?.let { AngleUtils.getRotation(player.eyePosition, it) }

    if (rotation == null) {
      castTargetPos = computeCastTarget(player)
      enterRotationLock(player)
      useRod(rodSlot)
      nextActionTick = gameTick + castSettleTicksSetting.value.toLong()
      setState(State.CASTING)
      return true
    }

    RotationExecutor.rotateTo(rotation, combatRotationStrategy)
    castTargetPos = target

    if (!isAimedAt(rotation)) {
      nextActionTick = gameTick + 1L
      setState(State.CASTING)
      return true
    }

    enterRotationLock(player)
    useRod(rodSlot)
    nextActionTick = gameTick + castSettleTicksSetting.value.toLong()
    setState(State.CASTING)
    return true
  }

  private fun surfSpot(): Vec3? {
    val x = surfSpotXSetting.value.trim().toDoubleOrNull() ?: return null
    val y = surfSpotYSetting.value.trim().toDoubleOrNull() ?: return null
    val z = surfSpotZSetting.value.trim().toDoubleOrNull() ?: return null
    return Vec3(x, y, z)
  }

  private fun surfAim(): SurfAim? {
    val yaw = surfYawSetting.value.trim().toFloatOrNull() ?: return null
    val pitch = surfPitchSetting.value.trim().toFloatOrNull() ?: return null
    val rotation = Rotation(AngleUtils.normalizeAngle(yaw), pitch.coerceIn(-89.9f, 89.9f))
    return SurfAim(rotation, surfLookBlockTarget())
  }

  private fun surfLookBlockTarget(): Vec3? {
    val level = mc.level ?: return null
    val x = surfLookBlockXSetting.value.trim().toIntOrNull() ?: return null
    val y = surfLookBlockYSetting.value.trim().toIntOrNull() ?: return null
    val z = surfLookBlockZSetting.value.trim().toIntOrNull() ?: return null
    val pos = BlockPos(x, y, z)
    val yOffset = if (level.getFluidState(pos).`is`(FluidTags.LAVA)) 0.55 else 0.5
    return Vec3(pos.x + 0.5, pos.y + yOffset, pos.z + 0.5)
  }

  private fun findLookedSurfBlock(player: Player): BlockPos? {
    val level = mc.level ?: return null
    val eye = player.eyePosition
    val end = eye.add(player.lookAngle.scale(CAST_RAYCAST_RANGE))
    val fluidResult = level.clip(ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, player))
    if (fluidResult.type == HitResult.Type.BLOCK) {
      return fluidResult.blockPos
    }

    val blockResult = level.clip(ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
    return if (blockResult.type == HitResult.Type.BLOCK) blockResult.blockPos else null
  }

  private fun findClosestLavaTarget(player: Player): Vec3? {
    val level = mc.level ?: return null
    val playerBlock = player.blockPosition()
    var bestPos: BlockPos? = null
    var bestDistanceSq = Double.MAX_VALUE

    for (dx in -SURF_LAVA_SEARCH_RADIUS..SURF_LAVA_SEARCH_RADIUS) {
      for (dy in -SURF_LAVA_VERTICAL_SEARCH..SURF_LAVA_VERTICAL_SEARCH) {
        for (dz in -SURF_LAVA_SEARCH_RADIUS..SURF_LAVA_SEARCH_RADIUS) {
          val pos = playerBlock.offset(dx, dy, dz)
          if (!level.getFluidState(pos).`is`(FluidTags.LAVA)) continue

          val target = Vec3(pos.x + 0.5, pos.y + 0.55, pos.z + 0.5)
          val distanceSq = player.eyePosition.distanceToSqr(target)
          if (distanceSq < bestDistanceSq) {
            bestDistanceSq = distanceSq
            bestPos = pos
          }
        }
      }
    }

    return bestPos?.let { Vec3(it.x + 0.5, it.y + 0.55, it.z + 0.5) }
  }

  private fun handleMovingToHotspot(player: Player, rodSlot: Int, gameTick: Long) {
    val target = navTargetPos ?: run { scheduleRecast(gameTick); return }

    if (gameTick - navStartTick >= navTimeoutTicksSetting.value.toLong()) {
      NativePathfinder.stop()
      MovementManager.setMovementLock(false)
      navTargetPos = null
      scheduleRecast(gameTick)
      return
    }

    val arrivalSq = navArrivalBlocksSetting.value * navArrivalBlocksSetting.value
    if (player.distanceToSqr(target) <= arrivalSq) {
      NativePathfinder.stop()
      MovementManager.setMovementLock(false)
      navTargetPos = null
      if (isSurfMode()) {
        tryStartSurfCast(player, rodSlot, gameTick)
        return
      }
      castTargetPos = FishingHotspotModule.nearestHotspotPos(player.position()) ?: computeCastTarget(player)
      enterRotationLock(player)
      useRod(rodSlot)
      nextActionTick = gameTick + castSettleTicksSetting.value.toLong()
      setState(State.CASTING)
      return
    }

    NativePathfinder.tick()?.applyToPlayer()
    setState(State.MOVING_TO_HOTSPOT)
  }

  private fun handleEtherwarpingToHotspot(player: Player, rodSlot: Int, gameTick: Long) {
    val target = navTargetPos ?: run {
      InventoryUtils.holdHotbarSlot(rodSlot)
      scheduleRecast(gameTick)
      return
    }

    if (etherwarpNavConfirmed) {
      etherwarpNavConfirmed = false
      etherwarpNavTriggered = false
      navTargetPos = null
      RotationExecutor.stopIfUsing(etherwarpNavRotationStrategy)
      InventoryUtils.holdHotbarSlot(rodSlot)
      castTargetPos = FishingHotspotModule.nearestHotspotPos(player.position()) ?: computeCastTarget(player)
      nextActionTick = gameTick + 5L
      setState(State.RECAST_DELAY)
      return
    }

    if (gameTick - navStartTick >= etherwarpNavTimeoutTicksSetting.value.toLong()) {
      RotationExecutor.stopIfUsing(etherwarpNavRotationStrategy)
      etherwarpNavTriggered = false
      InventoryUtils.holdHotbarSlot(rodSlot)
      NativePathfinder.setTargetWithRadius(target.x, target.y, target.z, navArrivalBlocksSetting.value)
      setState(State.MOVING_TO_HOTSPOT)
      return
    }

    val ewSlot = EtherwarpLogic.findEtherwarpHotbarSlot()
    if (ewSlot < 0) {
      RotationExecutor.stopIfUsing(etherwarpNavRotationStrategy)
      etherwarpNavTriggered = false
      navTargetPos = null
      InventoryUtils.holdHotbarSlot(rodSlot)
      scheduleRecast(gameTick)
      return
    }

    if (!etherwarpNavTriggered) {
      InventoryUtils.holdHotbarSlot(ewSlot)
      val targetCenter = Vec3(
        kotlin.math.floor(target.x) + 0.5,
        kotlin.math.floor(target.y) + 0.5,
        kotlin.math.floor(target.z) + 0.5,
      )
      val aimRot = AngleUtils.getRotation(player.eyePosition, targetCenter)
      RotationExecutor.rotateTo(aimRot, etherwarpNavRotationStrategy)

      if (isAimedAt(aimRot)) {
        etherwarpNavTriggered = true
        mc.options.keyShift.setDown(true)
        TickScheduler.schedule(2) {
          MouseUtils.rightClick()
          TickScheduler.schedule(3) { mc.options.keyShift.setDown(false) }
        }
      }
    }

    setState(State.ETHERWARPING_TO_HOTSPOT)
  }

  private fun computeNavTarget(player: Player, hotspotPos: Vec3): Vec3? {
    return when (hotspotNavModeSetting.value) {
      1 -> hotspotPos
      2 -> computeBankTarget(player, hotspotPos)
      else -> null
    }
  }

  private fun computeBankTarget(player: Player, hotspotPos: Vec3): Vec3? {
    val level = mc.level ?: return null
    data class Candidate(val pos: Vec3, val distToPlayer: Double)

    val candidates = ArrayList<Candidate>()

    for (i in 0 until BANK_SEARCH_ANGLES) {
      val angle = i * (2.0 * Math.PI / BANK_SEARCH_ANGLES)
      val dx = cos(angle)
      val dz = sin(angle)

      var r = BANK_SEARCH_MIN_RADIUS
      while (r <= BANK_SEARCH_MAX_RADIUS) {
        val cx = hotspotPos.x + dx * r
        val cz = hotspotPos.z + dz * r
        val blockPos = BlockPos.containing(cx, hotspotPos.y, cz)

        val fluidBelow = level.getFluidState(blockPos.below())
        if (!fluidBelow.isEmpty) { r += 1.0; continue }

        val foot = level.getBlockState(blockPos)
        val head = level.getBlockState(blockPos.above())
        val head2 = level.getBlockState(blockPos.above(2))

        if (!foot.isSolid || !head.isAir || !head2.isAir) { r += 1.0; continue }

        val standPos = Vec3(cx, blockPos.y + 1.0, cz)
        val eyePos = standPos.add(0.0, 1.62, 0.0)
        val losResult = level.clip(
          ClipContext(eyePos, hotspotPos.add(0.0, 0.5, 0.0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player),
        )
        if (losResult.type != HitResult.Type.BLOCK) {
          candidates += Candidate(standPos, player.distanceToSqr(standPos))
          break
        }
        r += 1.0
      }
    }

    return candidates.minByOrNull { it.distToPlayer }?.pos
  }

  private fun syncSmoothnessSliders() {
    val s = smoothnessSetting.value
    if (s == lastSmoothnessApplied) return
    lastSmoothnessApplied = s
    curveInSetting.value = lerp(0.10, 0.60, s)
    curveOutSetting.value = 0.92
    minScaleSetting.value = lerp(0.35, 0.12, s)
    snapThresholdSetting.value = lerp(0.50, 0.05, s)
  }

  private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t.coerceIn(0.0, 1.0)

  private fun isRareMob(mob: Mob): Boolean {
    val keywords = parseTargetKeywords(rareMobKeywordsSetting.value)
    if (keywords.isEmpty()) return false
    return matchesTargetKeyword(mob, keywords)
  }

  private fun triggerRareLoadout() {
    val setId = rareWardrobeSetSetting.value.toInt()
    if (setId in 1..27) {
      WardrobeModule.requestEquip(setId)
    }
    val petCmd = rarePetCommandSetting.value.trim().trimStart('/')
    if (petCmd.isNotBlank()) {
      mc.player?.connection?.sendCommand(petCmd)
    }
  }

  private fun resolveOwnedHook(player: Player): FishingHook? {
    val hook = player.fishing ?: return null
    if (!hook.isAlive) return null
    return if (hook.playerOwner?.uuid == player.uuid) hook else null
  }

  private fun hasHookedFishMarker(hook: FishingHook): Boolean {
    val level = mc.level ?: return false
    return level.entitiesForRendering().any { entity ->
      val stand = entity as? ArmorStand ?: return@any false
      if (!stand.isAlive) return@any false

      val rawName = stand.customName?.string ?: stand.name.string
      val name = ChatFormatting.stripFormatting(rawName)?.trim() ?: rawName.trim()
      name == HOOKED_FISH_MARKER_NAME &&
        stand.position().distanceToSqr(hook.position()) <= HOOKED_FISH_MARKER_RANGE_SQ
    }
  }

  private fun hasConfirmedBite(hook: FishingHook): Boolean {
    return isHookBiting(hook) || hasHookedFishMarker(hook)
  }

  private fun isHookBiting(hook: FishingHook): Boolean {
    return (hook as? FishingHookAccessor)?.isBiting() == true
  }

  private fun hookTimeoutTicks(): Int {
    return hookTimeoutTicksSetting.value.toInt().coerceAtLeast(MIN_HOOK_TIMEOUT_TICKS)
  }

  private fun useRod(slot: Int) {
    val player = mc.player ?: return
    if (slot !in 0..8) return
    if (!isFishingRod(player, slot)) return

    InventoryUtils.holdHotbarSlot(slot)
    MouseUtils.rightClick()
  }

  private fun computeCastTarget(player: Player): Vec3? {
    val level = mc.level ?: return null
    val eye = player.eyePosition
    val end = eye.add(player.lookAngle.scale(CAST_RAYCAST_RANGE))
    val result = level.clip(ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.SOURCE_ONLY, player))
    return if (result.type == HitResult.Type.BLOCK) result.location else null
  }

  private fun enterRotationLock(player: Player) {
    if (!lockRotationSetting.value) return
    FishingRotationLock.lock(player.yRot, player.xRot)
    if (ungrabMouseSetting.value) MouseUtils.ungrabMouse()
  }

  private fun exitRotationLock() {
    FishingRotationLock.unlock()
    MouseUtils.grabMouse()
  }

  private fun applyRotationLock() {
    if (!lockRotationSetting.value) return
    FishingRotationLock.applyLock()
  }

  private fun stopMacro() {
    releaseMacroSneak()
    exitRotationLock()
    castTargetPos = null
    pendingReel = false
    nextActionTick = 0L
    lastMissingRodNoticeTick = -1L
    lastMissingWeaponNoticeTick = -1L
    rememberedWeaponSlot = -1
    combatAnchorPos = null
    killStartTick = 0L
    lastAttackTick = Long.MIN_VALUE
    navTargetPos = null
    navStartTick = 0L
    etherwarpNavTriggered = false
    etherwarpNavConfirmed = false
    rareLoadoutTriggered = false
    NativePathfinder.stop()
    MovementManager.setMovementLock(false)
    RotationExecutor.stopIfUsing(combatRotationStrategy)
    RotationExecutor.stopIfUsing(etherwarpNavRotationStrategy)
    setState(State.IDLE)
  }

  private fun setState(next: State) {
    state = next
    stateText.value = when (next) {
      State.IDLE -> "Idle"
      State.CASTING -> "Casting"
      State.WAITING_BITE -> "Waiting Bite"
      State.REELING -> "Reeling"
      State.RECAST_DELAY -> "Recast Delay"
      State.MOVING_TO_HOTSPOT -> "Moving to Hotspot"
      State.ETHERWARPING_TO_HOTSPOT -> "Etherwarping"
      State.SWITCHING_TO_WEAPON -> "Switch Weapon"
      State.KILLING -> "Killing"
      State.SWITCHING_BACK -> "Switch Rod"
      State.MISSING_ROD -> "Missing Rod"
      State.PAUSED -> "Paused"
    }
  }

  private const val COMBAT_GROUP = "Combat"
  private const val TROPHY_GROUP = "Trophy Fishing"
  private const val ROTATION_LOCK_GROUP = "Rotation Lock"
  private const val HOTSPOT_NAV_GROUP = "Hotspot Navigation"
  private const val ROTATION_SETTINGS_GROUP = "Rotation Settings"
  private const val RARE_MOB_GROUP = "Rare Mob"
  private const val SURF_GROUP = "Surf Fishing"
  private const val BANK_SEARCH_ANGLES = 16
  private const val BANK_SEARCH_MIN_RADIUS = 3.0
  private const val BANK_SEARCH_MAX_RADIUS = 9.0
  private const val DEFAULT_RARE_MOB_KEYWORDS =
    "lord jawbus,jawbus,thunder,vanquisher,water hydra,deep sea protector"
  private const val MIN_SPLASH_HOOK_AGE_TICKS = 8
  private const val MIN_HOOK_TIMEOUT_TICKS = 700
  private const val MISSING_ROD_NOTICE_TICKS = 60L
  private const val MISSING_WEAPON_NOTICE_TICKS = 60L
  private const val HOOKED_FISH_MARKER_NAME = "!!!"
  private const val HOOKED_FISH_MARKER_RANGE_SQ = 2.25
  private const val TARGET_LABEL_RANGE_SQ = 9.0
  private const val CLOSE_HOOK_FALLBACK_RANGE_SQ = 16.0
  private const val CLOSE_HOOK_NON_KEYWORD_PENALTY = 5.0
  private const val HYPERION_PITCH = 90.0f
  private const val ATTACK_YAW_TOLERANCE = 14.0f
  private const val ATTACK_PITCH_TOLERANCE = 18.0f
  private const val SOUL_WHIP_ARC_SEGMENTS = 28
  private const val SOUL_WHIP_ARC_THICKNESS = 2.5f
  private val SOUL_WHIP_ARC_COLOR = Color(255, 0, 210, 230)
  private const val BOBBER_ARC_SEGMENTS = 18
  private const val BOBBER_ARC_THICKNESS = 1.15f
  private val BOBBER_ARC_COLOR = Color(235, 235, 255, 185)
  private const val DEFAULT_TARGET_KEYWORDS =
    "sea walker,sea guardian,sea witch,sea archer,rider of the deep,catfish,water hydra," +
      "phantom fisher,deep sea protector,nurse shark,blue shark,tiger shark,great white shark," +
      "thunder,lord jawbus,jawbus,squid,guardian,drowned,phantom,shark," +
      "moogma,magma slug,lava leech"
  private const val DEFAULT_HYPERION_TARGET_KEYWORDS = "moogma,magma slug,lava leech"
  private const val DEFAULT_LAVA_BURST_KEYWORDS = "from beneath the lava appears"
  private val SURFSTRIDER_TARGET_KEYWORDS = listOf("surfstrider", "surf strider", "surf-strider")
  private val SURF_LAVA_ROD_KEYWORDS = listOf("lava", "magma", "hellfire", "inferno")
  private val UNIVERSAL_SOUL_WHIP_WEAPON_KEYWORDS = listOf("soul whip", "flay")
  private val SURF_AXE_KEYWORDS = listOf("axe", "daedalus axe")
  private val VALID_WATER_ROD_NAMES = setOf(
    "fishing rod",
    "prismarine rod",
    "speedster rod",
    "farmer's rod",
    "winter rod",
    "ice rod",
    "challenging rod",
    "rod of champions",
    "rod of legends",
    "rod of the sea",
    "yeti rod",
    "auger rod",
    "phantom rod",
    "shredder",
  )
  private val VALID_LAVA_ROD_NAMES = setOf(
    "starter lava rod",
    "magma rod",
    "inferno rod",
    "hellfire rod",
  )
  private const val SURF_LAVA_SEARCH_RADIUS = 12
  private const val SURF_LAVA_VERTICAL_SEARCH = 5
  private const val CAST_RAYCAST_RANGE = 30.0
  private const val CAST_BOX_HALF = 0.15
  private const val BOBBER_BOX_HALF = 0.2
  private val CAST_BOX_COLOR = Color(0, 220, 220, 200)
  private val BOBBER_BOX_COLOR = Color(235, 235, 255, 200)
  private const val SOUL_WHIP_HORIZONTAL_RANGE_SQ = 13.0 * 13.0
  private const val SOUL_WHIP_VERTICAL_RANGE = 2.0
}
