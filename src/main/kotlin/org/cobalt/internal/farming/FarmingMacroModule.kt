package org.cobalt.internal.farming

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.awt.Color
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.event.impl.render.WorldRenderEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.inGroup
import org.cobalt.api.module.setting.impl.ActionSetting
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.InfoSetting
import org.cobalt.api.module.setting.impl.InfoType
import org.cobalt.api.module.setting.impl.ModeSetting
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.module.setting.impl.TextSetting
import org.cobalt.api.util.ChatUtils
import org.cobalt.api.util.InventoryUtils
import org.cobalt.api.util.render.Render3D
import org.cobalt.api.util.player.MovementManager

object FarmingMacroModule : Module("Farming Macro") {

  private const val REWARP_FILE_NAME = "farming_rewarps.json"
  private const val REWARP_ARM_TICKS = 20
  private const val SUCCESSFUL_TRAVEL_RESET_TICKS = 12
  private const val WARP_ARRIVAL_DIST_SQ = 25.0
  private const val ROW_SWITCH_TIMEOUT_BUFFER_TICKS = 12

  private val mc = Minecraft.getInstance()
  private val gson = GsonBuilder().setPrettyPrinting().create()
  private val rewarpFile: File by lazy {
    File(mc.gameDirectory, "config/cobalt/$REWARP_FILE_NAME")
  }

  private val laneKeyOptions = arrayOf("A", "D", "S")
  private val rowKeyOptions = arrayOf("W", "S")
  private val strategyOptions = arrayOf("Vertical", "Sugar Cane")
  private val presetOptions = arrayOf("Custom", "Vertical Crop", "Nether Wart", "Pumpkin/Melon", "Sugar Cane")

  val isActive: Boolean get() = enabledSetting.value

  private val enabledSetting = CheckboxSetting(
    "Enabled",
    "FarmHelper-style lane farming with rewarp and stuck recovery.",
    false
  ).inGroup("Macro")

  private val infoSetting = InfoSetting(
    "Macro",
    "FarmHelper-inspired first pass: presets, row switching, anti-stuck, and rewarp points.",
    InfoType.INFO
  ).inGroup("Macro")

  private val stateInfo = InfoSetting(
    "State",
    "Idle",
    InfoType.INFO
  ).inGroup("Macro")

  private val presetSetting = ModeSetting(
    "Preset",
    "Optional FarmHelper preset. Use Apply Preset to fill pitch and strategy values.",
    0,
    presetOptions
  ).inGroup("Macro")

  private val autoApplyPresetSetting = CheckboxSetting(
    "Auto Apply Preset",
    "Apply the selected preset whenever the macro starts.",
    false
  ).inGroup("Macro")

  private val applyPresetAction = ActionSetting(
    "Apply Preset",
    "Apply the selected FarmHelper preset to this module.",
    "Apply"
  ) {
    applyPreset(presetSetting.value)
  }.inGroup("Macro")

  private val strategySetting = ModeSetting(
    "Strategy",
    "Vertical uses A/D rows with W or S row changes. Sugar Cane uses S rows with A/D switches.",
    0,
    strategyOptions
  ).inGroup("Macro")

  private val yawSetting = SliderSetting(
    "Yaw",
    "Locked camera yaw.",
    0.0,
    -180.0,
    180.0,
    step = 1.0
  ).inGroup("Macro")

  private val pitchSetting = SliderSetting(
    "Pitch",
    "Locked camera pitch.",
    70.0,
    -90.0,
    90.0,
    step = 1.0
  ).inGroup("Macro")

  private val captureRotationSetting = ActionSetting(
    "Capture Rotation",
    "Copy your current yaw and pitch into the lock settings.",
    "Capture"
  ) {
    val player = mc.player ?: return@ActionSetting
    yawSetting.value = normalizeYaw(player.yRot.toDouble())
    pitchSetting.value = player.xRot.toDouble().coerceIn(-89.0, 89.0)
  }.inGroup("Macro")

  private val holdForwardDuringRowsSetting = CheckboxSetting(
    "Hold W During Rows",
    "Vertical strategy holds W while moving along A/D crop rows.",
    true
  ).inGroup("Movement")

  private val laneKeyOneSetting = ModeSetting(
    "Lane Key 1",
    "Primary lane key.",
    0,
    laneKeyOptions
  ).inGroup("Movement")

  private val laneKeyTwoSetting = ModeSetting(
    "Lane Key 2",
    "Secondary lane key.",
    1,
    laneKeyOptions
  ).inGroup("Movement")

