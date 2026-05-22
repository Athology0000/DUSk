package org.phantom.mixin.render;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.FishingHookRenderer;
import net.minecraft.world.entity.projectile.FishingHook;
import org.phantom.api.event.impl.client.FishingBobberRenderEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FishingHookRenderer.class)
public class FishingHookRendererMixin {

  @Inject(
    method = "shouldRender(Lnet/minecraft/world/entity/projectile/FishingHook;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z",
    at = @At("HEAD"),
    cancellable = true
  )
  private void phantom$hideOtherBobbers(
    FishingHook fishingHook,
    Frustum frustum,
    double cameraX,
    double cameraY,
    double cameraZ,
    CallbackInfoReturnable<Boolean> cir
  ) {
    FishingBobberRenderEvent event =
      new FishingBobberRenderEvent(fishingHook.getPlayerOwner() instanceof LocalPlayer);
    if (event.post()) {
      cir.setReturnValue(false);
    }
  }
}
