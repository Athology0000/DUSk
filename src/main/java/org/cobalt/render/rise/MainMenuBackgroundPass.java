package org.cobalt.render.rise;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

public final class MainMenuBackgroundPass {

    private final ShaderProgram backgroundProgram =
        ShaderProgram.fromResources("/assets/cobalt/shaders/rise/quad.vert", "/assets/minecraft/rise/shader/main_menu/background.frag");
    private final ShaderProgram filterProgram =
        ShaderProgram.fromResources("/assets/cobalt/shaders/rise/quad.vert", "/assets/minecraft/rise/shader/main_menu/filter.glsl");

    private final RenderTarget sceneTarget = new RenderTarget();
    private final RenderTarget filteredTarget = new RenderTarget();

    private boolean filterEnabled = true;

    public int renderToTexture(int width, int height, float timeSeconds) {
        if (width <= 0 || height <= 0 || !backgroundProgram.isValid()) {
            return 0;
        }

        sceneTarget.ensureSize(width, height);
        filteredTarget.ensureSize(width, height);

        int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int prevTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        boolean depthTestEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean cullEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);

        try {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_CULL_FACE);
            if (scissorEnabled) {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }

            sceneTarget.clear(0.0f, 0.0f, 0.0f, 1.0f);
            backgroundProgram.bind();
            backgroundProgram.set2f("resolution", width, height);
            backgroundProgram.set1f("time", timeSeconds);
            FullscreenQuadRenderer.draw();

            if (!filterEnabled || !filterProgram.isValid()) {
                return sceneTarget.getTextureId();
            }

            filteredTarget.clear(0.0f, 0.0f, 0.0f, 1.0f);
            filterProgram.bind();
            sceneTarget.bindTexture(0);
            filterProgram.set1i("u_texture", 0);
            filterProgram.set2f("u_texelSize", width, height);
            filterProgram.set1f("u_time", timeSeconds);
            FullscreenQuadRenderer.draw();

            return filteredTarget.getTextureId();
        } finally {
            GL20.glUseProgram(prevProgram);
            GL30.glBindVertexArray(prevVao);
            GL13.glActiveTexture(prevActiveTexture);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTexture);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
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

    public void setFilterEnabled(boolean filterEnabled) {
        this.filterEnabled = filterEnabled;
    }

    public void cleanup() {
        backgroundProgram.cleanup();
        filterProgram.cleanup();
        sceneTarget.cleanup();
        filteredTarget.cleanup();
    }
}