  private val rowSwitchKeySetting = ModeSetting(
    "Row Switch Key",
    "Vertical strategy key used to step onto the next row.",
    0,
    rowKeyOptions
  ).inGroup("Movement")

  private val rowSwitchTicksSetting = SliderSetting(
    "Row Switch Ticks",
    "Ticks to hold the row-switch key before flipping lanes. Set to 0 to keep the old instant flip.",
    3.0,
    0.0,
    20.0,
    step = 1.0
  ).inGroup("Movement")

  private val rowSwitchTravelSetting = SliderSetting(
    "Row Switch Travel",
    "Travel along the row-switch direction needed before the switch can finish.",
    0.60,
    0.0,
    2.0,
    step = 0.05
  ).inGroup("Movement")

  private val collisionTicksSetting = SliderSetting(
    "Collision Ticks",
    "Ticks of blocked travel before row switching.",
    4.0,
    1.0,
    20.0,
    step = 1.0
  ).inGroup("Recovery")

  private val switchCooldownTicksSetting = SliderSetting(
    "Switch Cooldown",
    "Ticks to wait after each lane swap.",
    10.0,
    1.0,
    30.0,
    step = 1.0
  ).inGroup("Recovery")

  private val minTravelPerTickSetting = SliderSetting(
    "Min Travel",
    "Minimum travel in the active direction before it counts as blocked.",
    0.02,
    0.0,
    0.20
  ).inGroup("Recovery")

  private val attackDuringSwitchSetting = CheckboxSetting(
    "Attack During Switch",
    "Keep breaking crops while switching rows.",
    true
  ).inGroup("Recovery")

  private val antiStuckSetting = CheckboxSetting(
    "Anti Stuck",
    "Count repeated failed row switches and rewarp when the macro stays stuck.",
    true
  ).inGroup("Recovery")

  private val antiStuckAttemptsSetting = SliderSetting(
    "Attempts Until Rewarp",
    "Consecutive failed row switches before the macro uses the warp command.",
    5.0,
    1.0,
    12.0,
    step = 1.0
  ).inGroup("Recovery")

  private val sprintSetting = CheckboxSetting(
    "Sprint",
    "Hold sprint while farming.",
    false
  ).inGroup("Recovery")

  private val autoSelectToolSetting = CheckboxSetting(
    "Auto Select Tool",
    "Select the first hotbar item whose name contains the tool text.",
    true
  ).inGroup("Tool")

  private val toolNameSetting = TextSetting(
    "Tool Name",
    "Hotbar item name fragment to select before farming.",
    "hoe"
  ).inGroup("Tool")

  private val pauseInScreensSetting = CheckboxSetting(
    "Pause In Screens",
    "Release movement and attack while a screen is open.",
    true
  ).inGroup("Tool")

  private val rewarpInfo = InfoSetting(
    "Rewarps",
    "0 points",
    InfoType.INFO
  ).inGroup("Rewarp")

  private val autoRewarpSetting = CheckboxSetting(
    "Auto Rewarp",
    "Use the warp command when you stand on a saved rewarp point.",
    true
  ).inGroup("Rewarp")

  private val warpCommandSetting = TextSetting(
    "Warp Command",
    "Command used for FarmHelper-style reset. Slash is optional.",
    "warp garden"
  ).inGroup("Rewarp")

  private val rewarpStandTicksSetting = SliderSetting(
    "Trigger Ticks",
    "Ticks spent standing on a rewarp point before warping.",
    4.0,
    1.0,
    20.0,
    step = 1.0
  ).inGroup("Rewarp")

  private val warpRetryMsSetting = SliderSetting(
    "Retry Delay",
    "Milliseconds between warp retries if the position never changes.",
    5000.0,
    1000.0,
    10000.0,
    step = 250.0
  ).inGroup("Rewarp")

  private val warpRetryLimitSetting = SliderSetting(
    "Retry Limit",
    "How many times the warp command can be retried before the macro stops.",
    3.0,
    1.0,
    10.0,
    step = 1.0
  ).inGroup("Rewarp")

  private val postWarpPauseTicksSetting = SliderSetting(
    "Post Warp Pause",
    "Ticks to wait after a successful warp before checking for blocked travel again.",
    15.0,
    0.0,
    60.0,
    step = 1.0
  ).inGroup("Rewarp")

  private val renderRewarpsSetting = CheckboxSetting(
    "Render Rewarps",
    "Draw saved rewarp points in the world.",
    true
  ).inGroup("Rewarp")

