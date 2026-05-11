package org.cobalt.internal.fishing.types

import net.minecraft.world.entity.projectile.FishingHook
import org.cobalt.internal.fishing.FishingMacroModule
import org.cobalt.internal.fishing.FishingRuntime
import org.cobalt.internal.fishing.FishingTypeProfile

object StriderSurferFishingProfile : FishingTypeProfile {
  override val type = FishingMacroModule.FishingType.STRIDERSURFER
  override val displayName = "Strider Surfer"

  override fun canReel(
    hook: FishingHook,
    runtime: FishingRuntime,
  ): Boolean {
    // Keeps it from instantly reeling on early weird packets.
    return hook.tickCount >= 12
  }

  override fun shouldAcceptSplash(
    hook: FishingHook,
    runtime: FishingRuntime,
  ): Boolean {
    return canReel(hook, runtime)
  }

  override fun reactionDelayTicks(runtime: FishingRuntime): Long {
    // Slightly slower reaction for stability.
    return runtime.reactionDelayTicks.coerceAtLeast(4L)
  }

  override fun recastDelayTicks(runtime: FishingRuntime): Long {
    return runtime.recastDelayTicks.coerceAtLeast(16L)
  }
}
