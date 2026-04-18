package org.cobalt.internal.mining

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.random.Random
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.BlockChangeEvent
import org.cobalt.api.event.impl.client.ChatEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.ActionSetting
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.InfoSetting
import org.cobalt.api.module.setting.impl.InfoType
import org.cobalt.api.module.setting.impl.KeyBindSetting
import org.cobalt.api.module.setting.impl.TextSetting
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.util.helper.KeyBind
import org.cobalt.api.event.impl.render.WorldRenderEvent
import org.cobalt.api.rotation.RotationExecutor
import org.cobalt.api.util.AngleUtils
import org.cobalt.internal.pathfinding.HeadRotationModule
import org.cobalt.api.util.ChatUtils
import org.cobalt.api.util.InventoryUtils
import org.cobalt.api.pathfinder.minecraft.MinecraftPathingRules
import org.cobalt.internal.etherwarp.EtherwarpLogic
import org.cobalt.internal.pathfinding.OverlayRenderEngine
import org.cobalt.api.pathfinder.jni.NativePathfinder
import org.cobalt.api.pathfinder.jni.PathStatus
import org.cobalt.api.util.player.MovementManager
import org.cobalt.internal.combat.CombatMacroModule
import org.cobalt.internal.pathfinding.PathfindingModule
import org.cobalt.internal.rotation.RotationsModule
import org.cobalt.internal.routes.RoutePickerSetting
import org.cobalt.internal.routes.RouteType
import org.cobalt.internal.ui.panel.panels.UIModuleList

object MiningMacroModule : Module("Mining Macro") {

  private val mc: Minecraft = Minecraft.getInstance()
  private const val OVERLAY_TAG = "mining-macro-targets"

  // Frame-based rotation - target set in onTick, applied every render frame via onFrame.
  private var frameRotTarget:     Vec3?  = null
  private var frameRotSpeedScale: Float  = 1f
  private var frameRotAccelScale: Float  = 1f
  private var frameRotPitchStep:  Float  = 3.5f
  private var frameRotMaxSpeed:   Float  = 100f
  private var frameRotMaxAccel:   Float  = 220f
  private var frameRotSnapThreshold: Float = 0f

  private val enabled = CheckboxSetting(
    "Enabled",
    "Automatically scan, move to, and mine veins.",
    false
  )

  private val toggleKeybind = KeyBindSetting(
    "Toggle Keybind",
    "Key to start/stop the mining macro.",
    KeyBind(-1)
  )

  private val info = InfoSetting(
    "Macro",
    "Scans for the closest vein and mines it until empty.",
    InfoType.INFO
  )

  private val useMiningModuleType = CheckboxSetting(
    "Use Mining Type",
    "Use the Mining module block type selection.",
    true
  )

  internal val blockTypes = TextSetting(
    "Block Types",
    "Active types (edit via + button).",
    "None"
  )

  private val blockTypesPicker = ActionSetting(
    "Pick Types",
    "Open multi-select block type picker.",
    "+"
  ) { BlockTypePickerPopup.open() }

  private val useDetectedBlock = CheckboxSetting(
    "Use Detected Block",
    "When type is Custom, use Mining module detected block id.",
    true
  )

  internal val scanRadius = SliderSetting(
    "Scan Radius",
    "Horizontal scan radius for veins.",
    32.0,
    8.0,
    96.0
  )

  internal val scanVertical = SliderSetting(
    "Scan Vertical",
    "Vertical scan range above/below you.",
    8.0,
    2.0,
    40.0
  )

  internal val scanPerTick = SliderSetting(
    "Scan Per Tick",
    "Blocks scanned per tick when searching.",
    400.0,
    50.0,
    2000.0
  )

  internal val maxVeinBlocks = SliderSetting(
    "Max Vein Blocks",
    "Maximum blocks collected per vein.",
    256.0,
    32.0,
    512.0
  )

  private val mineRange = SliderSetting(
    "Mine Range",
    "Distance to mine blocks.",
    4.5,
    2.0,
    6.0
  )

  private val stepToNearbyBlocks = CheckboxSetting(
    "Step To Nearby Blocks",
    "Walk a short distance to reach blocks that are just outside mining range while staying near the vein start.",
    true
  )

  private val anchorRadius = SliderSetting(
    "Anchor Radius",
    "Maximum distance from the vein start position before returning to anchor. Disabled when running via Routes.",
    14.0,
    4.0,
    48.0
  )

  private val goldenGoblinInterrupt = CheckboxSetting(
    "Golden Goblin Interrupt",
    "Pause mining and kill Golden Goblins as soon as the spawn message appears, then resume mining.",
    true
  )

  private val precisionPointRotationSpeed = SliderSetting(
    "Precision Point Rotation Speed %",
    "Rotation speed multiplier used when aiming at a precision point.",
    100.0,
    10.0,
    200.0,
    1.0
  )

  private val precisionPointChance = SliderSetting(
    "Chance to Look at Precision Point",
    "Percent chance to aim at a block's precision point while mining or tick gliding.",
    100.0,
    0.0,
    100.0,
    1.0
  )

  val tickGliding = CheckboxSetting(
    "Tick Gliding",
    "Start rotating to the next block during the ping-delay window.",
    false
  )

  private val etherwarpHeader = InfoSetting(
    "Etherwarp Settings",
    "Warp movement and aiming options.",
    InfoType.INFO
  )

  private val useInstantTransmission = CheckboxSetting(
    "Instant Transmission",
    "Use Etherwarp (Shift + Right Click) when possible.",
    true
  )

  private val warpMinDistance = SliderSetting(
    "Warp Min Dist",
    "Only warp if target is farther than this.",
    10.0,
    0.0,
    30.0
  )

  private val warpAimTolerance = SliderSetting(
    "Warp Aim Tol",
    "Yaw/pitch error before warping.",
    6.0,
    2.0,
    15.0
  )

  private val warpCooldownTicks = SliderSetting(
    "Warp Cooldown",
    "Ticks to wait between warp attempts.",
    24.0,
    6.0,
    80.0
  )

  private val usePathfinding = CheckboxSetting(
    "Use Pathfinding",
    "Walk to the target when warping is not possible.",
    true
  )

  val useVeinDirection = CheckboxSetting(
    "Vein Direction",
    "Use Vein Direction Setter coordinate pairs to control mining flow.",
    false
  )

  private val occupiedRadius = SliderSetting(
    "Occupied Radius",
    "Radius around a vein considered occupied.",
    6.0,
    1.0,
    10.0
  )

  val oreMinerRoutePicker = RoutePickerSetting(
    "Ore Miner Route",
    "ORE_MINER route to follow during the mining macro.",
    RouteType.ORE_MINER,
    "mining:ore",
  )

  init {
    MiningPrecisionTracker.ensureInitialized()
    MiningProfitTracker.ensureInitialized()

    addSetting(
      enabled,
      toggleKeybind,
      info,
      oreMinerRoutePicker,
      useMiningModuleType,
      blockTypes,
      blockTypesPicker,
      useDetectedBlock,
      scanRadius,
      scanVertical,
      scanPerTick,
      maxVeinBlocks,
      mineRange,
      stepToNearbyBlocks,
      anchorRadius,
      goldenGoblinInterrupt,
      precisionPointRotationSpeed,
      precisionPointChance,
      tickGliding,
      etherwarpHeader,
      useInstantTransmission,
      warpMinDistance,
      warpAimTolerance,
      warpCooldownTicks,
      usePathfinding,
      useVeinDirection,
      occupiedRadius,
    )

    val side = UIModuleList.SIDE_GROUP
    useMiningModuleType.uiGroup = side
    blockTypes.uiGroup          = side
    useDetectedBlock.uiGroup    = side
    scanRadius.uiGroup          = side
    scanVertical.uiGroup        = side
    scanPerTick.uiGroup         = side
    maxVeinBlocks.uiGroup       = side

    EventBus.register(this)
    EventBus.register(BlockTypePickerPopup)
  }

  private data class Offset(val dx: Int, val dy: Int, val dz: Int, val distSq: Int)

  private data class TypeSelection(
    val label: String,
    val ids: Set<String>,
  )

  private data class Vein(
    val blocks: MutableSet<BlockPos>,
    val typeLabel: String,
    val targetIds: Set<String>,
    val blockId: String,
    val bounds: AABB
  )

  private data class AimTarget(
    val point: Vec3,
    val usesPrecisionPoint: Boolean,
  )

  private var scanOffsets: List<Offset> = emptyList()
  private var scanIndex = 0
  private var scanOrigin: BlockPos = BlockPos.ZERO
  private var scanActive = false
  private var scanRadiusCached = 0
  private var scanVerticalCached = 0
  private var scanPriorityIndex = 0

  private var currentVein: Vein? = null
  private var veinStartAnchor: BlockPos? = null
  private var automationScanAnchor: BlockPos? = null
  private var automationCustomBlockIds: Set<String> = emptySet()
  private var currentTarget: BlockPos? = null
  private var currentTargetNoLosTicks = 0
  private var currentDirectionalFlow: VeinDirectionModule.VeinFlow? = null
  private var lastPathTarget: BlockPos? = null
  private var lastWarnTick = 0L

  private var warpStage = 0
  private var warpTarget: BlockPos? = null
  private var warpStageTicks = 0
  private var warpCooldownUntil = 0L
  private var warpRestoreSlot = -1

  private var miningActive = false
  private var miningOnTargetTicks = 0
  private var miningLockedTicks = 0
  private var miningOnTarget: BlockPos? = null
  private var cachedPreviewTarget: BlockPos? = null
  private var previewCacheTick = -1L
  private var lastPruneTick = 0L
  private var miningUsesPrecisionPoint = false
  private val precisionRolls = HashMap<Long, Boolean>()
  private var precisionPointChanceSnapshot = precisionPointChance.value
  private var startedPath = false
  private var lastPathStartTick = 0L
  private var approachTarget: BlockPos? = null
  private var approachStartTick = 0L
  private var approachStartDistance = 0.0
  private var wasEnabled = false
  private var lanternPlacedForVein = false
  private var lastLanternPlaceTick = 0L
  private var lastLanternRefreshTick = -1L
  private var goldenGoblinInterruptActive = false
  private var goldenGoblinInterruptOwnedCombat = false
  private var goldenGoblinLastSeenTick = -1L
  private var goldenGoblinReturnPending = false
  private var goldenGoblinReturnPos: Vec3? = null

  val isActive: Boolean get() = enabled.value

  fun startForAutomation(
    blockTypeNames: String,
    anchor: BlockPos? = null,
    customBlockId: String? = null,
    customBlockIds: Set<String> = emptySet(),
  ) {
    blockTypes.value = blockTypeNames
    automationScanAnchor = anchor?.immutable()
    val combined = linkedSetOf<String>()
    customBlockId
      ?.trim()
      ?.takeIf { it.isNotEmpty() && !MiningBlockRegistry.isBlacklisted(it) }
      ?.let { combined.add(it) }
    for (id in customBlockIds) {
      val trimmed = id.trim()
      if (trimmed.isNotEmpty() && !MiningBlockRegistry.isBlacklisted(trimmed)) combined.add(trimmed)
    }
    automationCustomBlockIds = combined
    val firstName = blockTypeNames.split(",").firstOrNull()?.trim().orEmpty()
    val idx = MiningBlockRegistry.BLOCK_TYPES.indexOf(firstName)
    if (idx >= 0) MiningModule.blockType.value = idx
    enabled.value = true
  }

  fun stopForAutomation() {
    automationScanAnchor = null
    automationCustomBlockIds = emptySet()
    enabled.value = false
  }

  fun hasCurrentVein(): Boolean = enabled.value && currentVein != null

  fun getSelectedTypesInOrder(): List<String> = parseSelectedTypes(blockTypes.value)

  fun getTrackedTargetBlock(): BlockPos? = if (enabled.value) miningOnTarget ?: currentTarget else null

  fun getTrackedTargetTicks(useGliding: Boolean = false): Int =
    if (enabled.value && miningOnTarget != null) {
      if (useGliding) miningOnTargetTicks else miningLockedTicks
    } else {
      0
    }

  fun getPrecisionTargetBlock(): BlockPos? = if (enabled.value) miningOnTarget ?: currentTarget else null

  fun isUsingPrecisionPoint(): Boolean =
    enabled.value && miningOnTarget != null && miningUsesPrecisionPoint

