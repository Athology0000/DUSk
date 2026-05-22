package org.phantom.mixin.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.KeyboardInput;
import org.phantom.api.event.impl.client.ForcedBackwardKeyEvent;
import org.phantom.api.util.player.MovementManager;
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
  private boolean phantom$overrideKeyboardTickState(KeyMapping keyBinding) {
    Minecraft mc = Minecraft.getInstance();
    boolean forcedBackward = new ForcedBackwardKeyEvent().post();

    if (mc != null && mc.options != null && MovementManager.isMovementLocked && MovementManager.hasForcedMovement) {
      if (keyBinding == mc.options.keyUp) {
        return MovementManager.forcedForward;
      }
      if (keyBinding == mc.options.keyDown) {
        return MovementManager.forcedBackward || forcedBackward;
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

    if (mc != null && mc.options != null && keyBinding == mc.options.keyDown && forcedBackward) {
      return true;
    }

    return keyBinding.isDown();
  }
}
