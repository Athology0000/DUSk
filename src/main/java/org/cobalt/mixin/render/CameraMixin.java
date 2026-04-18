package org.cobalt.mixin.render;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.cobalt.internal.etherwarp.SmoothAotvModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {

  @Shadow
  protected abstract void setPosition(Vec3 pos);

  @Inject(method = "setup", at = @At("TAIL"))
  private void cobalt$applySmoothAotvCamera(
    Level level,
    Entity entity,
    boolean detached,
    boolean thirdPersonReverse,
    float partialTick,
    CallbackInfo ci
  ) {
    Vec3 override = SmoothAotvModule.INSTANCE.interpolatedCameraPos();
    if (override != null) {
      setPosition(override);
    }
  }
}
