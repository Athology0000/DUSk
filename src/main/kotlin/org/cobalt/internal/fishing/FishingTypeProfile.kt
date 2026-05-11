package org.cobalt.internal.fishing

import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.FishingHook

interface FishingTypeProfile {
  val type: FishingMacroModule.FishingType
  val displayName: String

  /**
   * Called every tick while the macro is active.
   * Use this for type-specific helper logic.
   */
  fun onTick(
    mc: Minecraft,
    player: Player,
    runtime: FishingRuntime,
  ) = Unit

  /**
   * Whether a splash packet near the hook should cause the macro to reel.
   */
  fun shouldAcceptSplash(
    hook: FishingHook,
    runtime: FishingRuntime,
  ): Boolean = true

  /**
   * Whether the macro is allowed to reel right now.
   * This is where Trophy wait time, slug fishing, worm timing, etc. go.
   */
  fun canReel(
    hook: FishingHook,
    runtime: FishingRuntime,
  ): Boolean = true

  /**
   * Delay after a splash before reeling.
   */
  fun reactionDelayTicks(runtime: FishingRuntime): Long {
    return runtime.reactionDelayTicks
  }

  /**
   * Delay after reeling before casting again.
   */
  fun recastDelayTicks(runtime: FishingRuntime): Long {
    return runtime.recastDelayTicks
  }

  /**
   * Delay after casting before normal logic starts.
   */
  fun castSettleTicks(runtime: FishingRuntime): Long {
    return runtime.castSettleTicks
  }

  /**
   * Maximum time to leave hook out before forced recast.
   */
  fun hookTimeoutTicks(runtime: FishingRuntime): Long {
    return runtime.hookTimeoutTicks
  }
}
