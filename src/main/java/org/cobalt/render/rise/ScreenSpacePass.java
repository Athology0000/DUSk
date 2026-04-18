package org.cobalt.render.rise;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

public final class ScreenSpacePass extends AbstractShaderPass {

    public ScreenSpacePass() {
        super("/assets/cobalt/shaders/rise/post/screen_copy.frag", ShaderPassStage.OVERLAY);
    }

    public void renderTexture(int textureId, float alpha) {
        renderTexture(textureId, alpha, false);
    }

    public void renderTextureAdditive(int textureId, float alpha) {
        renderTexture(textureId, alpha, true);
    }

    public void renderToTarget(RenderTarget output, int textureId, float alpha) {
        if (textureId == 0) {
            return;
        }
        renderToTarget(output, () -> bindTextureUniforms(textureId, alpha));
    }

    private void renderTexture(int textureId, float alpha, boolean additive) {
        if (!isValid() || textureId == 0) {
            return;
        }

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

        try {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_BLEND);
            if (additive) {
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
            } else {
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            }

            program.bind();
            bindTextureUniforms(textureId, alpha);
            FullscreenQuadRenderer.draw();
        } finally {
            GL20.glUseProgram(prevProgram);
            GL30.glBindVertexArray(prevVao);
            GL13.glActiveTexture(prevActiveTexture);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTexture);
            GL30.glBlendFuncSeparate(prevBlendSrcRgb, prevBlendDstRgb, prevBlendSrcAlpha, prevBlendDstAlpha);

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

    private void bindTextureUniforms(int textureId, float alpha) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        program.set1i("u_texture", 0);
        program.set1f("u_alpha", alpha);
    }
}
