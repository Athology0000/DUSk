package org.cobalt.api.rotation.strategy

import net.minecraft.client.player.LocalPlayer
import org.cobalt.api.rotation.EasingType
import org.cobalt.api.rotation.IRotationStrategy
import org.cobalt.api.util.AngleUtils
import org.cobalt.api.util.PlayerUtils
import org.cobalt.api.util.helper.Rotation

class TimedEaseStrategy(
  private val yawEasing: EasingType,
  private val pitchEasing: EasingType,
  private val duration: Long,
) : IRotationStrategy {

  private var startYaw: Float = 0.0F
  private var startPitch: Float = 0.0F
  private var endTime: Long = 0L

  override fun onStart() {
    val (yaw, pitch) = PlayerUtils.rotation ?: throw IllegalStateException("Player rotation is null")
    startYaw = yaw
    startPitch = pitch
    endTime = System.currentTimeMillis() + duration
  }

  override fun onRotate(
    player: LocalPlayer,
    targetYaw: Float,
    targetPitch: Float,
  ): Rotation? {
    val now = System.currentTimeMillis()

    if (now >= endTime) {
      return null
    }

    val progress = 1f - ((endTime - now).toFloat() / duration.toFloat())
    val t = progress.coerceIn(0f, 1f)

    val yawDelta = AngleUtils.normalizeAngle(targetYaw - startYaw)
    val yaw = yawEasing.apply(startYaw, startYaw + yawDelta, t)

    val pitch = clampPitch(
      pitchEasing.apply(startPitch, clampPitch(targetPitch), t)
    )

    return Rotation(yaw, pitch)
  }

  private fun clampPitch(pitch: Float): Float {
    return pitch.coerceIn(-90f, 90f)
  }

}
