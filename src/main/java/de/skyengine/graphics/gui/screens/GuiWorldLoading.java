package de.skyengine.graphics.gui.screens;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.i18n.I18n;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.dimension.DimensionDefinition;
import de.skyengine.graphics.color.Colors;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.SpriteRenderer;
import de.skyengine.graphics.gui.GuiText;

/**
 * Welt-Ladebildschirm nach dem Eintritt. Er schließt erst, wenn der Lade-Fixpunkt erreicht
 * und das unmittelbare Chunk-Umfeld des Spielers tatsächlich in die aktuelle GPU-View hochgeladen ist.
 * Nicht schließbar und nicht pausierend, damit die Chunk-Pipeline weiterarbeiten kann.
 */
public final class GuiWorldLoading extends GuiScreen {

    private static final float BAR_W = 200, BAR_H = 6;

    public GuiWorldLoading() {
        super(null);
    }

    @Override
    public boolean isClosable() {
        return false;
    }

    @Override
    public boolean keyPressed(GuiManager gui, int key) {
        return true; // auch ESC schlucken — Abbruch gibt es hier nicht
    }

    @Override
    public void render(GuiManager gui, double mouseX, double mouseY) {
        Dimension world = SkyEngine.get().getGame().getDimension();
        EntityPlayer player = SkyEngine.get().getGame().getPlayer();
        if (world == null || world.getChunkManager().isInitialRenderReady(player)) {
            gui.close();
            return;
        }

        float progress = world.getChunkManager().initialRenderProgress(player);

        float vW = gui.vWidth(), vH = gui.vHeight();
        SpriteRenderer sr = gui.sprites();
        sr.begin(vW, vH);
        /* Deckender Kachel-Hintergrund — die halbfertige Welt dahinter soll nicht durchscheinen. */
        this.drawMenuTiles(gui);
        float bx = (vW - BAR_W) / 2f, by = vH / 2f + 8;
        sr.drawRect(bx - 1, by - 1, BAR_W + 2, BAR_H + 2, 1f, 1f, 1f, 0.35f);
        sr.drawRect(bx, by, BAR_W * progress, BAR_H, 1f, 1f, 1f, 0.9f);
        sr.end();

        String text = I18n.tr("world.loading", DimensionDefinition.displayName(world.getDimensionId()));
        gui.font().begin(vW, vH);
        gui.font().drawStringWithShadow(text,
                (vW - gui.font().getStringWidth(text, GuiText.MEDIUM)) / 2f, vH / 2f - 12, GuiText.MEDIUM, Colors.WHITE);
        gui.font().end();
    }
}
