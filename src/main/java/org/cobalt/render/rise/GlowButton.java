package org.cobalt.render.rise;

public final class GlowButton {

    private final String label;
    private final float x;
    private final float y;
    private final float width;
    private final float height;
    private final float radius;
    private final float hoverProgress;

    public GlowButton(String label, float x, float y, float width, float height, float radius, float hoverProgress) {
        this.label = label;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.radius = radius;
        this.hoverProgress = hoverProgress;
    }

    public String getLabel() {
        return label;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    public float getWidth() {
        return width;
    }

    public float getHeight() {
        return height;
    }

    public float getRadius() {
        return radius;
    }

    public float getHoverProgress() {
        return hoverProgress;
    }
}
