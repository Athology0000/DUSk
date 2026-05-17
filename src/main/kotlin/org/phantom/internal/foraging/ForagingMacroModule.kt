package org.phantom.internal.foraging

import kotlin.math.atan2
import kotlin.math.sqrt
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.phantom.api.event.EventBus
import org.phantom.api.event.annotation.SubscribeEvent
import org.phantom.api.event.impl.client.BlockChangeEvent
import org.phantom.api.event.impl.client.PacketEvent
import org.phantom.api.event.impl.client.TickEvent
import org.phantom.api.module.Module
import org.phantom.api.module.ModuleCategory
import org.phantom.api.module.setting.inGroup
import org.phantom.api.module.setting.impl.ActionSetting
import org.phantom.api.module.setting.impl.CheckboxSetting
import org.phantom.api.module.setting.impl.InfoSetting
import org.phantom.api.module.setting.impl.InfoType
import org.phantom.api.module.setting.impl.KeyBindSetting
import org.phantom.api.module.setting.impl.ModeSetting
import org.phantom.api.module.setting.impl.SliderSetting
import org.phantom.api.pathfinder.jni.NativePathfinder
import org.phantom.api.pathfinder.jni.PathExecutorState
import org.phantom.api.pathfinder.jni.PathStatus
import org.phantom.api.util.ChatUtils
import org.phantom.api.util.InventoryUtils
import org.phantom.api.util.helper.KeyBind
import org.phantom.api.util.player.MovementManager
import org.phantom.internal.rotation.PhantomRotation
import org.lwjgl.glfw.GLFW
import org.phantom.mixin.client.MinecraftAccessor

object ForagingMacroModule : Module("Foraging Macro") {

  override val category = ModuleCategory.FARMING
  override val isMacro = true
  override val autoDisableOnWorldUnload = true

  private val mc = Minecraft.getInstance()

  private val treeOptions = arrayOf(
    "Oak",
    "Birch",
    "Spruce",
    "Fig",
    "Jungle",
    "Acacia",
    "Dark Oak",
    "Mangrove",
    "Cherry",
  )

  private val enabled = CheckboxSetting(
    "Enabled",
    "Find nearby logs, walk toward them, and chop them.",
    false,
  ).inGroup("Macro")

  private val toggleKeybind = KeyBindSetting(
    "Toggle Keybind",
    "Key to start or stop the foraging macro.",
    KeyBind(-1),
  ).inGroup("Macro")

  private val stateInfo = InfoSetting(
    "State",
    "Idle",
    InfoType.INFO,
  ).inGroup("Macro")

  private val treeType = ModeSetting(
    "Tree Type",
    "Log block type to target. Fig maps to stripped spruce logs.",
    0,
    treeOptions,
  ).inGroup("Targets")

  private val axeSlot = SliderSetting(
    "Axe Slot",
    "Hotbar slot containing your axe.",
    1.0,
    1.0,
    9.0,
    step = 1.0,
  ).inGroup("Tool")

  private val scanRadius = SliderSetting(
    "Scan Radius",
    "Horizontal radius used to find logs.",
    32.0,
    8.0,
    80.0,
    step = 1.0,
  ).inGroup("Targets")

  private val scanVertical = SliderSetting(
    "Scan Vertical",
    "Vertical range above and below the player.",
    16.0,
    4.0,
    40.0,
    step = 1.0,
  ).inGroup("Targets")

  private val chopRange = SliderSetting(
    "Chop Range",
    "Maximum distance for chopping a visible log.",
    4.5,
    2.5,
    6.0,
  ).inGroup("Mining")

  private val walkStopRange = SliderSetting(
    "Walk Stop Range",
    "Stop walking and start chopping when this close to the nearest log.",
    0.65,
    0.5,
    6.0,
  ).inGroup("Movement")

  private val oakAction = startAction("Start Oak", 0, VANILLA_LOG_GROUP)
  private val birchAction = startAction("Start Birch", 1, VANILLA_LOG_GROUP)
  private val spruceAction = startAction("Start Spruce", 2, VANILLA_LOG_GROUP)
  private val figAction = startAction("Start Fig", 3, PRESSURE_LOG_GROUP)
  private val jungleAction = startAction("Start Jungle", 4, VANILLA_LOG_GROUP)
  private val acaciaAction = startAction("Start Acacia", 5, VANILLA_LOG_GROUP)
  private val darkOakAction = startAction("Start Dark Oak", 6, VANILLA_LOG_GROUP)
  private val mangroveAction = startAction("Start Mangrove", 7, PRESSURE_LOG_GROUP)
  private val cherryAction = startAction("Start Cherry", 8, VANILLA_LOG_GROUP)
  private val stopAction = ActionSetting(
    "Stop",
    "Stop the foraging macro.",
    "Stop",
  ) { stopMacro("Stopped") }.inGroup("Macro")

  private enum class State {
    IDLE,
    SCANNING,
    WALKING,
    CHOPPING,
  }

  private data class MineRay(
    val hit: BlockHitResult,
    val aim: Vec3,
  )

