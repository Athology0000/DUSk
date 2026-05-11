package org.cobalt.internal.fishing.types

import net.minecraft.world.entity.projectile.FishingHook
import org.cobalt.internal.fishing.FishingMacroModule
import org.cobalt.internal.fishing.FishingRuntime
import org.cobalt.internal.fishing.FishingTypeProfile

object TrophyFishingProfile : FishingTypeProfile {
  override val type = FishingMacroModule.FishingType.TROPHY
  override val displayName = "Trophy"

  override fun canReel(
    hook: FishingHook,
    runtime: FishingRuntime,
  ): Boolean {
    return hook.tickCount >= runtime.minimumTrophyWaitTicks
  }

  override fun shouldAcceptSplash(
    hook: FishingHook,
    runtime: FishingRuntime,
  ): Boolean {
    return canReel(hook, runtime)
  }

  override fun reactionDelayTicks(runtime: FishingRuntime): Long {
    return runtime.reactionDelayTicks
  }

  override fun castSettleTicks(runtime: FishingRuntime): Long {
    return runtime.castSettleTicks
  }
}
