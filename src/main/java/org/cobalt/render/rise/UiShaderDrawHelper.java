package org.cobalt.render.rise;

import org.cobalt.api.util.ui.helper.Gradient;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

public final class UiShaderDrawHelper {

    private UiShaderDrawHelper() {}

    public static void drawRoundedRect(float x, float y, float width, float height, float radius, int color, int framebufferHeight) {
        drawShader(ShaderRegistry.ROUNDED_RECT, x, y, width, height, framebufferHeight, program -> {
            program.set2f("u_size", width, height);
            program.set1f("u_radius", radius);
            UniformHelper.setColor(program, "u_color", color);
            program.set4f("u_edges", 1.0f, 1.0f, 1.0f, 1.0f);
        });
    }

    public static void drawGradientRoundedRect(
        float x,
        float y,
        float width,
        float height,
        float radius,
        int firstColor,
        int secondColor,
        Gradient gradient,
        int framebufferHeight
    ) {
        drawShader(ShaderRegistry.GRADIENT_ROUNDED_RECT, x, y, width, height, framebufferHeight, program -> {
            program.set2f("u_size", width, height);
            program.set1f("u_radius", radius);
            UniformHelper.setColor(program, "u_first_color", firstColor);
            UniformHelper.setColor(program, "u_second_color", secondColor);
            program.set4f("u_edges", 1.0f, 1.0f, 1.0f, 1.0f);
            applyGradientDirection(program, gradient);
        });
    }

    public static void drawAnimatedGradientRoundedRect(
        float x,
        float y,
        float width,
        float height,
        float radius,
        int firstColor,
        int secondColor,
        Gradient gradient,
        float timeSeconds,
        int framebufferHeight
    ) {
        drawShader(ShaderRegistry.ANIMATED_GRADIENT_ROUNDED_RECT, x, y, width, height, framebufferHeight, program -> {
            program.set2f("u_size", width, height);
            program.set1f("u_radius", radius);
            program.set1f("u_time", timeSeconds);
            UniformHelper.setColor(program, "u_first_color", firstColor);
            UniformHelper.setColor(program, "u_second_color", secondColor);
            program.set4f("u_edges", 1.0f, 1.0f, 1.0f, 1.0f);
            applyGradientDirection(program, gradient);
        });
    }

    public static void drawTriGradientRoundedRect(
        float x,
        float y,
        float width,
        float height,
        float radius,
        int firstColor,
        int secondColor,
        int thirdColor,
        Gradient gradient,
        float timeSeconds,
        int framebufferHeight
    ) {
        drawShader(ShaderRegistry.TRI_GRADIENT_ROUNDED_RECT, x, y, width, height, framebufferHeight, program -> {
            program.set2f("u_size", width, height);
            program.set1f("u_radius", radius);
            program.set1f("u_time", timeSeconds);
            UniformHelper.setColor(program, "u_first_color", firstColor);
            UniformHelper.setColor(program, "u_second_color", secondColor);
            UniformHelper.setColor(program, "u_third_color", thirdColor);
            program.set4f("u_edges", 1.0f, 1.0f, 1.0f, 1.0f);
            applyGradientDirection(program, gradient);
        });
    }

    public static void drawRoundedOutline(
        float x,
        float y,
        float width,
        float height,
        float radius,
        float borderSize,
        int color,
        int framebufferHeight
    ) {
        drawShader(ShaderRegistry.ROUNDED_OUTLINE, x, y, width, height, framebufferHeight, program -> {
            program.set2f("u_size", width, height);
            program.set1f("u_radius", radius);
            program.set1f("u_border_size", borderSize);
            UniformHelper.setColor(program, "u_color", color);
        });
    }

    public static void drawGradientOutline(
        float x,
        float y,
        float width,
        float height,
        float radius,
        float borderSize,
        int firstColor,
        int secondColor,
        Gradient gradient,
        int framebufferHeight
    ) {
        drawShader(ShaderRegistry.GRADIENT_OUTLINE, x, y, width, height, framebufferHeight, program -> {
            program.set2f("u_size", width, height);
            program.set1f("u_radius", radius);
            program.set1f("u_border_size", borderSize);
            UniformHelper.setColor(program, "u_color_1", firstColor);
            UniformHelper.setColor(program, "u_color_2", secondColor);
        });
    }

    private static void applyGradientDirection(ShaderProgram program, Gradient gradient) {
        program.set1i("u_direction", gradient == Gradient.TopToBottom ? 1 : 0);
    }

    private static void drawShader(
        ShaderProgram program,
        float x,
        float y,
        float width,
        float height,
        int framebufferHeight,
        ShaderUniformBinder uniformBinder
    ) {
        if (program == null || !program.isValid() || width <= 0.0f || height <= 0.0f || framebufferHeight <= 0) {
            return;
        }

        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevBlendSrcRgb = GL11.glGetInteger(GL30.GL_BLEND_SRC_RGB);
        int prevBlendDstRgb = GL11.glGetInteger(GL30.GL_BLEND_DST_RGB);
        int prevBlendSrcAlpha = GL11.glGetInteger(GL30.GL_BLEND_SRC_ALPHA);
        int prevBlendDstAlpha = GL11.glGetInteger(GL30.GL_BLEND_DST_ALPHA);
        boolean depthTestEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean cullEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);

        int viewportX = Math.round(x);
        int viewportY = Math.round(framebufferHeight - y - height);
        int viewportWidth = Math.max(1, Math.round(width));
        int viewportHeight = Math.max(1, Math.round(height));

        try {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glViewport(viewportX, viewportY, viewportWidth, viewportHeight);

            program.bind();
            uniformBinder.bind(program);
            FullscreenQuadRenderer.draw();
        } finally {
            GL20.glUseProgram(prevProgram);
            GL30.glBindVertexArray(prevVao);
            GL30.glBlendFuncSeparate(prevBlendSrcRgb, prevBlendDstRgb, prevBlendSrcAlpha, prevBlendDstAlpha);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);

            if (depthTestEnabled) {
                GL11.glEnable(GL11.GL_DEPTH_TEST);
            } else {
                GL11.glDisable(GL11.GL_DEPTH_TEST);
            }
            if (blendEnabled) {
                GL11.glEnable(GL11.GL_BLEND);
            } else {
                GL11.glDisable(GL11.GL_BLEND);
            }
            if (cullEnabled) {
                GL11.glEnable(GL11.GL_CULL_FACE);
            } else {
                GL11.glDisable(GL11.GL_CULL_FACE);
            }
            if (scissorEnabled) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
            } else {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }
        }
    }

    @FunctionalInterface
    private interface ShaderUniformBinder {
        void bind(ShaderProgram program);
    }
}