  private val addRewarpAction = ActionSetting(
    "Add Rewarp",
    "Save your current block as a rewarp point.",
    "Add"
  ) {
    addCurrentRewarpPoint()
  }.inGroup("Rewarp")

  private val removeNearestRewarpAction = ActionSetting(
    "Remove Nearest",
    "Remove the closest saved rewarp point.",
    "Remove"
  ) {
    removeNearestRewarpPoint()
  }.inGroup("Rewarp")

  private val clearRewarpAction = ActionSetting(
    "Clear Rewarps",
    "Remove every saved rewarp point.",
    "Clear"
  ) {
    rewarpPoints.clear()
    saveRewarpPoints()
    updateRewarpInfo()
    ChatUtils.sendMessage("Farming macro: cleared all rewarp points.")
  }.inGroup("Rewarp")

  private enum class MacroState {
    FARMING,
    SWITCHING_ROW,
    WAITING_FOR_WARP,
  }

  private enum class InputKey {
    FORWARD,
    BACKWARD,
    LEFT,
    RIGHT,
  }

  private var wasEnabled = false
  private var state = MacroState.FARMING
  private var activeLaneIndex = 0
  private var blockedTicks = 0
  private var switchCooldownTicks = 0
  private var activeTicks = 0
  private var stableTravelTicks = 0
  private var unstuckAttempts = 0
  private var lastX = 0.0
  private var lastZ = 0.0
  private var hasLastPos = false
  private var rowSwitchTicksRemaining = 0
  private var rowSwitchElapsedTicks = 0
  private var rowSwitchStartX = 0.0
  private var rowSwitchStartZ = 0.0
  private var rewarpStandingTicks = 0
  private var warpStartPos: Vec3? = null
  private var lastWarpAttemptMs = 0L
  private var warpRetryCount = 0

  private val rewarpPoints = mutableListOf<BlockPos>()

  init {
    addSetting(
      enabledSetting,
      infoSetting,
      stateInfo,
      presetSetting,
      autoApplyPresetSetting,
      applyPresetAction,
      strategySetting,
      yawSetting,
      pitchSetting,
      captureRotationSetting,
      holdForwardDuringRowsSetting,
      laneKeyOneSetting,
      laneKeyTwoSetting,
      rowSwitchKeySetting,
      rowSwitchTicksSetting,
      rowSwitchTravelSetting,
      collisionTicksSetting,
      switchCooldownTicksSetting,
      minTravelPerTickSetting,
      attackDuringSwitchSetting,
      antiStuckSetting,
      antiStuckAttemptsSetting,
      sprintSetting,
      autoSelectToolSetting,
      toolNameSetting,
      pauseInScreensSetting,
      rewarpInfo,
      autoRewarpSetting,
      warpCommandSetting,
      rewarpStandTicksSetting,
      warpRetryMsSetting,
      warpRetryLimitSetting,
      postWarpPauseTicksSetting,
      renderRewarpsSetting,
      addRewarpAction,
      removeNearestRewarpAction,
      clearRewarpAction,
    )

    loadRewarpPoints()
    updateRewarpInfo()
    updateStateInfo()
    EventBus.register(this)
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
    if (!enabledSetting.value) {
      if (wasEnabled) {
        stopMacro()
      }
      wasEnabled = false
      updateStateInfo()
      return
    }

    val player = mc.player
    if (player == null) {
      stopMacro()
      wasEnabled = false
      updateStateInfo()
      return
    }

    if (!wasEnabled) {
      startMacro(player)
    }
    wasEnabled = true

    val pauseForScreen = pauseInScreensSetting.value && mc.screen != null && state != MacroState.WAITING_FOR_WARP
    if (pauseForScreen) {
      releaseMovementAndAttack()
      updateStateInfo()
      return
    }

    activeTicks++
    MovementManager.setLookLock(true)
    lockRotation(player)
    maybeSelectTool()

    when (state) {
      MacroState.FARMING -> handleFarming(player)
      MacroState.SWITCHING_ROW -> handleRowSwitch(player)
      MacroState.WAITING_FOR_WARP -> handleWaitingForWarp(player)
    }

    updateStateInfo()
  }