  private var wasEnabled = false
  private var state = State.IDLE
  private var currentTree = linkedSetOf<BlockPos>()
  private var currentTarget: BlockPos? = null
  private var lastScanTick = -100L
  private var alignedTicks = 0
  private var escapeWasDown = false
  private var toggleWasDown = false
  private var lookTarget: BlockPos? = null
  private var lookYaw = 0f
  private var lookPitch = 0f
  private var failureCount = 0
  private var walkingTicks = 0
  private var noProgressTicks = 0
  private var lastWalkDistance = Double.POSITIVE_INFINITY
  private var pathStarted = false
  private var noPathCommandTicks = 0
  private var pendingFailureReason: String? = null
  private var pendingFailureStartedAtMs = 0L
  private var clickedTarget: BlockPos? = null
  private var clickHoldStartedAtMs = 0L
  private var lastMineClickAtMs = 0L
  private var lastAxeThrowAtMs = 0L
  private var pressureProgressSeen = false
  private var lockedMineYaw = 0f
  private var lockedMinePitch = 0f
  private var mineBlockedTicks = 0
  private var pathAttemptStartedAtMs = 0L
  private val ignoredTargets = HashMap<BlockPos, Long>()
  private val regeneratingTargets = HashMap<BlockPos, Long>()

  init {
    addSetting(
      enabled,
      toggleKeybind,
      stateInfo,
      treeType,
      oakAction,
      birchAction,
      spruceAction,
      jungleAction,
      acaciaAction,
      darkOakAction,
      cherryAction,
      figAction,
      mangroveAction,
      stopAction,
      axeSlot,
      scanRadius,
      scanVertical,
      chopRange,
      walkStopRange,
    )
    EventBus.register(this)
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
    if (consumeToggleKeybind()) {
      if (enabled.value) {
        stopMacro("Stopped")
      } else {
        enabled.value = true
        wasEnabled = false
        state = State.SCANNING
        currentTree.clear()
        currentTarget = null
        failureCount = 0
        resetProgressTracking()
      }
    }

    if (!enabled.value) {
      if (wasEnabled) stopMacro("Idle", updateEnabled = false)
      wasEnabled = false
      updateStateInfo("Idle")
      return
    }

    if (consumeEscapeStop()) {
      stopMacro("Stopped")
      return
    }

    val player = mc.player
    val level = mc.level
    if (player == null || level == null) {
      releaseControls()
      updateStateInfo("Paused")
      return
    }

    if (!wasEnabled) {
      wasEnabled = true
      state = State.SCANNING
      resetRotationTarget()
      failureCount = 0
      resetProgressTracking()
      ChatUtils.sendMessage("Foraging macro: started ${treeOptions[currentTreeIndex()]}.")
    }

    selectAxeSlot()

    when (state) {
      State.IDLE,
      State.SCANNING -> scanForTree(level, player)
      State.WALKING -> walkToTree(level, player)
      State.CHOPPING -> chopTree(level, player)
    }

    updateStateInfo()
  }

  @SubscribeEvent
  fun onBlockChange(event: BlockChangeEvent) {
    if (!enabled.value || currentTree.isEmpty()) return
    val level = mc.level ?: return
    if (!isTargetLog(level, event.pos)) {
      currentTree.remove(event.pos)
      if (currentTarget == event.pos) {
        finishCurrentTargetAndRestartPath()
      }
    }
  }

  @SubscribeEvent
  fun onPacket(event: PacketEvent.Incoming) {
    val packet = event.packet as? ClientboundLevelParticlesPacket ?: return
    val level = mc.level ?: return
    val particlePos = BlockPos.containing(packet.x, packet.y, packet.z)
    val seed = nearestTargetLogNear(level, particlePos, REGEN_PARTICLE_SCAN_RADIUS) ?: return
    if (currentTree.contains(seed)) return
    val expireAt = level.gameTime + REGEN_IGNORE_TICKS
    collectConnectedLogs(level, seed, mutableSetOf()).forEach { regeneratingTargets[it.immutable()] = expireAt }
  }

  private fun startAction(name: String, index: Int, group: String): ActionSetting =
    ActionSetting(
      name,
      "Start the macro targeting ${treeOptions[index]} logs.",
      "Start",
    ) {
      treeType.value = index
      enabled.value = true
      wasEnabled = false
      state = State.SCANNING
      currentTree.clear()
      currentTarget = null
      failureCount = 0
      resetProgressTracking()
    }.inGroup(group)

  private fun scanForTree(level: Level, player: LocalPlayer) {
    state = State.SCANNING
    releaseControls()
    pruneIgnoredTargets(level)
    pruneRegeneratingTargets(level)

    val now = level.gameTime
    if (now - lastScanTick < 10L) return
    lastScanTick = now

    val tree = findNearestTree(level, player)
    if (tree.isEmpty()) {
      updateStateInfo("Scanning (${treeOptions[currentTreeIndex()]})")
      return
    }
    val result = NativePathfinder.walkToNearestBlock(
      blockIds = targetBlockIds(),
      scanRadius = scanRadius.value.toInt().coerceAtLeast(1),
      scanVertical = scanVertical.value.toInt().coerceAtLeast(1),
      reachableDistance = chopRange.value,
      arrivalRadius = walkStopRange.value.coerceIn(0.5, 1.25),
      maxGoals = 32,
      excludedBlocks = excludedTargets(),
    )
    if (!result.started || result.targetBlock == null) {
      restartAfterFailure(result.reason)
      return
    }
    clearPendingFailure()

    currentTree = collectConnectedLogs(level, result.targetBlock, mutableSetOf()).toCollection(LinkedHashSet())
    if (currentTree.isEmpty()) {
      currentTree = tree.toCollection(LinkedHashSet())
    }
    currentTarget = result.targetBlock
    alignedTicks = 0
    lookTarget = null
    pathStarted = true
    pathAttemptStartedAtMs = System.currentTimeMillis()
    resetProgressTracking()
    state = State.WALKING
  }

