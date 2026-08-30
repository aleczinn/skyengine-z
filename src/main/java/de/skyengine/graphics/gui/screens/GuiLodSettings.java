package de.skyengine.graphics.gui.screens;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.i18n.I18n;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.GameContainer;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.layout.HStack;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.CycleButton;
import de.skyengine.graphics.gui.widget.Slider;

import static de.skyengine.graphics.gui.screens.GuiOptionsMenu.CELL_H;
import static de.skyengine.graphics.gui.screens.GuiOptionsMenu.CELL_W;

/**
 * LOD-Unterseite der Grafikoptionen: Ringe an/aus, Reichweite, AO-Qualität.
 *
 * <p>Alle drei Einstellungen liest der {@code LodManager} selbst; ein Voll-Remesh wie bei AO/Laub
 * ist NICHT nötig, weil er bei jeder Änderung die Settings-Epoche erhöht und damit alle
 * LOD-Regionen neu bauen lässt. Das echte Terrain (L0) ist von keiner davon betroffen.
 *
 * <p>Die LOD-Debug-Schalter (Level-Split, Level-Farben) bleiben bewusst im
 * {@link GuiDebugScreen} — sie sind Messwerkzeug, keine Spieleroption.
 */
public final class GuiLodSettings extends GuiOptionsScreen {

    private final GameSettings settings = GameSettings.get();

    public GuiLodSettings(GuiScreen parent) {
        super(parent);
    }

    @Override
    protected String title() {
        return I18n.tr("options.lod.title");
    }

    @Override
    protected void buildContent(GuiManager gui, VStack content) {
        GameContainer game = SkyEngine.get().getGame();

        CycleButton<Boolean> enabled = CycleButton.onOff(I18n.tr("options.lod.enabled"), CELL_W, CELL_H,
                this.settings.lodEnabled, v -> {
                    this.settings.lodEnabled = v;
                    game.applySettings(); // farPlane nachziehen; LodManager liest das Setting selbst
                });

        /* Erst beim Loslassen anwenden: ein Distanzwechsel wirft den kompletten Ring weg. */
        Slider distance = new Slider(CELL_W, CELL_H, 8, 256, 8, this.settings.lodMaxDistance,
                v -> I18n.tr("options.lod.distance", (int) v),
                v -> this.settings.lodMaxDistance = (int) v,
                game::applySettings);

        CycleButton<GameSettings.ScreenSpaceAoQuality> ssao = new CycleButton<>(I18n.tr("options.lod.ssao"),
                CELL_W, CELL_H, GameSettings.ScreenSpaceAoQuality.values(), this.settings.screenSpaceAoQuality,
                Enum::name, v -> this.settings.screenSpaceAoQuality = v)
                .tooltipOf(v -> I18n.tr("options.lod.ssao_hint"));

        content.add(new HStack(4, enabled, distance));
        content.add(new HStack(4, ssao, null));
    }

    @Override
    public void onClose() {
        this.settings.save();
    }
}