  @SubscribeEvent
  fun onRender(event: WorldRenderEvent.Last) {
    if (!renderRewarpsSetting.value || rewarpPoints.isEmpty()) return

    val player = mc.player
    rewarpPoints.forEach { point ->
      if (player != null && player.distanceToSqr(point.x + 0.5, point.y + 0.5, point.z + 0.5) > 4096.0) {
        return@forEach
      }

      val standingOnPoint = player != null && isStandingOnRewarpPoint(player, point)
      val color = if (standingOnPoint) {
        Color(76, 255, 114, 220)
      } else {
        Color(76, 173, 208, 170)
      }
      val inflate = if (standingOnPoint) 0.08 else 0.03
      val box = AABB(
        point.x - inflate,
        point.y - inflate,
        point.z - inflate,
        point.x + 1.0 + inflate,
        point.y + 1.0 + inflate,
        point.z + 1.0 + inflate
      )
      Render3D.drawBox(event.context, box, color, true)
    }
  }

  private fun startMacro(player: LocalPlayer) {
    if (autoApplyPresetSetting.value) {
      applyPreset(presetSetting.value)
    }

    state = MacroState.FARMING
    activeLaneIndex = 0
    blockedTicks = 0
    switchCooldownTicks = 0
    activeTicks = 0
    stableTravelTicks = 0
    unstuckAttempts = 0
    rowSwitchTicksRemaining = 0
    rowSwitchElapsedTicks = 0
    rowSwitchStartX = player.x
    rowSwitchStartZ = player.z
    rewarpStandingTicks = 0
    warpStartPos = null
    lastWarpAttemptMs = 0L
    warpRetryCount = 0
    rememberPosition(player)
    MovementManager.setLookLock(true)
    MovementManager.setMovementLock(true)
    releaseMovementAndAttack()
  }

  private fun stopMacro() {
    enabledSetting.value = false
    wasEnabled = false
    state = MacroState.FARMING
    blockedTicks = 0
    switchCooldownTicks = 0
    activeTicks = 0
    stableTravelTicks = 0
    unstuckAttempts = 0
    rowSwitchTicksRemaining = 0
    rowSwitchElapsedTicks = 0
    rewarpStandingTicks = 0
    warpStartPos = null
    lastWarpAttemptMs = 0L
    warpRetryCount = 0
    hasLastPos = false
    MovementManager.setLookLock(false)
    MovementManager.setMovementLock(false)
    releaseMovementAndAttack()
  }

  private fun handleFarming(player: LocalPlayer) {
    if (handleRewarpPointTrigger(player)) {
      return
    }

    val travelKey = currentTravelInput()
    val blockedNow = isBlockedInDirection(player, travelKey)
    updateMovementHistory(player, blockedNow)

    if (switchCooldownTicks > 0) {
      switchCooldownTicks--
    }

    blockedTicks = if (blockedNow && switchCooldownTicks <= 0) blockedTicks + 1 else 0
    if (blockedTicks >= collisionTicksSetting.value.toInt().coerceAtLeast(1)) {
      beginRowSwitch(player)
      return
    }

    applyFarmingInputs()
  }

  private fun beginRowSwitch(player: LocalPlayer) {
    blockedTicks = 0
    stableTravelTicks = 0

    if (antiStuckSetting.value) {
      unstuckAttempts++
      val attemptsLimit = antiStuckAttemptsSetting.value.toInt().coerceAtLeast(1)
      if (unstuckAttempts > attemptsLimit) {
        if (tryStartWarp("Farming macro: stuck, using warp command.")) {
          return
        }
        unstuckAttempts = 0
      }
    }

    val switchTicks = rowSwitchTicksSetting.value.toInt().coerceAtLeast(0)
    if (switchTicks == 0) {
      finishRowSwitch(player)
      return
    }

    state = MacroState.SWITCHING_ROW
    rowSwitchTicksRemaining = switchTicks
    rowSwitchElapsedTicks = 0
    rowSwitchStartX = player.x
    rowSwitchStartZ = player.z
    applyRowSwitchInputs()
  }

  private fun handleRowSwitch(player: LocalPlayer) {
    if (handleRewarpPointTrigger(player)) {
      return
    }

    applyRowSwitchInputs()
    rowSwitchTicksRemaining--
    rowSwitchElapsedTicks++

    val travelNeeded = rowSwitchTravelSetting.value.coerceAtLeast(0.0)
    val travelDone = movementAlongInput(
      player.x - rowSwitchStartX,
      player.z - rowSwitchStartZ,
      currentRowSwitchInput(),
      yawSetting.value
    )
    val minSwitchTicks = rowSwitchTicksSetting.value.toInt().coerceAtLeast(0)
    val minimumHoldReached = rowSwitchElapsedTicks >= minSwitchTicks
    val travelReached = travelNeeded <= 0.0 || travelDone >= travelNeeded
    val timeoutTicks = (minSwitchTicks + ROW_SWITCH_TIMEOUT_BUFFER_TICKS).coerceAtLeast(ROW_SWITCH_TIMEOUT_BUFFER_TICKS)

    if ((minimumHoldReached && travelReached) || rowSwitchElapsedTicks >= timeoutTicks) {
      finishRowSwitch(player)
    }
  }

