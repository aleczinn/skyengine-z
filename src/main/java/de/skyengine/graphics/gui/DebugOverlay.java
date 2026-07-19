package de.skyengine.graphics.gui;

import de.skyengine.core.SkyEngine;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.world.World;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.graphics.color.Color4;
import de.skyengine.graphics.gui.font.FontRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * F3-Debug-Overlay (Minecraft-artig): linke Spalte mit Engine-/Welt-/Spieler-Infos,
 * je Zeile ein halbtransparentes Hintergrund-Rechteck plus Text mit Schatten.
 * Gehalten vom GameContainer (braucht World/Player/Engine-Daten), gezeichnet nach
 * dem regulären GUI-Pass über die Renderer des {@link GuiManager}.
 */
public final class DebugOverlay {

    private static final float TEXT_SIZE = 10.0F;
    private static final float MARGIN = 2.5F;

    /** 8 Himmelsrichtungen, Index = round(yaw/45) % 8; yaw 0 blickt Richtung -Z. */
    private static final String[] FACING = {
            "Nord (-Z)",
            "Nordost",
            "Ost (+X)",
            "Südost",
            "Süd (+Z)",
            "Südwest",
            "West (-X)",
            "Nordwest"
    };

    private boolean visible;

    public void toggle() {
        this.visible = !this.visible;
    }

    public boolean isVisible() {
        return this.visible;
    }

    public void render(GuiManager gui, World world, EntityPlayer player) {
        FontRenderer font = gui.font();
        if (!font.available()) return;

        int bx = (int) Math.floor(player.x);
        int by = (int) Math.floor(player.y);
        int bz = (int) Math.floor(player.z);
        int facing = Math.round(player.yaw / 45.0F) & 7;
        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long maxMb = runtime.maxMemory() / (1024 * 1024);

        List<String> lines = new ArrayList<>();
        lines.add(SkyEngine.ENGINE_NAME + " v" + SkyEngine.ENGINE_VERSION);
        lines.add("FPS: %d  TPS: %d".formatted(SkyEngine.get().getCurrentFps(), SkyEngine.get().getCurrentTps()));
        lines.add(String.format(Locale.ROOT, "XYZ: %.3f / %.3f / %.3f", player.x, player.y, player.z));
        lines.add("Block: %d %d %d  Chunk: %d %d %d in %d %d".formatted(
                bx, by, bz,
                bx & ChunkSection.MASK, by & ChunkSection.MASK, bz & ChunkSection.MASK,
                bx >> ChunkSection.SHIFT, bz >> ChunkSection.SHIFT));
        lines.add(String.format(Locale.ROOT, "Facing: %s (yaw %.1f / pitch %.1f)", FACING[facing], player.yaw, player.pitch));
        lines.add("Biome: " + world.biomeAt(bx, bz).name);
        lines.add("Sections: %d/%d  Chunks: %d".formatted(
                world.getChunkRenderer().getRenderedSections(),
                world.getChunkRenderer().getTotalSections(),
                world.getChunkManager().getChunks().size()));
        lines.add("Mem: %d/%d MB".formatted(usedMb, maxMb));

        float lineStep = font.lineHeight(TEXT_SIZE) + 1;

        /* Pass 1: Hintergrund-Rechtecke (SpriteRenderer), Pass 2: Text — so bleibt es bei
           zwei GL-State-Wechseln statt einem pro Zeile. */
        SpriteRenderer sprites = gui.sprites();
        sprites.begin(gui.vWidth(), gui.vHeight());
        float y = MARGIN;
        for (String line : lines) {
            /* Höhe exakt lineStep: Rechteck i endet an der Startkante von i+1 — sonst
               überlappen die Halbtransparenzen und erzeugen dunkle Doppelkanten. */
            sprites.drawRect(MARGIN - 1, y, font.getStringWidth(line, TEXT_SIZE) + 2, lineStep, 0, 0, 0, 0.35F);
            y += lineStep;
        }
        sprites.end();

        font.begin(gui.vWidth(), gui.vHeight());
        y = MARGIN;
        for (String line : lines) {
            font.drawStringWithShadow(line, MARGIN, y, TEXT_SIZE, Color4.WHITE);
            y += lineStep;
        }
        font.end();
    }
}
