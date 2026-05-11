package org.cobalt.internal.fishing.types

import net.minecraft.world.entity.projectile.FishingHook
import org.cobalt.internal.fishing.FishingMacroModule
import org.cobalt.internal.fishing.FishingRuntime
import org.cobalt.internal.fishing.FishingTypeProfile

object WormFishingProfile : FishingTypeProfile {
  override val type = FishingMacroModule.FishingType.WORM
  override val displayName = "Worm"

  override fun canReel(
    hook: FishingHook,
    runtime: FishingRuntime,
  ): Boolean {
    return hook.tickCount >= 10
  }

  override fun castSettleTicks(runtime: FishingRuntime): Long {
    return runtime.castSettleTicks.coerceAtLeast(10L)
  }

  override fun hookTimeoutTicks(runtime: FishingRuntime): Long {
    return runtime.hookTimeoutTicks.coerceAtLeast(300L)
  }

  override fun recastDelayTicks(runtime: FishingRuntime): Long {
    return runtime.recastDelayTicks.coerceAtLeast(14L)
  }
}