  private fun finishRowSwitch(player: LocalPlayer) {
    activeLaneIndex = 1 - activeLaneIndex
    state = MacroState.FARMING
    switchCooldownTicks = switchCooldownTicksSetting.value.toInt().coerceAtLeast(1)
    blockedTicks = 0
    stableTravelTicks = 0
    rowSwitchTicksRemaining = 0
    rowSwitchElapsedTicks = 0
    rewarpStandingTicks = 0
    rememberPosition(player)
  }

  private fun handleWaitingForWarp(player: LocalPlayer) {
    releaseMovementAndAttack()

    val startPos = warpStartPos
    if (startPos != null && player.position().distanceToSqr(startPos) >= WARP_ARRIVAL_DIST_SQ) {
      finishWarp(player)
      return
    }

    val retryDelayMs = warpRetryMsSetting.value.toLong().coerceAtLeast(1000L)
    val now = System.currentTimeMillis()
    if (now - lastWarpAttemptMs < retryDelayMs) {
      return
    }

    val retryLimit = warpRetryLimitSetting.value.toInt().coerceAtLeast(1)
    if (warpRetryCount >= retryLimit) {
      ChatUtils.sendMessage("Farming macro: warp retry limit reached, stopping.")
      stopMacro()
      return
    }

    if (!sendWarpCommand()) {
      ChatUtils.sendMessage("Farming macro: warp command is empty, stopping.")
      stopMacro()
      return
    }

    warpRetryCount++
    lastWarpAttemptMs = now
  }

  private fun finishWarp(player: LocalPlayer) {
    state = MacroState.FARMING
    activeLaneIndex = 0
    blockedTicks = 0
    switchCooldownTicks = postWarpPauseTicksSetting.value.toInt().coerceAtLeast(0)
    stableTravelTicks = 0
    unstuckAttempts = 0
    rowSwitchTicksRemaining = 0
    rowSwitchElapsedTicks = 0
    rewarpStandingTicks = 0
    warpStartPos = null
    lastWarpAttemptMs = 0L
    warpRetryCount = 0
    activeTicks = 0
    rememberPosition(player)
    releaseMovementAndAttack()
  }

  private fun applyFarmingInputs() {
    val inputs = linkedSetOf<InputKey>()

    when (currentStrategy()) {
      0 -> {
        val laneKey = currentLaneInput()
        inputs.add(laneKey)
        if (holdForwardDuringRowsSetting.value && laneKey != InputKey.BACKWARD) {
          inputs.add(InputKey.FORWARD)
        }
      }
      1 -> {
        inputs.add(InputKey.BACKWARD)
      }
    }

    applyInputs(inputs, attack = true)
  }

  private fun applyRowSwitchInputs() {
    val inputs = linkedSetOf(currentRowSwitchInput())
    applyInputs(inputs, attack = attackDuringSwitchSetting.value)
  }

  private fun applyInputs(inputs: Set<InputKey>, attack: Boolean) {
    val forward = InputKey.FORWARD in inputs
    val backward = InputKey.BACKWARD in inputs
    val left = InputKey.LEFT in inputs
    val right = InputKey.RIGHT in inputs
    val sprint = sprintSetting.value && inputs.isNotEmpty()

    MovementManager.setMovementLock(true)
    MovementManager.setForcedMovement(
      forward = forward,
      backward = backward,
      left = left,
      right = right,
      jump = false,
      shift = false,
      sprint = sprint
    )
    MovementManager.forcedActionsEnabled = true
    MovementManager.forcedAttack = attack
    MovementManager.forcedUse = false

    syncMovementKeys(
      forward = forward,
      backward = backward,
      left = left,
      right = right,
      sprint = sprint,
      attack = attack,
    )
  }

  private fun releaseMovementAndAttack() {
    MovementManager.clearForcedMovement()
    syncMovementKeys(
      forward = false,
      backward = false,
      left = false,
      right = false,
      sprint = false,
      attack = false,
    )
  }