  private val skippedSeeds = HashSet<Long>()
  private const val FLOW_MATCH_MAX_DIST_SQ = 24.0 * 24.0
  private const val DIRECTIONAL_STEP_MAX_DIST_SQ = 64.0
  private const val DIRECTIONAL_MIN_PROJECTION = 0.05
  private const val DIRECTIONAL_MAX_LATERAL_SQ = 25.0
  private const val TARGET_LOS_GRACE_TICKS = 8
  private const val TARGET_STICKY_RANGE_EXTRA = 1.4
  private const val APPROACH_SCAN_RADIUS = 3
  private const val APPROACH_SCAN_VERTICAL = 2
  private const val APPROACH_TIMEOUT_TICKS = 18L
  private const val APPROACH_MIN_PROGRESS_BLOCKS = 1.0
  private const val APPROACH_HOLD_DISTANCE = 4.0
  private const val APPROACH_EDGE_MARGIN = 0.15
  private const val APPROACH_EDGE_WINDOW = 0.35
  private const val WARP_ALIGN_TICKS = 20
  private const val WARP_SNEAK_TICKS = 2
  private const val WARP_POST_TICKS = 6
  private const val LANTERN_PLACE_COOLDOWN_TICKS = 12L
  private const val LANTERN_REFRESH_TICKS = 20L * 60L
  private const val MIN_MINING_TICKS_PER_BLOCK = 40
  private const val MINING_TIMEOUT_SCALE = 1.6
  private const val MINING_TIMEOUT_EXTRA_TICKS = 8
  private const val FALLBACK_TIMEOUT_MINING_SPEED = 1000.0
  private const val WILL_O_WISP_NAME = "will o wisp"
  private const val REQUIRE_MINE_LOS = true
  private const val SKIP_OCCUPIED_VEINS = true
  private const val NUDGE_RANGE_EXTRA = 2.5   // blocks beyond mineRange to attempt a gentle nudge
  private const val MAX_WALK_BLOCKS = 5.0      // never walk more than this many blocks to reach a target
  private const val NEARBY_STEP_RANGE_EXTRA = 0.85
  private const val NEARBY_STEP_REACH_BUFFER = 0.65
  private const val BLOCKING_PLAYER_MIN_CLEARANCE = 1.35
  private const val BLOCKING_PLAYER_BOX_PADDING = 0.18
  private const val BLOCKING_PLAYER_PENALTY = 9.0
  private const val BLOCKING_PLAYER_REACT_RANGE = 3.25
  private const val BLOCKING_PLAYER_SIDESTEP_DISTANCE = 1.1
  private const val BLOCKING_PLAYER_SIDESTEP_FORWARD = 0.4
  private const val RETURN_TO_ANCHOR_ARRIVAL_DIST = 1.25
  private const val RETURN_TO_ANCHOR_SCAN_RADIUS = 2
  private const val RETURN_TO_ANCHOR_SCAN_VERTICAL = 2
  private const val GOLDEN_GOBLIN_NAME = "golden goblin"
  private const val GOLDEN_GOBLIN_LOST_TICKS = 20L

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
    if (toggleKeybind.value.isPressed()) {
      enabled.value = !enabled.value
    }

    if (!enabled.value) {
      if (wasEnabled) {
        stopMacro("Disabled.")
        wasEnabled = false
      }
      return
    }

    val player = mc.player ?: return
    val level = mc.level ?: return

    // First tick after enable - try to seed the vein immediately from the player's position.
    if (!wasEnabled) {
      wasEnabled = true
      MiningProfitTracker.resetSession()
      val selections = resolveTypeSelections()
      if (selections.isEmpty()) {
        ChatUtils.sendMessage("Mining macro: no block types selected, cannot start.")
        enabled.value = false
        wasEnabled = false
        return
      }
      // Attempt an immediate synchronous vein scan. If it succeeds the vein is ready on the
      // very next tick. If it fails (wrong type selected or no ore immediately nearby) just
      // allow the normal progressive scan to run - don't block the user from starting.
      if (!immediateStartScan(level, player, selections)) {
        ChatUtils.sendMessage("Mining macro: scanning for vein...")
      }
      return
    }

    if (AutoLanternModule.isPlacementInProgress()) {
      stopMiningKeys()
      if (startedPath && nativeActive()) {
        nativeStop()
      }
      startedPath = false
      lastPathTarget = null
      resetApproachTracking()
      MovementManager.clearForcedMovement()
      return
    }

    if (usePathfinding.value) {
      PathfindingModule.ensureEnabledForAutomation("mining macro")
    }

    if (!goldenGoblinInterruptActive && !goldenGoblinReturnPending) {
      if (startedPath && !nativeActive()) {
        startedPath = false
        lastPathTarget = null
        resetApproachTracking()
        MovementManager.clearForcedMovement()
      }
      if (startedPath && nativeActive()) {
        val cmd = NativePathfinder.tick()
        if (cmd != null) cmd.applyToPlayer()
        else MovementManager.clearForcedMovement()
      }
    }

    if (mc.screen != null) {
      stopMiningKeys()
      RotationExecutor.stopRotating()
      return
    }

    if (warpTarget != null) {
      handleWarp(player, level)
      return
    }

    if (goldenGoblinReturnPending) {
      handleGoldenGoblinReturn(level, player)
      return
    }

    if (goldenGoblinInterruptActive) {
      handleGoldenGoblinInterrupt(level, player)
      return
    }

    val selections = resolveTypeSelections()
    if (selections.isEmpty()) {
      resetScanState()
      warnOnce("No target block ids for selected type.")
      stopMiningKeys()
      return
    }

    val vein = currentVein
    if (vein == null) {
      currentVein = null
      veinStartAnchor = null
      currentTarget = null
      currentTargetNoLosTicks = 0
      currentDirectionalFlow = null
      resetApproachTracking()
      lanternPlacedForVein = false
      lastLanternRefreshTick = -1L
      startOrContinueScan(level, player, selections)
      return
    }

    val gameTime = level.gameTime
    if (gameTime - lastPruneTick >= 20L) {
      pruneVein(level, player, vein)
      lastPruneTick = gameTime
    }
    sanitizeActiveTargets(level, player, vein)
    if (vein.blocks.isEmpty()) {
      currentVein = null
      veinStartAnchor = null
      currentTarget = null
      currentTargetNoLosTicks = 0
      currentDirectionalFlow = null
      resetApproachTracking()
      resetScanState()
      lanternPlacedForVein = false
      lastLanternRefreshTick = -1L
      return
    }

    if (hasDriftedFromVeinStart(player)) {
      stopMiningKeys()
      RotationExecutor.stopRotating()
      currentTarget = null
      currentTargetNoLosTicks = 0
      returnToVeinAnchor(level, player)
      return
    }

    val target = selectMineTarget(level, player, vein)
    if (target == null) {
      currentTarget = null
      currentTargetNoLosTicks = 0
      val nearest = selectNearestBlock(level, player, vein.blocks, vein.targetIds)
      if (nearest != null) {
        val nearestDistSq = distanceToBlockSq(player, nearest)
        val shortStepLimit = maxNearbyStepDistance()
        val shortStepAllowed =
          canStepToNearbyTarget(player, nearest) && nearestDistSq <= shortStepLimit * shortStepLimit
        if (shortStepAllowed) {
          val blockingPlayer = findBlockingPlayerForTarget(level, player, nearest)
          if (useInstantTransmission.value && tryStartWarp(player, level, nearest)) {
            return
          }
          if (blockingPlayer != null && trySidestepAroundBlockingPlayer(level, player, nearest, blockingPlayer)) {
            return
          }
          when {
            blockingPlayer != null && usePathfinding.value -> {
              moveToward(
                level,
                player,
                nearest,
                forceApproach = true,
                avoidPlayer = blockingPlayer,
                maxTravelDistance = shortStepLimit,
              )
            }
            blockingPlayer != null -> {
              nudgeTowardApproach(level, player, nearest, blockingPlayer)
              focusApproachTarget(player, nearest)
            }
            stepToNearbyBlocks.value -> {
              if (usePathfinding.value) {
                moveToward(level, player, nearest, maxTravelDistance = shortStepLimit)
              } else {
                nudgeTowardApproach(level, player, nearest)
                focusApproachTarget(player, nearest)
              }
            }
            usePathfinding.value -> {
              moveToward(level, player, nearest)
            }
            else -> {
              MovementManager.clearForcedMovement()
            }
          }
        } else {
          if (usePathfinding.value && canStepToNearbyTarget(player, nearest)) {
            moveToward(level, player, nearest)
          } else {
            MovementManager.clearForcedMovement()
          }
        }
      }
      stopMiningKeys()
      RotationExecutor.stopRotating()
      return
    }

    currentTarget = target
    val distSq = distanceToBlockSq(player, target)
    val inRange = distSq <= mineRange.value * mineRange.value