  private fun walkToTree(level: Level, player: LocalPlayer) {
    pruneTree(level)
    if (currentTree.isEmpty()) {
      restartAfterFailure("tree disappeared while walking")
      return
    }

    val locked = currentTarget?.takeIf { currentTree.contains(it) }
    val visible = locked?.takeIf { canSeeAndReach(level, player, it) }
    val nearest = locked ?: chooseNearestLog(player)
    if (nearest == null) {
      restartAfterFailure("no walk target")
      return
    }

    currentTarget = nearest
    val rotationError = smoothLookAt(player, nearest)

    val distance = distanceTo(player, nearest)
    updateProgress(distance)
    val command = NativePathfinder.tick()
    if (pathStarted &&
      NativePathfinder.cachedPathNodes.isEmpty() &&
      System.currentTimeMillis() - pathAttemptStartedAtMs >= PATH_RESTART_MS
    ) {
      cycleMacro("path watchdog")
      return
    }
    if (NativePathfinder.status == PathStatus.FAILED) {
      if (System.currentTimeMillis() - pathAttemptStartedAtMs >= PATH_RESTART_MS) {
        cycleMacro("native path failed")
      }
      return
    }
    if (noProgressTicks >= NO_PROGRESS_FAIL_TICKS && distance > walkStopRange.value + 0.75) {
      restartAfterFailure("no walking progress")
      return
    }

    val closeEnoughToChop = !isPressureTree() || distance <= walkStopRange.value + 0.75 || NativePathfinder.status == PathStatus.ARRIVED
    if ((visible != null && closeEnoughToChop) || NativePathfinder.status == PathStatus.ARRIVED) {
      val chopTarget = visible
      if (chopTarget == null) {
        replanForCurrentTree(level, player, "arrived without mineable log")
        return
      }
      currentTarget = chopTarget
      smoothLookAt(level, player, chopTarget)
      if (!isPressureTree()) NativePathfinder.stop()
      pathStarted = false
      if (!isPressureTree()) stopWalking()
      resetProgressTracking()
      state = State.CHOPPING
      return
    }

    alignedTicks = if (rotationError <= 18f) alignedTicks + 1 else 0
    if (command != null) {
      PathExecutorState.forwardYawTolerance = 180.0
      PathExecutorState.disablePrecisionSneak = true
      command.applyToPlayer(applyRotation = false)
      MovementManager.forcedShift = false
      noPathCommandTicks = 0
      clearPendingFailure()
      syncClientKeys(
        forward = MovementManager.forcedForward,
        backward = MovementManager.forcedBackward,
        left = MovementManager.forcedLeft,
        right = MovementManager.forcedRight,
        jump = MovementManager.forcedJump,
        shift = false,
        sprint = MovementManager.forcedSprint,
        attack = false,
      )
    } else {
      syncClientKeys(false, false, false, false, false, false, false, false)
      if (NativePathfinder.status == PathStatus.PLANNING) {
        if (System.currentTimeMillis() - pathAttemptStartedAtMs >= PATH_RESTART_MS) {
          cycleMacro("path planning timeout")
        } else {
          noPathCommandTicks = 0
        }
      } else if (pathStarted && ++noPathCommandTicks >= NO_PATH_COMMAND_FAIL_TICKS) {
        cycleMacro("native path produced no movement")
      }
    }
  }

  private fun chopTree(level: Level, player: LocalPlayer) {
    val heldTarget = clickedTarget
    if (heldTarget != null) {
      continueClickHold(level)
      return
    }

    pruneTree(level)
    if (currentTree.isEmpty()) {
      releaseControls()
      failureCount = 0
      resetProgressTracking()
      state = State.SCANNING
      return
    }

    val target = currentTarget
      ?.takeIf { currentTree.contains(it) && canMineCurrentTreeLog(level, player, it) }

    if (target == null) {
      mineBlockedTicks++
      if (mineBlockedTicks >= MINE_BLOCKED_REPATH_TICKS) {
        replanForCurrentTree(level, player, "no visible chop target")
      } else {
        stopWalking()
        currentTarget = chooseNearestLog(player)
        state = State.WALKING
      }
      return
    }

    mineBlockedTicks = 0
    clearPendingFailure()
    currentTarget = target
    val rotationError = smoothLookAt(level, player, target)
    val readyToBreak = rotationError <= BREAK_ROTATION_TOLERANCE_DEGREES
    if (readyToBreak) holdClickThenCycle(level, player, target) else releaseAttackHold()
    MovementManager.setLookLock(true)
    applyMovement(
      forward = false,
      backward = false,
      left = false,
      right = false,
      jump = isPressureTree(),
      shift = false,
      sprint = false,
      attack = readyToBreak,
    )
  }

