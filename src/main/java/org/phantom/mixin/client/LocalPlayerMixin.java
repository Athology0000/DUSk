package org.phantom.mixin.client;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.phantom.api.event.impl.client.ItemDropQueryEvent;
import org.phantom.api.event.impl.client.PlayerVelocityCancelEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LocalPlayer.class)
public class LocalPlayerMixin {

  @Inject(method = "aiStep", at = @At("TAIL"))
  private void phantom$cancelVelocityForBonzoStaff(CallbackInfo ci) {
    if (!new PlayerVelocityCancelEvent().post()) {
      return;
    }
    LocalPlayer player = (LocalPlayer) (Object) this;
    if (!player.onGround()) {
      return;
    }
    Vec3 currentVel = player.getDeltaMovement();
    player.setDeltaMovement(0.0, currentVel.y, 0.0);
  }

  @Inject(method = "drop(Z)Z", at = @At("HEAD"), cancellable = true)
  private void phantom$preventLockedDrops(CallbackInfoReturnable<Boolean> cir) {
    if (new ItemDropQueryEvent().post()) {
      cir.setReturnValue(false);
    }
  }
}
