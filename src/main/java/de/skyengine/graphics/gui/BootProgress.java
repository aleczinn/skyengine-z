package de.skyengine.graphics.gui;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.Window;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.graphics.color.Colors;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Boot-Ladebildschirm: zeichnet zwischen den Init-Etappen je EINEN Frame (Balken + Stufenname)
 * direkt in den Default-Framebuffer und präsentiert ihn — bewusst grobkörnig und synchron,
 * kein Async. Braucht nur SpriteRenderer + FontRenderer (GuiManager.initEarly); Texturen/Icons
 * existieren zu diesem Zeitpunkt noch nicht.
 */
public final class BootProgress {

    private static final float BAR_W = 200, BAR_H = 6;

    private final GuiManager gui;

    public BootProgress(GuiManager gui) {
        this.gui = gui;
    }

    /** Render-Thread: einen Fortschritts-Frame zeichnen und swappen. */
    public void frame(String stage, float progress) {
        Window window = SkyEngine.get().getWindow();
        int w = window.getWidth(), h = window.getHeight();

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL11.glViewport(0, 0, w, h);
        GL11.glClearColor(0.06f, 0.06f, 0.06f, 1f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        float scale = GameSettings.get().guiScaleFactor();
        float vW = w / scale, vH = h / scale;

        SpriteRenderer sprites = this.gui.sprites();
        sprites.begin(vW, vH);
        float bx = (vW - BAR_W) / 2f, by = vH / 2f + 8;
        sprites.drawRect(bx - 1, by - 1, BAR_W + 2, BAR_H + 2, 1f, 1f, 1f, 0.35f);
        sprites.drawRect(bx, by, BAR_W * Math.clamp(progress, 0f, 1f), BAR_H, 1f, 1f, 1f, 0.9f);
        sprites.end();

        this.gui.font().begin(vW, vH);
        String title = SkyEngine.ENGINE_NAME;
        this.gui.font().drawStringWithShadow(title,
                (vW - this.gui.font().getStringWidth(title, GuiText.LARGE)) / 2f, vH / 2f - 40, GuiText.LARGE, Colors.WHITE);
        this.gui.font().drawStringWithShadow(stage,
                (vW - this.gui.font().getStringWidth(stage, GuiText.NORMAL)) / 2f, vH / 2f - 12, GuiText.NORMAL, Colors.WHITE);
        this.gui.font().end();

        GLFW.glfwSwapBuffers(window.getWindowID());
    }
}
