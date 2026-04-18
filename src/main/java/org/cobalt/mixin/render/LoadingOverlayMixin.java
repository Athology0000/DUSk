package org.cobalt.mixin.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.cobalt.render.LoadingScreenRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayMixin {

    @Shadow
    private long fadeOutStart;

    @Shadow
    private long fadeInStart;

    @Shadow
    private boolean fadeIn;

    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void cobalt$renderCustomOverlay(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        long now = Util.getMillis();
        if (this.fadeIn && this.fadeInStart == -1L) {
            this.fadeInStart = now;
        }

        float overlayAlpha = 1.0f;
        float fadeOutSeconds = this.fadeOutStart > -1L ? (now - this.fadeOutStart) / 1000.0f : -1.0f;
        String message = fadeOutSeconds >= 0.0f ? "Enjoy" : "dutt";
        if (fadeOutSeconds >= 0.0f) {
            overlayAlpha = 1.0f - Mth.clamp((fadeOutSeconds - 0.4f) / 0.55f, 0.0f, 1.0f);
            if (fadeOutSeconds >= 1.0f) {
                this.minecraft.setOverlay(null);
                ci.cancel();
                return;
            }
        }

        LoadingScreenRenderer.renderOverlay(message, overlayAlpha);
        ci.cancel();
    }
}
