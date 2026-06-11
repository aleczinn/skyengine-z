package de.skyengine.graphics.color;

import de.skyengine.utils.math.MathUtils;

public class Color3 {

    public float red;
    public float green;
    public float blue;

    public static final Color3 WHITE = new Color3(1.0F, 1.0F, 1.0F);
    public static final Color3 BLACK = new Color3(0, 0, 0);

    public static final Color3 RED = new Color3(1, 0, 0);
    public static final Color3 GREEN = new Color3(0, 1, 0);
    public static final Color3 BLUE = new Color3(0, 0, 1);

    public static final Color3 GRAY = new Color3(0.5F, 0.5F, 0.5F);
    public static final Color3 LIGHT_GRAY = new Color3(0.7F, 0.7F, 0.7F);
    public static final Color3 DARK_GRAY = new Color3(0.3F, 0.3F, 0.3F);

    public static final Color3 PINK = new Color3(1, 0.68F, 0.68F);
    public static final Color3 ORANGE = new Color3(1, 0.58F, 0);
    public static final Color3 YELLOW = new Color3(1, 1, 0);
    public static final Color3 MAGENTA = new Color3(1, 0, 1);
    public static final Color3 CYAN = new Color3(0, 1, 1);

    public Color3() {
        this.red = 1.0F;
        this.green = 1.0F;
        this.blue = 1.0F;
    }

    public Color3(Color3 color) {
        this.red = color.red;
        this.green = color.green;
        this.blue = color.blue;
    }

    public Color3(Color4 color) {
        this.red = color.red;
        this.green = color.green;
        this.blue = color.blue;
    }

    public Color3(float red, float green, float blue) {
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.clamp();
    }

    public Color3(int hexWithAlpha) {
        this.red = ((hexWithAlpha & 0x00ff0000) >>> 16) / 255F;
        this.green = ((hexWithAlpha & 0x0000ff00) >>> 8) / 255F;
        this.blue = ((hexWithAlpha & 0x000000ff)) / 255F;
        this.clamp();
    }

    public Color3 set(Color3 color) {
        this.red = color.red;
        this.green = color.green;
        this.blue = color.blue;
        return this.clamp();
    }

    public Color3 set(float red, float green, float blue) {
        this.red = red;
        this.green = green;
        this.blue = blue;
        return clamp();
    }

    public Color3 set(int hexWithAlpha) {
        this.red = ((hexWithAlpha & 0x00ff0000) >>> 16) / 255F;
        this.green = ((hexWithAlpha & 0x0000ff00) >>> 8) / 255F;
        this.blue = ((hexWithAlpha & 0x000000ff)) / 255f;
        return this.clamp();
    }

    public Color3 set(Color4 color) {
        this.red = color.red;
        this.green = color.green;
        this.blue = color.blue;
        return this.clamp();
    }

    public Color3 add(float red, float green, float blue) {
        this.red += red;
        this.green += green;
        this.blue += blue;
        return this.clamp();
    }

    public Color3 add(Color3 color) {
        this.red += color.red;
        this.green += color.green;
        this.blue += color.blue;
        return this.clamp();
    }

    public Color3 add(Color4 color) {
        this.red += color.red;
        this.green += color.green;
        this.blue += color.blue;
        return this.clamp();
    }

    public Color3 subtract(float red, float green, float blue) {
        this.red -= red;
        this.green -= green;
        this.blue -= blue;
        return this.clamp();
    }

    public Color3 subtract(Color3 color) {
        this.red -= color.red;
        this.green -= color.green;
        this.blue -= color.blue;
        return this.clamp();
    }

    public Color3 subtract(Color4 color) {
        this.red -= color.red;
        this.green -= color.green;
        this.blue -= color.blue;
        return this.clamp();
    }

    public Color3 multiply(float red, float green, float blue) {
        this.red *= red;
        this.green *= green;
        this.blue *= blue;
        return this.clamp();
    }

    public Color3 multiply(Color3 color) {
        this.red *= color.red;
        this.green *= color.green;
        this.blue *= color.blue;
        return this.clamp();
    }

    public Color3 multiply(Color4 color) {
        this.red *= color.red;
        this.green *= color.green;
        this.blue *= color.blue;
        return this.clamp();
    }

    public Color3 divide(float red, float green, float blue) {
        this.red /= red;
        this.green /= green;
        this.blue /= blue;
        return this.clamp();
    }

    public Color3 divide(Color3 color) {
        this.red /= color.red;
        this.green /= color.green;
        this.blue /= color.blue;
        return this.clamp();
    }

    public Color3 divide(Color4 color) {
        this.red /= color.red;
        this.green /= color.green;
        this.blue /= color.blue;
        return this.clamp();
    }

    public float getRed() {
        return red;
    }

    public void setRed(float red) {
        this.red = red;
        this.red = MathUtils.clamp(this.red, 0, 1);
    }

    public float getGreen() {
        return green;
    }

    public void setGreen(float green) {
        this.green = green;
        this.green = MathUtils.clamp(this.green, 0, 1);
    }

    public float getBlue() {
        return blue;
    }

    public void setBlue(float blue) {
        this.blue = blue;
        this.blue = MathUtils.clamp(this.blue, 0, 1);
    }

    public Color3 random() {
        return new Color3(
                MathUtils.randomAsFloat(),
                MathUtils.randomAsFloat(),
                MathUtils.randomAsFloat()
        );
    }

    public Color3 clamp() {
        this.red = MathUtils.clamp(this.red, 0, 1);
        this.green = MathUtils.clamp(this.green, 0, 1);
        this.blue = MathUtils.clamp(this.blue, 0, 1);
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Color3 color = (Color3) o;
        if (this.red != color.red) return false;
        if (this.green != color.green) return false;
        return this.blue == color.blue;
    }

    public Color3 copy() {
        return new Color3(this);
    }

    @Override
    public String toString() {
        return String.format(
                "Color3{red=%s, green=%s, blue=%s}",
                this.red,
                this.green,
                this.blue
        );
    }
}
