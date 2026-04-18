package org.cobalt.render;

import org.cobalt.render.rise.ShaderRegistry;

public final class TitleBackgroundRenderer {

    private TitleBackgroundRenderer() {}

    public static void init() {
        // Lazy-initialized through ShaderRegistry on first render.
    }

    public static int renderToTexture(int width, int height, float time) {
        return ShaderRegistry.MAIN_MENU_BACKGROUND.renderToTexture(width, height, time);
    }

    public static void renderToScreen(int width, int height, float time) {
        renderToScreen(width, height, time, 1.0f);
    }

    public static void renderToScreen(int width, int height, float time, float alpha) {
        int texture = renderToTexture(width, height, time);
        if (texture != 0) {
            ShaderRegistry.SCREEN_SPACE.renderTexture(texture, alpha);
        }
    }

    public static void cleanup() {
        ShaderRegistry.cleanup();
    }
}
