package org.cobalt.mixin.render;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.cobalt.api.event.impl.render.NvgEvent;
import org.cobalt.internal.visual.DarkModeModule;
import org.cobalt.render.DarkModeRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

  @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;incrementFrameNumber()V", shift = At.Shift.AFTER))
  public void renderNvg(DeltaTracker counter, boolean tick, CallbackInfo callbackInfo) {
    new NvgEvent().post();
    new org.cobalt.api.event.impl.render.GuiPostRenderEvent().post();
  }

  @Inject(
    method = "renderLevel",
    at = @At(
      value = "INVOKE",
      target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
      shift = At.Shift.AFTER
    ),
    require = 0
  )
  private void renderDarkModeAfterLevel(DeltaTracker deltaTracker, CallbackInfo ci) {
    Minecraft mc = Minecraft.getInstance();
    if (mc.level == null || !mc.options.getCameraType().isFirstPerson()) {
      return;
    }
    if (DarkModeModule.INSTANCE.isEnabled()) {
      DarkModeRenderer.renderDarkModeOverlay();
    }
  }
}
