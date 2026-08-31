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
import de.skyengine.graphics.gui.widget.Spacer;
import de.skyengine.graphics.post.PostProcessingSettings;
import de.skyengine.graphics.post.PostProcessingSettings.AntiAliasingMode;

import static de.skyengine.graphics.gui.screens.GuiOptionsMenu.CELL_H;
import static de.skyengine.graphics.gui.screens.GuiOptionsMenu.CELL_W;

/**
 * Debug-Unterseite des Optionsmenüs: transiente Entwickler-Schalter (Wireframe, AA-Modus)
 * und Aktionen (Chunks/Postprocessing neu laden). Nichts davon
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

        boolean paused = game.getDimension() != null && game.getDimension().getChunkManager().isLoadingPaused();
        CycleButton<Boolean> pauseLoading = CycleButton.onOff(I18n.tr("options.debug.pause_loading"), CELL_W, CELL_H,
                paused, v -> {
                    if (game.getDimension() != null) game.getDimension().getChunkManager().setLoadingPaused(v);
                });

        CycleButton<Boolean> guiSlots = CycleButton.onOff(I18n.tr("options.debug.gui_slots"), CELL_W, CELL_H, DebugFlags.guiSlotBounds, v -> DebugFlags.guiSlotBounds = v);
        CycleButton<Boolean> underwaterEffect = CycleButton.onOff(I18n.tr("options.debug.underwater_effect"), CELL_W, CELL_H, DebugFlags.underwaterEffect, v -> DebugFlags.underwaterEffect = v);

        Button reloadChunks = new Button(I18n.tr("options.debug.reload_chunks"), CELL_W, CELL_H, () -> {
            if (game.getDimension() != null) game.getDimension().reloadAllChunks();
        });
        Button reloadPost = new Button(I18n.tr("options.debug.reload_post"), CELL_W, CELL_H,
                () -> SkyEngine.get().getPostProcessor().getSettings().reloadFromFile());

        content.add(new HStack(4, wireframe, null));
        content.add(new HStack(4, underwaterEffect, guiSlots));
        content.add(new HStack(4, reloadChunks, reloadPost));
        content.add(new HStack(4, pauseLoading, null));
    }
}