  private fun findNearestTree(level: Level, player: LocalPlayer): Set<BlockPos> {
    val radius = scanRadius.value.toInt().coerceAtLeast(1)
    val vertical = scanVertical.value.toInt().coerceAtLeast(1)
    val origin = player.blockPosition()
    val visited = mutableSetOf<BlockPos>()
    var best: Set<BlockPos> = emptySet()
    var bestDist = Double.POSITIVE_INFINITY

    for (pos in BlockPos.betweenClosed(origin.offset(-radius, -vertical, -radius), origin.offset(radius, vertical, radius))) {
      val immutable = pos.immutable()
      if (immutable in visited || !isTargetLog(level, immutable)) continue
      if (immutable in ignoredTargets) continue
      if (immutable in regeneratingTargets) continue
      val tree = collectConnectedLogs(level, immutable, visited)
      if (tree.isEmpty()) continue
      if (tree.any { it in regeneratingTargets }) continue
      if (tree.none { isTrunkLevelReachable(player, it) }) continue

      val nearestDist = tree.minOf { player.distanceToSqr(it.x + 0.5, it.y + 0.5, it.z + 0.5) }
      if (nearestDist < bestDist) {
        bestDist = nearestDist
        best = tree
      }
    }

    return best
  }

  private fun collectConnectedLogs(level: Level, seed: BlockPos, globalVisited: MutableSet<BlockPos>): Set<BlockPos> {
    val result = linkedSetOf<BlockPos>()
    val queue = ArrayDeque<BlockPos>()
    queue.add(seed.immutable())
    globalVisited.add(seed.immutable())

    while (queue.isNotEmpty() && result.size < MAX_TREE_LOGS) {
      val pos = queue.removeFirst()
      if (!isTargetLog(level, pos) || pos in ignoredTargets) continue
      if (pos in regeneratingTargets) continue
      result.add(pos)

      for (direction in Direction.values()) {
        val next = pos.relative(direction).immutable()
        if (globalVisited.add(next)) queue.add(next)
      }
    }

    return result
  }

  private fun chooseNearestLog(player: LocalPlayer): BlockPos? =
    currentTree
      .filter { isTrunkLevelReachable(player, it) }
      .minByOrNull { player.distanceToSqr(it.x + 0.5, it.y + 0.5, it.z + 0.5) }

  private fun chooseVisibleReachableLog(level: Level, player: LocalPlayer): BlockPos? =
    currentTree
      .filter { canSeeAndReach(level, player, it) }
      .minWithOrNull(
        compareBy<BlockPos> { player.distanceToSqr(it.x + 0.5, it.y + 0.5, it.z + 0.5) }
          .thenBy { it.y }
      )

  private fun canSeeAndReach(level: Level, player: LocalPlayer, pos: BlockPos): Boolean {
    if (!isTrunkLevelReachable(player, pos)) return false
    return rayHitBlock(level, player, pos) != null
  }

  private fun canMineCurrentTreeLog(level: Level, player: LocalPlayer, pos: BlockPos): Boolean {
    if (!isPressureTree()) return canSeeAndReach(level, player, pos)
    return rayHitBlock(level, player, pos, trunkOnly = false) != null
  }

  private fun rayHitBlock(level: Level, player: LocalPlayer, pos: BlockPos): MineRay? {
    return rayHitBlock(level, player, pos, trunkOnly = true)
  }

  private fun rayHitBlock(level: Level, player: LocalPlayer, pos: BlockPos, trunkOnly: Boolean): MineRay? {
    if (trunkOnly && !isTrunkLevelReachable(player, pos)) return null
    val eye = player.eyePosition
    val maxDistanceSq = chopRange.value * chopRange.value
    for (target in eyeLevelAimPoints(pos)) {
      if (eye.distanceToSqr(target) > maxDistanceSq) continue
      val hit = level.clip(
        ClipContext(
          eye,
          target,
          ClipContext.Block.COLLIDER,
          ClipContext.Fluid.NONE,
          player,
        )
      )
      val blockHit = hit as? BlockHitResult
      if (blockHit != null && blockHit.type == HitResult.Type.BLOCK && blockHit.blockPos == pos) {
        return MineRay(blockHit, target)
      }
    }
    return null
  }

  private fun isTrunkLevelReachable(player: LocalPlayer, pos: BlockPos): Boolean {
    val feetY = player.blockPosition().y
    return pos.y >= feetY && pos.y <= feetY + 1
  }

  private fun eyeLevelAimPoints(pos: BlockPos): List<Vec3> {
    val x = pos.x.toDouble()
    val y = pos.y.toDouble()
    val z = pos.z.toDouble()
    return listOf(
      Vec3(x + 0.5, y + 0.5, z + 0.5),
      Vec3(x + 0.5, y - 0.5, z + 0.5),
      Vec3(x + 0.5, y + 1.5, z + 0.5),
    )
  }

  private fun pruneTree(level: Level) {
    currentTree.removeIf { !isTargetLog(level, it) }
    if (currentTarget != null && currentTarget !in currentTree) currentTarget = null
  }

  private fun isTargetLog(level: Level, pos: BlockPos): Boolean {
    val state = level.getBlockState(pos)
    if (state.isAir) return false
    val id = BuiltInRegistries.BLOCK.getKey(state.block).toString()
    return id in targetBlockIds()
  }

  private fun nearestTargetLogNear(level: Level, center: BlockPos, radius: Int): BlockPos? {
    var best: BlockPos? = null
    var bestDist = Double.POSITIVE_INFINITY
    for (pos in BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
      val immutable = pos.immutable()
      if (!isTargetLog(level, immutable)) continue
      val dist = immutable.distSqr(center)
      if (dist < bestDist) {
        bestDist = dist
        best = immutable
      }
    }
    return best
  }

