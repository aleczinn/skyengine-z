package de.skyengine.graphics.gui.screens;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.Window;
import de.skyengine.core.i18n.I18n;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.GameContainer;
import de.skyengine.graphics.color.Colors;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.GuiText;
import de.skyengine.graphics.gui.SpriteRenderer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.List;

/**
 * Nicht schliessbarer Zwischenbildschirm fuer einen synchronen Ressourcen-Reload. Der erste
 * normale GUI-Frame macht den Screen sichtbar; erst im folgenden Loop-Durchlauf startet
 * {@link #runReload(GameContainer, GuiManager)} die GL-/Audio-Arbeit. Zwischen den groben
 * Etappen wird direkt in den Default-Framebuffer gezeichnet und praesentiert.
 */
public final class GuiResourcePackLoading extends GuiScreen {

    private static final float BAR_W = 200, BAR_H = 6;

    private final GuiScreen returnTo;
    private final List<String> requested;
    private String stage = I18n.tr("resourcepacks.loading.prepare");
    private float progress;
    private boolean firstFramePresented;
    private boolean started;

    public GuiResourcePackLoading(GuiScreen returnTo, List<String> requested) {
        super(null);
        this.returnTo = returnTo;
        this.requested = List.copyOf(requested);
    }

    /** Wird vom Game-Loop abgefragt, damit mindestens ein vollstaendiger Lade-Frame sichtbar war. */
    public boolean isReadyToReload() {
        return this.firstFramePresented && !this.started;
    }

    /** Render-Thread: Ressourcen neu laden und bei Erfolg/Fehler zum passenden Screen wechseln. */
    public void runReload(GameContainer game, GuiManager gui) {
        if (!this.isReadyToReload()) return;
        this.started = true;
        String error = game.reloadResourcePacks(this.requested, (stage, progress) -> {
            this.stage = stage;
            this.progress = Math.clamp(progress, 0F, 1F);
            this.presentProgressFrame(gui);
        });
        if (error == null) {
            gui.open(this.returnTo);
        } else {
            gui.open(new GuiResourcePacks(this.returnTo, this.requested, error));
        }
    }

    @Override
    public boolean isClosable() {
        return false;
    }

    @Override
    public boolean keyPressed(GuiManager gui, int key) {
        return true;
    }

    @Override
    public boolean capturesKeys() {
        return true;
    }

    @Override
    public boolean capturesMouse() {
        return true;
    }

    @Override
    public boolean doesPausesGame() {
        return this.returnTo != null && this.returnTo.doesPausesGame();
    }

    @Override
    public void render(GuiManager gui, double mouseX, double mouseY) {
        this.draw(gui, gui.vWidth(), gui.vHeight());
        this.firstFramePresented = true;
    }

    private void draw(GuiManager gui, float vW, float vH) {
        SpriteRenderer sprites = gui.sprites();
        sprites.begin(vW, vH);
        sprites.drawRect(0, 0, vW, vH, 0.06F, 0.06F, 0.06F, 1F);
        float bx = (vW - BAR_W) / 2F, by = vH / 2F + 8;
        sprites.drawRect(bx - 1, by - 1, BAR_W + 2, BAR_H + 2, 1F, 1F, 1F, 0.35F);
        sprites.drawRect(bx, by, BAR_W * this.progress, BAR_H, 1F, 1F, 1F, 0.9F);
        sprites.end();

        String title = I18n.tr("resourcepacks.loading.title");
        gui.font().begin(vW, vH);
        gui.font().drawStringWithShadow(title,
                (vW - gui.font().getStringWidth(title, GuiText.LARGE)) / 2F,
                vH / 2F - 40, GuiText.LARGE, Colors.WHITE);
        gui.font().drawStringWithShadow(this.stage,
                (vW - gui.font().getStringWidth(this.stage, GuiText.NORMAL)) / 2F,
                vH / 2F - 12, GuiText.NORMAL, Colors.WHITE);
        gui.font().end();
    }

    /** Wie BootProgress: kompletter Zwischenframe, ohne den normalen Frame-Loop abzuwarten. */
    private void presentProgressFrame(GuiManager gui) {
        Window window = SkyEngine.get().getWindow();
        int w = window.getWidth(), h = window.getHeight();

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL11.glViewport(0, 0, w, h);
        GL11.glClearColor(0.06F, 0.06F, 0.06F, 1F);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        float scale = GuiManager.resolveScale(GameSettings.get().guiScaleLevel, w, h);
        this.draw(gui, w / scale, h / scale);
        GLFW.glfwSwapBuffers(window.getWindowID());
    }
}