    if (inRange) {
      // Crosshair-first mining: mine whatever valid vein block the crosshair is on.
      // This prevents dig packets firing at bedrock while the camera is still rotating.
      val hitPos = (mc.hitResult as? BlockHitResult)
        ?.takeIf { it.type == HitResult.Type.BLOCK }?.blockPos
      val crosshairVeinBlock = hitPos?.takeIf { pos ->
        vein.blocks.contains(pos) &&
          isMineableTarget(level, player, pos, vein.targetIds) &&
          distanceToBlockSq(player, pos) <= mineRange.value * mineRange.value
      }

      if (crosshairVeinBlock != null) {
        currentTarget = crosshairVeinBlock
        if (maybeRefreshLantern(level, player)) {
          stopMiningKeys()
          if (startedPath && nativeActive()) {
            nativeStop()
          }
          startedPath = false
          lastPathTarget = null
          resetApproachTracking()
          MovementManager.clearForcedMovement()
          return
        }
        if (startedPath && nativeActive()) {
          nativeStop()
        }
        startedPath = false
        lastPathTarget = null
        resetApproachTracking()
        MovementManager.clearForcedMovement()
        startMining(player, crosshairVeinBlock)
      } else {
        // Not on a vein block yet — release attack and keep rotating toward selected target.
        if (miningActive) {
          setMiningAttackDown(false)
          miningActive = false
        }
        val blockingPlayer = findBlockingPlayerForTarget(level, player, target)
        if (blockingPlayer != null) {
          if (trySidestepAroundBlockingPlayer(level, player, target, blockingPlayer)) {
            return
          }
          if (usePathfinding.value) {
            moveToward(level, player, target, forceApproach = true, avoidPlayer = blockingPlayer)
          } else {
            nudgeTowardApproach(level, player, target, blockingPlayer)
            focusApproachTarget(player, target)
          }
          return
        }
        val aim = resolveMiningAimPoint(player, target)
        val precisionRotScale =
          if (aim.usesPrecisionPoint) (precisionPointRotationSpeed.value / 100.0).coerceAtLeast(0.1)
          else 1.0
        frameRotSnapThreshold = RotationsModule.bezierSnapThreshold.value.toFloat()
        frameRotTarget     = aim.point
        frameRotSpeedScale = (RotationsModule.sample(RotationsModule.miningSpeedScale.value) * precisionRotScale).toFloat()
        frameRotAccelScale = (RotationsModule.sample(RotationsModule.miningAccelScale.value) * precisionRotScale).toFloat()
        frameRotPitchStep  = (RotationsModule.sample(RotationsModule.miningPitchStep.value) * precisionRotScale).toFloat()
        frameRotMaxSpeed   = (RotationsModule.sample(RotationsModule.miningMaxSpeed.value) * precisionRotScale).toFloat()
        frameRotMaxAccel   = (RotationsModule.sample(RotationsModule.miningMaxAccel.value) * precisionRotScale).toFloat()
      }
    } else {
      stopMiningKeys()
      RotationExecutor.stopRotating()
      if (useInstantTransmission.value && tryStartWarp(player, level, target)) {
        return
      }
      val blockingPlayer = findBlockingPlayerForTarget(level, player, target)
      if (blockingPlayer != null && trySidestepAroundBlockingPlayer(level, player, target, blockingPlayer)) {
        return
      }
      val nudgeThresh = mineRange.value + NUDGE_RANGE_EXTRA
      val shortStepLimit = maxNearbyStepDistance()
      val canShortStep =
        stepToNearbyBlocks.value &&
          canStepToNearbyTarget(player, target) &&
          distSq <= nudgeThresh * nudgeThresh
        when {
          blockingPlayer != null && usePathfinding.value && canShortStep -> {
            moveToward(
              level,
              player,
            target,
            forceApproach = true,
            avoidPlayer = blockingPlayer,
            maxTravelDistance = shortStepLimit,
          )
        }
          blockingPlayer != null && usePathfinding.value -> {
            moveToward(level, player, target, forceApproach = true, avoidPlayer = blockingPlayer)
          }
          canShortStep && usePathfinding.value -> {
            moveToward(level, player, target, maxTravelDistance = shortStepLimit)
          }
          canShortStep -> {
            nudgeTowardApproach(level, player, target, blockingPlayer)
            focusApproachTarget(player, target)
          }
        usePathfinding.value -> {
          moveToward(level, player, target, avoidPlayer = blockingPlayer)
        }
        else -> {
          MovementManager.clearForcedMovement()
        }
      }
    }
  }

  @SubscribeEvent
  fun onChat(event: ChatEvent.Receive) {
    val raw = event.message ?: return
    val message = ChatFormatting.stripFormatting(raw)?.lowercase() ?: raw.lowercase()

    if (enabled.value && goldenGoblinInterrupt.value && message.contains("a golden goblin has spawned")) {
      beginGoldenGoblinInterrupt()
      mc.level?.let { level -> goldenGoblinLastSeenTick = level.gameTime }
      return
    }

    if (goldenGoblinInterruptActive) {
      if (message.contains("from killing a golden goblin") || message.contains("golden goblin slayer commission complete")) {
        finishGoldenGoblinInterrupt()
      }
    }
  }

  @SubscribeEvent
  fun onBlockChange(event: BlockChangeEvent) {
    val vein = currentVein ?: return
    val newId = BuiltInRegistries.BLOCK.getKey(event.newBlock.block).toString()
    val level = mc.level
    val player = mc.player
    val stillTarget =
      vein.targetIds.contains(newId)
        && !MiningBlockRegistry.isBlacklisted(newId)
        && !event.newBlock.isAir
        && (level == null || player == null || event.newBlock.getDestroyProgress(player, level, event.pos) > 0f)

    if (!stillTarget) {
      vein.blocks.remove(event.pos)
      precisionRolls.remove(event.pos.asLong())
      if (currentTarget == event.pos) {
        currentTarget = null
        currentTargetNoLosTicks = 0
      }
      if (approachTarget == event.pos || lastPathTarget == event.pos) {
        stopApproachMovement()
      }
      if (miningOnTarget == event.pos) {
        stopMiningKeys()
      }
    } else if (!vein.blocks.contains(event.pos)) {
      // Block refreshed/respawned back to a target ore - re-add it to the active vein.
      vein.blocks.add(event.pos)
    }
  }

  private fun resolveTypeSelections(): List<TypeSelection> {
    val result = mutableListOf<TypeSelection>()
    for (rawLabel in getSelectedTypesInOrder()) {
      val label = MiningBlockRegistry.normalizeType(rawLabel)
      val baseIds =
        if (label.equals("Custom", ignoreCase = true)) {
          val automationIds = automationCustomBlockIds
            .asSequence()
            .filter { it.isNotEmpty() && !MiningBlockRegistry.isBlacklisted(it) }
            .toCollection(linkedSetOf())
          if (automationIds.isNotEmpty()) {
            automationIds
          } else {
            val detected = MiningModule.getDetectedBlockId()?.trim().orEmpty()
            if (useDetectedBlock.value && detected.isNotEmpty() && !MiningBlockRegistry.isBlacklisted(detected)) {
              linkedSetOf(detected)
            } else {
              emptySet()
            }
          }
        } else {
          val idsForLabel = MiningBlockRegistry.idsForType(label).toMutableSet()
          // Fold any automation-detected IDs whose type matches this label so the vein
          // flood-fill includes every nearby ore variant even when they aren't in the
          // static registry for this label (e.g. deepslate variants, mixed veins).
          for (id in automationCustomBlockIds) {
            if (id.isEmpty() || MiningBlockRegistry.isBlacklisted(id)) continue
            val mappedType = MiningBlockRegistry.BLOCK_ID_TO_TYPE[id] ?: continue
            if (MiningBlockRegistry.normalizeType(mappedType).equals(label, ignoreCase = true)) {
              idsForLabel.add(id)
            }
          }
          idsForLabel
        }
      val ids = expandSelectionIds(label, baseIds)
      if (ids.isNotEmpty()) {
        result.add(TypeSelection(label, ids))
      }
    }
    return result
  }

  private fun expandSelectionIds(label: String, ids: Set<String>): Set<String> {
    if (ids.isEmpty()) return ids
    if (!label.startsWith("Mithril")) return ids

    val expanded = LinkedHashSet(ids)
    expanded.addAll(MiningBlockRegistry.idsForType("Titanium"))
    return expanded
  }

  private fun parseSelectedTypes(raw: String): List<String> {
    val result = LinkedHashSet<String>()
    for (part in raw.split(",")) {
      val label = part.trim()
      if (label.isEmpty() || label.equals("none", ignoreCase = true)) continue
      val normalized = MiningBlockRegistry.normalizeType(label)
      if (MiningBlockRegistry.BLOCK_HARDNESS.containsKey(normalized)) {
        result.add(normalized)
      }
    }
    return result.toList()
  }

  private fun immediateStartScan(
    level: net.minecraft.world.level.Level,
    player: Player,
    selections: List<TypeSelection>
  ): Boolean {
    val merged = mergeSelections(selections)
    val radius = scanRadius.value.toInt()
    val vertical = scanVertical.value.toInt()
    val offsets = buildOffsets(radius, vertical)
    val origin = automationScanAnchor ?: player.blockPosition()
    for (off in offsets) {
      val pos = origin.offset(off.dx, off.dy, off.dz)
      if (skippedSeeds.contains(pos.asLong())) continue
      if (!isMineableTarget(level, player, pos, merged.ids)) continue
      val vein = buildVein(level, player, pos, merged, maxVeinBlocks.value.toInt())
      if (SKIP_OCCUPIED_VEINS && isVeinOccupied(level, vein, player)) {
        skippedSeeds.add(pos.asLong())
        continue
      }
      currentVein = vein
      veinStartAnchor = origin.immutable()
      currentTargetNoLosTicks = 0
      currentDirectionalFlow = null
      lanternPlacedForVein = false
      lastLanternRefreshTick = -1L
      scanActive = false
      scanPriorityIndex = 0
      ChatUtils.sendMessage("Mining macro: found ${vein.typeLabel} vein with ${vein.blocks.size} blocks.")
      return true
    }
    return false
  }

  private fun startOrContinueScan(
    level: net.minecraft.world.level.Level,
    player: Player,
    selections: List<TypeSelection>
  ) {
    val desiredOrigin = automationScanAnchor ?: player.blockPosition()
    if (!scanActive || desiredOrigin.distSqr(scanOrigin) > 0.0) {
      scanOrigin = desiredOrigin
      scanIndex = 0
      scanActive = true
      scanPriorityIndex = 0
    }

    val radius = scanRadius.value.toInt()
    val vertical = scanVertical.value.toInt()
    if (scanOffsets.isEmpty() || scanRadiusCached != radius || scanVerticalCached != vertical) {
      scanOffsets = buildOffsets(radius, vertical)
      scanRadiusCached = radius
      scanVerticalCached = vertical
      scanIndex = 0
    }

    // Merge all selected types into one combined selection so the vein flood-fill
    // crosses type boundaries. This ensures all selected ore types are mined in one pass.
    val merged = mergeSelections(selections)
    val perTick = scanPerTick.value.toInt().coerceAtLeast(1)
    var processed = 0
    while (scanIndex < scanOffsets.size && processed < perTick) {
      val off = scanOffsets[scanIndex++]
      processed++
      val pos = scanOrigin.offset(off.dx, off.dy, off.dz)
      if (skippedSeeds.contains(pos.asLong())) continue
      if (!isMineableTarget(level, player, pos, merged.ids)) continue

      val vein = buildVein(level, player, pos, merged, maxVeinBlocks.value.toInt())
      if (SKIP_OCCUPIED_VEINS && isVeinOccupied(level, vein, player)) {
        skippedSeeds.add(pos.asLong())
        continue
      }
      currentVein = vein
      veinStartAnchor = player.blockPosition().immutable()
      currentTargetNoLosTicks = 0
      currentDirectionalFlow = null
      lanternPlacedForVein = false
      lastLanternRefreshTick = -1L
      scanActive = false
      scanPriorityIndex = 0
      ChatUtils.sendMessage("Mining macro: found ${vein.typeLabel} vein with ${vein.blocks.size} blocks.")
      return
    }

    if (scanIndex >= scanOffsets.size) {
      resetScanState()
      warnOnce("No veins found in scan radius.")
    }
  }

  private fun resetScanState() {
    scanActive = false
    scanIndex = 0
    scanPriorityIndex = 0
  }

  private fun mergeSelections(selections: List<TypeSelection>): TypeSelection {
    if (selections.size == 1) return selections[0]
    val ids = selections.flatMapTo(linkedSetOf()) { it.ids }
    val label = selections.joinToString(", ") { it.label }
    return TypeSelection(label, ids)
  }

  private fun buildOffsets(radius: Int, vertical: Int): List<Offset> {
    val list = ArrayList<Offset>()
    val rSq = radius * radius
    for (dy in -vertical..vertical) {
      for (dx in -radius..radius) {
        for (dz in -radius..radius) {
          val horizSq = dx * dx + dz * dz
          if (horizSq > rSq) continue
          val distSq = horizSq + dy * dy
          list.add(Offset(dx, dy, dz, distSq))
        }
      }
    }
    list.sortWith(compareBy({ it.distSq }, { it.dy }))
    return list
  }

  private fun buildVein(
    level: net.minecraft.world.level.Level,
    player: Player,
    seed: BlockPos,
    selection: TypeSelection,
    maxBlocks: Int
  ): Vein {
    val seedBlockId = blockIdAt(level, seed)
    val blocks = LinkedHashSet<BlockPos>()
    val queue = ArrayDeque<BlockPos>()
    queue.add(seed)
    blocks.add(seed)

    var minX = seed.x
    var minY = seed.y
    var minZ = seed.z
    var maxX = seed.x
    var maxY = seed.y
    var maxZ = seed.z

    while (queue.isNotEmpty() && blocks.size < maxBlocks) {
      val pos = queue.removeFirst()
      for (dir in Direction.values()) {
        val next = pos.relative(dir)
        if (blocks.contains(next)) continue
        if (!isMineableTarget(level, player, next, selection.ids)) continue
        blocks.add(next)
        queue.add(next)
        if (next.x < minX) minX = next.x
        if (next.y < minY) minY = next.y
        if (next.z < minZ) minZ = next.z
        if (next.x > maxX) maxX = next.x
        if (next.y > maxY) maxY = next.y
        if (next.z > maxZ) maxZ = next.z
        if (blocks.size >= maxBlocks) break
      }
    }

    val bounds = AABB(
      minX.toDouble(),
      minY.toDouble(),
      minZ.toDouble(),
      maxX + 1.0,
      maxY + 1.0,
      maxZ + 1.0
    )
    return Vein(blocks, selection.label, selection.ids, seedBlockId, bounds)
  }

  private fun pruneVein(
    level: net.minecraft.world.level.Level,
    player: Player,
    vein: Vein
  ) {
    val iterator = vein.blocks.iterator()
    while (iterator.hasNext()) {
      val pos = iterator.next()
      if (!isMineableTarget(level, player, pos, vein.targetIds)) {
        iterator.remove()
      }
    }
  }

  private fun sanitizeActiveTargets(
    level: net.minecraft.world.level.Level,
    player: Player,
    vein: Vein
  ) {
    val activeMining = miningOnTarget
    if (activeMining != null && !isMineableTarget(level, player, activeMining, vein.targetIds)) {
      vein.blocks.remove(activeMining)
      precisionRolls.remove(activeMining.asLong())
      stopMiningKeys()
    }

    val activeTarget = currentTarget
    if (activeTarget != null && !vein.blocks.contains(activeTarget)) {
      precisionRolls.remove(activeTarget.asLong())
      currentTarget = null
      currentTargetNoLosTicks = 0
      if (approachTarget == activeTarget) {
        stopApproachMovement()
      }
    }
  }

  private fun selectMineTarget(
    level: net.minecraft.world.level.Level,
    player: Player,
    vein: Vein
  ): BlockPos? {
    // Priority: in-range titanium blocks beat everything else (including sticky non-titanium targets).
    // This ensures titanium that appears next to a mithril vein is mined immediately.
    val titaniumIds = MiningBlockRegistry.idsForType("Titanium")
    if (titaniumIds.any { it in vein.targetIds }) {
      val rangeSq = mineRange.value * mineRange.value
      val eye = player.eyePosition
      var bestTi: BlockPos? = null
      var bestTiAngle = Float.POSITIVE_INFINITY
      for (pos in vein.blocks) {
        if (!isMineableTarget(level, player, pos, vein.targetIds)) continue
        if (blockIdAt(level, pos) !in titaniumIds) continue
        if (distanceToBlockSq(player, pos) > rangeSq) continue
        val visiblePoint = if (REQUIRE_MINE_LOS) findVisibleAimPoint(level, player, eye, pos) else null
        if (REQUIRE_MINE_LOS && visiblePoint == null) continue
        val aimPoint = visiblePoint ?: Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        val ang = angularDistanceTo(player, aimPoint)
        if (ang < bestTiAngle) { bestTiAngle = ang; bestTi = pos }
      }
      if (bestTi != null) return bestTi
    }

    val sticky = currentTarget?.takeIf { vein.blocks.contains(it) && isMineableTarget(level, player, it, vein.targetIds) }
    if (sticky != null) {
      val distSq = distanceToBlockSq(player, sticky)
      val inAttackRange = distSq <= mineRange.value * mineRange.value

      // Only keep sticky when the block is already in attack range (stable mid-mine targeting).
      // When out of range, fall through and re-select the nearest block every tick so the
      // macro always approaches the closest available block first.
      if (inAttackRange) {
        val hasLos = !REQUIRE_MINE_LOS || hasLineOfSight(level, player, sticky)
        if (hasLos) {
          currentTargetNoLosTicks = 0
          return sticky
        }

        val stickyRange = mineRange.value + TARGET_STICKY_RANGE_EXTRA
        if (distSq <= stickyRange * stickyRange && currentTargetNoLosTicks < TARGET_LOS_GRACE_TICKS) {
          currentTargetNoLosTicks++
          return sticky
        }
      }
    }
    currentTargetNoLosTicks = 0

    if (useVeinDirection.value) {
      val directional = selectDirectionalMineTarget(level, player, vein)
      if (directional != null) {
        return directional
      }
    }

    val rangeSq = mineRange.value * mineRange.value
    val eye = player.eyePosition

    // For in-range blocks, prefer the one whose visible face is closest to the
    // player's current aim. Using the actual face point (not block center) gives
    // the real rotation cost and avoids picking blocks that require more turn than
    // their center angle implies.
    var bestInRange: BlockPos? = null
    var bestAngle = Float.POSITIVE_INFINITY
    for (pos in vein.blocks) {
      if (!isMineableTarget(level, player, pos, vein.targetIds)) continue
      if (distanceToBlockSq(player, pos) > rangeSq) continue
      val visiblePoint: Vec3? = if (REQUIRE_MINE_LOS) findVisibleAimPoint(level, player, eye, pos) else null
      if (REQUIRE_MINE_LOS && visiblePoint == null) continue
      val aimPoint = visiblePoint ?: Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
      val ang = angularDistanceTo(player, aimPoint)
      if (ang < bestAngle) { bestAngle = ang; bestInRange = pos }
    }
    if (bestInRange != null) return bestInRange

    // No in-range block - find closest LOS block to walk/warp toward.
    var best: BlockPos? = null
    var bestDist = Double.POSITIVE_INFINITY
    for (pos in vein.blocks) {
      if (!isMineableTarget(level, player, pos, vein.targetIds)) continue
      if (REQUIRE_MINE_LOS && !hasLineOfSight(level, player, pos)) continue
      val distSq = distanceToBlockSq(player, pos)
      if (distSq < bestDist) { bestDist = distSq; best = pos }
    }
    // If no block has LOS, fall back only to in-range blocks where the crosshair is already
    // on the block. This lets us click without rotating (avoiding aiming through bedrock).
    if (best == null) {
      for (pos in vein.blocks) {
        if (!isMineableTarget(level, player, pos, vein.targetIds)) continue
        val distSq = distanceToBlockSq(player, pos)
        if (distSq > rangeSq) continue
        if (!isCrosshairOnTarget(pos)) continue
        if (distSq < bestDist) { bestDist = distSq; best = pos }
      }
    }
    return best
  }

  private fun selectDirectionalMineTarget(
    level: net.minecraft.world.level.Level,
    player: Player,
    vein: Vein
  ): BlockPos? {
    val flows = VeinDirectionModule.getFlowsForVein(vein.blockId)
    if (flows.isEmpty()) {
      return null
    }

    val flow = resolveDirectionalFlow(vein, flows) ?: return null
    val vecX = (flow.end.x - flow.start.x).toDouble()
    val vecY = (flow.end.y - flow.start.y).toDouble()
    val vecZ = (flow.end.z - flow.start.z).toDouble()
    val len = sqrt(vecX * vecX + vecY * vecY + vecZ * vecZ)
    if (len < 0.001) return null

    val nx = vecX / len
    val ny = vecY / len
    val nz = vecZ / len

    val activeAnchor = currentTarget?.takeIf { vein.blocks.contains(it) }
    val anchor = activeAnchor
      ?: selectClosestBlockToReference(level, player, vein.blocks, flow.start, vein.targetIds)
      ?: selectNearestBlock(level, player, vein.blocks, vein.targetIds)
      ?: return null

    if (activeAnchor == null) {
      return anchor
    }

    val forward = selectForwardDirectionalTarget(level, player, vein.blocks, anchor, nx, ny, nz, vein.targetIds)
    if (forward != null) {
      return forward
    }

    if (!REQUIRE_MINE_LOS || hasLineOfSight(level, player, anchor)) {
      return anchor
    }
    return selectNearestBlock(level, player, vein.blocks, vein.targetIds)
  }

  private fun resolveDirectionalFlow(
    vein: Vein,
    flows: List<VeinDirectionModule.VeinFlow>
  ): VeinDirectionModule.VeinFlow? {
    currentDirectionalFlow?.let { cached ->
      if (flows.contains(cached) && flowDistanceSqToVein(cached, vein) <= FLOW_MATCH_MAX_DIST_SQ) {
        return cached
      }
    }

    var best: VeinDirectionModule.VeinFlow? = null
    var bestDistSq = Double.POSITIVE_INFINITY
    for (flow in flows) {
      val distSq = flowDistanceSqToVein(flow, vein)
      if (distSq < bestDistSq) {
        bestDistSq = distSq
        best = flow
      }
    }

    if (best == null || bestDistSq > FLOW_MATCH_MAX_DIST_SQ) {
      currentDirectionalFlow = null
      return null
    }

    currentDirectionalFlow = best
    return best
  }

  private fun flowDistanceSqToVein(flow: VeinDirectionModule.VeinFlow, vein: Vein): Double {
    val startDistSq = pointDistanceSqToBounds(flow.start, vein.bounds)
    val endDistSq = pointDistanceSqToBounds(flow.end, vein.bounds)
    return minOf(startDistSq, endDistSq)
  }

  private fun pointDistanceSqToBounds(pos: BlockPos, bounds: AABB): Double {
    val px = pos.x + 0.5
    val py = pos.y + 0.5
    val pz = pos.z + 0.5

    val dx = when {
      px < bounds.minX -> bounds.minX - px
      px > bounds.maxX -> px - bounds.maxX
      else -> 0.0
    }
    val dy = when {
      py < bounds.minY -> bounds.minY - py
      py > bounds.maxY -> py - bounds.maxY
      else -> 0.0
    }
    val dz = when {
      pz < bounds.minZ -> bounds.minZ - pz
      pz > bounds.maxZ -> pz - bounds.maxZ
      else -> 0.0
    }

    return dx * dx + dy * dy + dz * dz
  }

  private fun selectClosestBlockToReference(
    level: net.minecraft.world.level.Level,
    player: Player,
    blocks: Set<BlockPos>,
    reference: BlockPos,
    allowedIds: Set<String>
  ): BlockPos? {
    var best: BlockPos? = null
    var bestDistSq = Double.POSITIVE_INFINITY
    for (pos in blocks) {
      if (!isMineableTarget(level, player, pos, allowedIds)) continue
      if (REQUIRE_MINE_LOS && !hasLineOfSight(level, player, pos)) continue
      val dx = (pos.x - reference.x).toDouble()
      val dy = (pos.y - reference.y).toDouble()
      val dz = (pos.z - reference.z).toDouble()
      val distSq = dx * dx + dy * dy + dz * dz
      if (distSq < bestDistSq) {
        bestDistSq = distSq
        best = pos
      }
    }
    return best
  }

  private fun selectForwardDirectionalTarget(
    level: net.minecraft.world.level.Level,
    player: Player,
    blocks: Set<BlockPos>,
    anchor: BlockPos,
    nx: Double,
    ny: Double,
    nz: Double,
    allowedIds: Set<String>
  ): BlockPos? {
    var best: BlockPos? = null
    var bestScore = Double.NEGATIVE_INFINITY
    for (pos in blocks) {
      if (pos == anchor) continue
      if (!isMineableTarget(level, player, pos, allowedIds)) continue
      if (REQUIRE_MINE_LOS && !hasLineOfSight(level, player, pos)) continue

      val relX = (pos.x - anchor.x).toDouble()
      val relY = (pos.y - anchor.y).toDouble()
      val relZ = (pos.z - anchor.z).toDouble()
      val distSq = relX * relX + relY * relY + relZ * relZ
      if (distSq <= 0.25 || distSq > DIRECTIONAL_STEP_MAX_DIST_SQ) continue

      val projection = relX * nx + relY * ny + relZ * nz
      if (projection <= DIRECTIONAL_MIN_PROJECTION) continue

      val lateralSq = (distSq - projection * projection).coerceAtLeast(0.0)
      if (lateralSq > DIRECTIONAL_MAX_LATERAL_SQ) continue

      val score = projection - sqrt(lateralSq) * 0.45 - sqrt(distSq) * 0.12
      if (score > bestScore) {
        bestScore = score
        best = pos
      }
    }
    return best
  }

  private fun selectNearestBlock(
    level: net.minecraft.world.level.Level,
    player: Player,
    blocks: Set<BlockPos>,
    allowedIds: Set<String>? = null,
  ): BlockPos? {
    var best: BlockPos? = null
    var bestDist = Double.POSITIVE_INFINITY
    for (pos in blocks) {
      if (!isMineableTarget(level, player, pos, allowedIds)) continue
      val distSq = distanceToBlockSq(player, pos)
      if (distSq < bestDist) {
        bestDist = distSq
        best = pos
      }
    }
    return best
  }

  private fun resolvePreviewTarget(
    level: net.minecraft.world.level.Level,
    player: Player
  ): BlockPos? {
    val vein = currentVein ?: return currentTarget
    val active = miningOnTarget

    currentTarget
      ?.takeIf { it != active && vein.blocks.contains(it) && isMineableTarget(level, player, it, vein.targetIds) }
      ?.let { return it }

    var best: BlockPos? = null
    var bestDist = Double.POSITIVE_INFINITY
    for (pos in vein.blocks) {
      if (pos == active) continue
      if (!isMineableTarget(level, player, pos, vein.targetIds)) continue
      if (REQUIRE_MINE_LOS && !hasLineOfSight(level, player, pos)) continue
      val distSq = distanceToBlockSq(player, pos)
      if (distSq < bestDist) {
        bestDist = distSq
        best = pos
      }
    }
    return best ?: vein.blocks.firstOrNull { it != active && isMineableTarget(level, player, it, vein.targetIds) }
  }

  private fun startMining(player: Player, target: BlockPos) {
    if (target != miningOnTarget) {
      miningOnTarget = target
      miningOnTargetTicks = 0
      miningLockedTicks = 0
    }
    miningOnTargetTicks++
    val currentAim = resolveMiningAimPoint(player, target)
    miningUsesPrecisionPoint = currentAim.usesPrecisionPoint
    val maxTicksForBlock = maxMiningTicksForTarget(target)
    if (miningOnTargetTicks > maxTicksForBlock) {
      currentVein?.blocks?.remove(target)
      miningOnTarget = null
      miningOnTargetTicks = 0
      stopMiningKeys()
      return
    }
    if (isCrosshairOnTarget(target)) {
      miningLockedTicks++
    }
    val aim = resolveGlideAimTarget(player, target, currentAim)
    val precisionRotScale =
      if (aim.usesPrecisionPoint) (precisionPointRotationSpeed.value / 100.0).coerceAtLeast(0.1)
      else 1.0
    frameRotSnapThreshold = RotationsModule.bezierSnapThreshold.value.toFloat()
    frameRotTarget     = aim.point
    frameRotSpeedScale = (RotationsModule.sample(RotationsModule.miningSpeedScale.value) * precisionRotScale).toFloat()
    frameRotAccelScale = (RotationsModule.sample(RotationsModule.miningAccelScale.value) * precisionRotScale).toFloat()
    frameRotPitchStep  = (RotationsModule.sample(RotationsModule.miningPitchStep.value) * precisionRotScale).toFloat()
    frameRotMaxSpeed   = (RotationsModule.sample(RotationsModule.miningMaxSpeed.value) * precisionRotScale).toFloat()
    frameRotMaxAccel   = (RotationsModule.sample(RotationsModule.miningMaxAccel.value) * precisionRotScale).toFloat()
    setMiningAttackDown(true)
    miningActive = true
  }

  private fun setMiningAttackDown(attack: Boolean) {
    mc.options.keyAttack?.setDown(attack)
    mc.options.keyUse?.setDown(false)
    MovementManager.forcedActionsEnabled = attack
    MovementManager.forcedAttack = attack
    MovementManager.forcedUse = false
  }

  private fun maxMiningTicksForTarget(target: BlockPos): Int {
    val calculatedTicks = MiningModule.getCalculatedLookTicks(includePingDelay = true)
    if (calculatedTicks > 0.0) {
      return ceil(calculatedTicks * MINING_TIMEOUT_SCALE + MINING_TIMEOUT_EXTRA_TICKS)
        .toInt()
        .coerceAtLeast(MIN_MINING_TICKS_PER_BLOCK)
    }

    val level = mc.level
    val blockId = level?.let { blockIdAt(it, target) }
    val blockType = blockId?.let { MiningBlockRegistry.BLOCK_ID_TO_TYPE[it] }
    val hardness = blockType?.let { MiningBlockRegistry.BLOCK_HARDNESS[it] }
    val fallbackTicks = MiningBreakThresholds.getOptimalTicks(blockType, FALLBACK_TIMEOUT_MINING_SPEED, hardness)
    return ceil(fallbackTicks * MINING_TIMEOUT_SCALE + MINING_TIMEOUT_EXTRA_TICKS)
      .toInt()
      .coerceAtLeast(MIN_MINING_TICKS_PER_BLOCK)
  }

  private fun stopMiningKeys() {
    if (miningActive) {
      setMiningAttackDown(false)
      miningActive = false
    } else {
      setMiningAttackDown(false)
    }
    frameRotTarget = null
    frameRotSnapThreshold = 0f
    miningOnTarget = null
    miningOnTargetTicks = 0
    miningLockedTicks = 0
    miningUsesPrecisionPoint = false
  }

  private fun maybeRefreshLantern(
    level: net.minecraft.world.level.Level,
    player: Player
  ): Boolean {
    val needsRefresh =
      !lanternPlacedForVein ||
        !AutoLanternModule.isLanternBuffActive() ||
        lastLanternRefreshTick < 0L ||
        level.gameTime - lastLanternRefreshTick >= LANTERN_REFRESH_TICKS
    if (!needsRefresh) {
      return false
    }
    if (tryPlaceLantern(level, player)) {
      lanternPlacedForVein = true
      lastLanternRefreshTick = level.gameTime
      return true
    }
    return false
  }

  private fun tryPlaceLantern(
    level: net.minecraft.world.level.Level,
    player: Player
  ): Boolean {
    if (level.gameTime - lastLanternPlaceTick < LANTERN_PLACE_COOLDOWN_TICKS) {
      return false
    }

    val lanternSlot = findLanternHotbarSlot(player)
    if (lanternSlot !in 0..8) {
      return false
    }
    if (AutoLanternModule.isPlacementInProgress()) {
      return false
    }

    if (!AutoLanternModule.tryStartLanternPlacement(lanternSlot)) {
      return false
    }
    lastLanternPlaceTick = level.gameTime
    return true
  }

  private fun findLanternHotbarSlot(player: Player): Int {
    val inventory = player.inventory
    for (i in 0..8) {
      val stack = inventory.getItem(i)
      if (stack.isEmpty) continue
      val normalized = normalizeHotbarItemName(stack.hoverName.string)
      if (
        normalized.contains("mithril lantern") ||
        normalized.contains("titanium lantern") ||
        normalized.contains("glacite lantern") ||
        normalized.contains(WILL_O_WISP_NAME)
      ) {
        return i
      }
    }
    return -1
  }

  private fun normalizeHotbarItemName(rawName: String): String {
    return (ChatFormatting.stripFormatting(rawName) ?: rawName)
      .lowercase()
      .replace(Regex("[^a-z0-9]+"), " ")
      .trim()
  }

  /**
   * Presses the minimal set of movement keys to walk the player toward [target] in world space,
   * regardless of where the camera is pointing.
   */
  private fun nudgeToward(player: Player, target: BlockPos) {
    nudgeToward(player, Vec3(target.x + 0.5, player.y, target.z + 0.5))
  }

  private fun nudgeToward(player: Player, targetPoint: Vec3) {
    val dx = targetPoint.x - player.x
    val dz = targetPoint.z - player.z
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
    // Project world-space direction onto player's local forward (+Z in local) and strafe (+X) axes.
    val fwd = (-nx * sinYaw + nz * cosYaw).toFloat()
    val str = ( nx * cosYaw + nz * sinYaw).toFloat()
    val threshold = 0.35f
    MovementManager.setForcedMovement(
      forward  = fwd >  threshold,
      backward = fwd < -threshold,
      right    = str >  threshold,
      left     = str < -threshold,
      jump = false, shift = false, sprint = false
    )
  }

  private fun nudgeTowardApproach(
    level: net.minecraft.world.level.Level,
    player: Player,
    target: BlockPos,
    avoidPlayer: Player? = null,
  ) {
    val shortStepLimit = maxNearbyStepDistance()
    val approach = findApproach(
      level,
      player,
      target,
      avoidPlayer = avoidPlayer,
      preferredStandOff = preferredNearbyStepDistance(),
      maxTravelDistance = shortStepLimit,
    )
    val stepPoint = approach?.let { Vec3(it.x + 0.5, player.y, it.z + 0.5) }
      ?: resolveNearbyStepPoint(player, target, avoidPlayer)
    nudgeToward(player, clampNearbyStepPoint(player, stepPoint, shortStepLimit))
  }

  private fun resolveNearbyStepPoint(
    player: Player,
    target: BlockPos,
    avoidPlayer: Player? = null,
  ): Vec3 {
    val centerX = target.x + 0.5
    val centerZ = target.z + 0.5
    var dirX = player.x - centerX
    var dirZ = player.z - centerZ
    avoidPlayer?.let { blocker ->
      dirX += (player.x - blocker.x) * 0.9
      dirZ += (player.z - blocker.z) * 0.9
    }
    var len = sqrt(dirX * dirX + dirZ * dirZ)
    if (len < 1.0e-4) {
      dirX = 1.0
      dirZ = 0.0
      len = 1.0
    }
    val scale = preferredNearbyStepDistance() / len
    return Vec3(centerX + dirX * scale, player.y, centerZ + dirZ * scale)
  }

  private fun clampNearbyStepPoint(player: Player, point: Vec3, maxDistance: Double): Vec3 {
    val dx = point.x - player.x
    val dz = point.z - player.z
    val distSq = dx * dx + dz * dz
    if (distSq <= maxDistance * maxDistance) return point
    val dist = sqrt(distSq)
    if (dist <= 1.0e-4) return point
    val scale = maxDistance / dist
    return Vec3(player.x + dx * scale, player.y, player.z + dz * scale)
  }

  private fun trySidestepAroundBlockingPlayer(
    level: net.minecraft.world.level.Level,
    player: Player,
    target: BlockPos,
    blocker: Player,
  ): Boolean {
    if (player.distanceToSqr(blocker) > BLOCKING_PLAYER_REACT_RANGE * BLOCKING_PLAYER_REACT_RANGE) {
      return false
    }

    val sidestepPoint = resolveBlockingPlayerSidestep(level, player, target, blocker) ?: return false
    stopApproachMovement()
    nudgeToward(player, sidestepPoint)
    focusApproachTarget(player, target)
    return true
  }

  private fun resolveBlockingPlayerSidestep(
    level: net.minecraft.world.level.Level,
    player: Player,
    target: BlockPos,
    blocker: Player,
  ): Vec3? {
    val targetCenterX = target.x + 0.5
    val targetCenterZ = target.z + 0.5
    val towardTargetX = targetCenterX - player.x
    val towardTargetZ = targetCenterZ - player.z
    val towardTargetLen = sqrt(towardTargetX * towardTargetX + towardTargetZ * towardTargetZ)
    if (towardTargetLen < 1.0e-4) {
      return null
    }

    val nx = towardTargetX / towardTargetLen
    val nz = towardTargetZ / towardTargetLen
    val leftX = -nz
    val leftZ = nx
    val rightX = nz
    val rightZ = -nx
    val candidates = listOf(
      Vec3(
        player.x + leftX * BLOCKING_PLAYER_SIDESTEP_DISTANCE + nx * BLOCKING_PLAYER_SIDESTEP_FORWARD,
        player.y,
        player.z + leftZ * BLOCKING_PLAYER_SIDESTEP_DISTANCE + nz * BLOCKING_PLAYER_SIDESTEP_FORWARD,
      ),
      Vec3(
        player.x + rightX * BLOCKING_PLAYER_SIDESTEP_DISTANCE + nx * BLOCKING_PLAYER_SIDESTEP_FORWARD,
        player.y,
        player.z + rightZ * BLOCKING_PLAYER_SIDESTEP_DISTANCE + nz * BLOCKING_PLAYER_SIDESTEP_FORWARD,
      ),
      Vec3(
        player.x + leftX * BLOCKING_PLAYER_SIDESTEP_DISTANCE,
        player.y,
        player.z + leftZ * BLOCKING_PLAYER_SIDESTEP_DISTANCE,
      ),
      Vec3(
        player.x + rightX * BLOCKING_PLAYER_SIDESTEP_DISTANCE,
        player.y,
        player.z + rightZ * BLOCKING_PLAYER_SIDESTEP_DISTANCE,
      ),
    )

    var best: Vec3? = null
    var bestScore = Double.POSITIVE_INFINITY
    val playerEyeOffset = player.eyeY - player.y
    val maxTargetDist = mineRange.value + NUDGE_RANGE_EXTRA
    val preferredTargetDist = preferredNearbyStepDistance()

    for (candidate in candidates) {
      val candidateBlock = BlockPos.containing(candidate.x, player.y, candidate.z)
      val resolvedBlock =
        if (MinecraftPathingRules.isWalkable(level, candidateBlock)) {
          candidateBlock
        } else {
          findNearestWalkableAround(level, candidateBlock, 1, 1)
        } ?: continue

      val point = Vec3(resolvedBlock.x + 0.5, player.y, resolvedBlock.z + 0.5)
      val eye = Vec3(point.x, point.y + playerEyeOffset, point.z)
      val visiblePoint = findVisibleAimPoint(level, player, eye, target) ?: continue
      val blockerDistSq = horizontalDistSq(point.x, point.z, blocker.x, blocker.z)
      if (blockerDistSq < BLOCKING_PLAYER_MIN_CLEARANCE * BLOCKING_PLAYER_MIN_CLEARANCE) {
        continue
      }
      val targetDist = sqrt(horizontalDistSq(point.x, point.z, targetCenterX, targetCenterZ))
      if (targetDist > maxTargetDist) {
        continue
      }
      val moveDistSq = horizontalDistSq(point.x, point.z, player.x, player.z)
      val targetDistPenalty = abs(targetDist - preferredTargetDist) * 18.0
      val visibleDistSq = eye.distanceToSqr(visiblePoint)
      val blockerBonus = blockerDistSq * 0.45
      val score = moveDistSq + targetDistPenalty * targetDistPenalty + visibleDistSq * 0.03 - blockerBonus
      if (score < bestScore) {
        bestScore = score
        best = point
      }
    }

    return best
  }

  private fun horizontalDistSq(x1: Double, z1: Double, x2: Double, z2: Double): Double {
    val dx = x1 - x2
    val dz = z1 - z2
    return dx * dx + dz * dz
  }

  private fun canStepToNearbyTarget(player: Player, target: BlockPos): Boolean {
    if (RoutesModule.isRunning && RoutesModule.routeOwnsMining) return true
    val anchor = veinStartAnchor ?: return true
    val r = anchorRadius.value
    if (player.blockPosition().distSqr(anchor) > r * r) return false
    val maxTargetDist = r + mineRange.value
    return anchor.distSqr(target) <= maxTargetDist * maxTargetDist
  }

  private fun hasDriftedFromVeinStart(player: Player): Boolean {
    if (RoutesModule.isRunning && RoutesModule.routeOwnsMining) return false
    val anchor = veinStartAnchor ?: return false
    val r = anchorRadius.value
    return player.blockPosition().distSqr(anchor) > r * r
  }

  private fun returnToVeinAnchor(
    level: net.minecraft.world.level.Level,
    player: Player,
  ) {
    val anchor = veinStartAnchor ?: return
    val arrivalSq = RETURN_TO_ANCHOR_ARRIVAL_DIST * RETURN_TO_ANCHOR_ARRIVAL_DIST
    if (player.blockPosition().distSqr(anchor) <= arrivalSq) {
      stopApproachMovement()
      MovementManager.clearForcedMovement()
      return
    }

    val destination =
      if (MinecraftPathingRules.isWalkable(level, anchor)) {
        anchor
      } else {
        findNearestWalkableAround(level, anchor, RETURN_TO_ANCHOR_SCAN_RADIUS, RETURN_TO_ANCHOR_SCAN_VERTICAL)
      } ?: run {
        MovementManager.clearForcedMovement()
        return
      }

    if (!nativeActive() || lastPathTarget == null || lastPathTarget?.distSqr(destination) ?: 0.0 > 1.0) {
      if (level.gameTime - lastPathStartTick < 8L) {
        return
      }
      lastPathStartTick = level.gameTime
      NativePathfinder.setTarget(destination.x + 0.5, destination.y.toDouble(), destination.z + 0.5)
      startedPath = true
      lastPathTarget = destination
    }
    // Ticking is handled by the early path block in onTick to avoid double-ticking.
  }

  private fun findNearestWalkableAround(
    level: net.minecraft.world.level.Level,
    origin: BlockPos,
    radius: Int,
    vertical: Int,
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

  private fun captureGoldenGoblinReturnSpot(player: Player): Vec3 {
    val level = mc.level ?: return player.position()
    val origin = player.blockPosition()
    val destination =
      if (MinecraftPathingRules.isWalkable(level, origin)) {
        origin
      } else {
        findNearestWalkableAround(level, origin, RETURN_TO_ANCHOR_SCAN_RADIUS, RETURN_TO_ANCHOR_SCAN_VERTICAL)
      } ?: return player.position()
    return Vec3(destination.x + 0.5, destination.y.toDouble(), destination.z + 0.5)
  }

  private fun beginGoldenGoblinInterrupt() {
    if (goldenGoblinInterruptActive) return

    goldenGoblinInterruptActive = true
    goldenGoblinInterruptOwnedCombat = true
    goldenGoblinReturnPending = false
    goldenGoblinReturnPos = mc.player?.let(::captureGoldenGoblinReturnSpot)
    if (startedPath && nativeActive()) {
      nativeStop()
    } else {
      MovementManager.clearForcedMovement()
    }
    startedPath = false
    lastPathTarget = null
    lastPathStartTick = 0L
    currentTarget = null
    currentTargetNoLosTicks = 0
    resetApproachTracking()
    stopMiningKeys()
    RotationExecutor.stopRotating()
    CombatMacroModule.startForAutomation(GOLDEN_GOBLIN_NAME)
    ChatUtils.sendMessage("Mining macro: Golden Goblin spawned, interrupting vein.")
  }

  private fun handleGoldenGoblinInterrupt(
    level: net.minecraft.world.level.Level,
    player: Player,
  ) {
    stopMiningKeys()
    currentTarget = null
    currentTargetNoLosTicks = 0

    if (!CombatMacroModule.isActive) {
      CombatMacroModule.startForAutomation(GOLDEN_GOBLIN_NAME)
      goldenGoblinInterruptOwnedCombat = true
    }

    val goblin = findGoldenGoblin(player)
    if (goblin != null) {
      goldenGoblinLastSeenTick = level.gameTime
      return
    }

    if (goldenGoblinLastSeenTick >= 0L && level.gameTime - goldenGoblinLastSeenTick >= GOLDEN_GOBLIN_LOST_TICKS) {
      finishGoldenGoblinInterrupt()
    }
  }

  private fun finishGoldenGoblinInterrupt() {
    if (goldenGoblinInterruptOwnedCombat && CombatMacroModule.isActive) {
      CombatMacroModule.stopForAutomation()
    }
    if (nativeActive()) {
      nativeStop()
    }
    goldenGoblinInterruptActive = false
    goldenGoblinInterruptOwnedCombat = false
    goldenGoblinLastSeenTick = -1L
    startedPath = false
    lastPathTarget = null
    lastPathStartTick = 0L
    resetApproachTracking()
    MovementManager.clearForcedMovement()
    RotationExecutor.stopRotating()
    goldenGoblinReturnPending = goldenGoblinReturnPos != null
    if (goldenGoblinReturnPending) {
      ChatUtils.sendMessage("Mining macro: Golden Goblin handled, returning to mining spot.")
    } else {
      ChatUtils.sendMessage("Mining macro: Golden Goblin handled, resuming vein.")
    }
  }

  private fun findGoldenGoblin(player: Player): LivingEntity? {
    val level = mc.level ?: return null
    val maxDistSq = 64.0 * 64.0
    return level.entitiesForRendering()
      .asSequence()
      .mapNotNull { it as? LivingEntity }
      .filter { it.isAlive && it.health > 0f && it !== player }
      .firstOrNull { entity ->
        player.distanceToSqr(entity) <= maxDistSq &&
          CombatMacroModule.matchesAutomationTarget(entity, GOLDEN_GOBLIN_NAME)
      }
  }

  private fun handleGoldenGoblinReturn(
    level: net.minecraft.world.level.Level,
    player: Player,
  ) {
    stopMiningKeys()
    currentTarget = null
    currentTargetNoLosTicks = 0

    val returnPos = goldenGoblinReturnPos ?: run {
      goldenGoblinReturnPending = false
      return
    }
    val arrivalDistSq = 0.85 * 0.85
    if (player.position().distanceToSqr(returnPos) <= arrivalDistSq) {
      stopApproachMovement()
      MovementManager.clearForcedMovement()
      goldenGoblinReturnPending = false
      goldenGoblinReturnPos = null
      ChatUtils.sendMessage("Mining macro: Back at mining spot, resuming vein.")
      return
    }

    PathfindingModule.ensureEnabledForAutomation("golden goblin return")
    val rawDestination = BlockPos.containing(returnPos.x, returnPos.y, returnPos.z)
    val destination =
      if (MinecraftPathingRules.isWalkable(level, rawDestination)) {
        rawDestination
      } else {
        findNearestWalkableAround(level, rawDestination, RETURN_TO_ANCHOR_SCAN_RADIUS, RETURN_TO_ANCHOR_SCAN_VERTICAL)
      }

    if (destination == null) {
      nudgeToward(player, Vec3(returnPos.x, player.y, returnPos.z))
      return
    }

    if (player.blockPosition().distSqr(destination) <= 1.0) {
      stopApproachMovement()
      MovementManager.clearForcedMovement()
      goldenGoblinReturnPending = false
      goldenGoblinReturnPos = null
      ChatUtils.sendMessage("Mining macro: Back at mining spot, resuming vein.")
      return
    }

    if (!nativeActive() || lastPathTarget == null || lastPathTarget?.distSqr(destination) ?: 0.0 > 1.0) {
      if (level.gameTime - lastPathStartTick < 8L) {
        return
      }
      lastPathStartTick = level.gameTime
      NativePathfinder.setTarget(destination.x + 0.5, destination.y.toDouble(), destination.z + 0.5)
      startedPath = true
      lastPathTarget = destination
    }

    val cmd = NativePathfinder.tick()
    if (cmd != null) {
      cmd.applyToPlayer()
    } else {
      nudgeToward(player, Vec3(destination.x + 0.5, player.y, destination.z + 0.5))
    }
  }

  private fun moveToward(
    level: net.minecraft.world.level.Level,
    player: Player,
    target: BlockPos,
    forceApproach: Boolean = false,
    avoidPlayer: Player? = null,
    maxTravelDistance: Double = Double.POSITIVE_INFINITY,
  ) {
    PathfindingModule.ensureEnabledForAutomation("mining macro")
    val currentDistance = sqrt(distanceToBlockSq(player, target))
    val holdDistance = preferredApproachDistance()
    // Only skip stepping when we're already in an ideal spot: close AND with LOS.
    // Without the LOS check, a block that sits within 4 blocks but is occluded (corners,
    // ceiling ores, short walls) would never trigger a step to a visible position.
    if (!forceApproach && currentDistance <= holdDistance &&
        (!REQUIRE_MINE_LOS || hasLineOfSight(level, player, target))) {
      stopApproachMovement()
      focusApproachTarget(player, target)
      return
    }
    if (approachTarget != target) {
      approachTarget = target
      approachStartTick = level.gameTime
      approachStartDistance = currentDistance
    } else if (
      level.gameTime - approachStartTick >= APPROACH_TIMEOUT_TICKS &&
      approachStartDistance - currentDistance < APPROACH_MIN_PROGRESS_BLOCKS
    ) {
      abandonApproachTarget(target)
      warnOnce("Mining macro: approach timed out, skipping block.")
      return
    }

    val approach = lastPathTarget?.takeIf {
      isApproachUsable(
        level,
        player,
        it,
        target,
        avoidPlayer = avoidPlayer,
        preferredStandOff = holdDistance,
        maxTravelDistance = maxTravelDistance,
      )
    } ?: findApproach(
      level,
      player,
      target,
      avoidPlayer = avoidPlayer,
      preferredStandOff = holdDistance,
      maxTravelDistance = maxTravelDistance,
    ) ?: run {
      stopApproachMovement()
      return
    }
    if (currentDistance <= holdDistance + 0.85) {
      focusApproachTarget(player, target)
    }
    if (!nativeActive() || lastPathTarget == null || lastPathTarget?.distSqr(approach) ?: 0.0 > 1.0) {
      if (level.gameTime - lastPathStartTick < 8L) {
        return
      }
      lastPathStartTick = level.gameTime
      NativePathfinder.setTarget(approach.x + 0.5, approach.y.toDouble(), approach.z + 0.5)
      startedPath = true
      lastPathTarget = approach
    }
    // Ticking is handled by the early path block in onTick to avoid double-ticking.
  }

  private fun abandonApproachTarget(target: BlockPos) {
    if (startedPath && nativeActive()) {
      nativeStop()
    } else {
      MovementManager.clearForcedMovement()
    }
    startedPath = false
    lastPathTarget = null
    lastPathStartTick = 0L
    resetApproachTracking()
    currentVein?.blocks?.remove(target)
    currentTarget = null
    currentTargetNoLosTicks = 0
    currentDirectionalFlow = null
  }

  private fun stopApproachMovement() {
    if (startedPath && nativeActive()) {
      nativeStop()
    } else {
      MovementManager.clearForcedMovement()
    }
    startedPath = false
    lastPathTarget = null
    lastPathStartTick = 0L
    resetApproachTracking()
  }

  private fun focusApproachTarget(player: Player, target: BlockPos) {
    val aim = resolveMiningAimPoint(player, target)
    val precisionRotScale =
      if (aim.usesPrecisionPoint) (precisionPointRotationSpeed.value / 100.0).coerceAtLeast(0.1)
      else 1.0
    frameRotTarget = aim.point
    frameRotSnapThreshold = RotationsModule.bezierSnapThreshold.value.toFloat()
    frameRotSpeedScale = (RotationsModule.sample(RotationsModule.miningSpeedScale.value) * precisionRotScale).toFloat()
    frameRotAccelScale = (RotationsModule.sample(RotationsModule.miningAccelScale.value) * precisionRotScale).toFloat()
    frameRotPitchStep = (RotationsModule.sample(RotationsModule.miningPitchStep.value) * precisionRotScale).toFloat()
    frameRotMaxSpeed = (RotationsModule.sample(RotationsModule.miningMaxSpeed.value) * precisionRotScale).toFloat()
    frameRotMaxAccel = (RotationsModule.sample(RotationsModule.miningMaxAccel.value) * precisionRotScale).toFloat()
  }

  private fun resetApproachTracking() {
    approachTarget = null
    approachStartTick = 0L
    approachStartDistance = 0.0
  }

  private fun findApproach(
    level: net.minecraft.world.level.Level,
    player: Player,
    target: BlockPos,
    avoidPlayer: Player? = null,
    preferredStandOff: Double = preferredApproachDistance(),
    maxTravelDistance: Double = Double.POSITIVE_INFINITY,
  ): BlockPos? {
    var best: BlockPos? = null
    var bestScore = Double.POSITIVE_INFINITY

    val primary = listOf(
      intArrayOf(1, 0, 0), intArrayOf(-1, 0, 0), intArrayOf(0, 0, 1), intArrayOf(0, 0, -1),
      intArrayOf(1, -1, 0), intArrayOf(-1, -1, 0), intArrayOf(0, -1, 1), intArrayOf(0, -1, -1),
      intArrayOf(1, 1, 0), intArrayOf(-1, 1, 0), intArrayOf(0, 1, 1), intArrayOf(0, 1, -1),
      intArrayOf(1, 0, 1), intArrayOf(1, 0, -1), intArrayOf(-1, 0, 1), intArrayOf(-1, 0, -1),
    )

    for (off in primary) {
      val candidate = target.offset(off[0], off[1], off[2])
      val score = approachScore(level, player, candidate, target, avoidPlayer, preferredStandOff, maxTravelDistance)
      if (score < bestScore) {
        bestScore = score
        best = candidate
      }
    }
    if (best != null) return best

    var dy = -APPROACH_SCAN_VERTICAL
    while (dy <= APPROACH_SCAN_VERTICAL) {
      var dx = -APPROACH_SCAN_RADIUS
      while (dx <= APPROACH_SCAN_RADIUS) {
        var dz = -APPROACH_SCAN_RADIUS
        while (dz <= APPROACH_SCAN_RADIUS) {
          if (dx != 0 || dy != 0 || dz != 0) {
            val candidate = target.offset(dx, dy, dz)
            val score =
              approachScore(level, player, candidate, target, avoidPlayer, preferredStandOff, maxTravelDistance)
            if (score < bestScore) {
              bestScore = score
              best = candidate
            }
          }
          dz++
        }
        dx++
      }
      dy++
    }

    if (best != null) return best
    return null
  }

  private fun isApproachUsable(
    level: net.minecraft.world.level.Level,
    player: Player,
    candidate: BlockPos,
    target: BlockPos,
    avoidPlayer: Player? = null,
    preferredStandOff: Double = preferredApproachDistance(),
    maxTravelDistance: Double = Double.POSITIVE_INFINITY,
  ): Boolean =
    approachScore(level, player, candidate, target, avoidPlayer, preferredStandOff, maxTravelDistance).isFinite()

  private fun approachScore(
    level: net.minecraft.world.level.Level,
    player: Player,
    candidate: BlockPos,
    target: BlockPos,
    avoidPlayer: Player? = null,
    preferredStandOff: Double = preferredApproachDistance(),
    maxTravelDistance: Double = Double.POSITIVE_INFINITY,
  ): Double {
    if (!MinecraftPathingRules.isWalkable(level, candidate)) {
      return Double.POSITIVE_INFINITY
    }

    val centerDx = (candidate.x + 0.5) - (target.x + 0.5)
    val centerDy = (candidate.y + 0.5) - (target.y + 0.5)
    val centerDz = (candidate.z + 0.5) - (target.z + 0.5)
    val targetDistSq = centerDx * centerDx + centerDy * centerDy + centerDz * centerDz
    val targetDist = sqrt(targetDistSq)
    val standOff = preferredStandOff.coerceAtLeast(0.5)
    val maxMineDist = (mineRange.value - APPROACH_EDGE_MARGIN).coerceAtLeast(0.5)
    val minMineDist = (standOff - APPROACH_EDGE_WINDOW).coerceAtLeast(0.0)
    if (targetDist < minMineDist || targetDist > maxMineDist) {
      return Double.POSITIVE_INFINITY
    }

    if (REQUIRE_MINE_LOS && !hasLineOfSightFrom(level, player, candidate, target)) {
      return Double.POSITIVE_INFINITY
    }

    val playerDistSq = player.blockPosition().distSqr(candidate).toDouble()
    if (playerDistSq > maxTravelDistance * maxTravelDistance) {
      return Double.POSITIVE_INFINITY
    }
    val verticalPenalty = abs(candidate.y - target.y) * 0.75
    val edgePenalty = abs(targetDist - standOff) * 12.0
    var score = edgePenalty * edgePenalty + playerDistSq * 0.35 + verticalPenalty
    avoidPlayer?.let { blocker ->
      val blockerDx = (candidate.x + 0.5) - blocker.x
      val blockerDz = (candidate.z + 0.5) - blocker.z
      val blockerDistSq = blockerDx * blockerDx + blockerDz * blockerDz
      if (blockerDistSq < BLOCKING_PLAYER_MIN_CLEARANCE * BLOCKING_PLAYER_MIN_CLEARANCE) {
        return Double.POSITIVE_INFINITY
      }
      score += BLOCKING_PLAYER_PENALTY / blockerDistSq.coerceAtLeast(0.25)
    }
    return score
  }

  private fun preferredApproachDistance(): Double {
    return minOf(APPROACH_HOLD_DISTANCE, (mineRange.value - APPROACH_EDGE_MARGIN).coerceAtLeast(0.5))
  }

  private fun preferredNearbyStepDistance(): Double {
    return (mineRange.value - NEARBY_STEP_REACH_BUFFER).coerceAtLeast(0.5)
  }

  private fun maxNearbyStepDistance(): Double {
    return minOf(MAX_WALK_BLOCKS, mineRange.value + NEARBY_STEP_RANGE_EXTRA)
  }

  /** Angular distance in degrees between the player's current look direction and a target block. */
  private fun angularDistanceTo(player: Player, target: BlockPos): Float =
    angularDistanceTo(player, Vec3(target.x + 0.5, target.y + 0.5, target.z + 0.5))

  private fun angularDistanceTo(player: Player, point: Vec3): Float {
    val dx = point.x - player.x
    val dy = point.y - player.eyeY
    val dz = point.z - player.z
    val targetYaw = Math.toDegrees(kotlin.math.atan2(-dx, dz)).toFloat()
    val horizDist = sqrt(dx * dx + dz * dz)
    val targetPitch = Math.toDegrees(kotlin.math.atan2(-dy, horizDist)).toFloat()
    val yawDelta = abs(AngleUtils.getRotationDelta(player.yRot, targetYaw))
    val pitchDelta = abs(targetPitch - player.xRot)
    return sqrt(yawDelta * yawDelta + pitchDelta * pitchDelta)
  }

  private fun hasLineOfSightFrom(
    level: net.minecraft.world.level.Level,
    player: Player,
    from: BlockPos,
    target: BlockPos
  ): Boolean {
    val eyeY = from.y + if (player.isShiftKeyDown) 1.54 else 1.62
    val eye = Vec3(from.x + 0.5, eyeY, from.z + 0.5)
    return losCheck(level, player, eye, target)
  }

  /**
   * Checks line of sight from [eye] to [target] by sampling the block face
   * closest to the eye. This handles wall-embedded ores where a ray to the
   * block center is occluded by the stone above the ore.
   */
  private fun losCheck(
    level: net.minecraft.world.level.Level,
    entity: net.minecraft.world.entity.Entity,
    eye: Vec3,
    target: BlockPos
  ): Boolean {
    return findVisibleAimPoint(level, entity, eye, target) != null
  }

  private fun findBlockingPlayer(
    level: net.minecraft.world.level.Level,
    player: Player,
    eye: Vec3,
    point: Vec3,
  ): Player? {
    val rayBounds = AABB(eye, point).inflate(BLOCKING_PLAYER_BOX_PADDING)
    var best: Player? = null
    var bestDistSq = eye.distanceToSqr(point)
    for (other in level.players()) {
      if (other == player) continue
      if (other.isSpectator || !other.isAlive) continue
      val hitBox = other.boundingBox.inflate(BLOCKING_PLAYER_BOX_PADDING)
      if (!rayBounds.intersects(hitBox)) continue
      val hit = hitBox.clip(eye, point).orElse(null) ?: continue
      val distSq = eye.distanceToSqr(hit)
      if (distSq < bestDistSq) {
        bestDistSq = distSq
        best = other
      }
    }
    return best
  }

  private fun findBlockingPlayerForTarget(
    level: net.minecraft.world.level.Level,
    player: Player,
    target: BlockPos,
  ): Player? {
    val eye = player.eyePosition
    var best: Player? = null
    var bestDistSq = Double.POSITIVE_INFINITY
    for (point in buildAimSamplePoints(eye, target)) {
      if (!canSeeAimPoint(level, player, eye, point, target, ignorePlayerObstructions = true)) continue
      val blocker = findBlockingPlayer(level, player, eye, point) ?: continue
      val distSq = player.distanceToSqr(blocker)
      if (distSq < bestDistSq) {
        bestDistSq = distSq
        best = blocker
      }
    }
    return best
  }

  private fun buildAimSamplePoints(eye: Vec3, target: BlockPos): List<Vec3> {
    val cx = target.x + 0.5
    val cy = target.y + 0.5
    val cz = target.z + 0.5
    // Offset toward the eye on each axis, clamped to stay within the block face
    val ox = ((eye.x - cx) * 0.49).coerceIn(-0.49, 0.49)
    val oy = ((eye.y - cy) * 0.49).coerceIn(-0.49, 0.49)
    val oz = ((eye.z - cz) * 0.49).coerceIn(-0.49, 0.49)
    val center = Vec3(cx, cy, cz)
    return listOf(
      center,
      Vec3(cx + ox, cy, cz),
      Vec3(cx, cy + oy, cz),
      Vec3(cx, cy, cz + oz),
      Vec3(cx + ox, cy + oy, cz + oz),
    )
  }

  private fun findVisibleAimPoint(
    level: net.minecraft.world.level.Level,
    entity: net.minecraft.world.entity.Entity,
    eye: Vec3,
    target: BlockPos,
    ignorePlayerObstructions: Boolean = false,
  ): Vec3? {
    for (point in buildAimSamplePoints(eye, target)) {
      if (canSeeAimPoint(level, entity, eye, point, target, ignorePlayerObstructions)) {
        return point
      }
    }
    return null
  }

  private fun canSeeAimPoint(
    level: net.minecraft.world.level.Level,
    entity: net.minecraft.world.entity.Entity,
    eye: Vec3,
    point: Vec3,
    target: BlockPos,
    ignorePlayerObstructions: Boolean = false,
  ): Boolean {
    val hit = level.clip(
      net.minecraft.world.level.ClipContext(
        eye,
        point,
        net.minecraft.world.level.ClipContext.Block.OUTLINE,
        net.minecraft.world.level.ClipContext.Fluid.NONE,
        entity
      )
    )
    if (hit.type != net.minecraft.world.phys.HitResult.Type.BLOCK || hit.blockPos != target) {
      return false
    }
    if (!ignorePlayerObstructions && entity is Player && findBlockingPlayer(level, entity, eye, point) != null) {
      return false
    }
    return true
  }

  private fun tryStartWarp(
    player: Player,
    level: net.minecraft.world.level.Level,
    target: BlockPos
  ): Boolean {
    if (!useInstantTransmission.value) return false
    if (level.gameTime < warpCooldownUntil) return false
    if (!EtherwarpLogic.holdingEtherwarpItem()) return false
    if (!EtherwarpLogic.canEtherwarp()) return false

    val eye = player.eyePosition
    val targetCenter = Vec3(target.x + 0.5, target.y + 0.5, target.z + 0.5)
    val distSq = eye.distanceToSqr(targetCenter)
    val minDistSq = warpMinDistance.value * warpMinDistance.value
    if (distSq < minDistSq) return false

    val range = EtherwarpLogic.getEtherwarpRange().toDouble()
    if (distSq > range * range) return false

    if (!hasLineOfSight(level, player, target)) return false
    if (!ensureEtherwarpHotbarSelected()) return false

    if (startedPath && nativeActive()) {
      nativeStop()
    }
    startedPath = false
    lastPathTarget = null
    resetApproachTracking()
    stopMiningKeys()
    RotationExecutor.stopRotating()
    mc.options.keyUse?.setDown(false)
    mc.options.keyShift?.setDown(false)

    warpTarget = target
    warpStage = 0
    warpStageTicks = 0
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

    val targetCenter = Vec3(target.x + 0.5, target.y + 0.5, target.z + 0.5)
    frameRotTarget     = targetCenter
    frameRotSnapThreshold = 0f
    frameRotSpeedScale = RotationsModule.sample(RotationsModule.warpSpeedScale.value).toFloat()
    frameRotAccelScale = RotationsModule.sample(RotationsModule.warpAccelScale.value).toFloat()
    frameRotPitchStep  = RotationsModule.sample(RotationsModule.miningPitchStep.value).toFloat()
    frameRotMaxSpeed   = RotationsModule.sample(RotationsModule.miningMaxSpeed.value).toFloat()
    frameRotMaxAccel   = RotationsModule.sample(RotationsModule.miningMaxAccel.value).toFloat()
    val targetRotation = AngleUtils.getRotation(targetCenter)
    val yawError   = abs(AngleUtils.getRotationDelta(player.yRot, targetRotation.yaw)).toDouble()
    val pitchError = abs(targetRotation.pitch - player.xRot).toDouble()

    when (warpStage) {
      0 -> {
        val tol = warpAimTolerance.value
        if ((yawError <= tol && pitchError <= tol) || warpStageTicks >= WARP_ALIGN_TICKS) {
          mc.options.keyShift?.setDown(true)
          warpStage = 1
          warpStageTicks = 0
          return
        }
        warpStageTicks++
      }
      1 -> {
        mc.options.keyShift?.setDown(true)
        if (warpStageTicks >= WARP_SNEAK_TICKS) {
          mc.options.keyUse?.setDown(true)
          warpStage = 2
          warpStageTicks = 0
          return
        }
        warpStageTicks++
      }
      else -> {
        mc.options.keyShift?.setDown(true)
        if (warpStageTicks >= 1) {
          mc.options.keyUse?.setDown(false)
        }
        if (warpStageTicks >= WARP_POST_TICKS) {
          mc.options.keyUse?.setDown(false)
          mc.options.keyShift?.setDown(false)
          warpCooldownUntil = level.gameTime + warpCooldownTicks.value.toLong()
          restoreEtherwarpSlot()
          resetWarp()
          return
        }
        warpStageTicks++
      }
    }
  }

  private fun clampAimPointInsideBlock(point: Vec3, target: BlockPos): Vec3 {
    val inset = 0.02
    return Vec3(
      point.x.coerceIn(target.x + inset, target.x + 1.0 - inset),
      point.y.coerceIn(target.y + inset, target.y + 1.0 - inset),
      point.z.coerceIn(target.z + inset, target.z + 1.0 - inset),
    )
  }

  private fun resolveMiningAimPoint(player: Player, target: BlockPos): AimTarget {
    val level = mc.level
    val eye = player.eyePosition
    val usePrecision = shouldUsePrecisionPoint(target)
    if (level != null && usePrecision) {
      MiningPrecisionTracker.getPrecisionPointFor(target, allowTentative = true)?.let { point ->
        val clampedPoint = clampAimPointInsideBlock(point, target)
        if (canSeeAimPoint(level, player, eye, clampedPoint, target) || losCheck(level, player, eye, target)) {
          return AimTarget(clampedPoint, true)
        }
      }
      // Tracker has no data yet — use the exact face hit point from the crosshair raycast.
      // This fires immediately when the crosshair lands on the block so the precision
      // rotation scale applies from the very first tick of mining.
      val hit = mc.hitResult
      if (hit is BlockHitResult && hit.type == HitResult.Type.BLOCK && hit.blockPos == target) {
        return AimTarget(clampAimPointInsideBlock(hit.location, target), true)
      }
    }
    if (level != null) {
      findVisibleAimPoint(level, player, eye, target)?.let { return AimTarget(it, false) }
    }
    return AimTarget(Vec3(target.x + 0.5, target.y + 0.5, target.z + 0.5), false)
  }

  private fun resolveGlideAimTarget(
    player: Player,
    target: BlockPos,
    currentAim: AimTarget,
  ): AimTarget {
    if (!tickGliding.value) {
      return currentAim
    }
    val level = mc.level ?: return currentAim
    val glideStartTicks = MiningModule.getCalculatedLookTicks(includePingDelay = false)
    if (glideStartTicks <= 0.0 || miningLockedTicks.toDouble() < glideStartTicks) {
      return currentAim
    }
    val preview = resolvePreviewTarget(level, player) ?: return currentAim
    if (preview == target) {
      return currentAim
    }
    return resolveMiningAimPoint(player, preview)
  }

  private fun shouldUsePrecisionPoint(target: BlockPos): Boolean {
    refreshPrecisionPointChanceRolls()
    val hasLivePrecisionPoint = MiningPrecisionTracker.getPrecisionPointFor(target, allowTentative = true) != null
    if (!MiningModule.precisionActive.value && !hasLivePrecisionPoint) {
      precisionRolls.clear()
      return false
    }
    return precisionRolls.getOrPut(target.asLong()) {
      val chance = precisionPointChance.value.coerceIn(0.0, 100.0)
      chance >= 100.0 || (chance > 0.0 && Random.nextDouble(100.0) < chance)
    }
  }

  private fun refreshPrecisionPointChanceRolls() {
    val currentChance = precisionPointChance.value.coerceIn(0.0, 100.0)
    if (currentChance != precisionPointChanceSnapshot) {
      precisionPointChanceSnapshot = currentChance
      precisionRolls.clear()
    }
  }

  @SubscribeEvent
  fun onFrame(@Suppress("UNUSED_PARAMETER") event: WorldRenderEvent.Last) {
    val target = frameRotTarget ?: return
    val player = mc.player ?: return

    // Inversely scale speed with angular distance: small deltas get the largest boost so that
    // adjacent-block rotations (typically 5-20 deg) are not glacially slow.
    val targetRot  = AngleUtils.getRotation(target)
    val yawDelta   = abs(AngleUtils.getRotationDelta(player.yRot, targetRot.yaw).toFloat())
    val pitchDelta = abs(targetRot.pitch - player.xRot)
    val maxDelta   = maxOf(yawDelta, pitchDelta).coerceAtLeast(1f)
    val distScale  = (60f / maxDelta).coerceIn(1f, 6f)

    applyHeadRotation(
      player, target,
      maxSpeedScale = frameRotSpeedScale * distScale,
      accelScale    = frameRotAccelScale * distScale,
      maxPitchStep  = frameRotPitchStep,
      maxTurnSpeed  = frameRotMaxSpeed,
      maxTurnAccel  = frameRotMaxAccel,
      snapThreshold = frameRotSnapThreshold,
    )
  }

  @SubscribeEvent
  fun onRender(event: WorldRenderEvent.Last) {
    val level = mc.level ?: run {
      OverlayRenderEngine.clearTag(OVERLAY_TAG)
      return
    }
    if (!enabled.value) {
      OverlayRenderEngine.clearTag(OVERLAY_TAG)
      return
    }

    val active = miningOnTarget
    val player = mc.player ?: run {
      OverlayRenderEngine.clearTag(OVERLAY_TAG)
      return
    }
    val gameTime = level.gameTime
    if (gameTime != previewCacheTick) {
      cachedPreviewTarget = resolvePreviewTarget(level, player)
      previewCacheTick = gameTime
    }
    val preview = cachedPreviewTarget
    val returnPos = goldenGoblinReturnPos

    OverlayRenderEngine.clearTag(OVERLAY_TAG)
    if (active == null && preview == null && returnPos == null) {
      return
    }

    val activeFill = OverlayRenderEngine.Color(0x2D, 0xE2, 0xFF, 0x44)
    val activeOutline = OverlayRenderEngine.Color(0x2D, 0xE2, 0xFF, 0xFF)
    val previewOutline = OverlayRenderEngine.Color(0xFF, 0xD8, 0x4C, 0xFF)
    val returnOutline = OverlayRenderEngine.Color(0x6E, 0xEA, 0x92, 0xFF)

    if (active != null) {
      val pad = 0.002
      OverlayRenderEngine.addBox(
        level,
        active.x - pad,
        active.y - pad,
        active.z - pad,
        active.x + 1.0 + pad,
        active.y + 1.0 + pad,
        active.z + 1.0 + pad,
        activeFill,
        activeOutline,
        2.0f,
        2,
        OVERLAY_TAG,
      )
    }
    if (preview != null && preview != active) {
      OverlayRenderEngine.outlineBlockColor(level, preview, previewOutline, 2, OVERLAY_TAG, 2.0f)
    }
    if (returnPos != null) {
      renderReturnSpotMarker(level, returnPos, returnOutline)
    }
  }

  private fun renderReturnSpotMarker(
    level: net.minecraft.world.level.Level,
    position: Vec3,
    color: OverlayRenderEngine.Color,
  ) {
    val centerY = position.y + 0.05
    val radius = 0.7
    val segments = 28
    for (index in 0 until segments) {
      val startAngle = (Math.PI * 2.0 * index.toDouble()) / segments.toDouble()
      val endAngle = (Math.PI * 2.0 * (index + 1).toDouble()) / segments.toDouble()
      val x1 = position.x + kotlin.math.cos(startAngle) * radius
      val z1 = position.z + kotlin.math.sin(startAngle) * radius
      val x2 = position.x + kotlin.math.cos(endAngle) * radius
      val z2 = position.z + kotlin.math.sin(endAngle) * radius
      OverlayRenderEngine.addLine(
        level,
        x1,
        centerY,
        z1,
        x2,
        centerY,
        z2,
        color,
        2.0f,
        2,
        OVERLAY_TAG,
      )
    }

    OverlayRenderEngine.addLine(
      level,
      position.x,
      centerY,
      position.z,
      position.x,
      position.y + 1.45,
      position.z,
      color,
      1.8f,
      2,
      OVERLAY_TAG,
    )
  }

  private fun applyHeadRotation(
    player: Player,
    target: Vec3,
    maxSpeedScale: Float = 1f,
    accelScale: Float    = 1f,
    maxPitchStep: Float  = 6f,
    maxTurnSpeed: Float  = 100f,
    maxTurnAccel: Float  = 220f,
    snapThreshold: Float = 0f,
  ): Pair<Double, Double> {
    val targetRotation = AngleUtils.getRotation(target)
    val currentYawError = abs(AngleUtils.getRotationDelta(player.yRot, targetRotation.yaw))
    val currentPitchError = abs(targetRotation.pitch - player.xRot)
    if (snapThreshold > 0f && currentYawError <= snapThreshold && currentPitchError <= snapThreshold) {
      player.yRot = AngleUtils.normalizeAngle(targetRotation.yaw)
      player.yHeadRot = player.yRot
      player.yBodyRot = player.yRot
      player.xRot = targetRotation.pitch.coerceIn(-89.9f, 89.9f)
      return 0.0 to 0.0
    }
    val easeMode = RotationsModule.currentMiningEase()
    val yawDelta = AngleUtils.getRotationDelta(player.yRot, targetRotation.yaw)
    val yawStep  = HeadRotationModule.computeTurnDelta(
      yawDelta,
      maxSpeedScale = maxSpeedScale,
      accelScale    = accelScale,
      maxTurnSpeed  = maxTurnSpeed,
      maxTurnAccel  = maxTurnAccel,
      easeMode      = easeMode,
    )
    player.yRot     = AngleUtils.normalizeAngle(player.yRot + yawStep)
    player.yHeadRot = player.yRot
    player.yBodyRot = player.yRot

    val pitchDelta = targetRotation.pitch - player.xRot
    val pitchStep  = HeadRotationModule.computePitchDelta(
      pitchDelta,
      maxSpeedScale = maxSpeedScale,
      accelScale    = accelScale,
      maxPitchSpeed = maxPitchStep * 20f,
      maxPitchAccel = maxPitchStep * 60f,
      easeMode      = easeMode,
    )
    player.xRot = (player.xRot + pitchStep).coerceIn(-89.9f, 89.9f)

    val yawError   = abs(AngleUtils.getRotationDelta(player.yRot, targetRotation.yaw)).toDouble()
    val pitchError = abs(targetRotation.pitch - player.xRot).toDouble()
    return yawError to pitchError
  }

  private fun resetWarp() {
    mc.options.keyUse?.setDown(false)
    mc.options.keyShift?.setDown(false)
    warpStage = 0
    warpTarget = null
    warpStageTicks = 0
    frameRotTarget = null
    frameRotSnapThreshold = 0f
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

  private fun restoreEtherwarpSlot() {
    if (warpRestoreSlot in 0..8) {
      InventoryUtils.holdHotbarSlot(warpRestoreSlot)
    }
    warpRestoreSlot = -1
  }

  private fun hasLineOfSight(
    level: net.minecraft.world.level.Level,
    player: Player,
    target: BlockPos
  ): Boolean {
    val eye = player.eyePosition
    return losCheck(level, player, eye, target)
  }

  private fun blockIdAt(level: net.minecraft.world.level.Level, pos: BlockPos): String {
    val state = level.getBlockState(pos)
    return BuiltInRegistries.BLOCK.getKey(state.block).toString()
  }

  private fun isMineableTarget(
    level: net.minecraft.world.level.Level,
    player: Player,
    pos: BlockPos,
    allowedIds: Set<String>? = null,
  ): Boolean {
    val state = level.getBlockState(pos)
    if (state.isAir) return false
    val id = BuiltInRegistries.BLOCK.getKey(state.block).toString()
    if (allowedIds != null && !allowedIds.contains(id)) return false
    if (MiningBlockRegistry.isBlacklisted(id)) return false
    return state.getDestroyProgress(player, level, pos) > 0f
  }

  private fun isCrosshairOnTarget(target: BlockPos): Boolean {
    val hit = mc.hitResult
    return hit is BlockHitResult && hit.type == HitResult.Type.BLOCK && hit.blockPos == target
  }

  private fun distanceToBlockSq(player: Player, pos: BlockPos): Double {
    val dx = (pos.x + 0.5) - player.x
    val dy = (pos.y + 0.5) - player.y
    val dz = (pos.z + 0.5) - player.z
    return dx * dx + dy * dy + dz * dz
  }

  private fun isVeinOccupied(
    level: net.minecraft.world.level.Level,
    vein: Vein,
    player: Player
  ): Boolean {
    val radius = occupiedRadius.value
    val bounds = vein.bounds
    val aabb = AABB(
      bounds.minX - radius,
      bounds.minY - radius,
      bounds.minZ - radius,
      bounds.maxX + radius,
      bounds.maxY + radius,
      bounds.maxZ + radius
    )
    for (other in level.players()) {
      if (other == player) continue
      if (other.isSpectator) continue
      if (aabb.intersects(other.boundingBox)) {
        return true
      }
    }
    return false
  }

  private fun warnOnce(message: String) {
    val level = mc.level ?: return
    if (level.gameTime - lastWarnTick < 60L) return
    lastWarnTick = level.gameTime
    ChatUtils.sendMessage(message)
  }

  private fun nativeActive(): Boolean =
    NativePathfinder.status.let { it != PathStatus.IDLE && it != PathStatus.ARRIVED && it != PathStatus.FAILED }

  private fun nativeStop() {
    NativePathfinder.stop()
    MovementManager.setMovementLock(false)
  }

  private fun stopMacro(reason: String) {
    if (goldenGoblinInterruptOwnedCombat && CombatMacroModule.isActive) {
      CombatMacroModule.stopForAutomation()
    }
    if (startedPath && nativeActive()) {
      nativeStop()
    }
    startedPath = false
    lastPathStartTick = 0L
    stopMiningKeys()
    RotationExecutor.stopRotating()
    restoreEtherwarpSlot()
    resetWarp()
    currentVein = null
    veinStartAnchor = null
    currentTarget = null
    currentTargetNoLosTicks = 0
    currentDirectionalFlow = null
    lastPathTarget = null
    cachedPreviewTarget = null
    previewCacheTick = -1L
    lastPruneTick = 0L
    resetApproachTracking()
    lanternPlacedForVein = false
    lastLanternRefreshTick = -1L
    resetScanState()
    skippedSeeds.clear()
    miningOnTarget = null
    miningOnTargetTicks = 0
    frameRotSnapThreshold = 0f
    precisionRolls.clear()
    goldenGoblinInterruptActive = false
    goldenGoblinInterruptOwnedCombat = false
    goldenGoblinLastSeenTick = -1L
    goldenGoblinReturnPending = false
    goldenGoblinReturnPos = null
    automationScanAnchor = null
    automationCustomBlockIds = emptySet()
  }
}
