package org.cobalt.api.rotation

import net.minecraft.client.player.LocalPlayer
import org.cobalt.api.util.helper.Rotation

interface IRotationStrategy {

  fun onRotate(
    player: LocalPlayer,
    targetYaw: Float,
    targetPitch: Float,
  ): Rotation?

  fun onStart() {
    // Optional lifecycle hook
  }

  fun onStop() {
    // Optional lifecycle hook
  }

}
