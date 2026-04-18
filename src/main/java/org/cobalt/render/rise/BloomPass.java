package org.cobalt.render.rise;

public final class BloomPass {

    private final BlurPass horizontalPass;
    private final BlurPass verticalPass;

    public BloomPass(BlurPass horizontalPass, BlurPass verticalPass) {
        this.horizontalPass = horizontalPass;
        this.verticalPass = verticalPass;
    }

    public boolean isValid() {
        return horizontalPass != null && horizontalPass.isValid()
            && verticalPass != null && verticalPass.isValid();
    }

    public void render(RenderTarget input, RenderTarget ping, RenderTarget output, int radius) {
        if (!isValid() || ping == null || output == null) {
            return;
        }
        horizontalPass.render(input, input, ping, radius, 1.0f, 0.0f);
        verticalPass.render(ping, input, output, radius, 0.0f, 1.0f);
    }
}
