package org.phantom.mixin.render;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.input.KeyEvent;
import org.phantom.api.event.impl.client.ScreenKeyEvent;
import org.phantom.api.event.impl.render.ScreenDrawEvent;
import org.phantom.render.LoadingScreenRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Screen.class)
public class ScreenMixin {

    @Inject(method = "renderWithTooltipAndSubtitles", at = @At("HEAD"), cancellable = true)
    private void phantom$renderScreenOverlay(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        Screen screen = (Screen) (Object) this;
        if (new ScreenDrawEvent(screen, guiGraphics).post()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
    private void phantom$renderRiseBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        Screen screen = (Screen) (Object) this;
        if (
            !(screen instanceof JoinMultiplayerScreen) &&
            !(screen instanceof ConnectScreen) &&
            !(screen instanceof ProgressScreen)
        ) {
            return;
        }

        LoadingScreenRenderer.renderBackground(1.0f);
        ci.cancel();
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void phantom$handleScreenKeyPress(KeyEvent input, CallbackInfoReturnable<Boolean> cir) {
        if (new ScreenKeyEvent(input, false, null).post()) {
            cir.setReturnValue(true);
        }
    }
}