  private fun targetBlockIds(): Set<String> =
    when (currentTreeIndex()) {
      1 -> setOf("minecraft:birch_log", "minecraft:birch_wood", "minecraft:stripped_birch_log", "minecraft:stripped_birch_wood")
      2 -> setOf("minecraft:spruce_log", "minecraft:spruce_wood", "minecraft:stripped_spruce_log", "minecraft:stripped_spruce_wood")
      3 -> setOf("minecraft:stripped_spruce_log", "minecraft:stripped_spruce_wood")
      4 -> setOf("minecraft:jungle_log", "minecraft:jungle_wood", "minecraft:stripped_jungle_log", "minecraft:stripped_jungle_wood")
      5 -> setOf("minecraft:acacia_log", "minecraft:acacia_wood", "minecraft:stripped_acacia_log", "minecraft:stripped_acacia_wood")
      6 -> setOf("minecraft:dark_oak_log", "minecraft:dark_oak_wood", "minecraft:stripped_dark_oak_log", "minecraft:stripped_dark_oak_wood")
      7 -> setOf(
        "minecraft:mangrove_log",
        "minecraft:mangrove_wood",
        "minecraft:stripped_mangrove_log",
        "minecraft:stripped_mangrove_wood",
        "minecraft:mangrove_roots",
      )
      8 -> setOf("minecraft:cherry_log", "minecraft:cherry_wood", "minecraft:stripped_cherry_log", "minecraft:stripped_cherry_wood")
      else -> setOf("minecraft:oak_log", "minecraft:oak_wood", "minecraft:stripped_oak_log", "minecraft:stripped_oak_wood")
    }

  private fun currentTreeIndex(): Int =
    treeType.value.coerceIn(0, treeOptions.lastIndex)

  private fun isPressureTree(): Boolean =
    currentTreeIndex() == 3 || currentTreeIndex() == 7

  private fun selectAxeSlot() {
    InventoryUtils.holdHotbarSlot(axeSlot.value.toInt().coerceIn(1, 9) - 1)
  }

  private fun rotateTo(player: LocalPlayer, pos: BlockPos) {
    smoothLookAt(player, pos)
  }

  private fun smoothLookAt(player: LocalPlayer, pos: BlockPos): Float {
    return smoothLookAt(null, player, pos)
  }

  private fun smoothLookAt(level: Level?, player: LocalPlayer, pos: BlockPos): Float {
    val eye = player.eyePosition
    val target = level?.let { rayHitBlock(it, player, pos)?.aim } ?: center(pos)
    val dx = target.x - eye.x
    val dy = target.y - eye.y
    val dz = target.z - eye.z
    val horizontal = sqrt(dx * dx + dz * dz)
    val targetYaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
    val targetPitch = Math.toDegrees(-atan2(dy, horizontal)).toFloat().coerceIn(-89f, 89f)

    if (lookTarget != pos) lookTarget = pos.immutable()
    lookYaw = targetYaw
    lookPitch = targetPitch

    val yawDelta = wrapDegrees(lookYaw - player.yRot)
    val pitchDelta = lookPitch - player.xRot
    val error = sqrt((yawDelta * yawDelta + pitchDelta * pitchDelta).toDouble()).toFloat()

    PhantomRotation.blockController.smoothingRate = 7.5
    PhantomRotation.blockController.setDirectTarget(lookYaw, lookPitch)
    return error
  }

  private fun wrapDegrees(value: Float): Float {
    var result = value
    while (result <= -180f) result += 360f
    while (result > 180f) result -= 360f
    return result
  }

  private fun resetRotationTarget() {
    alignedTicks = 0
    lookTarget = null
  }

  private fun distanceTo(player: LocalPlayer, pos: BlockPos): Double =
    sqrt(player.distanceToSqr(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5))

