package de.skyengine.graphics.gui.screens;

import de.skyengine.core.i18n.I18n;
import de.skyengine.game.world.block.entity.CoalGeneratorBlockEntity;
import de.skyengine.game.world.block.entity.ItemStorage;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.recipe.RecipeManager;
import de.skyengine.graphics.color.Colors;
import de.skyengine.graphics.gui.EnergyText;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiText;
import de.skyengine.graphics.gui.Slot;
import de.skyengine.graphics.gui.SlotGroup;
import de.skyengine.graphics.gui.SpriteRenderer;

import java.util.ArrayList;
import java.util.List;

public final class GuiCoalGenerator extends GuiContainer {
    private static final int W = 176, H = 166;
    private final CoalGeneratorBlockEntity generator;
    private final ItemStorage machine;
    private final ItemStorage player;
    private float x, y;

    public GuiCoalGenerator(CoalGeneratorBlockEntity generator, ItemStorage player) {
        super(player, generator.getInventory());
        this.generator = generator;
        this.machine = generator.getInventory();
        this.player = player;
    }

    @Override public void init(GuiManager gui, float vW, float vH) {
        this.x = (vW - W) / 2F;
        this.y = (vH - H) / 2F;
        int gx = Math.round(this.x), gy = Math.round(this.y);
        this.slots.clear();
        this.slots.add(new Slot(this.machine, 0, gx + 28, gy + 42, SlotGroup.MACHINE_FUEL,
                GuiCoalGenerator::validFuel));
        for (int row = 0; row < 3; row++) for (int col = 0; col < COLS; col++) {
            this.slots.add(new Slot(this.player, COLS + row * COLS + col,
                    gx + 8 + col * STEP, gy + 84 + row * STEP, SlotGroup.INVENTORY));
        }
        for (int col = 0; col < COLS; col++) this.slots.add(new Slot(this.player, col,
                gx + 8 + col * STEP, gy + 142, SlotGroup.HOTBAR));
    }

    private static boolean validFuel(ItemStack stack) {
        return RecipeManager.get().fuels().burnTime(RecipeManager.SOLID_FUEL, stack) > 0;
    }

    @Override protected List<Slot> quickMoveTargets(SlotGroup from) {
        if (from == SlotGroup.MACHINE_FUEL) {
            List<Slot> result = new ArrayList<>(this.slotsOf(SlotGroup.HOTBAR));
            result.addAll(this.slotsOf(SlotGroup.INVENTORY));
            return result;
        }
        List<Slot> result = new ArrayList<>(this.slotsOf(SlotGroup.MACHINE_FUEL));
        result.addAll(this.slotsOf(from == SlotGroup.HOTBAR ? SlotGroup.INVENTORY : SlotGroup.HOTBAR));
        return result;
    }

    @Override protected boolean isInsideWindow(double mx, double my) {
        return mx >= this.x && mx < this.x + W && my >= this.y && my < this.y + H;
    }

    @Override public void render(GuiManager gui, double mouseX, double mouseY) {
        SpriteRenderer sr = gui.sprites();
        sr.begin(gui.vWidth(), gui.vHeight());
        this.renderBackground(gui);
        sr.drawRect(this.x, this.y, W, H, .12F, .12F, .14F, .98F);
        sr.drawRect(this.x + 8, this.y + 83, W - 16, 1, .35F, .35F, .38F, 1F);
        sr.drawRect(this.x + 61, this.y + 26, 16, 50, .03F, .04F, .05F, 1F);
        float energyHeight = 48 * this.generator.getEnergy() / (float) this.generator.getCapacity();
        sr.drawRect(this.x + 62, this.y + 75 - energyHeight, 14, energyHeight, .15F, .75F, .95F, 1F);
        sr.drawRect(this.x + 26, this.y + 40, 20, 20, .25F, .25F, .28F, 1F);
        if (this.generator.getBurnTime() > 0) {
            float burn = 14 * this.generator.getBurnTime() / (float) Math.max(1, this.generator.getBurnDuration());
            sr.drawRect(this.x + 29, this.y + 66 - burn, 14, burn, 1F, .45F, .05F, 1F);
        }
        this.drawSlotHover(gui, mouseX, mouseY);
        sr.end();
        this.drawSlotIcons(gui, mouseX, mouseY);
        gui.font().begin(gui.vWidth(), gui.vHeight());
        gui.font().drawString(I18n.tr("gui.coal_generator.title"), this.x + 8, this.y + 7,
                GuiText.NORMAL, Colors.WHITE);
        gui.font().drawString(EnergyText.format(this.generator.getEnergy()) + " / "
                        + EnergyText.format(this.generator.getCapacity()), this.x + 82, this.y + 32,
                GuiText.SMALL, Colors.WHITE);
        gui.font().drawString(I18n.tr("gui.coal_generator.production",
                        EnergyText.format(CoalGeneratorBlockEntity.PRODUCTION)), this.x + 82, this.y + 48,
                GuiText.SMALL, Colors.WHITE);
        gui.font().drawString(I18n.tr("gui.coal_generator.output",
                        EnergyText.format(CoalGeneratorBlockEntity.MAX_OUTPUT)), this.x + 82, this.y + 61,
                GuiText.SMALL, Colors.WHITE);
        gui.font().end();
        this.drawTooltip(gui, mouseX, mouseY);
    }
}
