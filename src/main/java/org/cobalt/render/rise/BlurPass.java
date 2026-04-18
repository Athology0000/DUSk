package org.cobalt.render.rise;

import java.nio.FloatBuffer;

public final class BlurPass extends AbstractShaderPass {

    private final int maxRadius;
    private final int kernelSize;

    public BlurPass() {
        this("/assets/minecraft/rise/shader/blur.frag", 127, 128);
    }

    public BlurPass(String fragmentPath) {
        this(fragmentPath, 31, 32);
    }

    public BlurPass(String fragmentPath, int maxRadius, int kernelSize) {
        super(fragmentPath, ShaderPassStage.OVERLAY);
        this.maxRadius = Math.max(0, maxRadius);
        this.kernelSize = Math.max(1, kernelSize);
    }

    public void render(RenderTarget input, RenderTarget output, int radius, float directionX, float directionY) {
        render(input, input, output, radius, directionX, directionY);
    }

    public void render(RenderTarget input, RenderTarget other, RenderTarget output, int radius, float directionX, float directionY) {
        if (input == null || !input.isReady()) {
            return;
        }

        int clampedRadius = Math.max(0, Math.min(radius, Math.min(maxRadius, kernelSize - 1)));
        FloatBuffer kernel = GaussianKernelFactory.getKernel(clampedRadius, kernelSize);

        renderToTarget(output, () -> {
            input.bindTexture(0);
            program.set1i("u_diffuse_sampler", 0);
            if (other != null && other.isReady()) {
                other.bindTexture(1);
            } else {
                input.bindTexture(1);
            }
            program.set1i("u_other_sampler", 1);
            program.set1f("u_radius", clampedRadius);
            UniformHelper.setTexelSize(program, "u_texel_size", input.getWidth(), input.getHeight());
            UniformHelper.setDirection(program, "u_direction", directionX, directionY);
            UniformHelper.uploadKernel(program, "u_kernel", kernel);
        });
    }
}
