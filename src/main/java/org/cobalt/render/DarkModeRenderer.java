package org.cobalt.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Dark Mode Shader Renderer
 * Applies a color tint overlay with advanced effects.
 */
public class DarkModeRenderer {

  private static DarkTintShader tintShader;
  private static boolean initialized = false;

  private static int quadVAO;
  private static int quadVBO;

  private static int tempTexture = 0;
  private static int tempWidth = 0;
  private static int tempHeight = 0;

  private static long lastValidationTime = 0;
  private static final long VALIDATION_INTERVAL_MS = 5000;

  private static int lastFbWidth = 0;
  private static int lastFbHeight = 0;
  private static int resizeSkipFrames = 0;
  private static long lastRenderNs = 0L;

  private static float tintR = 0.2f;
  private static float tintG = 0.1f;
  private static float tintB = 0.3f;
  private static float tintA = 1.0f;
  private static float intensity = 0.6f;
  private static int blendMode = 0;
  private static float vignetteStrength = 0.0f;
  private static float saturation = 1.0f;
  private static float contrast = 1.0f;
  private static float chromaticAberration = 0.0f;
  private static float brightness = 1.5f;

  private static boolean excludeViewmodel = true;
  private static float depthThreshold = 0.15f;

  public static void init() {
    if (initialized) return;
    try {
      tintShader = new DarkTintShader();
      if (tintShader == null || !tintShader.isValid()) {
        tintShader = null;
        return;
      }
      createFullscreenQuad();
      initialized = true;
    } catch (Exception e) {
      System.err.println("[DarkModeRenderer] Failed to initialize: " + e.getMessage());
      e.printStackTrace();
    }
  }

  private static void createFullscreenQuad() {
    float[] vertices = {
      -1.0f, 1.0f, 0.0f, 1.0f,
      -1.0f, -1.0f, 0.0f, 0.0f,
      1.0f, -1.0f, 1.0f, 0.0f,
      -1.0f, 1.0f, 0.0f, 1.0f,
      1.0f, -1.0f, 1.0f, 0.0f,
      1.0f, 1.0f, 1.0f, 1.0f
    };

    quadVAO = GL30.glGenVertexArrays();
    quadVBO = GL20.glGenBuffers();

    GL30.glBindVertexArray(quadVAO);
    GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, quadVBO);
    GL20.glBufferData(GL20.GL_ARRAY_BUFFER, vertices, GL20.GL_STATIC_DRAW);