  private fun syncMovementKeys(
    forward: Boolean,
    backward: Boolean,
    left: Boolean,
    right: Boolean,
    sprint: Boolean,
    attack: Boolean,
  ) {
    mc.options.keyUp.setDown(forward)
    mc.options.keyDown.setDown(backward)
    mc.options.keyLeft.setDown(left)
    mc.options.keyRight.setDown(right)
    mc.options.keySprint.setDown(sprint)
    mc.options.keyAttack.setDown(attack)
    mc.options.keyUse.setDown(false)
  }

  private fun currentStrategy(): Int {
    return strategySetting.value.coerceIn(0, strategyOptions.lastIndex)
  }

  private fun currentTravelInput(): InputKey {
    return when (currentStrategy()) {
      1 -> InputKey.BACKWARD
      else -> currentLaneInput()
    }
  }

  private fun currentRowSwitchInput(): InputKey {
    return when (currentStrategy()) {
      1 -> currentLaneInput()
      else -> rowKeyFromSetting(rowSwitchKeySetting.value)
    }
  }

  private fun currentLaneInput(): InputKey {
    val setting = if (activeLaneIndex == 0) laneKeyOneSetting else laneKeyTwoSetting
    return laneKeyFromSetting(setting.value)
  }

  private fun laneKeyFromSetting(value: Int): InputKey {
    return when (value.coerceIn(0, laneKeyOptions.lastIndex)) {
      0 -> InputKey.LEFT
      1 -> InputKey.RIGHT
      2 -> InputKey.BACKWARD
      else -> InputKey.LEFT
    }
  }

  private fun rowKeyFromSetting(value: Int): InputKey {
    return when (value.coerceIn(0, rowKeyOptions.lastIndex)) {
      1 -> InputKey.BACKWARD
      else -> InputKey.FORWARD
    }
  }

  private fun updateMovementHistory(player: LocalPlayer, blockedNow: Boolean) {
    if (blockedNow) {
      stableTravelTicks = 0
    } else {
      stableTravelTicks = (stableTravelTicks + 1).coerceAtMost(SUCCESSFUL_TRAVEL_RESET_TICKS)
      if (stableTravelTicks >= SUCCESSFUL_TRAVEL_RESET_TICKS) {
        unstuckAttempts = 0
      }
    }
    rememberPosition(player)
  }

  private fun rememberPosition(player: LocalPlayer) {
    lastX = player.x
    lastZ = player.z
    hasLastPos = true
  }

  private fun isBlockedInDirection(player: LocalPlayer, inputKey: InputKey): Boolean {
    val directionWalkable = isDirectionWalkable(player, inputKey)
    if (!hasLastPos) return player.horizontalCollision || !directionWalkable

    var blockedNow = player.horizontalCollision
    val dx = player.x - lastX
    val dz = player.z - lastZ
    val travel = movementAlongInput(dx, dz, inputKey, yawSetting.value)
    val minTravel = minTravelPerTickSetting.value.coerceAtLeast(0.0)

    if (activeTicks > 5 && !directionWalkable && travel <= minTravel && player.onGround()) {
      blockedNow = true
    }

    return blockedNow
  }

  private fun movementAlongInput(dx: Double, dz: Double, inputKey: InputKey, yawDegrees: Double): Double {
    val vector = inputVector(inputKey, yawDegrees)
    return dx * vector.first + dz * vector.second
  }

  private fun inputVector(inputKey: InputKey, yawDegrees: Double): Pair<Double, Double> {
    val yawRad = Math.toRadians(normalizeYaw(yawDegrees))
    val forwardX = -sin(yawRad)
    val forwardZ = cos(yawRad)
    val rightX = forwardZ
    val rightZ = -forwardX

    val vector = when (inputKey) {
      InputKey.FORWARD -> Pair(forwardX, forwardZ)
      InputKey.BACKWARD -> Pair(-forwardX, -forwardZ)
      InputKey.LEFT -> Pair(-rightX, -rightZ)
      InputKey.RIGHT -> Pair(rightX, rightZ)
    }

    return vector
  }

