package org.cobalt.mixin.render;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.cobalt.internal.visual.TitleScreenRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Locale;

@Mixin(TitleScreen.class)
public class TitleScreenMixin {

    private static final int OFFSCREEN_X = -2000;
    private static final int OFFSCREEN_Y = -2000;

    @Inject(method = "init", at = @At("TAIL"))
    private void cobalt$layoutButtons(CallbackInfo ci) {
        Screen screen = (Screen) (Object) this;
        int centerX = screen.width / 2;
        int singleplayerY = screen.height / 4 + 48;
        int multiplayerY = screen.height / 4 + 72;
        int splitRowY = screen.height / 4 + 132;

        for (GuiEventListener child : screen.children()) {
            if (!(child instanceof AbstractButton button)) {
                continue;
            }

            if (button instanceof SpriteIconButton || button instanceof PlainTextButton) {
                button.setPosition(OFFSCREEN_X, OFFSCREEN_Y);
                continue;
            }

            String label = normalizeLabel(button.getMessage().getString());

            if (label.contains("singleplayer")) {
                button.setWidth(200);
                button.setPosition(centerX - 100, singleplayerY);
                continue;
            }
            if (label.contains("multiplayer")) {
                button.setWidth(200);
                button.setPosition(centerX - 100, multiplayerY);
                continue;
            }
            if (label.startsWith("options")) {
                button.setWidth(98);
                button.setPosition(centerX - 100, splitRowY);
                continue;
            }
            if (label.startsWith("quit")) {
                button.setWidth(98);
                button.setPosition(centerX + 2, splitRowY);
                continue;
            }
            if (
                label.contains("online")
                    || label.contains("realms")
                    || label.contains("demo")
                    || label.contains("test world")
            ) {
                button.setPosition(OFFSCREEN_X, OFFSCREEN_Y);
            }
        }
    }

    private static String normalizeLabel(String label) {
        return label
            .replace("...", "")
            .replace("\u2026", "")
            .trim()
            .toLowerCase(Locale.ROOT);
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void customRender(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        TitleScreenRenderer.INSTANCE.render(guiGraphics, mouseX, mouseY, partialTick);
        ci.cancel();
    }
}
