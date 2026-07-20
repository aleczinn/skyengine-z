package de.skyengine.graphics.gui.screens;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.world.World;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.graphics.color.Colors;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.Screen;
import de.skyengine.graphics.gui.SpriteRenderer;
import de.skyengine.graphics.texture.Texture;

/**
 * Welt-Ladebildschirm nach dem Eintritt: Balken ≈ READY-Chunks / Lade-Kreis; schließt sich,
 * sobald {@code ChunkManager.isInitialLoadComplete()} den Lade-Fixpunkt meldet (einmaliger
 * Latch — spätere Resets des Flags durch Remesh/Renderdistanz sind hier egal, der Screen
 * existiert dann nicht mehr). Nicht schließbar, pausiert NICHT (sonst laden nie Chunks).
 */
public final class WorldLoadingScreen extends Screen {

    private static final float TILE = 32;
    private static final float BAR_W = 200, BAR_H = 6;

    public WorldLoadingScreen() {
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
        World world = SkyEngine.get().getGame().getWorld();
        if (world == null || world.getChunkManager().isInitialLoadComplete()) {
            gui.close();
            return;
        }

        int rd = GameSettings.get().renderDistance;
        int target = Math.max(1, (int) Math.round(Math.PI * rd * rd));
        int ready = 0;
        for (Chunk chunk : world.getChunkManager().getChunks().values()) {
            if (chunk.status.isAtLeast(ChunkStatus.READY)) ready++;
        }
        float progress = Math.min(1f, ready / (float) target);

        float vW = gui.vWidth(), vH = gui.vHeight();
        SpriteRenderer sr = gui.sprites();
        sr.begin(vW, vH);
        /* Deckender Kachel-Hintergrund (Schwarz unterlegt — die Kachel ist halbtransparent),
           die halbfertige Welt dahinter soll nicht durchscheinen. */
        sr.drawRect(0, 0, vW, vH, 0.06f, 0.06f, 0.06f, 1f);
        Texture tex = gui.textures().menuBackground;
        for (float y = 0; y < vH; y += TILE) {
            for (float x = 0; x < vW; x += TILE) {
                sr.drawSprite(tex, x, y, TILE, TILE);
            }
        }
        float bx = (vW - BAR_W) / 2f, by = vH / 2f + 8;
        sr.drawRect(bx - 1, by - 1, BAR_W + 2, BAR_H + 2, 1f, 1f, 1f, 0.35f);
        sr.drawRect(bx, by, BAR_W * progress, BAR_H, 1f, 1f, 1f, 0.9f);
        sr.end();

        String text = "Welt wird generiert...";
        gui.font().begin(vW, vH);
        gui.font().drawStringWithShadow(text,
                (vW - gui.font().getStringWidth(text, 12)) / 2f, vH / 2f - 12, 12, Colors.WHITE);
        gui.font().end();
    }
}
