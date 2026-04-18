package org.cobalt.mixin.render;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.vertex.PoseStack;
import org.cobalt.internal.visual.MobEspModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin<S extends LivingEntityRenderState, M extends EntityModel<? super S>> {

  @Shadow protected M model;

  @Shadow public abstract Identifier getTextureLocation(S state);

  @Shadow protected abstract float getWhiteOverlayProgress(S state);

  @Shadow protected abstract int getModelTint(S state);

  @Inject(
    method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V",
    at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V")
  )
  private void cobalt$submitMobEspFill(
    S state,
    PoseStack poseStack,
    SubmitNodeCollector submitNodeCollector,
    CameraRenderState cameraRenderState,
    CallbackInfo ci
  ) {
    if (!MobEspModule.shouldRenderFill(state.entityType, state.isInvisible)) {
      return;
    }

    int fillTint = multiplyArgb(MobEspModule.fillTintArgb(), this.getModelTint(state));
    if (((fillTint >>> 24) & 0xFF) <= 0) {
      return;
    }

    int overlay = LivingEntityRenderer.getOverlayCoords(state, this.getWhiteOverlayProgress(state));
    RenderType renderType = RenderTypes.itemEntityTranslucentCull(this.getTextureLocation(state));
    submitNodeCollector.submitModel(
      this.model,
      state,
      poseStack,
      renderType,
      state.lightCoords,
      overlay,
      fillTint,
      null,
      EntityRenderState.NO_OUTLINE,
      (ModelFeatureRenderer.CrumblingOverlay) null
    );
  }

  private static int multiplyArgb(int first, int second) {
    int a = ((first >>> 24) & 0xFF) * ((second >>> 24) & 0xFF) / 255;
    int r = ((first >>> 16) & 0xFF) * ((second >>> 16) & 0xFF) / 255;
    int g = ((first >>> 8) & 0xFF) * ((second >>> 8) & 0xFF) / 255;
    int b = (first & 0xFF) * (second & 0xFF) / 255;
    return (a << 24) | (r << 16) | (g << 8) | b;
  }
}