    GL30.glEnableVertexAttribArray(0);
    GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 4 * Float.BYTES, 0);

    GL30.glEnableVertexAttribArray(1);
    GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);

    GL30.glBindVertexArray(0);
  }

  private static void ensureTempTexture(int width, int height) {
    if (tempTexture == 0) {
      tempTexture = GL11.glGenTextures();
    }

    if (tempWidth != width || tempHeight != height) {
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, tempTexture);
      GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

      tempWidth = width;
      tempHeight = height;
    }
  }

  public static void renderDarkModeOverlay() {
    long nowNs = System.nanoTime();
    if (nowNs - lastRenderNs < 1_000_000L) {
      return;
    }
    lastRenderNs = nowNs;

    Minecraft mc = Minecraft.getInstance();
    if (mc.level == null || mc.player == null) {
      return;
    }

    if (!initialized) {
      init();
    }
    if (!initialized || tintShader == null || !tintShader.isValid()) {
      return;
    }

    long currentTime = System.currentTimeMillis();
    if (currentTime - lastValidationTime > VALIDATION_INTERVAL_MS) {
      lastValidationTime = currentTime;
      if (quadVAO == 0 || quadVBO == 0) {
        initialized = false;
        init();
        if (!initialized) return;
      }
      if (tempTexture != 0 && !GL11.glIsTexture(tempTexture)) {
        tempTexture = 0;
        tempWidth = 0;
        tempHeight = 0;
      }
    }

    int prevShaderProgram = 0;
    int prevVAO = 0;
    int prevActiveTexture = 0;
    int prevBoundTexture = 0;
    int prevBlendSrcRGB = 0;
    int prevBlendDstRGB = 0;
    int prevBlendSrcAlpha = 0;
    int prevBlendDstAlpha = 0;
    int prevBlendEquationRGB = 0;
    int prevBlendEquationAlpha = 0;
    boolean blendEnabled = false;
    boolean depthTestEnabled = false;
    boolean cullFaceEnabled = false;
    boolean scissorTestEnabled = false;
    boolean depthMaskEnabled = false;
    int[] viewport = new int[4];
    boolean stateCaptured = false;

    try {
      RenderTarget mainFramebuffer = mc.getMainRenderTarget();
      if (mainFramebuffer == null) {
        return;
      }

      int fbWidth = mainFramebuffer.width;
      int fbHeight = mainFramebuffer.height;
      if (fbWidth <= 0 || fbHeight <= 0) {
        return;
      }

      if (fbWidth != lastFbWidth || fbHeight != lastFbHeight) {
        lastFbWidth = fbWidth;
        lastFbHeight = fbHeight;
        resizeSkipFrames = 3;
        tempWidth = 0;
        tempHeight = 0;
      }

      if (resizeSkipFrames > 0) {
        resizeSkipFrames--;
        return;
      }

      ensureTempTexture(fbWidth, fbHeight);

      prevShaderProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
      prevVAO = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
      prevActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
      prevBoundTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
      prevBlendSrcRGB = GL11.glGetInteger(GL30.GL_BLEND_SRC_RGB);
      prevBlendDstRGB = GL11.glGetInteger(GL30.GL_BLEND_DST_RGB);
      prevBlendSrcAlpha = GL11.glGetInteger(GL30.GL_BLEND_SRC_ALPHA);
      prevBlendDstAlpha = GL11.glGetInteger(GL30.GL_BLEND_DST_ALPHA);
      prevBlendEquationRGB = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
      prevBlendEquationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
      blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
      depthTestEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
      cullFaceEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
      scissorTestEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
      depthMaskEnabled = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
      GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
      stateCaptured = true;
      GL11.glViewport(0, 0, fbWidth, fbHeight);

      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, tempTexture);
      GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, fbWidth, fbHeight);

      if (scissorTestEnabled) {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
      }

      GL11.glDisable(GL11.GL_DEPTH_TEST);
      GL11.glDepthMask(false);
      GL11.glDisable(GL11.GL_CULL_FACE);
      GL11.glEnable(GL11.GL_BLEND);
      GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
      GL20.glBlendEquationSeparate(GL20.GL_FUNC_ADD, GL20.GL_FUNC_ADD);

      if (!tintShader.use()) {
        System.err.println("[DarkModeRenderer] Shader program failed to bind; reinitializing.");
        tintShader.cleanup();
        tintShader = null;
        initialized = false;
        return;
      }
      tintShader.setTexture(0);
      tintShader.setExcludeViewmodel(false);
      tintShader.setDepthThreshold(depthThreshold);
      tintShader.setTintColor(tintR, tintG, tintB, tintA);
      tintShader.setIntensity(intensity);
      tintShader.setBlendMode(blendMode);
      tintShader.setVignetteStrength(vignetteStrength);
      tintShader.setSaturation(saturation);
      tintShader.setContrast(contrast);
      tintShader.setChromaticAberration(chromaticAberration);
      tintShader.setBrightness(brightness);

      GL30.glBindVertexArray(quadVAO);
      GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
      GL30.glBindVertexArray(0);
    } catch (Exception e) {
      System.err.println("[DarkModeRenderer] Error during rendering: " + e.getMessage());
      e.printStackTrace();
    } finally {
      if (!stateCaptured) {
        return;
      }

      GL20.glUseProgram(prevShaderProgram);
      GL30.glBindVertexArray(prevVAO);
      GL13.glActiveTexture(prevActiveTexture);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevBoundTexture);
      GL30.glBlendFuncSeparate(prevBlendSrcRGB, prevBlendDstRGB, prevBlendSrcAlpha, prevBlendDstAlpha);
      GL20.glBlendEquationSeparate(prevBlendEquationRGB, prevBlendEquationAlpha);

      if (!blendEnabled) GL11.glDisable(GL11.GL_BLEND);
      if (depthTestEnabled) GL11.glEnable(GL11.GL_DEPTH_TEST);
      if (cullFaceEnabled) GL11.glEnable(GL11.GL_CULL_FACE);
      if (scissorTestEnabled) GL11.glEnable(GL11.GL_SCISSOR_TEST);
      GL11.glDepthMask(depthMaskEnabled);
      GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
    }
  }

  public static void setTintColor(float r, float g, float b) {
    tintR = r;
    tintG = g;
    tintB = b;
  }

  public static void setIntensity(float value) {
    intensity = Math.max(0.0f, Math.min(1.0f, value));
  }

  public static void setBlendMode(int mode) {
    blendMode = Math.max(0, Math.min(3, mode));
  }

  public static void setVignetteStrength(float strength) {
    vignetteStrength = Math.max(0.0f, Math.min(1.0f, strength));
  }

  public static void setSaturation(float sat) {
    saturation = Math.max(0.0f, Math.min(2.0f, sat));
  }

  public static void setContrast(float con) {
    contrast = Math.max(0.0f, Math.min(2.0f, con));
  }

  public static void setChromaticAberration(float amount) {
    chromaticAberration = Math.max(0.0f, Math.min(0.01f, amount));
  }

  public static void setBrightness(float bright) {
    brightness = Math.max(0.1f, Math.min(5.0f, bright));
  }

  public static void setExcludeViewmodel(boolean exclude) {
    excludeViewmodel = exclude;
  }

  public static void setDepthThreshold(float threshold) {
    depthThreshold = Math.max(0.0f, Math.min(1.0f, threshold));
  }

  public static void cleanup() {
    if (tintShader != null) {
      tintShader.cleanup();
      tintShader = null;
    }
    if (quadVAO != 0) GL30.glDeleteVertexArrays(quadVAO);
    if (quadVBO != 0) GL20.glDeleteBuffers(quadVBO);
    if (tempTexture != 0) {
      GL11.glDeleteTextures(tempTexture);
      tempTexture = 0;
    }
    tempWidth = 0;
    tempHeight = 0;
    initialized = false;
  }
}