  private fun center(pos: BlockPos): Vec3 =
    Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)

  private fun stopMacro(label: String, updateEnabled: Boolean = true, clearIgnoredTargets: Boolean = true) {
    if (updateEnabled) enabled.value = false
    wasEnabled = false
    state = State.IDLE
    currentTree.clear()
    currentTarget = null
    if (clearIgnoredTargets) ignoredTargets.clear()
    alignedTicks = 0
    lookTarget = null
    resetProgressTracking()
    pathStarted = false
    noPathCommandTicks = 0
    clickedTarget = null
    clickHoldStartedAtMs = 0L
    lastMineClickAtMs = 0L
    lastAxeThrowAtMs = 0L
    pressureProgressSeen = false
    lockedMineYaw = 0f
    lockedMinePitch = 0f
    mineBlockedTicks = 0
    clearPendingFailure()
    if (updateEnabled) failureCount = 0
    NativePathfinder.stop()
    PathExecutorState.disablePrecisionSneak = false
    PhantomRotation.blockController.cancel()
    releaseControls()
    updateStateInfo(label)
  }

  private fun stopWalking() {
    MovementManager.clearForcedMovement()
    MovementManager.forcedAttack = false
    MovementManager.forcedUse = false
    syncClientKeys(false, false, false, false, false, false, false, false)
    mc.options.keyAttack.setDown(false)
  }

  private fun releaseControls() {
    PhantomRotation.blockController.cancel()
    MovementManager.clearForcedMovement()
    MovementManager.setLookLock(false)
    MovementManager.forcedActionsEnabled = false
    MovementManager.forcedAttack = false
    MovementManager.forcedUse = false
    clickedTarget = null
    clickHoldStartedAtMs = 0L
    lastMineClickAtMs = 0L
    lastAxeThrowAtMs = 0L
    pressureProgressSeen = false
    lockedMineYaw = 0f
    lockedMinePitch = 0f
    mineBlockedTicks = 0
    syncClientKeys(false, false, false, false, false, false, false, false)
  }

  private fun holdClickThenCycle(level: Level, @Suppress("UNUSED_PARAMETER") player: LocalPlayer, target: BlockPos) {
    val localPlayer = mc.player ?: return
    rayHitBlock(level, localPlayer, target) ?: return
    val now = System.currentTimeMillis()
    if (clickedTarget != target) {
      clickedTarget = target.immutable()
      clickHoldStartedAtMs = now
      lastMineClickAtMs = now
      lastAxeThrowAtMs = 0L
      pressureProgressSeen = false
      lockedMineYaw = localPlayer.yRot
      lockedMinePitch = localPlayer.xRot
      mc.options.keyAttack.setDown(false)
      mc.options.keyAttack.setDown(true)
      (mc as? MinecraftAccessor)?.leftClick()
    }
    MovementManager.forcedActionsEnabled = true
    MovementManager.forcedAttack = true
    MovementManager.forcedUse = false
    mc.options.keyUse.setDown(false)
    mc.options.keyAttack.setDown(true)
    continueClickHold(level)
  }

  private fun continueClickHold(@Suppress("UNUSED_PARAMETER") level: Level) {
    val startedAt = clickHoldStartedAtMs
    val now = System.currentTimeMillis()
    if (startedAt <= 0L) {
      releaseAttackHold()
      cycleMacro("target clicked")
      return
    }
    val player = mc.player
    val pressureJump = player != null && clickedTarget?.let { shouldJumpForPressureTarget(player, it) } == true
    if (isPressureTree()) {
      NativePathfinder.tick()?.let { command ->
        PathExecutorState.forwardYawTolerance = 180.0
        PathExecutorState.disablePrecisionSneak = true
        command.applyToPlayer(applyRotation = false)
      }
      MovementManager.forcedJump = MovementManager.forcedJump || pressureJump
      MovementManager.forcedShift = false
    } else {
      MovementManager.setMovementLock(true)
      MovementManager.setForcedMovement(
        forward = false,
        backward = false,
        left = false,
        right = false,
        jump = false,
        shift = false,
        sprint = false,
      )
    }
    MovementManager.forcedActionsEnabled = true
    MovementManager.forcedAttack = true
    MovementManager.forcedUse = false
    if (isPressureTree() && player != null && clickedTarget != null) {
      MovementManager.setLookLock(true)
      smoothLookAt(level, player, clickedTarget!!)
    } else if (player != null) {
      MovementManager.setLookLock(false)
      PhantomRotation.blockController.cancel()
      player.yRot = lockedMineYaw
      player.xRot = lockedMinePitch
    }
    mc.options.keyUp.setDown(if (isPressureTree()) MovementManager.forcedForward else false)
    mc.options.keyDown.setDown(if (isPressureTree()) MovementManager.forcedBackward else false)
    mc.options.keyLeft.setDown(if (isPressureTree()) MovementManager.forcedLeft else false)
    mc.options.keyRight.setDown(if (isPressureTree()) MovementManager.forcedRight else false)
    mc.options.keyJump.setDown(if (isPressureTree()) pressureJump || MovementManager.forcedJump else false)
    mc.options.keyShift.setDown(false)
    mc.options.keySprint.setDown(if (isPressureTree()) MovementManager.forcedSprint else false)
    mc.options.keyUse.setDown(false)
    mc.options.keyAttack.setDown(true)
    if (isPressureTree() && now - lastAxeThrowAtMs >= PRESSURE_AXE_THROW_INTERVAL_MS) {
      mc.options.keyUse.setDown(false)
      mc.options.keyUse.setDown(true)
      lastAxeThrowAtMs = now
    }
    if (isPressureTree() && now - lastMineClickAtMs >= PRESSURE_CLICK_INTERVAL_MS) {
      mc.options.keyAttack.setDown(false)
      mc.options.keyAttack.setDown(true)
      (mc as? MinecraftAccessor)?.leftClick()
      lastMineClickAtMs = now
    }
    val pressureFinished = if (isPressureTree()) pressureMiningFinished(level) else false
    if (pressureFinished || (!isPressureTree() && now - startedAt >= CLICK_HOLD_MS)) {
      ignoreCurrentTree(level)
      mc.options.keyAttack.setDown(false)
      MovementManager.forcedAttack = false
      MovementManager.forcedActionsEnabled = false
      lockedMineYaw = 0f
      lockedMinePitch = 0f
      lastMineClickAtMs = 0L
      lastAxeThrowAtMs = 0L
      pressureProgressSeen = false
      cycleMacro("target clicked")
      return
    }
    if (isPressureTree() && now - startedAt >= PRESSURE_LOG_HOLD_MS) {
      moveToNextPressureLog(level)
    }
  }

  private fun moveToNextPressureLog(level: Level) {
    val player = mc.player ?: return
    val previous = clickedTarget
    val next = currentTree
      .filter { it != previous && canMineCurrentTreeLog(level, player, it) }
      .minWithOrNull(
        compareBy<BlockPos> { player.distanceToSqr(it.x + 0.5, it.y + 0.5, it.z + 0.5) }
          .thenBy { it.y }
      )
    if (next == null) return

    releaseAttackHold()
    clickedTarget = null
    clickHoldStartedAtMs = 0L
    lastMineClickAtMs = 0L
    lastAxeThrowAtMs = 0L
    currentTarget = next
    lookTarget = null
    val result = NativePathfinder.walkToBlock(
      target = next,
      reachableDistance = chopRange.value,
      arrivalRadius = walkStopRange.value.coerceIn(0.5, 1.0),
      maxGoals = 48,
      trunkOnly = false,
    )
    if (result.started) {
      pathStarted = true
      pathAttemptStartedAtMs = System.currentTimeMillis()
      resetProgressTracking()
      state = State.WALKING
    } else {
      state = State.CHOPPING
    }
  }

  private fun pressureMiningFinished(level: Level): Boolean {
    val progress = pressureProgressPercent(level)
    if (progress != null) {
      pressureProgressSeen = true
      return progress >= PRESSURE_COMPLETE_PERCENT
    }
    return pressureProgressSeen
  }

  private fun shouldJumpForPressureTarget(player: LocalPlayer, pos: BlockPos): Boolean {
    return pos.y > player.blockPosition().y + 1
  }

  private fun pressureProgressPercent(level: Level): Double? {
    val target = clickedTarget ?: currentTarget ?: return null
    val clientLevel = mc.level ?: return null
    return clientLevel.entitiesForRendering()
      .filterIsInstance<ArmorStand>()
      .asSequence()
      .filter { it.distanceToSqr(target.x + 0.5, target.y + 0.5, target.z + 0.5) <= PRESSURE_STAND_RANGE_SQ }
      .mapNotNull { stand ->
        val raw = stand.customName?.string ?: stand.name.string
        PRESSURE_PERCENT_REGEX.find(ChatFormatting.stripFormatting(raw).orEmpty())
          ?.groupValues
          ?.getOrNull(1)
          ?.toDoubleOrNull()
      }
      .maxOrNull()
  }

  private fun releaseAttackHold() {
    MovementManager.forcedAttack = false
    mc.options.keyAttack.setDown(false)
  }

  private fun finishCurrentTargetAndRestartPath() {
    currentTree.clear()
    currentTarget = null
    lookTarget = null
    clickedTarget = null
    clickHoldStartedAtMs = 0L
    lastMineClickAtMs = 0L
    lastAxeThrowAtMs = 0L
    pressureProgressSeen = false
    lockedMineYaw = 0f
    lockedMinePitch = 0f
    mineBlockedTicks = 0
    pathStarted = false
    resetProgressTracking()
    NativePathfinder.stop()
    releaseControls()
    cycleMacro("target finished")
  }

  private fun replanForCurrentTree(level: Level, player: LocalPlayer, reason: String) {
    stopWalking()
    pruneIgnoredTargets(level)
    NativePathfinder.stop()
    lookTarget = null
    pathStarted = false
    resetProgressTracking()
    mineBlockedTicks = 0

    val result = NativePathfinder.walkToNearestBlock(
      blockIds = targetBlockIds(),
      scanRadius = scanRadius.value.toInt().coerceAtLeast(1),
      scanVertical = scanVertical.value.toInt().coerceAtLeast(1),
      reachableDistance = chopRange.value,
      arrivalRadius = walkStopRange.value.coerceIn(0.5, 1.0),
      maxGoals = 64,
      excludedBlocks = excludedTargets(),
    )
    if (!result.started || result.targetBlock == null) {
      restartAfterFailure("$reason; ${result.reason}")
      return
    }

    currentTree = collectConnectedLogs(level, result.targetBlock, mutableSetOf()).toCollection(LinkedHashSet())
    currentTarget = result.targetBlock
    pathStarted = true
    pathAttemptStartedAtMs = System.currentTimeMillis()
    state = State.WALKING
  }

  private fun cycleMacro(@Suppress("UNUSED_PARAMETER") reason: String) {
    val selectedTree = currentTreeIndex()
    enabled.value = false
    stopMacro("Restarting", clearIgnoredTargets = false)
    treeType.value = selectedTree
    enabled.value = true
    wasEnabled = false
    state = State.SCANNING
    currentTree.clear()
    currentTarget = null
    pathAttemptStartedAtMs = 0L
    resetProgressTracking()
  }

  private fun ignoreCurrentTree(level: Level) {
    val expireAt = level.gameTime + TARGET_IGNORE_TICKS
    val ignoredBlocks = if (isPressureTree()) {
      currentTree.filter { !isTargetLog(level, it) }
    } else {
      currentTree
    }
    if (ignoredBlocks.isNotEmpty()) {
      ignoredBlocks.forEach { pos ->
        ignoredTargets[pos.immutable()] = expireAt
        for (direction in Direction.values()) {
          ignoredTargets[pos.relative(direction).immutable()] = expireAt
        }
      }
    } else {
      currentTarget?.let { target ->
        ignoredTargets[target.immutable()] = expireAt
        for (direction in Direction.values()) {
          ignoredTargets[target.relative(direction).immutable()] = expireAt
        }
      }
    }
  }

  private fun pruneIgnoredTargets(level: Level) {
    val now = level.gameTime
    ignoredTargets.entries.removeIf { (pos, expireAt) ->
      expireAt <= now
    }
  }

  private fun pruneRegeneratingTargets(level: Level) {
    val now = level.gameTime
    regeneratingTargets.entries.removeIf { (_, expireAt) -> expireAt <= now }
  }

  private fun excludedTargets(): Set<BlockPos> =
    ignoredTargets.keys + regeneratingTargets.keys

  private fun applyMovement(
    forward: Boolean,
    backward: Boolean,
    left: Boolean,
    right: Boolean,
    jump: Boolean,
    shift: Boolean,
    sprint: Boolean,
    attack: Boolean,
  ) {
    MovementManager.setMovementLock(true)
    MovementManager.setForcedMovement(
      forward = forward,
      backward = backward,
      left = left,
      right = right,
      jump = jump,
      shift = shift,
      sprint = sprint,
    )
    MovementManager.forcedActionsEnabled = true
    MovementManager.forcedAttack = attack
    MovementManager.forcedUse = false
    syncClientKeys(forward, backward, left, right, jump, shift, sprint, attack)
  }

  private fun syncClientKeys(
    forward: Boolean,
    backward: Boolean,
    left: Boolean,
    right: Boolean,
    jump: Boolean,
    shift: Boolean,
    sprint: Boolean,
    attack: Boolean,
  ) {
    mc.options.keyUp.setDown(forward)
    mc.options.keyDown.setDown(backward)
    mc.options.keyLeft.setDown(left)
    mc.options.keyRight.setDown(right)
    mc.options.keyJump.setDown(jump)
    mc.options.keyShift.setDown(shift)
    mc.options.keySprint.setDown(sprint)
    mc.options.keyAttack.setDown(attack)
    mc.options.keyUse.setDown(false)
  }

  private fun consumeEscapeStop(): Boolean {
    val window = GLFW.glfwGetCurrentContext()
    if (window == 0L) return false
    val down = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS
    val consumed = down && !escapeWasDown
    escapeWasDown = down
    return consumed
  }

  private fun consumeToggleKeybind(): Boolean {
    if (mc.screen != null) {
      toggleWasDown = false
      return false
    }
    val keyCode = toggleKeybind.value.keyCode
    if (keyCode < GLFW.GLFW_KEY_SPACE) {
      toggleWasDown = false
      return false
    }
    val window = GLFW.glfwGetCurrentContext()
    if (window == 0L) return false
    val down = GLFW.glfwGetKey(window, keyCode) == GLFW.GLFW_PRESS
    val consumed = down && !toggleWasDown
    toggleWasDown = down
    return consumed
  }

  private fun restartAfterFailure(reason: String) {
    val now = System.currentTimeMillis()
    if (pendingFailureReason != reason) {
      pendingFailureReason = reason
      pendingFailureStartedAtMs = now
      updateStateInfo("Recovering")
      return
    }
    if (now - pendingFailureStartedAtMs < FAILURE_GRACE_MS) {
      updateStateInfo("Recovering")
      return
    }
    clearPendingFailure()
    cycleMacro(reason)
  }

  private fun clearPendingFailure() {
    pendingFailureReason = null
    pendingFailureStartedAtMs = 0L
  }

  private fun updateProgress(distance: Double) {
    walkingTicks++
    if (distance < lastWalkDistance - 0.12) {
      noProgressTicks = 0
      lastWalkDistance = distance
      return
    }
    if (walkingTicks > 20) noProgressTicks++
    if (distance < lastWalkDistance) lastWalkDistance = distance
  }

  private fun resetProgressTracking() {
    walkingTicks = 0
    noProgressTicks = 0
    noPathCommandTicks = 0
    clearPendingFailure()
    lastWalkDistance = Double.POSITIVE_INFINITY
  }

  private fun updateStateInfo(override: String? = null) {
    stateInfo.value = override ?: when (state) {
      State.IDLE -> "Idle"
      State.SCANNING -> "Scanning"
      State.WALKING -> "Walking (${currentTree.size} logs)"
      State.CHOPPING -> "Chopping (${currentTree.size} logs)"
    }
  }

  private const val MAX_TREE_LOGS = 96
  private const val MAX_FAILURES = 7
  private const val NO_PROGRESS_FAIL_TICKS = 80
  private const val NO_PATH_COMMAND_FAIL_TICKS = 60
  private const val FAILURE_GRACE_MS = 2_500L
  private const val PATH_RESTART_MS = 2_500L
  private const val CLICK_HOLD_MS = 900L
  private const val PRESSURE_LOG_HOLD_MS = 900L
  private const val PRESSURE_CLICK_INTERVAL_MS = 450L
  private const val PRESSURE_AXE_THROW_INTERVAL_MS = 1_000L
  private const val PRESSURE_COMPLETE_PERCENT = 99.0
  private const val PRESSURE_STAND_RANGE_SQ = 16.0
  private const val REGEN_PARTICLE_SCAN_RADIUS = 3
  private const val REGEN_IGNORE_TICKS = 80L
  private const val TARGET_IGNORE_TICKS = 200L
  private const val MINE_BLOCKED_REPATH_TICKS = 10
  private const val BREAK_ROTATION_TOLERANCE_DEGREES = 1.75f
  private const val VANILLA_LOG_GROUP = "Vanilla Logs"
  private const val PRESSURE_LOG_GROUP = "Pressure Logs"
  private val PRESSURE_PERCENT_REGEX = Regex("""(\d+(?:\.\d+)?)\s*%""")
}
