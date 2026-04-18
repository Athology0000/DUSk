package org.cobalt.render.rise;

public final class ShaderRegistry {

    public static final ScreenSpacePass SCREEN_SPACE = new ScreenSpacePass();
    public static final OutlinePass OUTLINE = new OutlinePass();
    public static final BlurPass BLUR_A = new BlurPass();
    public static final BlurPass BLUR_B = new BlurPass();
    public static final BlurPass BLOOM_A = new BlurPass("/assets/minecraft/rise/shader/bloom.frag", 23, 24);
    public static final BlurPass BLOOM_B = new BlurPass("/assets/minecraft/rise/shader/bloom.frag", 23, 24);
    public static final BloomPass BLOOM = new BloomPass(BLOOM_A, BLOOM_B);
    public static final MainMenuBackgroundPass MAIN_MENU_BACKGROUND = new MainMenuBackgroundPass();

    public static final ShaderProgram ROUNDED_RECT =
        ShaderProgram.fromResources("/assets/cobalt/shaders/rise/quad.vert", "/assets/minecraft/rise/shader/rq.frag");
    public static final ShaderProgram GRADIENT_ROUNDED_RECT =
        ShaderProgram.fromResources("/assets/cobalt/shaders/rise/quad.vert", "/assets/minecraft/rise/shader/rgq.glsl");
    public static final ShaderProgram ANIMATED_GRADIENT_ROUNDED_RECT =
        ShaderProgram.fromResources("/assets/cobalt/shaders/rise/quad.vert", "/assets/minecraft/rise/shader/rgqtest.glsl");
    public static final ShaderProgram TRI_GRADIENT_ROUNDED_RECT =
        ShaderProgram.fromResources("/assets/cobalt/shaders/rise/quad.vert", "/assets/minecraft/rise/shader/trirgq.glsl");
    public static final ShaderProgram ROUNDED_OUTLINE =
        ShaderProgram.fromResources("/assets/cobalt/shaders/rise/quad.vert", "/assets/minecraft/rise/shader/roq.glsl");
    public static final ShaderProgram GRADIENT_OUTLINE =
        ShaderProgram.fromResources("/assets/cobalt/shaders/rise/quad.vert", "/assets/minecraft/rise/shader/rogq.frag");

    private ShaderRegistry() {}

    public static void cleanup() {
        SCREEN_SPACE.cleanup();
        OUTLINE.cleanup();
        BLUR_A.cleanup();
        BLUR_B.cleanup();
        BLOOM_A.cleanup();
        BLOOM_B.cleanup();
        MAIN_MENU_BACKGROUND.cleanup();
        ROUNDED_RECT.cleanup();
        GRADIENT_ROUNDED_RECT.cleanup();
        ANIMATED_GRADIENT_ROUNDED_RECT.cleanup();
        TRI_GRADIENT_ROUNDED_RECT.cleanup();
        ROUNDED_OUTLINE.cleanup();
        GRADIENT_OUTLINE.cleanup();
        FullscreenQuadRenderer.cleanup();
    }
}
