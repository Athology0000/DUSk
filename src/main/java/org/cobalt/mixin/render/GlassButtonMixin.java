package org.cobalt.mixin.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import org.cobalt.internal.visual.DuttWelcomeScreen;
import org.cobalt.render.rise.UiShaderDrawHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractButton.class)
public abstract class GlassButtonMixin {

    @Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true)
    private void renderGlass(GuiGraphics gg, int mx, int my, float dt, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        AbstractButton btn = (AbstractButton) (Object) this;
        if (mc.screen instanceof JoinMultiplayerScreen) {
            ci.cancel();
            renderMultiplayerButton(mc, gg, btn);
            return;
        }

        // TitleScreen is handled by TitleScreenRenderer; leave welcome on vanilla widgets.
        if (
            mc.screen == null ||
            mc.screen instanceof TitleScreen ||
            mc.screen instanceof DuttWelcomeScreen
        ) return;

        ci.cancel();

        int x = btn.getX();
        int y = btn.getY();
        int w = btn.getWidth();
        int h = btn.getHeight();
        boolean hov = btn.isHoveredOrFocused();

        // -- Glass fill --------------------------------------------------------
        int fill   = hov ? (0x55 << 24) | 0x7A90B8 : (0x28 << 24) | 0x7A90B8;
        int shine  = 0x1EAACCEE;
        int border = hov ? (0xA0 << 24) | 0x99BBDD : (0x44 << 24) | 0x99BBDD;

        gg.fill(x, y, x + w, y + h, fill);
        // Specular streak across top ~38%
        gg.fill(x, y, x + w, y + (int)(h * 0.38f), shine);

        // -- 1-pixel border ----------------------------------------------------
        gg.fill(x,       y,       x + w, y + 1,     border);   // top
        gg.fill(x,       y + h-1, x + w, y + h,     border);   // bottom
        gg.fill(x,       y,       x + 1, y + h,     border);   // left
        gg.fill(x + w-1, y,       x + w, y + h,     border);   // right

        // -- Label -------------------------------------------------------------
        int textColor = hov ? 0xFFEEF4FF : 0xFFAABBCC;
        gg.drawCenteredString(mc.font, btn.getMessage(), x + w / 2, y + (h - 8) / 2, textColor);
    }

    private static void renderMultiplayerButton(Minecraft mc, GuiGraphics gg, AbstractButton btn) {
        int x = btn.getX();
        int y = btn.getY();
        int w = btn.getWidth();
        int h = btn.getHeight();
        boolean hovered = btn.isHoveredOrFocused();
        boolean active = btn.active;
        float scale = mc.getWindow().getGuiScale();
        int framebufferHeight = mc.getWindow().getScreenHeight();

        float sx = x * scale;
        float sy = y * scale;
        float sw = w * scale;
        float sh = h * scale;
        float radius = 6.5f * scale;

        int shadow = active ? 0x24000000 : 0x14000000;
        int fillTop = active
            ? (hovered ? 0x7A0C0D11 : 0x62090A0E)
            : 0x3807080C;
        int fillBottom = active
            ? (hovered ? 0x9006070B : 0x78050609)
            : 0x44040507;
        int border = active
            ? (hovered ? 0x6EFFFFFF : 0x42D8E0EA)
            : 0x245D6670;
        int innerBorder = active
            ? (hovered ? 0x18FFFFFF : 0x106E7A88)
            : 0x0C5D6670;
        int textColor = active
            ? (hovered ? 0xFFF2F6FB : 0xFFD4DCE7)
            : 0x7FA0A8B2;

        UiShaderDrawHelper.drawRoundedRect(
            sx,
            sy + 1.5f * scale,
            sw,
            sh,
            radius,
            shadow,
            framebufferHeight
        );
        UiShaderDrawHelper.drawGradientRoundedRect(
            sx,
            sy,
            sw,
            sh,
            radius,
            fillTop,
            fillBottom,
            org.cobalt.api.util.ui.helper.Gradient.TopToBottom,
            framebufferHeight
        );
        UiShaderDrawHelper.drawRoundedOutline(
            sx,
            sy,
            sw,
            sh,
            radius,
            Math.max(1.0f, 1.0f * scale),
            border,
            framebufferHeight
        );
        UiShaderDrawHelper.drawRoundedOutline(
            sx + 1.0f * scale,
            sy + 1.0f * scale,
            sw - 2.0f * scale,
            sh - 2.0f * scale,
            Math.max(0.0f, radius - 1.0f * scale),
            Math.max(1.0f, 0.75f * scale),
            innerBorder,
            framebufferHeight
        );

        gg.drawCenteredString(mc.font, btn.getMessage(), x + w / 2, y + (h - 8) / 2, textColor);
    }
}
