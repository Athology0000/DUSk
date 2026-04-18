package org.cobalt.render.rise;

import java.nio.FloatBuffer;

public final class UniformHelper {

    private UniformHelper() {}

    public static void setColor(ShaderProgram program, String uniform, int argb) {
        float alpha = ((argb >>> 24) & 0xFF) / 255.0f;
        float red = ((argb >>> 16) & 0xFF) / 255.0f;
        float green = ((argb >>> 8) & 0xFF) / 255.0f;
        float blue = (argb & 0xFF) / 255.0f;
        program.set4f(uniform, red, green, blue, alpha);
    }

    public static void setDirection(ShaderProgram program, String uniform, float x, float y) {
        float length = (float) Math.sqrt(x * x + y * y);
        if (length < 1.0e-4f) {
            program.set2f(uniform, 1.0f, 0.0f);
            return;
        }
        program.set2f(uniform, x / length, y / length);
    }

    public static void setTexelSize(ShaderProgram program, String uniform, int width, int height) {
        float texelX = width <= 0 ? 0.0f : 1.0f / width;
        float texelY = height <= 0 ? 0.0f : 1.0f / height;
        program.set2f(uniform, texelX, texelY);
    }

    public static void uploadKernel(ShaderProgram program, String uniform, FloatBuffer kernel) {
        program.setFloatBuffer(uniform, kernel);
    }
}
