package org.cobalt.internal.mining

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.math.cos
import kotlin.random.Random
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.cobalt.api.pathfinder.jni.NativePathfinder
import org.cobalt.api.pathfinder.jni.PathStatus
import org.cobalt.api.util.AngleUtils
import org.cobalt.api.util.InventoryUtils
import org.cobalt.api.util.player.MovementManager
import org.cobalt.internal.etherwarp.EtherwarpLogic
import org.cobalt.internal.pathfinding.HeadRotationModule
import org.cobalt.internal.pathfinding.OverlayRenderEngine
import org.cobalt.internal.rotation.RotationsModule

internal typealias WarpAimTarget = MiningMacroModule.AimTarget
internal typealias WarpVein = MiningMacroModule.Vein

internal fun MiningMacroModule.tryStartWarp(
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
  org.cobalt.api.rotation.RotationExecutor.stopRotating()
  mc.options.keyUse?.setDown(false)
  mc.options.keyShift?.setDown(false)

  warpTarget = target
  warpStage = 0
  warpStageTicks = 0
  return true
}

internal fun MiningMacroModule.handleWarp(
  player: Player,
  level: net.minecraft.world.level.Level
) {
  val target = warpTarget ?: run {
    resetWarp()
    return
  }

  val targetCenter = Vec3(target.x + 0.5, target.y + 0.5, target.z + 0.5)
  frameRotTarget = targetCenter
  frameRotSnapThreshold = 0f
  frameRotSpeedScale = RotationsModule.sample(RotationsModule.warpSpeedScale.value).toFloat()
  frameRotAccelScale = RotationsModule.sample(RotationsModule.warpAccelScale.value).toFloat()
  frameRotPitchStep = RotationsModule.sample(RotationsModule.miningPitchStep.value).toFloat()
  frameRotMaxSpeed = RotationsModule.sample(RotationsModule.miningMaxSpeed.value).toFloat()
  frameRotMaxAccel = RotationsModule.sample(RotationsModule.miningMaxAccel.value).toFloat()
  val targetRotation = AngleUtils.getRotation(targetCenter)
  val yawError = abs(AngleUtils.getRotationDelta(player.yRot, targetRotation.yaw)).toDouble()
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
        if (!EtherwarpLogic.tryConsumeTeleportUseThisTick()) return
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

internal fun MiningMacroModule.clampAimPointInsideBlock(point: Vec3, target: BlockPos): Vec3 {
  val inset = 0.02
  return Vec3(
    point.x.coerceIn(target.x + inset, target.x + 1.0 - inset),
    point.y.coerceIn(target.y + inset, target.y + 1.0 - inset),
    point.z.coerceIn(target.z + inset, target.z + 1.0 - inset),
  )
}

internal fun MiningMacroModule.resolveMiningAimPoint(player: Player, target: BlockPos): WarpAimTarget {
  val level = mc.level
  val eye = player.eyePosition
  val usePrecision = shouldUsePrecisionPoint(target)
  if (level != null && usePrecision) {
    MiningPrecisionTracker.getPrecisionPointFor(target, allowTentative = true)?.let { point ->
      val clampedPoint = clampAimPointInsideBlock(point, target)
      if (canSeeAimPoint(level, player, eye, clampedPoint, target) || losCheck(level, player, eye, target)) {
        return WarpAimTarget(clampedPoint, true)
      }
    }
    val hit = mc.hitResult
    if (hit is BlockHitResult && hit.type == HitResult.Type.BLOCK && hit.blockPos == target) {
      return WarpAimTarget(clampAimPointInsideBlock(hit.location, target), true)
    }
  }
  if (level != null) {
    findVisibleAimPoint(level, player, eye, target)?.let { return WarpAimTarget(it, false) }
  }
  return WarpAimTarget(Vec3(target.x + 0.5, target.y + 0.5, target.z + 0.5), false)
}

internal fun MiningMacroModule.resolveGlideAimTarget(
  player: Player,
  target: BlockPos,
  currentAim: WarpAimTarget,
): WarpAimTarget {
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

internal fun MiningMacroModule.shouldUsePrecisionPoint(target: BlockPos): Boolean {
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

internal fun MiningMacroModule.refreshPrecisionPointChanceRolls() {
  val currentChance = precisionPointChance.value.coerceIn(0.0, 100.0)
  if (currentChance != precisionPointChanceSnapshot) {
    precisionPointChanceSnapshot = currentChance
    precisionRolls.clear()
  }
}

internal fun MiningMacroModule.applyHeadRotation(
  player: Player,
  target: Vec3,
  maxSpeedScale: Float = 1f,
  accelScale: Float = 1f,
  maxPitchStep: Float = 6f,
  maxTurnSpeed: Float = 100f,
  maxTurnAccel: Float = 220f,
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
  val yawStep = HeadRotationModule.computeTurnDelta(
    yawDelta,
    maxSpeedScale = maxSpeedScale,
    accelScale = accelScale,
    maxTurnSpeed = maxTurnSpeed,
    maxTurnAccel = maxTurnAccel,
    easeMode = easeMode,
  )
  player.yRot = AngleUtils.normalizeAngle(player.yRot + yawStep)
  player.yHeadRot = player.yRot
  player.yBodyRot = player.yRot

  val pitchDelta = targetRotation.pitch - player.xRot
  val pitchStep = HeadRotationModule.computePitchDelta(
    pitchDelta,
    maxSpeedScale = maxSpeedScale,
    accelScale = accelScale,
    maxPitchSpeed = maxPitchStep * 20f,
    maxPitchAccel = maxPitchStep * 60f,
    easeMode = easeMode,
  )
  player.xRot = (player.xRot + pitchStep).coerceIn(-89.9f, 89.9f)

  val yawError = abs(AngleUtils.getRotationDelta(player.yRot, targetRotation.yaw)).toDouble()
  val pitchError = abs(targetRotation.pitch - player.xRot).toDouble()
  return yawError to pitchError
}

internal fun MiningMacroModule.resetWarp() {
  mc.options.keyUse?.setDown(false)
  mc.options.keyShift?.setDown(false)
  warpStage = 0
  warpTarget = null
  warpStageTicks = 0
  frameRotTarget = null
  frameRotSnapThreshold = 0f
  frameRotInitialDist = 0f
  frameRotLastNs = 0L
}

internal fun MiningMacroModule.ensureEtherwarpHotbarSelected(): Boolean {
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

internal fun MiningMacroModule.restoreEtherwarpSlot() {
  if (warpRestoreSlot in 0..8) {
    InventoryUtils.holdHotbarSlot(warpRestoreSlot)
  }
  warpRestoreSlot = -1
}

internal fun MiningMacroModule.hasLineOfSight(
  level: net.minecraft.world.level.Level,
  player: Player,
  target: BlockPos
): Boolean {
  val eye = player.eyePosition
  return losCheck(level, player, eye, target)
}

internal fun MiningMacroModule.blockIdAt(level: net.minecraft.world.level.Level, pos: BlockPos): String {
  val state = level.getBlockState(pos)
  return BuiltInRegistries.BLOCK.getKey(state.block).toString()
}

internal fun MiningMacroModule.isMineableTarget(
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

internal fun MiningMacroModule.isCrosshairOnTarget(target: BlockPos): Boolean {
  val hit = mc.hitResult
  return hit is BlockHitResult && hit.type == HitResult.Type.BLOCK && hit.blockPos == target
}

internal fun MiningMacroModule.distanceToBlockSq(player: Player, pos: BlockPos): Double {
  val dx = (pos.x + 0.5) - player.x
  val dy = (pos.y + 0.5) - player.y
  val dz = (pos.z + 0.5) - player.z
  return dx * dx + dy * dy + dz * dz
}

internal fun MiningMacroModule.isVeinOccupied(
  level: net.minecraft.world.level.Level,
  vein: WarpVein,
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

internal fun MiningMacroModule.warnOnce(message: String) {
  val level = mc.level ?: return
  if (level.gameTime - lastWarnTick < 60L) return
  lastWarnTick = level.gameTime
  org.cobalt.api.util.ChatUtils.sendMessage(message)
}

internal fun MiningMacroModule.nativeActive(): Boolean =
  NativePathfinder.status.let { it != PathStatus.IDLE && it != PathStatus.ARRIVED && it != PathStatus.FAILED }

internal fun MiningMacroModule.nativeStop() {
  NativePathfinder.stop()
  MovementManager.setMovementLock(false)
}

internal fun MiningMacroModule.renderReturnSpotMarker(
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
    val x1 = position.x + cos(startAngle) * radius
    val z1 = position.z + sin(startAngle) * radius
    val x2 = position.x + cos(endAngle) * radius
    val z2 = position.z + sin(endAngle) * radius
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
      "mining-macro-targets",
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
    "mining-macro-targets",
  )
}
