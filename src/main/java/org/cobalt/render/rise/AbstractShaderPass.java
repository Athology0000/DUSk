package org.cobalt.render.rise;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

public abstract class AbstractShaderPass {

    protected final ShaderProgram program;
    private final ShaderPassStage stage;

    protected AbstractShaderPass(String fragmentPath, ShaderPassStage stage) {
        this("/assets/cobalt/shaders/rise/quad.vert", fragmentPath, stage);
    }

    protected AbstractShaderPass(String vertexPath, String fragmentPath, ShaderPassStage stage) {
        this.program = ShaderProgram.fromResources(vertexPath, fragmentPath);
        this.stage = stage;
    }

    public boolean isValid() {
        return program != null && program.isValid();
    }

    public ShaderPassStage getStage() {
        return stage;
    }

    public void cleanup() {
        if (program != null) {
            program.cleanup();
        }
    }

    protected void renderToTarget(RenderTarget output, Runnable uniformBinder) {
        if (!isValid() || output == null || !output.isReady()) {
            return;
        }

        int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int prevTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevBlendSrcRgb = GL11.glGetInteger(GL30.GL_BLEND_SRC_RGB);
        int prevBlendDstRgb = GL11.glGetInteger(GL30.GL_BLEND_DST_RGB);
        int prevBlendSrcAlpha = GL11.glGetInteger(GL30.GL_BLEND_SRC_ALPHA);
        int prevBlendDstAlpha = GL11.glGetInteger(GL30.GL_BLEND_DST_ALPHA);
        boolean depthTestEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean cullEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);

        try {
            output.clear(0.0f, 0.0f, 0.0f, 0.0f);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

            program.bind();
            uniformBinder.run();
            FullscreenQuadRenderer.draw();
        } finally {
            GL20.glUseProgram(prevProgram);
            GL30.glBindVertexArray(prevVao);
            GL13.glActiveTexture(prevActiveTexture);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTexture);
            GL30.glBlendFuncSeparate(prevBlendSrcRgb, prevBlendDstRgb, prevBlendSrcAlpha, prevBlendDstAlpha);
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
        }
    }
}
