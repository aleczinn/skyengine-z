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
    private static final int W = 190, H = 128;
    private static final int BUTTON_W = 52, BUTTON_H = 18;
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
        sr.drawRect(this.x, this.y, W, H, .10F, .12F, .15F, .98F);
        sr.drawRect(this.x + 8, this.y + 25, W - 16, 14, .03F, .04F, .05F, 1F);
        float fill = (W - 18) * this.cube.getEnergy() / (float) this.cube.getCapacity();
        sr.drawRect(this.x + 9, this.y + 26, fill, 12, .15F, .75F, .95F, 1F);
        RelativeSide[] sides = RelativeSide.values();
        for (int i = 0; i < sides.length; i++) {
            float bx = this.x + 8 + (i % 3) * 58;
            float by = this.y + 55 + (i / 3) * 25;
            EnergySideMode mode = this.cube.getSideMode(sides[i]);
            float r = mode == EnergySideMode.OUTPUT ? .75F : mode == EnergySideMode.DISABLED ? .25F : .12F;
            float g = mode == EnergySideMode.INPUT ? .65F : mode == EnergySideMode.DISABLED ? .25F : .28F;
            sr.drawRect(bx, by, BUTTON_W, BUTTON_H, r, g, .30F, 1F);
        }
        sr.end();

        gui.font().begin(gui.vWidth(), gui.vHeight());
        gui.font().drawString(I18n.tr("gui.energy_cube.title"), this.x + 8, this.y + 7,
                GuiText.NORMAL, Colors.WHITE);
        String energy = EnergyText.format(this.cube.getEnergy()) + " / " + EnergyText.format(this.cube.getCapacity());
        gui.font().drawString(energy, this.x + 10, this.y + 42, GuiText.SMALL, Colors.WHITE);
        for (int i = 0; i < sides.length; i++) {
            float bx = this.x + 10 + (i % 3) * 58;
            float by = this.y + 59 + (i / 3) * 25;
            String label = I18n.tr("gui.energy.side." + sides[i].name().toLowerCase()) + ": "
                    + I18n.tr("gui.energy.mode." + this.cube.getSideMode(sides[i]).name().toLowerCase());
            gui.font().drawString(label, bx, by, GuiText.TINY, Colors.WHITE);
        }
        gui.font().drawString(I18n.tr("gui.energy_cube.rate", EnergyText.format(EnergyCubeBlockEntity.TRANSFER_RATE)),
                this.x + 8, this.y + 109, GuiText.SMALL, Colors.WHITE);
        gui.font().end();
    }

    @Override public boolean mousePressed(GuiManager gui, double mouseX, double mouseY, int button) {
        if (button != 0) return super.mousePressed(gui, mouseX, mouseY, button);
        RelativeSide[] sides = RelativeSide.values();
        for (int i = 0; i < sides.length; i++) {
            float bx = this.x + 8 + (i % 3) * 58;
            float by = this.y + 55 + (i / 3) * 25;
            if (mouseX >= bx && mouseX < bx + BUTTON_W && mouseY >= by && mouseY < by + BUTTON_H) {
                this.cube.cycleSideMode(sides[i]);
                gui.sound().playUiClick();
                return true;
            }
        }
        return super.mousePressed(gui, mouseX, mouseY, button);
    }
}
