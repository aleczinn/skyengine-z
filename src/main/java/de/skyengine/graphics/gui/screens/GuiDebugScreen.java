package de.skyengine.graphics.gui.screens;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.i18n.I18n;
import de.skyengine.game.GameContainer;
import de.skyengine.graphics.DebugFlags;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.layout.HStack;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.Button;
import de.skyengine.graphics.gui.widget.CycleButton;
import de.skyengine.graphics.post.PostProcessingSettings;
import de.skyengine.graphics.post.PostProcessingSettings.AntiAliasingMode;
import de.skyengine.graphics.world.GpuCull;

import static de.skyengine.graphics.gui.screens.GuiOptionsMenu.CELL_H;
import static de.skyengine.graphics.gui.screens.GuiOptionsMenu.CELL_W;

/**
 * Debug-Unterseite des Optionsmenüs: transiente Entwickler-Schalter (Wireframe, GPU-Cull,
 * LOD-Level-Farben, AA-Modus) und Aktionen (Chunks/Postprocessing neu laden). Nichts davon
 * wird persistiert — beim Neustart wieder Standard. Welt-abhängige Schalter sind null-geguardet
 * (der Screen ist auch aus dem Titelmenü ohne Welt erreichbar).
 */
public final class GuiDebugScreen extends GuiOptionsScreen {

    public GuiDebugScreen(GuiScreen parent) {
        super(parent);
    }

    @Override
    protected String title() {
        return I18n.tr("options.debug.title");
    }

    @Override
    protected void buildContent(GuiManager gui, VStack content) {
        GameContainer game = SkyEngine.get().getGame();

        CycleButton<Boolean> wireframe = CycleButton.onOff(I18n.tr("options.debug.wireframe"), CELL_W, CELL_H,
                DebugFlags.wireframe, v -> DebugFlags.wireframe = v);

        CycleButton<Boolean> gpuCull = CycleButton.onOff(I18n.tr("options.debug.gpu_cull"), CELL_W, CELL_H,
                GpuCull.ENABLED, v -> GpuCull.ENABLED = v);

        CycleButton<Boolean> gpuCullTint = CycleButton.onOff(I18n.tr("options.debug.gpu_cull_tint"), CELL_W, CELL_H,
                GpuCull.DEBUG_TINT, v -> GpuCull.DEBUG_TINT = v);

        /* Hi-Z getrennt schaltbar: gemessen kostet die Occlusion deutlich mehr, als sie bei
           heutigem Content spart — das reine Compute-Frustum ist dagegen fast gratis. */
        CycleButton<Boolean> gpuCullHiZ = CycleButton.onOff(I18n.tr("options.debug.gpu_cull_hiz"), CELL_W, CELL_H,
                !GpuCull.FRUSTUM_ONLY, v -> GpuCull.FRUSTUM_ONLY = !v);

        CycleButton<Boolean> lodColors = CycleButton.onOff(I18n.tr("options.debug.lod_colors"), CELL_W, CELL_H,
                DebugFlags.lodLevelColors, v -> DebugFlags.lodLevelColors = v);

        boolean paused = game.getWorld() != null && game.getWorld().getChunkManager().isLoadingPaused();
        CycleButton<Boolean> pauseLoading = CycleButton.onOff(I18n.tr("options.debug.pause_loading"), CELL_W, CELL_H,
                paused, v -> {
                    if (game.getWorld() != null) game.getWorld().getChunkManager().setLoadingPaused(v);
                });

        CycleButton<Boolean> guiSlots = CycleButton.onOff(I18n.tr("options.debug.gui_slots"), CELL_W, CELL_H,
                DebugFlags.guiSlotBounds, v -> DebugFlags.guiSlotBounds = v);

        PostProcessingSettings post = SkyEngine.get().getPostProcessor().getSettings();
        CycleButton<AntiAliasingMode> aa = new CycleButton<>(I18n.tr("options.debug.aa_mode"), CELL_W, CELL_H,
                AntiAliasingMode.values(), post.getAaMode(), Enum::name, post::setAaMode);

        Button reloadChunks = new Button(I18n.tr("options.debug.reload_chunks"), CELL_W, CELL_H, () -> {
            if (game.getWorld() != null) game.getWorld().reloadAllChunks();
        });
        Button reloadPost = new Button(I18n.tr("options.debug.reload_post"), CELL_W, CELL_H,
                () -> SkyEngine.get().getPostProcessor().getSettings().reloadFromFile());

        content.add(new HStack(4, wireframe, gpuCull));
        content.add(new HStack(4, gpuCullHiZ, gpuCullTint));
        content.add(lodColors);
        content.add(new HStack(4, pauseLoading, guiSlots));
        content.add(aa);
        content.add(new HStack(4, reloadChunks, reloadPost));
    }
}
