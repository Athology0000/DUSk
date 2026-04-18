package org.cobalt.mixin.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.KeyboardInput;
import org.cobalt.api.util.player.MovementManager;
import org.cobalt.internal.dungeons.DungeonsModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(KeyboardInput.class)
public class KeyboardInputMixin {

  @Redirect(
    method = "tick",
    at = @At(
      value = "INVOKE",
      target = "Lnet/minecraft/client/KeyMapping;isDown()Z"
    ),
    require = 0
  )
  private boolean cobalt$overrideKeyboardTickState(KeyMapping keyBinding) {
    Minecraft mc = Minecraft.getInstance();
    if (mc != null && mc.options != null && MovementManager.isMovementLocked && MovementManager.hasForcedMovement) {
      if (keyBinding == mc.options.keyUp) {
        return MovementManager.forcedForward;
      }
      if (keyBinding == mc.options.keyDown) {
        return MovementManager.forcedBackward || DungeonsModule.INSTANCE.shouldPressBackward();
      }
      if (keyBinding == mc.options.keyLeft) {
        return MovementManager.forcedLeft;
      }
      if (keyBinding == mc.options.keyRight) {
        return MovementManager.forcedRight;
      }
      if (keyBinding == mc.options.keyJump) {
        return MovementManager.forcedJump;
      }
      if (keyBinding == mc.options.keyShift) {
        return MovementManager.forcedShift;
      }
      if (keyBinding == mc.options.keySprint) {
        return MovementManager.forcedSprint;
      }
    }

    if (mc != null && mc.options != null && keyBinding == mc.options.keyDown && DungeonsModule.INSTANCE.shouldPressBackward()) {
      return true;
    }

    return keyBinding.isDown();
  }
}
