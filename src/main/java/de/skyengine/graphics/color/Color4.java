package de.skyengine.graphics.color;

import de.skyengine.utils.math.MathUtils;

public class Color4 {

    public float red;
    public float green;
    public float blue;
    public float alpha;

    public static final Color4 WHITE = new Color4(1.0F, 1.0F, 1.0F, 1.0F);
    public static final Color4 BLACK = new Color4(0.0F, 0.0F, 0.0F, 1.0F);

    public static final Color4 RED = new Color4(1.0F, 0.0F, 0.0F, 1.0F);
    public static final Color4 GREEN = new Color4(0.0F, 1.0F, 0.0F, 1.0F);
    public static final Color4 BLUE = new Color4(0.0F, 0.0F, 1.0F, 1.0F);

    public static final Color4 GRAY = new Color4(0.5F, 0.5F, 0.5F, 1.0F);
    public static final Color4 LIGHT_GRAY = new Color4(0.7F, 0.7F, 0.7F, 1.0F);
    public static final Color4 DARK_GRAY = new Color4(0.3F, 0.3F, 0.3F, 1.0F);

    public static final Color4 PINK = new Color4(1.0F, 0.68F, 0.68F, 1.0F);
    public static final Color4 ORANGE = new Color4(1.0F, 0.58F, 0, 1.0F);
    public static final Color4 YELLOW = new Color4(1.0F, 1.0F, 0, 1.0F);
    public static final Color4 MAGENTA = new Color4(1.0F, 0, 1.0F, 1.0F);
    public static final Color4 CYAN = new Color4(0, 1.0F, 1.0F, 1.0F);

    public Color4() {
        this.red = 1.0F;
        this.green = 1.0F;
        this.blue = 1.0F;
        this.alpha = 1.0F;
    }

    public Color4(Color4 color) {
        this.red = color.red;
        this.green = color.green;
        this.blue = color.blue;
        this.alpha = color.alpha;
    }

    public Color4(Color3 color) {
        this.red = color.red;
        this.green = color.green;
        this.blue = color.blue;
        this.alpha = 1.0F;
    }

    public Color4(float red, float green, float blue, float alpha) {
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.alpha = alpha;
        this.clamp();
    }

    public Color4(int hex) {
        this.alpha = ((hex & 0xff000000) >>> 24) / 255F;
        this.red = ((hex & 0x00ff0000) >>> 16) / 255F;
        this.green = ((hex & 0x0000ff00) >>> 8) / 255F;
        this.blue = ((hex & 0x000000ff)) / 255f;
        this.clamp();
    }

    public static Color4 fade(Color4 out, Color4 color1, Color4 color2, float alpha) {
        float red1 = color1.red;
        float green1 = color1.green;
        float blue1 = color1.blue;

        float red2 = color2.red;
        float green2 = color2.green;
        float blue2 = color2.blue;

        float iRed = MathUtils.interpolate(red2, red1, alpha);
        float iGreen = MathUtils.interpolate(green2, green1, alpha);
        float iBlue = MathUtils.interpolate(blue2, blue1, alpha);
        out.set(iRed, iGreen, iBlue, 1.0F);
        return out;
    }

    public Color4 set(Color4 color) {
        this.red = color.red;
        this.green = color.green;
        this.blue = color.blue;
        this.alpha = color.alpha;
        return this.clamp();
    }

    public Color4 set(Color3 color) {
        this.red = color.red;
        this.green = color.green;
        this.blue = color.blue;
        this.alpha = 1.0F;
        return this.clamp();
    }

    public Color4 set(float red, float green, float blue, float alpha) {
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.alpha = alpha;
        return this.clamp();
    }

    public Color4 set(int hex) {
        this.alpha = ((hex & 0xff000000) >>> 24) / 255F;
        this.red = ((hex & 0x00ff0000) >>> 16) / 255F;
        this.green = ((hex & 0x0000ff00) >>> 8) / 255F;
        this.blue = ((hex & 0x000000ff)) / 255f;
        return this.clamp();
    }

    public Color4 add(float red, float green, float blue, float alpha) {
        this.red += red;
        this.green += green;
        this.blue += blue;
        this.alpha += alpha;
        return this.clamp();
    }

    public Color4 add(Color4 color) {
        this.red += color.red;
        this.green += color.green;
        this.blue += color.blue;
        this.alpha += color.alpha;
        return this.clamp();
    }

    public Color4 add(Color3 color) {
        this.red += color.red;
        this.green += color.green;
        this.blue += color.blue;
        return this.clamp();
    }

    public Color4 subtract(float red, float green, float blue) {
        this.red -= red;
        this.green -= green;
        this.blue -= blue;
        return this.clamp();
    }

    public Color4 subtract(float red, float green, float blue, float alpha) {
        this.red -= red;
        this.green -= green;
        this.blue -= blue;
        this.alpha -= alpha;
        return this.clamp();
    }

    public Color4 subtract(Color4 color) {
        this.red -= color.red;
        this.green -= color.green;
        this.blue -= color.blue;
        this.alpha -= color.alpha;
        return this.clamp();
    }