  private fun isDirectionWalkable(player: LocalPlayer, inputKey: InputKey): Boolean {
    val level = mc.level ?: return true
    val vector = inputVector(inputKey, yawSetting.value)
    val currentFeet = BlockPos.containing(player.x, player.y, player.z)

    var targetFeet = BlockPos.containing(
      player.x + vector.first * 0.85,
      player.y,
      player.z + vector.second * 0.85
    )

    if (targetFeet == currentFeet) {
      targetFeet = BlockPos.containing(
        player.x + vector.first * 1.15,
        player.y,
        player.z + vector.second * 1.15
      )
    }

    val headPos = targetFeet.above()
    val floorPos = targetFeet.below()
    return canWalkThrough(level, targetFeet) &&
      canWalkThrough(level, headPos) &&
      hasSupportBelow(level, floorPos)
  }

  private fun canWalkThrough(level: Level, pos: BlockPos): Boolean {
    val state = level.getBlockState(pos)
    if (state.isAir) return true
    if (!state.fluidState.isEmpty) return false
    return state.getCollisionShape(level, pos).isEmpty
  }

  private fun hasSupportBelow(level: Level, pos: BlockPos): Boolean {
    val state = level.getBlockState(pos)
    return !state.getCollisionShape(level, pos).isEmpty
  }

  private fun maybeSelectTool() {
    if (!autoSelectToolSetting.value) return

    val toolName = toolNameSetting.value.trim()
    if (toolName.isEmpty()) return

    val slot = InventoryUtils.findItemInHotbar(toolName)
    if (slot in 0..8) {
      InventoryUtils.holdHotbarSlot(slot)
    }
  }

  private fun lockRotation(player: LocalPlayer) {
    val yaw = normalizeYaw(yawSetting.value).toFloat()
    val pitch = pitchSetting.value.toFloat().coerceIn(-89f, 89f)

    player.setYRot(yaw)
    player.setXRot(pitch)
    player.yHeadRot = yaw
    player.yBodyRot = yaw
  }

  private fun handleRewarpPointTrigger(player: LocalPlayer): Boolean {
    if (!autoRewarpSetting.value || activeTicks < REWARP_ARM_TICKS || rewarpPoints.isEmpty()) {
      rewarpStandingTicks = 0
      return false
    }

    val standing = rewarpPoints.any { isStandingOnRewarpPoint(player, it) }
    if (!standing) {
      rewarpStandingTicks = 0
      return false
    }

    rewarpStandingTicks++
    val triggerTicks = rewarpStandTicksSetting.value.toInt().coerceAtLeast(1)
    if (rewarpStandingTicks < triggerTicks) {
      return false
    }

    return tryStartWarp("Farming macro: standing on rewarp point, warping.")
  }

  private fun isStandingOnRewarpPoint(player: LocalPlayer, point: BlockPos): Boolean {
    val centerX = point.x + 0.5
    val centerZ = point.z + 0.5
    val dx = player.x - centerX
    val dz = player.z - centerZ
    val dy = abs(player.y - point.y.toDouble())
    return dx * dx + dz * dz <= 0.81 && dy <= 1.5
  }

  private fun tryStartWarp(message: String): Boolean {
    if (state == MacroState.WAITING_FOR_WARP) return true
    if (!sendWarpCommand()) return false

    state = MacroState.WAITING_FOR_WARP
    warpStartPos = mc.player?.position()
    lastWarpAttemptMs = System.currentTimeMillis()
    warpRetryCount = 0
    rewarpStandingTicks = 0
    releaseMovementAndAttack()
    ChatUtils.sendMessage(message)
    return true
  }

  private fun sendWarpCommand(): Boolean {
    val trimmed = warpCommandSetting.value.trim().removePrefix("/")
    if (trimmed.isEmpty()) {
      return false
    }
    mc.player?.connection?.sendCommand(trimmed)
    return true
  }

  private fun addCurrentRewarpPoint() {
    val player = mc.player ?: return
    val point = player.blockPosition()
    if (rewarpPoints.any { it == point }) {
      ChatUtils.sendMessage("Farming macro: that rewarp point already exists.")
      return
    }
    rewarpPoints.add(point)
    saveRewarpPoints()
    updateRewarpInfo()
    ChatUtils.sendMessage("Farming macro: added rewarp at ${point.x} ${point.y} ${point.z}.")
  }

  private fun removeNearestRewarpPoint() {
    val player = mc.player ?: return
    val nearest = rewarpPoints.minByOrNull { player.distanceToSqr(it.x + 0.5, it.y + 0.5, it.z + 0.5) }
    if (nearest == null) {
      ChatUtils.sendMessage("Farming macro: no rewarp points saved.")
      return
    }
    rewarpPoints.remove(nearest)
    saveRewarpPoints()
    updateRewarpInfo()
    ChatUtils.sendMessage("Farming macro: removed rewarp at ${nearest.x} ${nearest.y} ${nearest.z}.")
  }

