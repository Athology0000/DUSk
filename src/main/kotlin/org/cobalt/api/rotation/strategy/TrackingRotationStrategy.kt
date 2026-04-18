package org.cobalt.api.rotation.strategy

import org.cobalt.api.rotation.IRotationStrategy
import org.cobalt.api.util.AngleUtils
import org.cobalt.api.util.helper.Rotation
import net.minecraft.client.player.LocalPlayer

class TrackingRotationStrategy(
  private val maxYawStep: Float = 12f,
  private val maxPitchStep: Float = 10f,
) : IRotationStrategy {

  override fun onRotate(player: LocalPlayer, targetYaw: Float, targetPitch: Float): Rotation? {
    val yawStep = AngleUtils.getRotationDelta(player.yRot, targetYaw).coerceIn(-maxYawStep, maxYawStep)
    val pitchStep = (targetPitch - player.xRot).coerceIn(-maxPitchStep, maxPitchStep)
    val nextYaw = player.yRot + yawStep
    val nextPitch = (player.xRot + pitchStep).coerceIn(-90f, 90f)
    return Rotation(nextYaw, nextPitch)
  }
}
