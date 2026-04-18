package org.cobalt.render.rise;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class ShaderProgram {

    private final String label;
    private final int programId;
    private final Map<String, Integer> uniformLocations = new HashMap<>();
    private boolean cleanedUp = false;
    private boolean bindFailureLogged = false;

    private ShaderProgram(String label, int programId) {
        this.label = label;
        this.programId = programId;
    }

    public static ShaderProgram fromResources(String vertexPath, String fragmentPath) {
        try {
            String vertexSource = readResource(vertexPath);
            String fragmentSource = readResource(fragmentPath);
            return new ShaderProgram(fragmentPath, compile(vertexSource, fragmentSource, vertexPath, fragmentPath));
        } catch (Exception exception) {
            System.out.println("[ShaderProgram] Failed to load resources " + vertexPath + " and " + fragmentPath);
            exception.printStackTrace();
            return new ShaderProgram(fragmentPath, 0);
        }
    }

    private static String readResource(String path) throws IOException {
        try (InputStream inputStream = ShaderProgram.class.getResourceAsStream(path)) {
            if (inputStream == null) {
                throw new IOException("Shader resource not found: " + path);
            }
            String source = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            return adaptRiseShaderSource(path, source);
        }
    }

    private static String adaptRiseShaderSource(String path, String source) {
        if (!path.startsWith("/assets/minecraft/rise/shader/")) {
            return source;
        }

        String adapted = source.replace("\r\n", "\n");
        adapted = adapted.replaceAll("(?s)#ifdef GL_ES\\s*precision mediump float;\\s*#endif\\s*", "");
        adapted = adapted.replaceAll("(?m)^#extension\\s+GL_OES_standard_derivatives\\s*:\\s*enable\\s*$\\n?", "");
        adapted = adapted.replaceAll("(?m)^#version\\s+\\d+\\s*$\\n?", "");
        adapted = adapted.replace("gl_TexCoord[0].st", "fragTexCoord");
        adapted = adapted.replace("gl_TexCoord[0].s", "fragTexCoord.x");
        adapted = adapted.replace("gl_TexCoord[0].t", "fragTexCoord.y");
        adapted = adapted.replace("texture2D(", "texture(");
        adapted = adapted.replace("gl_FragColor", "fragColor");
        adapted = adapted.replace("u_direction > 0.0", "u_direction > 0");
        adapted = adapted.replace("dot(c,12.9898)", "(c * 12.9898)");
        adapted = adapted.replace("dot(c, 12.9898)", "(c * 12.9898)");

        StringBuilder builder = new StringBuilder("#version 150\n\n");
        if (!path.endsWith("/main_menu/background.frag")) {
            builder.append("in vec2 fragTexCoord;\n");
        }
        builder.append("out vec4 fragColor;\n\n");
        builder.append(adapted.stripLeading());
        return builder.toString();
    }

    private static int compile(String vertexSource, String fragmentSource, String vertexName, String fragmentName) {
        int vertexShader = compileShader(GL20.GL_VERTEX_SHADER, vertexSource, vertexName);
        if (vertexShader == 0) {
            return 0;
        }

        int fragmentShader = compileShader(GL20.GL_FRAGMENT_SHADER, fragmentSource, fragmentName);
        if (fragmentShader == 0) {
            GL20.glDeleteShader(vertexShader);
            return 0;
        }

        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vertexShader);
        GL20.glAttachShader(program, fragmentShader);
        GL20.glBindAttribLocation(program, 0, "position");
        GL20.glBindAttribLocation(program, 1, "texCoord");
        GL20.glLinkProgram(program);

        GL20.glDeleteShader(vertexShader);
        GL20.glDeleteShader(fragmentShader);

        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            System.out.println("[ShaderProgram] Program link failed for " + fragmentName + ":");
            System.out.println(GL20.glGetProgramInfoLog(program));
            GL20.glDeleteProgram(program);
            return 0;
        }

        return program;
    }

    private static int compileShader(int shaderType, String source, String name) {
        int shader = GL20.glCreateShader(shaderType);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);

        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            System.out.println("[ShaderProgram] Shader compile failed for " + name + ":");
            System.out.println(GL20.glGetShaderInfoLog(shader));
            GL20.glDeleteShader(shader);
            return 0;
        }

        return shader;
    }

    public boolean isValid() {
        return programId != 0;
    }

    public void bind() {
        if (programId != 0) {
            GL20.glUseProgram(programId);
            if (!isCurrentProgram() && !bindFailureLogged) {
                bindFailureLogged = true;
                System.out.println("[ShaderProgram] Failed to bind " + label + " (program " + programId + ")");
            }
        }
    }

    public void cleanup() {
        if (!cleanedUp && programId != 0) {
            GL20.glDeleteProgram(programId);
            cleanedUp = true;
        }
    }

    public int uniform(String name) {
        if (!isCurrentProgram()) {
            return -1;
        }
        Integer cached = uniformLocations.get(name);
        if (cached != null) {
            return cached;
        }
        int location = GL20.glGetUniformLocation(programId, name);
        uniformLocations.put(name, location);
        return location;
    }

    public void set1i(String name, int value) {
        int location = uniform(name);
        if (location >= 0) {
            GL20.glUniform1i(location, value);
        }
    }

    public void set1f(String name, float value) {
        int location = uniform(name);
        if (location >= 0) {
            GL20.glUniform1f(location, value);
        }
    }

    public void set2f(String name, float x, float y) {
        int location = uniform(name);
        if (location >= 0) {
            GL20.glUniform2f(location, x, y);
        }
    }

    public void set3f(String name, float x, float y, float z) {
        int location = uniform(name);
        if (location >= 0) {
            GL20.glUniform3f(location, x, y, z);
        }
    }

    public void set4f(String name, float x, float y, float z, float w) {
        int location = uniform(name);
        if (location >= 0) {
            GL20.glUniform4f(location, x, y, z, w);
        }
    }

    public void setFloatBuffer(String name, FloatBuffer buffer) {
        int location = uniform(name);
        if (location >= 0) {
            buffer.rewind();
            GL20.glUniform1fv(location, buffer);
        }
    }

    private boolean isCurrentProgram() {
        return programId != 0 && GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) == programId;
    }
}