  private fun loadRewarpPoints() {
    rewarpPoints.clear()
    if (!rewarpFile.exists()) return

    val text = runCatching { rewarpFile.readText() }.getOrNull()?.trim().orEmpty()
    if (text.isEmpty()) return

    val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return
    val points = root.getAsJsonArray("points") ?: return
    points.forEach { element ->
      val obj = runCatching { element.asJsonObject }.getOrNull() ?: return@forEach
      val x = obj["x"]?.asInt ?: return@forEach
      val y = obj["y"]?.asInt ?: return@forEach
      val z = obj["z"]?.asInt ?: return@forEach
      rewarpPoints.add(BlockPos(x, y, z))
    }
  }

  private fun saveRewarpPoints() {
    if (!rewarpFile.parentFile.exists()) {
      rewarpFile.parentFile.mkdirs()
    }

    val root = JsonObject()
    val points = JsonArray()
    rewarpPoints.forEach { point ->
      val obj = JsonObject()
      obj.addProperty("x", point.x)
      obj.addProperty("y", point.y)
      obj.addProperty("z", point.z)
      points.add(obj)
    }
    root.add("points", points)
    rewarpFile.writeText(gson.toJson(root))
  }

  private fun updateRewarpInfo() {
    rewarpInfo.value = "${rewarpPoints.size} points"
  }

  private fun updateStateInfo() {
    stateInfo.value = when {
      !enabledSetting.value -> "Idle"
      state == MacroState.WAITING_FOR_WARP -> "Warping (${warpRetryCount}/${warpRetryLimitSetting.value.toInt()})"
      state == MacroState.SWITCHING_ROW -> "Switching Row"
      currentStrategy() == 1 -> "Farming (${currentLaneLabel()})"
      else -> "Farming (${currentLaneLabel()} + ${if (holdForwardDuringRowsSetting.value) "W" else "-"})"
    }
  }

  private fun currentLaneLabel(): String {
    return if (activeLaneIndex == 0) {
      laneKeyOptions[laneKeyOneSetting.value.coerceIn(0, laneKeyOptions.lastIndex)]
    } else {
      laneKeyOptions[laneKeyTwoSetting.value.coerceIn(0, laneKeyOptions.lastIndex)]
    }
  }

  private fun applyPreset(presetIndex: Int) {
    when (presetIndex.coerceIn(0, presetOptions.lastIndex)) {
      1 -> {
        strategySetting.value = 0
        pitchSetting.value = 3.0
        laneKeyOneSetting.value = 0
        laneKeyTwoSetting.value = 1
        rowSwitchKeySetting.value = 0
        holdForwardDuringRowsSetting.value = true
        rowSwitchTicksSetting.value = 3.0
        rowSwitchTravelSetting.value = 0.60
        minTravelPerTickSetting.value = 0.02
      }
      2 -> {
        strategySetting.value = 0
        pitchSetting.value = 0.0
        laneKeyOneSetting.value = 0
        laneKeyTwoSetting.value = 1
        rowSwitchKeySetting.value = 0
        holdForwardDuringRowsSetting.value = true
        rowSwitchTicksSetting.value = 3.0
        rowSwitchTravelSetting.value = 0.60
        minTravelPerTickSetting.value = 0.02
      }
      3 -> {
        strategySetting.value = 0
        pitchSetting.value = 29.0
        laneKeyOneSetting.value = 0
        laneKeyTwoSetting.value = 1
        rowSwitchKeySetting.value = 0
        holdForwardDuringRowsSetting.value = true
        rowSwitchTicksSetting.value = 4.0
        rowSwitchTravelSetting.value = 0.75
        minTravelPerTickSetting.value = 0.02
      }
      4 -> {
        strategySetting.value = 1
        pitchSetting.value = 0.0
        laneKeyOneSetting.value = 0
        laneKeyTwoSetting.value = 1
        holdForwardDuringRowsSetting.value = false
        rowSwitchTicksSetting.value = 4.0
        rowSwitchTravelSetting.value = 0.70
        minTravelPerTickSetting.value = 0.015
      }
    }
  }

  private fun normalizeYaw(yaw: Double): Double {
    var result = yaw
    while (result > 180.0) result -= 360.0
    while (result <= -180.0) result += 360.0
    return if (abs(result) < 1.0e-6) 0.0 else result
  }
}
