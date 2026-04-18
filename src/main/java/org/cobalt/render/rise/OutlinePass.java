package org.cobalt.render.rise;

public final class OutlinePass extends AbstractShaderPass {

    public OutlinePass() {
        super("/assets/minecraft/rise/shader/outline.frag", ShaderPassStage.OVERLAY);
    }

    public void render(RenderTarget input, RenderTarget output, float radius, int color) {
        if (input == null || !input.isReady()) {
            return;
        }
        renderToTarget(output, () -> {
            input.bindTexture(0);
            program.set1i("u_texture", 0);
            UniformHelper.setTexelSize(program, "u_texel_size", input.getWidth(), input.getHeight());
            program.set1f("u_radius", radius);
            UniformHelper.setColor(program, "u_color", color);
        });
    }
}
