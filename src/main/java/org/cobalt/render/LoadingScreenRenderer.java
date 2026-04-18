package org.cobalt.render;

import net.minecraft.client.Minecraft;
import org.cobalt.api.util.ui.NVGRenderer;

public final class LoadingScreenRenderer {

    private LoadingScreenRenderer() {}

    public static void renderBackground(float alpha) {
        Minecraft minecraft = Minecraft.getInstance();
        float screenWidth = minecraft.getWindow().getScreenWidth();
        float screenHeight = minecraft.getWindow().getScreenHeight();
        float timeSeconds = (System.currentTimeMillis() % 1_000_000L) / 1000.0f;

        NVGRenderer.INSTANCE.beginFrame(screenWidth, screenHeight);
        TitleBackgroundRenderer.renderToScreen((int) screenWidth, (int) screenHeight, timeSeconds, alpha);
        NVGRenderer.INSTANCE.endFrame();
    }

    public static void renderOverlay(String message, float alpha) {
        Minecraft minecraft = Minecraft.getInstance();
        float screenWidth = minecraft.getWindow().getScreenWidth();
        float screenHeight = minecraft.getWindow().getScreenHeight();
        float guiScale = minecraft.getWindow().getGuiScale();
        float timeSeconds = (System.currentTimeMillis() % 1_000_000L) / 1000.0f;
        float textSize = 30.0f * guiScale;
        float textWidth = NVGRenderer.INSTANCE.textWidth(message, textSize, NVGRenderer.INSTANCE.getInterFont());
        float textX = screenWidth / 2.0f - textWidth / 2.0f;
        float textY = screenHeight / 2.0f - textSize / 2.0f;
        int shadowAlpha = Math.round(130.0f * alpha);
        int glowAlpha = Math.round(18.0f * alpha);
        int textAlpha = Math.round(244.0f * alpha);

        NVGRenderer.INSTANCE.beginFrame(screenWidth, screenHeight);
        TitleBackgroundRenderer.renderToScreen((int) screenWidth, (int) screenHeight, timeSeconds, alpha);

        float[][] glowOffsets = {
            { -1.0f, 0.0f },
            { 1.0f, 0.0f },
            { 0.0f, -1.0f },
            { 0.0f, 1.0f }
        };
        for (float[] offset : glowOffsets) {
            NVGRenderer.INSTANCE.text(
                message,
                textX + offset[0] * 1.5f * guiScale,
                textY + offset[1] * 1.5f * guiScale,
                textSize,
                withAlpha(0xFFFFFF, glowAlpha),
                NVGRenderer.INSTANCE.getInterFont()
            );
        }

        NVGRenderer.INSTANCE.text(
            message,
            textX,
            textY + 3.0f * guiScale,
            textSize,
            withAlpha(0x02060C, shadowAlpha),
            NVGRenderer.INSTANCE.getInterFont()
        );
        NVGRenderer.INSTANCE.text(
            message,
            textX,
            textY,
            textSize,
            withAlpha(0xF4F7FF, textAlpha),
            NVGRenderer.INSTANCE.getInterFont()
        );
        NVGRenderer.INSTANCE.endFrame();
    }

    private static int withAlpha(int rgb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0x00FFFFFF);
    }
}
