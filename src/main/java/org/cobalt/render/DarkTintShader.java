package org.cobalt.render;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Dark mode tint shader - applies color overlay to the world.
 */
public class DarkTintShader {

  private int programId;
  private int vertexShaderId;
  private int fragmentShaderId;
  private boolean linked;

  private int uTexture;
  private int uTintColor;
  private int uIntensity;
  private int uBlendMode;
  private int uVignetteStrength;
  private int uSaturation;
  private int uContrast;
  private int uChromaticAberration;
  private int uBrightness;

  private int uDepthTexture;
  private int uDepthThreshold;
  private int uExcludeViewmodel;

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
        uniform vec4 uTintColor;
        uniform float uIntensity;
        uniform int uBlendMode;
        uniform float uVignetteStrength;
        uniform float uSaturation;
        uniform float uContrast;
        uniform float uChromaticAberration;
        uniform float uBrightness;

        uniform sampler2D uDepthTexture;
        uniform float uDepthThreshold;
        uniform int uExcludeViewmodel;

        out vec4 fragColor;

        float getLuminance(vec3 color) {
            return dot(color, vec3(0.299, 0.587, 0.114));
        }

        vec3 applySaturation(vec3 color, float sat) {
            float lum = getLuminance(color);
            return mix(vec3(lum), color, sat);
        }

        vec3 applyContrast(vec3 color, float contrast) {
            return (color - 0.5) * contrast + 0.5;
        }

        float getVignette(vec2 uv, float strength) {
            vec2 center = uv - 0.5;
            float dist = length(center);
            return 1.0 - smoothstep(0.3, 1.0, dist) * strength;
        }

        void main() {
            vec2 uv = fragTexCoord;

            if (uExcludeViewmodel == 1) {
                float depth = texture(uDepthTexture, uv).r;
                if (depth < uDepthThreshold) {
                    fragColor = texture(uTexture, uv);
                    return;
                }
            }

            vec3 worldColor;
            if (uChromaticAberration > 0.001) {
                vec2 dir = (uv - 0.5);
                float dist = length(dir);
                vec2 offset = normalize(dir) * dist * uChromaticAberration;
                worldColor.r = texture(uTexture, uv + offset * 0.5).r;
                worldColor.g = texture(uTexture, uv).g;
                worldColor.b = texture(uTexture, uv - offset * 0.5).b;
            } else {
                worldColor = texture(uTexture, uv).rgb;
            }

            worldColor = applySaturation(worldColor, uSaturation);
            worldColor = applyContrast(worldColor, uContrast);

            vec3 result;
            if (uBlendMode == 0) {
                vec3 tint = mix(vec3(1.0), uTintColor.rgb, uIntensity);
                result = worldColor * tint;
            } else if (uBlendMode == 1) {
                vec3 tint = uTintColor.rgb;
                vec3 base = worldColor;
                vec3 overlayed;
                for (int i = 0; i < 3; i++) {
                    if (base[i] < 0.5) {
                        overlayed[i] = 2.0 * base[i] * tint[i];
                    } else {
                        overlayed[i] = 1.0 - 2.0 * (1.0 - base[i]) * (1.0 - tint[i]);
                    }
                }
                result = mix(base, overlayed, uIntensity);
            } else if (uBlendMode == 2) {
                result = worldColor + uTintColor.rgb * uIntensity;
            } else {
                vec3 inverted = vec3(1.0) - (vec3(1.0) - worldColor) * (vec3(1.0) - uTintColor.rgb);
                result = mix(worldColor, inverted, uIntensity);
            }

            float vignette = getVignette(uv, uVignetteStrength);
            result *= vignette;
            result *= uBrightness;

            fragColor = vec4(result, 1.0);
        }
        """;

  public DarkTintShader() {
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
        System.err.println("[DarkTintShader] Vertex shader compilation failed:");
        System.err.println(GL20.glGetShaderInfoLog(vertexShaderId));
        cleanup();
        return;
      }

      fragmentShaderId = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
      GL20.glShaderSource(fragmentShaderId, FRAGMENT_SHADER);
      GL20.glCompileShader(fragmentShaderId);
      if (GL20.glGetShaderi(fragmentShaderId, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
        System.err.println("[DarkTintShader] Fragment shader compilation failed:");
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
        System.err.println("[DarkTintShader] Program linking failed:");
        System.err.println(GL20.glGetProgramInfoLog(programId));
        cleanup();
        return;
      }

      uTexture = GL20.glGetUniformLocation(programId, "uTexture");
      uTintColor = GL20.glGetUniformLocation(programId, "uTintColor");
      uIntensity = GL20.glGetUniformLocation(programId, "uIntensity");
      uBlendMode = GL20.glGetUniformLocation(programId, "uBlendMode");
      uVignetteStrength = GL20.glGetUniformLocation(programId, "uVignetteStrength");
      uSaturation = GL20.glGetUniformLocation(programId, "uSaturation");
      uContrast = GL20.glGetUniformLocation(programId, "uContrast");
      uChromaticAberration = GL20.glGetUniformLocation(programId, "uChromaticAberration");
      uBrightness = GL20.glGetUniformLocation(programId, "uBrightness");
      uDepthTexture = GL20.glGetUniformLocation(programId, "uDepthTexture");
      uDepthThreshold = GL20.glGetUniformLocation(programId, "uDepthThreshold");
      uExcludeViewmodel = GL20.glGetUniformLocation(programId, "uExcludeViewmodel");
      linked = true;

      System.out.println("[DarkTintShader] Dark tint shader compiled successfully!");
    } catch (Exception e) {
      System.err.println("[DarkTintShader] Failed to compile shader: " + e.getMessage());
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

  public void setTintColor(float r, float g, float b, float a) {
    GL20.glUniform4f(uTintColor, r, g, b, a);
  }

  public void setIntensity(float intensity) {
    GL20.glUniform1f(uIntensity, intensity);
  }

  public void setBlendMode(int mode) {
    GL20.glUniform1i(uBlendMode, mode);
  }

  public void setVignetteStrength(float strength) {
    GL20.glUniform1f(uVignetteStrength, strength);
  }

  public void setSaturation(float saturation) {
    GL20.glUniform1f(uSaturation, saturation);
  }

  public void setContrast(float contrast) {
    GL20.glUniform1f(uContrast, contrast);
  }

  public void setChromaticAberration(float amount) {
    GL20.glUniform1f(uChromaticAberration, amount);
  }

  public void setBrightness(float brightness) {
    GL20.glUniform1f(uBrightness, brightness);
  }

  public void setDepthTexture(int textureUnit) {
    GL20.glUniform1i(uDepthTexture, textureUnit);
  }

  public void setDepthThreshold(float threshold) {
    GL20.glUniform1f(uDepthThreshold, threshold);
  }

  public void setExcludeViewmodel(boolean exclude) {
    GL20.glUniform1i(uExcludeViewmodel, exclude ? 1 : 0);
  }

  public void unbind() {
    GL20.glUseProgram(0);
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
