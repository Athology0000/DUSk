package org.cobalt.mixin.client;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.cobalt.api.event.impl.client.MouseEvent;
import org.cobalt.api.util.MouseUtils;
import org.cobalt.api.util.player.MovementManager;
import org.cobalt.internal.dungeons.DungeonsModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

  @Shadow
  private boolean mouseGrabbed;

  @Shadow
  public abstract void releaseMouse();

  @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
  private void onMouseButton(long window, MouseButtonInfo input, int action, CallbackInfo ci) {
    if (input.button() == 0 && action == 1) {
      if (DungeonsModule.onLeftClick()) {
        return;
      }
    }

    MouseEvent event = cobalt$createMouseEvent(input.button(), action == 1);

    if (event != null && event.post()) {
      ci.cancel();
    }
  }

  @Unique
  private MouseEvent cobalt$createMouseEvent(int button, boolean isDown) {
    return switch (button) {
      case 0 -> isDown ? new MouseEvent.LeftClick(button) : new MouseEvent.LeftRelease(button);
      case 1 -> isDown ? new MouseEvent.RightClick(button) : new MouseEvent.RightRelease(button);
      case 2 -> isDown ? new MouseEvent.MiddleClick(button) : new MouseEvent.MiddleRelease(button);
      default -> null;
    };
  }

  @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
  private void onUpdateMouse(CallbackInfo callbackInfo) {
    if (MovementManager.isLookLocked) {
      callbackInfo.cancel();
    }
  }

  @Inject(method = "isMouseGrabbed", at = @At("HEAD"), cancellable = true)
  private void onIsCursorLocked(CallbackInfoReturnable<Boolean> callbackInfoReturnable) {
    if (MouseUtils.isMouseUngrabbed()) {
      if (this.mouseGrabbed) {
        this.releaseMouse();
      }

      callbackInfoReturnable.setReturnValue(false);
    }
  }

  @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
  private void onLockCursor(CallbackInfo callbackInfo) {
    if (MouseUtils.isMouseUngrabbed()) {
      callbackInfo.cancel();
    }
  }

}
