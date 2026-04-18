package org.cobalt.mixin.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.cobalt.api.util.player.MovementManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(KeyMapping.class)
public class KeyMappingMixin {

  @Inject(method = "isDown", at = @At("HEAD"), cancellable = true)
  private void cobalt$lockMovement(CallbackInfoReturnable<Boolean> cir) {
    Minecraft mc = Minecraft.getInstance();
    if (mc == null || mc.options == null) {
      return;
    }
    KeyMapping self = (KeyMapping) (Object) this;
    if (self == mc.options.keyAttack) {
      if (MovementManager.forcedActionsEnabled) {
        cir.setReturnValue(MovementManager.forcedAttack);
      }
      return;
    }
    if (self == mc.options.keyUse) {
      if (MovementManager.forcedActionsEnabled) {
        cir.setReturnValue(MovementManager.forcedUse);
      }
      return;
    }
    if (!MovementManager.isMovementLocked) {
      return;
    }
    if (self == mc.options.keyUp) {
      cir.setReturnValue(MovementManager.hasForcedMovement && MovementManager.forcedForward);
      return;
    }
    if (self == mc.options.keyDown) {
      cir.setReturnValue(MovementManager.hasForcedMovement && MovementManager.forcedBackward);
      return;
    }
    if (self == mc.options.keyLeft) {
      cir.setReturnValue(MovementManager.hasForcedMovement && MovementManager.forcedLeft);
      return;
    }
    if (self == mc.options.keyRight) {
      cir.setReturnValue(MovementManager.hasForcedMovement && MovementManager.forcedRight);
      return;
    }
    if (self == mc.options.keyJump) {
      cir.setReturnValue(MovementManager.hasForcedMovement && MovementManager.forcedJump);
      return;
    }
    if (self == mc.options.keyShift) {
      // Allow manual crouch unless automation is explicitly forcing shift.
      if (MovementManager.hasForcedMovement && MovementManager.forcedShift) {
        cir.setReturnValue(true);
      }
      return;
    }
    if (self == mc.options.keySprint) {
      cir.setReturnValue(MovementManager.hasForcedMovement && MovementManager.forcedSprint);
      return;
    }
  }
}
