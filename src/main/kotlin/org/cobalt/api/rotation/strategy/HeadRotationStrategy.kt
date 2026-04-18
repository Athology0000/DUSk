package org.cobalt.api.rotation.strategy

import net.minecraft.client.player.LocalPlayer
import org.cobalt.api.rotation.IRotationStrategy
import org.cobalt.api.util.AngleUtils
import org.cobalt.api.util.helper.Rotation
import org.cobalt.internal.pathfinding.HeadRotationModule
import kotlin.math.exp
import kotlin.math.max
import kotlin.random.Random

/**
 * Rotation strategy that uses HeadRotationModule's acceleration/easing model.
 * All samplers are called each frame so they can provide randomised human-like values.
 */
class HeadRotationStrategy(
  private val speedScaleSampler: () -> Float,
  private val accelScaleSampler: () -> Float,
  private val pitchStepSampler: () -> Float,
  private val maxSpeedSampler: () -> Float,
  private val maxAccelSampler: () -> Float,
  private val easeModeSampler: () -> HeadRotationModule.EaseMode = { HeadRotationModule.EaseMode.TANH },
  private val snapThresholdSampler: () -> Float = { 0f },
) : IRotationStrategy {
  private var driftYaw: Float = 0f
  private var driftPitch: Float = 0f
  private var driftYawTarget: Float = 0f
  private var driftPitchTarget: Float = 0f
  private var nextRetargetNs: Long = 0L
  private var lastHumanizeNs: Long = 0L
  private val rng = Random.Default

  override fun onStart() {
    resetHumanization()
    HeadRotationModule.resetVelocity()
  }

  override fun onStop() {
    resetHumanization()
    HeadRotationModule.resetVelocity()
  }

  override fun onRotate(player: LocalPlayer, targetYaw: Float, targetPitch: Float): Rotation {
    val maxSpeedScale = speedScaleSampler()
    val accelScale    = accelScaleSampler()
    val maxTurnSpeed  = maxSpeedSampler()
    val maxTurnAccel  = maxAccelSampler()
    val maxPitchStep  = pitchStepSampler()
    val easeMode      = easeModeSampler()
    val snapThreshold = snapThresholdSampler().coerceAtLeast(0f)

    val currentYawError = kotlin.math.abs(AngleUtils.getRotationDelta(player.yRot, targetYaw))
    val currentPitchError = kotlin.math.abs(targetPitch - player.xRot)
    if (snapThreshold > 0f && currentYawError <= snapThreshold && currentPitchError <= snapThreshold) {
      return Rotation(targetYaw, targetPitch.coerceIn(-89.9f, 89.9f))
    }

    updateHumanization(System.nanoTime(), currentYawError, currentPitchError)

    val yawDelta = AngleUtils.getRotationDelta(player.yRot, targetYaw)
    val yawStep  = HeadRotationModule.computeTurnDelta(
      yawDelta,
      maxSpeedScale = maxSpeedScale,
      accelScale    = accelScale,
      maxTurnSpeed  = maxTurnSpeed,
      maxTurnAccel  = maxTurnAccel,
      easeMode      = easeMode,
    )
    val newYaw = AngleUtils.normalizeAngle(player.yRot + yawStep)

    val pitchDelta = targetPitch - player.xRot
    val pitchStep  = HeadRotationModule.computePitchDelta(
      pitchDelta,
      maxSpeedScale = maxSpeedScale,
      accelScale    = accelScale,
      maxPitchSpeed = maxPitchStep * 20f,
      maxPitchAccel = maxPitchStep * 60f,
      easeMode      = easeMode,
    )
    val newPitch = (player.xRot + pitchStep + driftPitch).coerceIn(-89.9f, 89.9f)

    return Rotation(AngleUtils.normalizeAngle(newYaw + driftYaw), newPitch)
  }

  private fun updateHumanization(nowNs: Long, currentYawError: Float, currentPitchError: Float) {
    val trackingError = max(currentYawError, currentPitchError)
    val calmScale = 1f - (trackingError / HUMANIZATION_SUPPRESS_ERROR).coerceIn(0f, 1f)
    if (nextRetargetNs == 0L || nowNs >= nextRetargetNs) {
      driftYawTarget = randomCentered(MAX_YAW_DRIFT * calmScale)
      driftPitchTarget = randomCentered(MAX_PITCH_DRIFT * calmScale)
      nextRetargetNs = nowNs + rng.nextLong(RETARGET_MIN_NS, RETARGET_MAX_NS + 1L)
    }
    val dt =
      if (lastHumanizeNs == 0L) 1f / 20f
      else ((nowNs - lastHumanizeNs) / 1_000_000_000.0f).coerceIn(1f / 240f, 0.1f)
    lastHumanizeNs = nowNs
    val blend = (1.0 - exp(-DRIFT_BLEND_SPEED * dt)).toFloat()
    driftYaw += (driftYawTarget - driftYaw) * blend
    driftPitch += (driftPitchTarget - driftPitch) * blend
  }

  private fun randomCentered(magnitude: Float): Float {
    if (magnitude <= 0f) return 0f
    return (rng.nextFloat() * 2f - 1f) * magnitude
  }

  private fun resetHumanization() {
    driftYaw = 0f
    driftPitch = 0f
    driftYawTarget = 0f
    driftPitchTarget = 0f
    nextRetargetNs = 0L
    lastHumanizeNs = 0L
  }

  private companion object {
    private const val DRIFT_BLEND_SPEED = 6.5
    private const val HUMANIZATION_SUPPRESS_ERROR = 30f
    private const val MAX_YAW_DRIFT = 0.18f
    private const val MAX_PITCH_DRIFT = 0.10f
    private const val RETARGET_MIN_NS = 220_000_000L
    private const val RETARGET_MAX_NS = 440_000_000L
  }
}
