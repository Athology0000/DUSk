package org.cobalt.render.rise;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

public final class RenderTarget {

    private final boolean withDepthBuffer;

    private int fboId;
    private int textureId;
    private int depthBufferId;
    private int width;
    private int height;

    public RenderTarget() {
        this(false);
    }

    public RenderTarget(boolean withDepthBuffer) {
        this.withDepthBuffer = withDepthBuffer;
    }

    public void ensureSize(int newWidth, int newHeight) {
        if (newWidth <= 0 || newHeight <= 0) {
            return;
        }
        if (newWidth == width && newHeight == height && fboId != 0) {
            return;
        }

        cleanup();

        width = newWidth;
        height = newHeight;

        fboId = GL30.glGenFramebuffers();
        textureId = GL11.glGenTextures();

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, textureId, 0);

        if (withDepthBuffer) {
            depthBufferId = GL30.glGenRenderbuffers();
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthBufferId);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH24_STENCIL8, width, height);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL30.GL_RENDERBUFFER, depthBufferId);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);
        }

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            System.err.println("[RenderTarget] Framebuffer incomplete: 0x" + Integer.toHexString(status));
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    public void bind() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
        GL11.glViewport(0, 0, width, height);
    }

    public void clear(float red, float green, float blue, float alpha) {
        bind();
        GL11.glClearColor(red, green, blue, alpha);
        int clearMask = GL11.GL_COLOR_BUFFER_BIT;
        if (withDepthBuffer) {
            clearMask |= GL11.GL_DEPTH_BUFFER_BIT;
        }
        GL11.glClear(clearMask);
    }

    public void bindTexture(int textureUnit) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + textureUnit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
    }

    public boolean isReady() {
        return fboId != 0 && textureId != 0 && width > 0 && height > 0;
    }

    public int getTextureId() {
        return textureId;
    }

    public int getFboId() {
        return fboId;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public void cleanup() {
        if (depthBufferId != 0) {
            GL30.glDeleteRenderbuffers(depthBufferId);
            depthBufferId = 0;
        }
        if (textureId != 0) {
            GL11.glDeleteTextures(textureId);
            textureId = 0;
        }
        if (fboId != 0) {
            GL30.glDeleteFramebuffers(fboId);
            fboId = 0;
        }
        width = 0;
        height = 0;
    }
}
