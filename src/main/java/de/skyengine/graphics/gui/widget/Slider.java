package de.skyengine.graphics.gui.widget;

import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.texture.Texture;
import org.lwjgl.glfw.GLFW;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/**
 * Schieberegler im MC-Look ({@code widget/slider.png} als Track, {@code slider_handle.png} als
 * Griff). Werte rasten auf {@code step}; {@code onChange} feuert live beim Ziehen,
 * {@code onRelease} erst beim Loslassen — für teure Anwendungen (Render-Distanz, GUI-Scale).
 */
public final class Slider extends GuiComponent {

    private static final float HANDLE_W = 8;
    private static final float TEXT_SIZE = 10;

    private final double min, max, step;
    private double value;
    private final DoubleFunction<String> format;
    private final DoubleConsumer onChange;
    private final Runnable onRelease;

    private boolean dragging;

    public Slider(float w, float h, double min, double max, double step, double value,
                  DoubleFunction<String> format, DoubleConsumer onChange, Runnable onRelease) {
        this.w = w;
        this.h = h;
        this.min = min;
        this.max = max;
        this.step = step;
        this.value = clampToStep(value, min, max, step);
        this.format = format;
        this.onChange = onChange;
        this.onRelease = onRelease;
    }

    public double getValue() {
        return this.value;
    }

    private static double clampToStep(double v, double min, double max, double step) {
        double snapped = min + Math.round((v - min) / step) * step;
        return Math.clamp(snapped, min, max);
    }

    private void updateFromMouse(double mx) {
        double t = Math.clamp((mx - this.x - HANDLE_W / 2f) / (this.w - HANDLE_W), 0.0, 1.0);
        double next = clampToStep(this.min + t * (this.max - this.min), this.min, this.max, this.step);
        if (next != this.value) {
            this.value = next;
            if (this.onChange != null) this.onChange.accept(next);
        }
    }

    @Override
    public void renderBackground(GuiManager gui, double mx, double my) {
        boolean active = this.hovered || this.dragging;
        Texture track = active ? gui.textures().sliderHighlighted : gui.textures().slider;
        Texture handle = active ? gui.textures().sliderHandleHighlighted : gui.textures().sliderHandle;
        gui.sprites().drawNineSlice(track, this.x, this.y, this.w, this.h, 3);
        float t = (float) ((this.value - this.min) / (this.max - this.min));
        gui.sprites().drawNineSlice(handle, this.x + t * (this.w - HANDLE_W), this.y, HANDLE_W, this.h, 3);
    }

    @Override
    public void renderText(GuiManager gui, double mx, double my) {
        String label = this.format.apply(this.value);
        float tx = this.x + (this.w - gui.font().getStringWidth(label, TEXT_SIZE)) / 2f;
        float ty = this.y + (this.h - gui.font().lineHeight(TEXT_SIZE)) / 2f;
        gui.font().drawStringWithShadow(label, tx, ty, TEXT_SIZE, de.skyengine.graphics.color.Colors.WHITE);
    }

    @Override
    public boolean mousePressed(double mx, double my, int button) {
        if (!this.enabled || button != GLFW.GLFW_MOUSE_BUTTON_LEFT || !this.isMouseOver(mx, my)) {
            return false;
        }
        this.dragging = true;
        this.updateFromMouse(mx);
        return true;
    }

    @Override
    public void mouseDragged(double mx, double my, int button) {
        if (this.dragging && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            this.updateFromMouse(mx);
        }
    }

    @Override
    public void mouseReleased(double mx, double my, int button) {
        if (this.dragging && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            this.dragging = false;
            if (this.onRelease != null) this.onRelease.run();
        }
    }
}
