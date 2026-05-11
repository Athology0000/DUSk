package org.cobalt.render;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

public class HudGlowShader {

  private int programId;
  private int vertexShaderId;
  private int fragmentShaderId;
  private boolean linked;

  private int uScreenSize;
  private int uRect;
  private int uCornerRadius;
  private int uGlowSize;
  private int uAlpha;
  private int uTime;
  private int uColorA;
  private int uColorB;

  private static final String VERTEX_SHADER = """
      #version 150

      in vec2 position;
      in vec2 texCoord;

      out vec2 fragTexCoord;

      void main() {
          gl_Position = vec4(position, 0.0, 1.0);
          fragTexCoord = texCoord;
      }
      """;

  private static final String FRAGMENT_SHADER = """
      #version 150

      in vec2 fragTexCoord;

      uniform vec2 uScreenSize;
      uniform vec4 uRect;
      uniform float uCornerRadius;
      uniform float uGlowSize;
      uniform float uAlpha;
      uniform float uTime;
      uniform vec4 uColorA;
      uniform vec4 uColorB;

      out vec4 fragColor;

      float roundedBoxSdf(vec2 p, vec2 halfSize, float radius) {
          vec2 q = abs(p) - halfSize + vec2(radius);
          return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
      }

      void main() {
          vec2 pixel = vec2(
              fragTexCoord.x * uScreenSize.x,
              (1.0 - fragTexCoord.y) * uScreenSize.y
          );

          vec2 rectCenter = uRect.xy + uRect.zw * 0.5;
          vec2 halfSize = uRect.zw * 0.5;
          float sdf = roundedBoxSdf(pixel - rectCenter, halfSize, uCornerRadius);
          float aa = max(fwidth(sdf), 1.0);
          float inside = 1.0 - smoothstep(-aa, aa, sdf);
          float outside = max(sdf, 0.0);
          float falloff = exp(-outside / max(uGlowSize * 0.48, 1.0));
          float cutoff = 1.0 - smoothstep(uGlowSize * 0.85, uGlowSize * 1.25, outside);
          float halo = falloff * cutoff * (1.0 - inside);
          float edge = 1.0 - smoothstep(0.0, aa * 3.25, abs(sdf));
          float innerEdge = (1.0 - smoothstep(0.0, uGlowSize * 0.38, max(-sdf, 0.0))) * inside;
          float alpha = (halo * 0.95 + edge * 0.9 + innerEdge * 0.32) * uAlpha;

          if (alpha <= 0.002) {
              discard;
          }

          float t = clamp((pixel.x - uRect.x) / max(uRect.z, 1.0), 0.0, 1.0);
          float shimmer = sin(uTime * 1.75 + pixel.x * 0.018 + pixel.y * 0.012) * 0.5 + 0.5;
          t = clamp(mix(t, shimmer, 0.16), 0.0, 1.0);
          float breathe = 0.84 + 0.16 * sin(uTime * 2.2);
          vec4 color = mix(uColorA, uColorB, t);
          fragColor = vec4(color.rgb, alpha * breathe * color.a);
      }
      """;

  public HudGlowShader() {
    compile();
  }

  private void compile() {
    cleanup();
    try {
      programId = GL20.glCreateProgram();

      vertexShaderId = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
      GL20.glShaderSource(vertexShaderId, VERTEX_SHADER);
      GL20.glCompileShader(vertexShaderId);
      if (GL20.glGetShaderi(vertexShaderId, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
        System.err.println("[HudGlowShader] Vertex shader compilation failed:");
        System.err.println(GL20.glGetShaderInfoLog(vertexShaderId));
        cleanup();
        return;
      }

      fragmentShaderId = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
      GL20.glShaderSource(fragmentShaderId, FRAGMENT_SHADER);
      GL20.glCompileShader(fragmentShaderId);
      if (GL20.glGetShaderi(fragmentShaderId, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
        System.err.println("[HudGlowShader] Fragment shader compilation failed:");
        System.err.println(GL20.glGetShaderInfoLog(fragmentShaderId));
        cleanup();
        return;
      }

      GL20.glAttachShader(programId, vertexShaderId);
      GL20.glAttachShader(programId, fragmentShaderId);
      GL20.glBindAttribLocation(programId, 0, "position");
      GL20.glBindAttribLocation(programId, 1, "texCoord");
      GL30.glBindFragDataLocation(programId, 0, "fragColor");
      GL20.glLinkProgram(programId);
      if (GL20.glGetProgrami(programId, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
        System.err.println("[HudGlowShader] Program linking failed:");
        System.err.println(GL20.glGetProgramInfoLog(programId));
        cleanup();
        return;
      }

      uScreenSize = GL20.glGetUniformLocation(programId, "uScreenSize");
      uRect = GL20.glGetUniformLocation(programId, "uRect");
      uCornerRadius = GL20.glGetUniformLocation(programId, "uCornerRadius");
      uGlowSize = GL20.glGetUniformLocation(programId, "uGlowSize");
      uAlpha = GL20.glGetUniformLocation(programId, "uAlpha");
      uTime = GL20.glGetUniformLocation(programId, "uTime");
      uColorA = GL20.glGetUniformLocation(programId, "uColorA");
      uColorB = GL20.glGetUniformLocation(programId, "uColorB");
      linked = true;
    } catch (Exception e) {
      System.err.println("[HudGlowShader] Failed to compile shader: " + e.getMessage());
      e.printStackTrace();
      cleanup();
    }
  }

  public boolean use() {
    if (!isValid()) {
      return false;
    }
    GL20.glUseProgram(programId);
    return GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) == programId;
  }

  public void setScreenSize(float width, float height) {
    GL20.glUniform2f(uScreenSize, width, height);
  }

  public void setRect(float x, float y, float width, float height) {
    GL20.glUniform4f(uRect, x, y, width, height);
  }

  public void setCornerRadius(float radius) {
    GL20.glUniform1f(uCornerRadius, radius);
  }

  public void setGlowSize(float glowSize) {
    GL20.glUniform1f(uGlowSize, glowSize);
  }

  public void setAlpha(float alpha) {
    GL20.glUniform1f(uAlpha, alpha);
  }

  public void setTime(float time) {
    GL20.glUniform1f(uTime, time);
  }

  public void setColorA(int argb) {
    setColor(uColorA, argb);
  }

  public void setColorB(int argb) {
    setColor(uColorB, argb);
  }

  private void setColor(int uniform, int argb) {
    GL20.glUniform4f(
      uniform,
      ((argb >>> 16) & 0xFF) / 255f,
      ((argb >>> 8) & 0xFF) / 255f,
      (argb & 0xFF) / 255f,
      ((argb >>> 24) & 0xFF) / 255f
    );
  }

  public boolean isValid() {
    return linked && programId != 0;
  }

  public void cleanup() {
    if (vertexShaderId != 0) GL20.glDeleteShader(vertexShaderId);
    if (fragmentShaderId != 0) GL20.glDeleteShader(fragmentShaderId);
    if (programId != 0) GL20.glDeleteProgram(programId);
    vertexShaderId = 0;
    fragmentShaderId = 0;
    programId = 0;
    linked = false;
  }
}
