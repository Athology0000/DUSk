package org.cobalt.mixin.client;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.cobalt.internal.dungeons.DungeonsModule;
import org.cobalt.internal.qol.ItemLockingModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LocalPlayer.class)
public class LocalPlayerMixin {

  @Inject(method = "aiStep", at = @At("TAIL"))
  private void cobalt$cancelVelocityForBonzoStaff(CallbackInfo ci) {
    if (!DungeonsModule.INSTANCE.shouldCancelVelocity()) {
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
  private void cobalt$preventLockedDrops(CallbackInfoReturnable<Boolean> cir) {
    if (ItemLockingModule.INSTANCE.shouldCancelSelectedItemDrop()) {
      cir.setReturnValue(false);
    }
  }
}
