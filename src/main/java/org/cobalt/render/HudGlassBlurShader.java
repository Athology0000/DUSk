package org.cobalt.render;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

public class HudGlassBlurShader {

  private int programId;
  private int vertexShaderId;
  private int fragmentShaderId;
  private boolean linked;

  private int uTexture;
  private int uScreenSize;
  private int uRect;
  private int uCornerRadius;
  private int uBlurStrength;

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

      uniform sampler2D uTexture;
      uniform vec2 uScreenSize;
      uniform vec4 uRect;
      uniform float uCornerRadius;
      uniform float uBlurStrength;

      out vec4 fragColor;

      float roundedBoxSdf(vec2 p, vec2 halfSize, float radius) {
          vec2 q = abs(p) - halfSize + vec2(radius);
          return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
      }

      vec3 sampleGaussianBlur(vec2 uv, vec2 texel) {
          float sigma = 2.15;
          vec3 accum = vec3(0.0);
          float total = 0.0;

          for (int x = -3; x <= 3; x++) {
              for (int y = -3; y <= 3; y++) {
                  float dist2 = float(x * x + y * y);
                  float weight = exp(-dist2 / (2.0 * sigma * sigma));
                  vec2 offset = vec2(float(x), float(y)) * texel * uBlurStrength;
                  accum += texture(uTexture, uv + offset).rgb * weight;
                  total += weight;
              }
          }

          return accum / max(total, 0.0001);
      }

      void main() {
          vec2 pixel = vec2(
              fragTexCoord.x * uScreenSize.x,
              (1.0 - fragTexCoord.y) * uScreenSize.y
          );

          vec2 rectCenter = uRect.xy + uRect.zw * 0.5;
          vec2 halfSize = uRect.zw * 0.5;
          float sdf = roundedBoxSdf(pixel - rectCenter, halfSize, uCornerRadius);
          float mask = 1.0 - smoothstep(0.0, 1.65, sdf);
          if (mask <= 0.001) {
              discard;
          }

          vec2 texel = vec2(1.0 / uScreenSize.x, 1.0 / uScreenSize.y);
          vec3 blurred = sampleGaussianBlur(fragTexCoord, texel);
          fragColor = vec4(blurred, mask);
      }
      """;

  public HudGlassBlurShader() {
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
        System.err.println("[HudGlassBlurShader] Vertex shader compilation failed:");
        System.err.println(GL20.glGetShaderInfoLog(vertexShaderId));
        cleanup();
        return;
      }

      fragmentShaderId = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
      GL20.glShaderSource(fragmentShaderId, FRAGMENT_SHADER);
      GL20.glCompileShader(fragmentShaderId);
      if (GL20.glGetShaderi(fragmentShaderId, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
        System.err.println("[HudGlassBlurShader] Fragment shader compilation failed:");
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
        System.err.println("[HudGlassBlurShader] Program linking failed:");
        System.err.println(GL20.glGetProgramInfoLog(programId));
        cleanup();
        return;
      }

      uTexture = GL20.glGetUniformLocation(programId, "uTexture");
      uScreenSize = GL20.glGetUniformLocation(programId, "uScreenSize");
      uRect = GL20.glGetUniformLocation(programId, "uRect");
      uCornerRadius = GL20.glGetUniformLocation(programId, "uCornerRadius");
      uBlurStrength = GL20.glGetUniformLocation(programId, "uBlurStrength");
      linked = true;
    } catch (Exception e) {
      System.err.println("[HudGlassBlurShader] Failed to compile shader: " + e.getMessage());
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

  public void setTexture(int textureUnit) {
    GL20.glUniform1i(uTexture, textureUnit);
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

  public void setBlurStrength(float blurStrength) {
    GL20.glUniform1f(uBlurStrength, blurStrength);
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
