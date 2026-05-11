package org.cobalt.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

public class HudGlowRenderer {

  private static final Minecraft MC = Minecraft.getInstance();

  private static HudGlowShader glowShader;
  private static boolean initialized = false;

  private static int quadVao;
  private static int quadVbo;

  public static void renderGlowRect(
    float x,
    float y,
    float width,
    float height,
    float cornerRadius,
    float glowSize,
    int colorA,
    int colorB,
    float alpha
  ) {
    return;
  }

  public static void renderGlowRectDisabled(
    float x,
    float y,
    float width,
    float height,
    float cornerRadius,
    float glowSize,
    int colorA,
    int colorB,
    float alpha
  ) {
    if (width <= 1f || height <= 1f || alpha <= 0f) {
      return;
    }

    if (!initialized) {
      init();
    }
    if (!initialized || glowShader == null || !glowShader.isValid()) {
      return;
    }

    RenderTarget framebuffer = MC.getMainRenderTarget();
    if (framebuffer == null || framebuffer.width <= 0 || framebuffer.height <= 0) {
      return;
    }

    int screenWidth = MC.getWindow().getScreenWidth();
    int screenHeight = MC.getWindow().getScreenHeight();
    if (screenWidth <= 0 || screenHeight <= 0) {
      return;
    }

    float scaleX = framebuffer.width / (float) screenWidth;
    float scaleY = framebuffer.height / (float) screenHeight;
    float framebufferX = x * scaleX;
    float framebufferY = y * scaleY;
    float framebufferWidth = width * scaleX;
    float framebufferHeight = height * scaleY;
    float framebufferRadius = cornerRadius * Math.max(scaleX, scaleY);
    float framebufferGlow = glowSize * Math.max(scaleX, scaleY);

    int prevFramebuffer = 0;
    int prevProgram = 0;
    int prevVao = 0;
    int prevActiveTexture = 0;
    int prevBoundTexture = 0;
    int prevBlendSrcRgb = 0;
    int prevBlendDstRgb = 0;
    int prevBlendSrcAlpha = 0;
    int prevBlendDstAlpha = 0;
    int prevBlendEquationRgb = 0;
    int prevBlendEquationAlpha = 0;
    boolean blendEnabled = false;
    boolean depthTestEnabled = false;
    boolean cullFaceEnabled = false;
    boolean scissorEnabled = false;
    boolean depthMaskEnabled = false;
    int[] viewport = new int[4];
    boolean stateCaptured = false;

    try {
      prevFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
      prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
      prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
      prevActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
      prevBoundTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
      prevBlendSrcRgb = GL11.glGetInteger(GL30.GL_BLEND_SRC_RGB);
      prevBlendDstRgb = GL11.glGetInteger(GL30.GL_BLEND_DST_RGB);
      prevBlendSrcAlpha = GL11.glGetInteger(GL30.GL_BLEND_SRC_ALPHA);
      prevBlendDstAlpha = GL11.glGetInteger(GL30.GL_BLEND_DST_ALPHA);
      prevBlendEquationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
      prevBlendEquationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
      blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
      depthTestEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
      cullFaceEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
      scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
      depthMaskEnabled = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
      GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
      stateCaptured = true;

      GL11.glViewport(0, 0, framebuffer.width, framebuffer.height);

      if (scissorEnabled) {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
      }
      GL11.glDisable(GL11.GL_DEPTH_TEST);
      GL11.glDepthMask(false);
      GL11.glDisable(GL11.GL_CULL_FACE);
      GL11.glEnable(GL11.GL_BLEND);
      GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
      GL20.glBlendEquationSeparate(GL20.GL_FUNC_ADD, GL20.GL_FUNC_ADD);

      if (!glowShader.use()) {
        initialized = false;
        return;
      }

      glowShader.setScreenSize(framebuffer.width, framebuffer.height);
      glowShader.setRect(framebufferX, framebufferY, framebufferWidth, framebufferHeight);
      glowShader.setCornerRadius(Math.max(0f, Math.min(framebufferRadius, Math.min(framebufferWidth, framebufferHeight) * 0.5f)));
      glowShader.setGlowSize(Math.max(4f, framebufferGlow));
      glowShader.setAlpha(Math.max(0f, Math.min(alpha, 1f)));
      glowShader.setTime((System.currentTimeMillis() % 1_000_000L) / 1000f);
      glowShader.setColorA(colorA);
      glowShader.setColorB(colorB);

      GL30.glBindVertexArray(quadVao);
      GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
      GL30.glBindVertexArray(0);
    } catch (Exception e) {
      System.err.println("[HudGlowRenderer] Error during glow rendering: " + e.getMessage());
      e.printStackTrace();
    } finally {
      if (!stateCaptured) {
        return;
      }

      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFramebuffer);
      GL20.glUseProgram(prevProgram);
      GL30.glBindVertexArray(prevVao);
      GL13.glActiveTexture(prevActiveTexture);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevBoundTexture);
      GL30.glBlendFuncSeparate(prevBlendSrcRgb, prevBlendDstRgb, prevBlendSrcAlpha, prevBlendDstAlpha);
      GL20.glBlendEquationSeparate(prevBlendEquationRgb, prevBlendEquationAlpha);

      if (!blendEnabled) GL11.glDisable(GL11.GL_BLEND);
      if (depthTestEnabled) GL11.glEnable(GL11.GL_DEPTH_TEST);
      if (cullFaceEnabled) GL11.glEnable(GL11.GL_CULL_FACE);
      if (scissorEnabled) GL11.glEnable(GL11.GL_SCISSOR_TEST);
      GL11.glDepthMask(depthMaskEnabled);
      GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
    }
  }

  public static void cleanup() {
    if (glowShader != null) {
      glowShader.cleanup();
      glowShader = null;
    }
    if (quadVao != 0) GL30.glDeleteVertexArrays(quadVao);
    if (quadVbo != 0) GL20.glDeleteBuffers(quadVbo);
    quadVao = 0;
    quadVbo = 0;
    initialized = false;
  }

  private static void init() {
    try {
      glowShader = new HudGlowShader();
      if (glowShader == null || !glowShader.isValid()) {
        glowShader = null;
        return;
      }
      createFullscreenQuad();
      initialized = true;
    } catch (Exception e) {
      System.err.println("[HudGlowRenderer] Failed to initialize: " + e.getMessage());
      e.printStackTrace();
    }
  }

  private static void createFullscreenQuad() {
    float[] vertices = {
      -1.0f,  1.0f, 0.0f, 1.0f,
      -1.0f, -1.0f, 0.0f, 0.0f,
       1.0f, -1.0f, 1.0f, 0.0f,
      -1.0f,  1.0f, 0.0f, 1.0f,
       1.0f, -1.0f, 1.0f, 0.0f,
       1.0f,  1.0f, 1.0f, 1.0f
    };

    quadVao = GL30.glGenVertexArrays();
    quadVbo = GL20.glGenBuffers();

    GL30.glBindVertexArray(quadVao);
    GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, quadVbo);
    GL20.glBufferData(GL20.GL_ARRAY_BUFFER, vertices, GL20.GL_STATIC_DRAW);

    GL30.glEnableVertexAttribArray(0);
    GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 4 * Float.BYTES, 0);

    GL30.glEnableVertexAttribArray(1);
    GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 4 * Float.BYTES, 2L * Float.BYTES);

    GL30.glBindVertexArray(0);
  }
}