    public Color4 subtract(Color3 color) {
        this.red -= color.red;
        this.green -= color.green;
        this.blue -= color.blue;
        return this.clamp();
    }

    public Color4 multiply(float red, float green, float blue, float alpha) {
        this.red *= red;
        this.green *= green;
        this.blue *= blue;
        this.alpha *= alpha;
        return this.clamp();
    }

    public Color4 multiply(Color4 color) {
        this.red *= color.red;
        this.green *= color.green;
        this.blue *= color.blue;
        this.alpha *= color.alpha;
        return this.clamp();
    }

    public Color4 multiply(Color3 color) {
        this.red *= color.red;
        this.green *= color.green;
        this.blue *= color.blue;
        return this.clamp();
    }

    public Color4 divide(float red, float green, float blue, float alpha) {
        this.red /= red;
        this.green /= green;
        this.blue /= blue;
        this.alpha /= alpha;
        return this.clamp();
    }

    public Color4 divide(Color4 color) {
        this.red /= color.red;
        this.green /= color.green;
        this.blue /= color.blue;
        this.alpha /= color.alpha;
        return this.clamp();
    }

    public Color4 divide(Color3 color) {
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

    public float getAlpha() {
        return alpha;
    }

    public Color4 setAlpha(float alpha) {
        this.alpha = alpha;
        this.alpha = MathUtils.clamp(this.alpha, 0, 1);
        return this;
    }

    /**
     * Packs the color components into a 32-bit integer with the format ABGR. Note that no range checking is performed for higher performance.
     *
     * @param r the red component, 0 - 255
     * @param g the green component, 0 - 255
     * @param b the blue component, 0 - 255
     * @param a the alpha component, 0 - 255
     * @return the packed color as a 32-bit int
     */
    public static int toIntBits(int r, int g, int b, int a) {
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    /**
     * Packs the color components into a 32-bit integer with the format ABGR.
     *
     * @return the packed color as a 32-bit int.
     */
    public int toIntBits() {
        return ((int) (255 * this.alpha) << 24) | ((int) (255 * this.blue) << 16) | ((int) (255 * this.green) << 8) | ((int) (255 * this.red));
    }

    public static int alpha(float alpha) {
        return (int) (alpha * 255.0f);
    }

    public static int luminanceAlpha(float luminance, float alpha) {
        return ((int) (luminance * 255.0f) << 8) | (int) (alpha * 255);
    }

    public static int rgb565(float r, float g, float b) {
        return ((int) (r * 31) << 11) | ((int) (g * 63) << 5) | (int) (b * 31);
    }

    public static int rgba4444(float r, float g, float b, float a) {
        return ((int) (r * 15) << 12) | ((int) (g * 15) << 8) | ((int) (b * 15) << 4) | (int) (a * 15);
    }

    public static int rgb888(float r, float g, float b) {
        return ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }

    public static int rgba8888(float r, float g, float b, float a) {
        return ((int) (r * 255) << 24) | ((int) (g * 255) << 16) | ((int) (b * 255) << 8) | (int) (a * 255);
    }

    public static int argb8888(float a, float r, float g, float b) {
        return ((int) (a * 255) << 24) | ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }

    public static int rgb565(Color4 color) {
        return ((int) (color.red * 31) << 11) | ((int) (color.green * 63) << 5) | (int) (color.blue * 31);
    }

    public static int rgba4444(Color4 color) {
        return ((int) (color.red * 15) << 12) | ((int) (color.green * 15) << 8) | ((int) (color.blue * 15) << 4) | (int) (color.alpha * 15);
    }

    public static int rgb888(Color4 color) {
        return ((int) (color.red * 255) << 16) | ((int) (color.green * 255) << 8) | (int) (color.blue * 255);
    }

    public static int rgba8888(Color4 color) {
        return ((int) (color.red * 255) << 24) | ((int) (color.green * 255) << 16) | ((int) (color.blue * 255) << 8) | (int) (color.alpha * 255);
    }

    public static int argb8888(Color4 color) {
        return ((int) (color.alpha * 255) << 24) | ((int) (color.red * 255) << 16) | ((int) (color.green * 255) << 8) | (int) (color.blue * 255);
    }

    public Color4 brighter() {
        return this.brighter(0.7F);
    }

    public Color4 brighter(float factor) {
        float r = this.red;
        float g = this.green;
        float b = this.blue;

        float i = (1.0F / (1.0F - factor));
        if (this.red == 0 && this.green == 0 && this.blue == 0) {
            return new Color4(i, i, i, this.alpha);
        }

        if (r > 0 && r < i) r = i;
        if (g > 0 && g < i) g = i;
        if (b > 0 && b < i) b = i;

        this.red = Math.min(r / factor, 1.0F);
        this.blue = Math.min(b / factor, 1.0F);
        this.green = Math.min(g / factor, 1.0F);
        return this.clamp();
    }

    public Color4 darker() {
        return this.darker(0.7F);
    }

    public Color4 darker(float factor) {
        this.red *= factor;
        this.green *= factor;
        this.blue *= factor;
        return this.clamp();
    }

    public static Color4 random() {
        return new Color4(
                MathUtils.randomAsFloat(),
                MathUtils.randomAsFloat(),
                MathUtils.randomAsFloat(),
                MathUtils.randomAsFloat()
        );
    }

    public static Color4 random(float alpha) {
        return new Color4(
                MathUtils.randomAsFloat(),
                MathUtils.randomAsFloat(),
                MathUtils.randomAsFloat(),
                alpha
        );
    }

    /**
     * Sets the RGB Color components using the specified Hue-Saturation-Value. Note
     * that HSV components are voluntary not clamped to preserve high range color
     * and can range beyond typical values.
     *
     * @param hue        The Hue in degree from 0 to 360
     * @param saturation The Saturation from 0.0 to 1.0
     * @param brightness The Brightness from 0.0 to 1.0
     * @return The modified Color for chaining.
     */
    public Color4 fromHSV(float hue, float saturation, float brightness) {
        saturation = MathUtils.clamp(saturation, 0.0F, 1.0F);
        brightness = MathUtils.clamp(brightness, 0.0F, 1.0F);

        float x = (hue / 60F + 6) % 6;
        int i = (int) x;
        float f = x - i;
        float p = brightness * (1.0F - saturation);
        float q = brightness * (1.0F - saturation * f);
        float t = brightness * (1.0F - saturation * (1.0F - f));

        switch (i) {
            case 0:
                this.red = brightness;
                this.green = t;
                this.blue = p;
                break;
            case 1:
                this.red = q;
                this.green = brightness;
                this.blue = p;
                break;
            case 2:
                this.red = p;
                this.green = brightness;
                this.blue = t;
                break;
            case 3:
                this.red = p;
                this.green = q;
                this.blue = brightness;
                break;
            case 4:
                this.red = t;
                this.green = p;
                this.blue = brightness;
                break;
            default:
                this.red = brightness;
                this.green = p;
                this.blue = q;
        }

        return this.clamp();
    }

    /**
     * Extract Hue-Saturation-Value. This is the inverse of {@link #fromHSV(float, float, float)}.
     *
     * @param hsv The HSV array to be modified.
     * @return HSV components for chaining.
     */
    public float[] toHsv(float[] hsv) {
        float max = Math.max(Math.max(this.red, this.green), this.blue);
        float min = Math.min(Math.min(this.red, this.green), this.blue);
        float range = max - min;

        if (range == 0) {
            hsv[0] = 0;
        } else if (max == this.red) {
            hsv[0] = (60 * (this.green - this.blue) / range + 360) % 360;
        } else if (max == this.green) {
            hsv[0] = 60 * (this.blue - this.red) / range + 120;
        } else {
            hsv[0] = 60 * (this.red - this.green) / range + 240;
        }

        if (max > 0) {
            hsv[1] = 1 - min / max;
        } else {
            hsv[1] = 0;
        }

        hsv[2] = max;
        return hsv;
    }

    public int toHex() {
        String red = this.formatHex(Integer.toHexString(Math.round(this.red * 255.0F)));
        String green = this.formatHex(Integer.toHexString(Math.round(this.green * 255.0F)));
        String blue = this.formatHex(Integer.toHexString(Math.round(this.blue * 255.0F)));
        String alpha = this.formatHex(Integer.toHexString(Math.round(this.alpha * 255.0F)));

        String hex = alpha + red + green + blue;
        return (int) Long.parseLong(hex, 16);
    }

    private String formatHex(String s) {
        return s.length() == 1 ? 0 + s : s;
    }

    public Color4 toShadowColor() {
        int color = this.toHex();
        color = (color & 16579836) >> 2 | color & -16777216;
        return this.set(color);
    }

    public Color4 clamp() {
        this.red = MathUtils.clamp(this.red, 0, 1);
        this.green = MathUtils.clamp(this.green, 0, 1);
        this.blue = MathUtils.clamp(this.blue, 0, 1);
        this.alpha = MathUtils.clamp(this.alpha, 0, 1);
        return this;
    }

    public Color3 asColor3() {
        return new Color3(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Color4 color = (Color4) o;
        return this.toIntBits() == color.toIntBits();
    }

    @Override
    public int hashCode() {
        int result = (this.red != +0.0F ? Float.floatToIntBits(this.red) : 0);
        result = 31 * result + (this.green != +0.0F ? Float.floatToIntBits(this.green) : 0);
        result = 31 * result + (this.blue != +0.0F ? Float.floatToIntBits(this.blue) : 0);
        result = 31 * result + (this.alpha != +0.0F ? Float.floatToIntBits(this.alpha) : 0);
        return result;
    }

    public Color4 copy() {
        return new Color4(this);
    }

    @Override
    public String toString() {
        return String.format(
                "Color4{red=%s, green=%s, blue=%s, alpha=%s}",
                this.red,
                this.green,
                this.blue,
                this.alpha
        );
    }
}
