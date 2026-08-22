package de.skyengine.graphics.gui;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.i18n.I18n;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.world.World;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.graphics.color.Color4;
import de.skyengine.graphics.FrameProfiler;
import de.skyengine.graphics.PerformanceProfiler;
import de.skyengine.graphics.DebugFlags;
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

    private static final float TEXT_SIZE = GuiText.NORMAL;
    private static final float MARGIN = 2.5F;

    /** 8 Himmelsrichtungen (i18n-Keys), Index = round(yaw/45) % 8; yaw 0 blickt Richtung -Z. */
    private static final String[] FACING = {
            "debug.facing.north",
            "debug.facing.northeast",
            "debug.facing.east",
            "debug.facing.southeast",
            "debug.facing.south",
            "debug.facing.southwest",
            "debug.facing.west",
            "debug.facing.northwest"
    };

    private boolean visible;

    /* Biome-Cache: biomeAt ist ein voller Klima-Noise-Sample — das Ergebnis ändert sich
       nur beim Blockwechsel, nicht pro Frame. */
    private int lastBiomeX = Integer.MIN_VALUE, lastBiomeZ;
    private String lastBiomeName = "";

    public void toggle() {
        this.visible = !this.visible;
    }

    public boolean isVisible() {
        return this.visible;
    }

    /** F3+P: Profiler ist sitzungsbezogen und erzwingt das normale Debug-Overlay. */
    public void toggleProfiler() {
        boolean enable = !FrameProfiler.isEnabled();
        FrameProfiler.setEnabled(enable);
        if (enable) this.visible = true;
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
        lines.add(String.format(Locale.ROOT, "Facing: %s (yaw %.1f / pitch %.1f)",
                I18n.tr(FACING[facing]), player.yaw, player.pitch));
        if (bx != this.lastBiomeX || bz != this.lastBiomeZ) {
            this.lastBiomeX = bx;
            this.lastBiomeZ = bz;
            this.lastBiomeName = world.biomeAt(bx, bz).name;
        }
        lines.add("Biome: " + this.lastBiomeName);
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

    /** Detaillierter Profiler-Pass; der Aufrufer schichtet ihn vor einen offenen GuiScreen. */
    public void renderProfiler(GuiManager gui) {
        FontRenderer font = gui.font();
        if (!font.available()) return;
        PerformanceProfiler.ProfilerSnapshot snapshot = PerformanceProfiler.get().publishSnapshot();
        float vW = gui.vWidth(), vH = gui.vHeight();
        boolean compact = vW < 520 || vH < 420;
        float graphH = compact ? 34 : 52;
        float graphY = gui.vHeight() - graphH - MARGIN;
        float normalBottom = MARGIN + 8 * (font.lineHeight(TEXT_SIZE) + 1) + 2;
        float panelsTop = normalBottom;
        PanelRect[] layout = workerPanelLayout(vW, panelsTop, graphY);
        float panelW = layout[0].width, panelH = layout[0].height;
        float workerSize = workerTextSize(font, panelH, 12);

        List<WorkerPanel> panels = workerPanels(snapshot);

        SpriteRenderer sprites = gui.sprites();
        sprites.begin(vW, vH);
        if (vW >= 470) {
            float summaryStep = font.lineHeight(GuiText.SMALL) + 0.5F;
            sprites.drawRect(vW - 155, MARGIN - 1, 154, 4 * summaryStep + 2,
                    0, 0, 0, 0.55F);
        }
        for (int i = 0; i < panels.size(); i++) {
            PanelRect rect = layout[i];
            sprites.drawRect(rect.x, rect.y, rect.width, rect.height, 0, 0, 0, 0.58F);
        }
        drawGraphs(sprites, snapshot.graph(), MARGIN, graphY, vW - 2 * MARGIN, graphH);
        sprites.end();

        font.begin(vW, vH);
        if (vW >= 470) drawSummary(font, snapshot, vW);
        for (int i = 0; i < panels.size(); i++) {
            PanelRect rect = layout[i];
            drawWorkerPanel(font, panels.get(i), rect.x, rect.y,
                    rect.width, rect.height, workerSize);
        }
        drawGraphLabels(font, graphY, graphH, vW);
        font.end();
    }

    private static float workerTextSize(FontRenderer font, float panelH, int lines) {
        float size = GuiText.TINY;
        float step = font.lineHeight(size) + 0.35F;
        if (step * lines > panelH - 2) size *= (panelH - 2) / (step * lines);
        return Math.max(4.5F, size);
    }

    static PanelRect[] workerPanelLayout(float vWidth, float panelsTop, float graphY) {
        float gap = 1.5F;
        float width = (vWidth - 2 * MARGIN - 2 * gap) / 3F;
        float height = Math.max(1, (graphY - panelsTop - gap) / 2F);
        PanelRect[] result = new PanelRect[6];
        for (int i = 0; i < result.length; i++) {
            result[i] = new PanelRect(MARGIN + (i % 3) * (width + gap),
                    panelsTop + (i / 3) * (height + gap), width, height);
        }
        return result;
    }

    private static List<WorkerPanel> workerPanels(PerformanceProfiler.ProfilerSnapshot snapshot) {
        List<WorkerPanel> panels = new ArrayList<>(6);
        panels.add(new WorkerPanel("L0", List.of(
                row("Queue/job", snapshot.l0().get(PerformanceProfiler.WorkerSection.L0_QUEUE_WAIT)),
                row("Disk/chunk", snapshot.l0().get(PerformanceProfiler.WorkerSection.L0_DISK_LOAD)),
                row("Terrain/chunk", snapshot.l0().get(PerformanceProfiler.WorkerSection.L0_TERRAIN)),
                row("Features/chunk", snapshot.l0().get(PerformanceProfiler.WorkerSection.L0_FEATURES)),
                row("Light init/chunk", snapshot.l0().get(PerformanceProfiler.WorkerSection.L0_INITIAL_LIGHT)),
                row("Light update/job", snapshot.l0().get(PerformanceProfiler.WorkerSection.L0_LIGHT_UPDATE)),
                row("Mesh init/section", snapshot.l0().get(PerformanceProfiler.WorkerSection.L0_INITIAL_MESH)),
                row("Remesh/section", snapshot.l0().get(PerformanceProfiler.WorkerSection.L0_REMESH)),
                row("Upload wait/batch", snapshot.l0().get(PerformanceProfiler.WorkerSection.L0_UPLOAD_WAIT)),
                row("Upload/section", snapshot.l0().get(PerformanceProfiler.WorkerSection.L0_UPLOAD)))));
        for (int level = 1; level <= 5; level++) {
            panels.add(new WorkerPanel("L" + level, List.of(
                    lodRow(snapshot, level, "Queue/region", PerformanceProfiler.WorkerSection.LOD_WORKER_QUEUE),
                    lodRow(snapshot, level, "Source I/O/chunk", PerformanceProfiler.WorkerSection.LOD_SOURCE_CACHE_DISK_SAVE),
                    lodRow(snapshot, level, "Terrain/chunk", PerformanceProfiler.WorkerSection.LOD_SOURCE_TERRAIN),
                    lodRow(snapshot, level, "Features/chunk", PerformanceProfiler.WorkerSection.LOD_SOURCE_FEATURES),
                    lodRow(snapshot, level, "Projection/chunk", PerformanceProfiler.WorkerSection.LOD_PROJECTION),
                    lodRow(snapshot, level, "Reduction/chunk", PerformanceProfiler.WorkerSection.LOD_REDUCTION),
                    lodRow(snapshot, level, "Sampling/region", PerformanceProfiler.WorkerSection.LOD_MESH_SAMPLING),
                    lodRow(snapshot, level, "Mesh/light/region", PerformanceProfiler.WorkerSection.LOD_MESH_GEOMETRY),
                    lodRow(snapshot, level, "Result wait/region", PerformanceProfiler.WorkerSection.LOD_RESULT_QUEUE),
                    lodRow(snapshot, level, "Upload/region", PerformanceProfiler.WorkerSection.LOD_GPU_UPLOAD))));
        }
        return panels;
    }

    private static WorkerRow row(String label, PerformanceProfiler.TimingStats stats) {
        return new WorkerRow(label, stats == null ? PerformanceProfiler.TimingStats.EMPTY : stats);
    }

    private static WorkerRow lodRow(PerformanceProfiler.ProfilerSnapshot snapshot, int level,
                                    String label, PerformanceProfiler.WorkerSection section) {
        return row(label, snapshot.lod(section, level));
    }

    private static void drawWorkerPanel(FontRenderer font, WorkerPanel panel, float x, float y,
                                        float width, float height, float size) {
        float step = font.lineHeight(size) + 0.35F;
        float labelX = x + 2;
        float avgRight = x + width * 0.63F, p95Right = x + width * 0.805F, maxRight = x + width - 2;
        font.drawStringWithShadow(panel.title, labelX, y + 1, size, Color4.CYAN);
        drawRight(font, "avg", avgRight, y + 1, size, Color4.LIGHT_GRAY);
        drawRight(font, "p95", p95Right, y + 1, size, Color4.LIGHT_GRAY);
        drawRight(font, "max", maxRight, y + 1, size, Color4.LIGHT_GRAY);
        float rowY = y + 1 + step;
        boolean narrow = width < 140;
        for (WorkerRow row : panel.rows) {
            if (rowY + step > y + height) break;
            font.drawStringWithShadow(row.label, labelX, rowY, size, Color4.WHITE);
            drawRight(font, stat(row.stats, 0, narrow), avgRight, rowY, size, Color4.WHITE);
            drawRight(font, stat(row.stats, 1, narrow), p95Right, rowY, size, Color4.WHITE);
            drawRight(font, stat(row.stats, 2, narrow), maxRight, rowY, size, Color4.WHITE);
            rowY += step;
        }
    }

    private static String stat(PerformanceProfiler.TimingStats stats, int column, boolean narrow) {
        if (stats == null || stats.samples() == 0) return "-";
        double value = switch (column) {
            case 0 -> stats.meanMillis();
            case 1 -> stats.p95Millis();
            default -> stats.maxMillis();
        };
        if (value >= 1_000) return ">999";
        return String.format(Locale.ROOT, narrow ? "%.1f" : "%.2f", value);
    }

    private static void drawRight(FontRenderer font, String text, float right, float y,
                                  float size, Color4 color) {
        font.drawStringWithShadow(text, right - font.getStringWidth(text, size), y, size, color);
    }

    private static void drawSummary(FontRenderer font, PerformanceProfiler.ProfilerSnapshot snapshot, float vW) {
        float size = GuiText.SMALL, step = font.lineHeight(size) + 0.5F;
        float x = vW - 154, y = MARGIN;
        font.drawStringWithShadow(DebugFlags.lodLevelSplit ? "Profiler [LOD split!]" : "Profiler",
                x, y, size, Color4.WHITE);
        float[] rights = {x + 67, x + 94, x + 121, x + 151};
        String[] headings = {"cur", "avg", "p95", "max"};
        for (int i = 0; i < headings.length; i++) {
            drawRight(font, headings[i], rights[i], y, size, Color4.LIGHT_GRAY);
        }
        drawSummaryRow(font, "CPU", snapshot.cpu().get(PerformanceProfiler.CpuSection.FRAME), x, y + step, size);
        drawSummaryRow(font, "GPU", snapshot.gpu().get(PerformanceProfiler.GpuSection.FRAME_SPAN), x, y + 2 * step, size);
        drawSummaryRow(font, "Tick", snapshot.tick().get(PerformanceProfiler.TickSection.TOTAL), x, y + 3 * step, size);
    }

    private static void drawSummaryRow(FontRenderer font, String label, PerformanceProfiler.TimingStats stats,
                                       float x, float y, float size) {
        font.drawStringWithShadow(label, x, y, size, Color4.WHITE);
        float[] rights = {x + 67, x + 94, x + 121, x + 151};
        if (stats == null || stats.samples() == 0) {
            drawRight(font, "-", rights[0], y, size, Color4.WHITE);
            return;
        }
        double[] values = {stats.currentMillis(), stats.meanMillis(), stats.p95Millis(), stats.maxMillis()};
        for (int i = 0; i < values.length; i++) {
            drawRight(font, String.format(Locale.ROOT, "%.2f", values[i]), rights[i], y, size, Color4.WHITE);
        }
    }

    private static void drawGraphs(SpriteRenderer sprites, List<PerformanceProfiler.GraphSample> samples,
                                   float x, float y, float width, float height) {
        float gap = 2;
        float graphWidth = (width - gap) * 0.5F;
        float tickX = x + graphWidth + gap;
        sprites.drawRect(x, y, graphWidth, height, 0, 0, 0, 0.46F);
        sprites.drawRect(tickX, y, graphWidth, height, 0, 0, 0, 0.46F);
        float plotTop = y + 9, plotHeight = height - 10;
        List<SpriteRenderer.Rect> batch = new ArrayList<>(4 + samples.size() * 3);
        for (double threshold : new double[]{8.33, 16.67, 33.3}) {
            float ty = plotTop + plotHeight - (float) Math.min(1, threshold / 40.0) * plotHeight;
            batch.add(new SpriteRenderer.Rect(x, ty, graphWidth, 0.45F, 0.8F, 0.8F, 0.8F, 0.25F));
        }
        float tpsLine = plotTop + plotHeight - (float) (50.0 / 55.0) * plotHeight;
        batch.add(new SpriteRenderer.Rect(tickX, tpsLine, graphWidth, 0.55F, 1F, 0.35F, 0.2F, 0.5F));
        if (!samples.isEmpty()) {
            float barW = Math.max(0.35F, graphWidth / 200F);
            int skip = Math.max(0, samples.size() - 200);
            for (int i = skip; i < samples.size(); i++) {
                PerformanceProfiler.GraphSample sample = samples.get(i);
                float framePx = x + graphWidth - (samples.size() - i) * barW;
                float tickPx = tickX + graphWidth - (samples.size() - i) * barW;
                addBar(batch, framePx, plotTop, barW, plotHeight,
                        sample.cpuFrameMillis(), 40.0, 0.2F, 0.85F, 0.35F);
                addBar(batch, framePx, plotTop, Math.max(0.25F, barW * 0.55F), plotHeight,
                        sample.gpuFrameMillis(), 40.0, 0.3F, 0.55F, 1F);
                addBar(batch, tickPx, plotTop, barW, plotHeight,
                        sample.tickMillis(), 55.0, 1F, 0.65F, 0.15F);
            }
        }
        sprites.drawRects(batch);
    }

    private static void addBar(List<SpriteRenderer.Rect> batch, float x, float top,
                               float width, float height, double millis, double scale,
                               float r, float g, float b) {
        float h = (float) Math.min(1, millis / scale) * height;
        if (h > 0) {
            h = Math.max(0.5F, h);
            batch.add(new SpriteRenderer.Rect(x, top + height - h, width, h, r, g, b, 0.9F));
        }
    }

    private static void drawGraphLabels(FontRenderer font, float graphY, float graphH, float vW) {
        float size = vW < 520 ? 5.5F : GuiText.TINY;
        float gap = 2, width = vW - 2 * MARGIN;
        float graphW = (width - gap) * 0.5F;
        float tickX = MARGIN + graphW + gap;
        font.drawStringWithShadow("CPU", MARGIN + 2, graphY + 1, size, Color4.GREEN);
        font.drawStringWithShadow("GPU", MARGIN + 17, graphY + 1, size, Color4.CYAN);
        font.drawStringWithShadow("10 s", MARGIN + 32, graphY + 1, size, Color4.LIGHT_GRAY);
        font.drawStringWithShadow("Tick  10 s", tickX + 2, graphY + 1, size, Color4.ORANGE);
        float plotTop = graphY + 9, plotH = graphH - 10;
        for (double threshold : new double[]{8.33, 16.67, 33.3}) {
            float ty = plotTop + plotH - (float) Math.min(1, threshold / 40.0) * plotH;
            String label = threshold < 10 ? "8.3" : threshold < 20 ? "16.7" : "33.3";
            drawRight(font, label, MARGIN + graphW - 1, ty - size * 0.45F, size, Color4.LIGHT_GRAY);
        }
        float tpsY = plotTop + plotH - (float) (50.0 / 55.0) * plotH;
        drawRight(font, "50", tickX + graphW - 1, tpsY - size * 0.45F, size, Color4.ORANGE);
    }

    private record WorkerPanel(String title, List<WorkerRow> rows) {}
    private record WorkerRow(String label, PerformanceProfiler.TimingStats stats) {}
    record PanelRect(float x, float y, float width, float height) {}
}
