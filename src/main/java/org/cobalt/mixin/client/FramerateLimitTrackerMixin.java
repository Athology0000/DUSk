package org.cobalt.mixin.client;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Bypasses Minecraft's automatic FPS throttling when the window is iconified or the player
 * is AFK. Without this, the game drops to 10 FPS when tabbed out, which makes automation
 * behave erratically and produces a detectable movement pattern.
 *
 * The stored {@code framerateLimit} is the user-configured value (set via the video settings
 * slider). This mixin ensures {@link FramerateLimitTracker#getFramerateLimit()} never returns
 * less than that value — it only prevents downward throttling, never forces a higher FPS than
 * the user chose.
 */
@Mixin(FramerateLimitTracker.class)
public class FramerateLimitTrackerMixin {

  private static final int MIN_FRAMERATE = 60;

  @Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
  private void bypassThrottle(CallbackInfoReturnable<Integer> cir) {
    if (cir.getReturnValue() < MIN_FRAMERATE) {
      cir.setReturnValue(MIN_FRAMERATE);
    }
  }
}
