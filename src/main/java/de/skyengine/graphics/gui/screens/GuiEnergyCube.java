package de.skyengine.graphics.gui.screens;

import de.skyengine.core.i18n.I18n;
import de.skyengine.game.world.block.entity.EnergyCubeBlockEntity;
import de.skyengine.game.world.block.entity.EnergySideMode;
import de.skyengine.game.world.block.entity.RelativeSide;
import de.skyengine.graphics.color.Colors;
import de.skyengine.graphics.gui.EnergyText;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.GuiText;
import de.skyengine.graphics.gui.SpriteRenderer;

/** RF overview and six-side configuration for the basic energy cube. */
public final class GuiEnergyCube extends GuiScreen {
    private static final int W = 176, H = 166;
    private static final int BUTTON_W = 46, BUTTON_H = 20;
    private final EnergyCubeBlockEntity cube;
    private float x, y;

    public GuiEnergyCube(EnergyCubeBlockEntity cube) { super(null); this.cube = cube; }

    @Override public void init(GuiManager gui, float vW, float vH) {
        this.x = (vW - W) / 2F;
        this.y = (vH - H) / 2F;
    }

    @Override public void render(GuiManager gui, double mouseX, double mouseY) {
        SpriteRenderer sr = gui.sprites();
        sr.begin(gui.vWidth(), gui.vHeight());
        this.renderBackground(gui);
        sr.drawNineSlice(gui.textures().mekanismBase, this.x, this.y, W, H, 2);
        sr.drawSprite(gui.textures().mekanismEnergyInfoTab, this.x + W - 2, this.y + 5, 26, 26);
        sr.drawSprite(gui.textures().mekanismEnergy, this.x + W + 2, this.y + 9, 18, 18);
        sr.drawSprite(gui.textures().mekanismWideGauge, this.x + 56, this.y + 19, 64, 48);
        float fill = 58 * this.cube.getEnergy() / (float) this.cube.getCapacity();
        sr.drawRect(this.x + 59, this.y + 43, fill, 8, .08F, .72F, 1F, 1F);
        sr.drawSprite(gui.textures().mekanismConfiguration, this.x + 8, this.y + 26, 18, 18);
        RelativeSide[] sides = RelativeSide.values();
        for (int i = 0; i < sides.length; i++) {
            float bx = this.x + 14 + (i % 3) * 50;
            float by = this.y + 86 + (i / 3) * 25;
            EnergySideMode mode = this.cube.getSideMode(sides[i]);
            float r = mode == EnergySideMode.OUTPUT ? .75F : mode == EnergySideMode.DISABLED ? .25F : .12F;
            float g = mode == EnergySideMode.INPUT ? .65F : mode == EnergySideMode.DISABLED ? .25F : .28F;
            sr.drawNineSlice(gui.textures().mekanismBase, bx, by, BUTTON_W, BUTTON_H, 2);
            sr.drawRect(bx + 2, by + 2, 5, BUTTON_H - 4, r, g, .30F, 1F);
        }
        sr.end();

        gui.font().begin(gui.vWidth(), gui.vHeight());
        gui.font().drawString(I18n.tr("gui.energy_cube.title"), this.x + 8, this.y + 7,
                GuiText.NORMAL, Colors.DARK_GRAY);
        String energy = EnergyText.format(this.cube.getEnergy()) + " / " + EnergyText.format(this.cube.getCapacity());
        gui.font().drawString(energy, this.x + 57, this.y + 70, GuiText.SMALL, Colors.DARK_GRAY);
        gui.font().drawString(I18n.tr("gui.energy_cube.configuration"), this.x + 30, this.y + 31,
                GuiText.SMALL, Colors.DARK_GRAY);
        for (int i = 0; i < sides.length; i++) {
            float bx = this.x + 22 + (i % 3) * 50;
            float by = this.y + 91 + (i / 3) * 25;
            String label = I18n.tr("gui.energy.side." + sides[i].name().toLowerCase()) + ": "
                    + I18n.tr("gui.energy.mode." + this.cube.getSideMode(sides[i]).name().toLowerCase());
            gui.font().drawString(label, bx, by, GuiText.TINY, Colors.DARK_GRAY);
        }
        gui.font().drawString(I18n.tr("gui.energy_cube.rate", EnergyText.format(EnergyCubeBlockEntity.TRANSFER_RATE)),
                this.x + 8, this.y + 148, GuiText.SMALL, Colors.DARK_GRAY);
        gui.font().end();
    }

    @Override public boolean mousePressed(GuiManager gui, double mouseX, double mouseY, int button) {
        if (button != 0) return super.mousePressed(gui, mouseX, mouseY, button);
        RelativeSide[] sides = RelativeSide.values();
        for (int i = 0; i < sides.length; i++) {
            float bx = this.x + 14 + (i % 3) * 50;
            float by = this.y + 86 + (i / 3) * 25;
            if (mouseX >= bx && mouseX < bx + BUTTON_W && mouseY >= by && mouseY < by + BUTTON_H) {
                this.cube.cycleSideMode(sides[i]);
                gui.sound().playUiClick();
                return true;
            }
        }
        return super.mousePressed(gui, mouseX, mouseY, button);
    }
}
