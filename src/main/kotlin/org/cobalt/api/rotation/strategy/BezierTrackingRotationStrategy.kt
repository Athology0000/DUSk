package org.cobalt.api.rotation.strategy

import kotlin.math.abs
import kotlin.math.min
import net.minecraft.client.player.LocalPlayer
import org.cobalt.api.rotation.IRotationStrategy
import org.cobalt.api.util.AngleUtils
import org.cobalt.api.util.helper.Rotation

class BezierTrackingRotationStrategy(
  private val maxYawStep: Float = 12f,
  private val maxPitchStep: Float = 10f,
  private val curveIn: Float = 0.06f,
  private val curveOut: Float = 0.94f,
  private val minScale: Float = 0.18f,
  private val snapThreshold: Float = 0.25f,
  /** If provided, called each frame to get the effective max yaw step (overrides [maxYawStep]). */
  private val yawStepSampler: (() -> Float)? = null,
  /** If provided, called each frame to get the effective max pitch step (overrides [maxPitchStep]). */
  private val pitchStepSampler: (() -> Float)? = null,
  /** If provided, called each frame to get the bezier curveIn value (overrides [curveIn]). */
  private val curveInProvider: (() -> Float)? = null,
  /** If provided, called each frame to get the bezier curveOut value (overrides [curveOut]). */
  private val curveOutProvider: (() -> Float)? = null,
  /** If provided, called each frame to get the minScale value (overrides [minScale]). */
  private val minScaleProvider: (() -> Float)? = null,
  /** If provided, called each frame to get the snap threshold (overrides [snapThreshold]). */
  private val snapThresholdProvider: (() -> Float)? = null,
) : IRotationStrategy {

  override fun onRotate(player: LocalPlayer, targetYaw: Float, targetPitch: Float): Rotation? {
    val effectiveYaw = yawStepSampler?.invoke() ?: maxYawStep
    val effectivePitch = pitchStepSampler?.invoke() ?: maxPitchStep
    val effectiveCurveIn = curveInProvider?.invoke() ?: curveIn
    val effectiveCurveOut = curveOutProvider?.invoke() ?: curveOut
    val effectiveMinScale = minScaleProvider?.invoke() ?: minScale
    val effectiveSnap = snapThresholdProvider?.invoke() ?: snapThreshold

    val yawDelta = AngleUtils.getRotationDelta(player.yRot, targetYaw)
    val pitchDelta = targetPitch - player.xRot
    val nextYaw = player.yRot + smoothStep(yawDelta, effectiveYaw, effectiveCurveIn, effectiveCurveOut, effectiveMinScale, effectiveSnap)
    val nextPitch = (player.xRot + smoothStep(pitchDelta, effectivePitch, effectiveCurveIn, effectiveCurveOut, effectiveMinScale, effectiveSnap)).coerceIn(-90f, 90f)
    return Rotation(nextYaw, nextPitch)
  }

  private fun smoothStep(delta: Float, maxStep: Float, cIn: Float, cOut: Float, mScale: Float, snap: Float): Float {
    val absDelta = abs(delta)
    if (absDelta < snap) {
      return delta
    }
    val stepLimit = min(absDelta, maxStep)
    val t = (absDelta / maxStep).coerceIn(0f, 1f)
    val eased = cubicBezier(t, cIn, cOut)
    val scale = mScale + (1f - mScale) * eased
    val step = stepLimit * scale
    return if (delta < 0f) -step else step
  }

  private fun cubicBezier(t: Float, p1: Float, p2: Float): Float {
    val inv = 1f - t
    return (3f * inv * inv * t * p1) + (3f * inv * t * t * p2) + (t * t * t)
  }
}
