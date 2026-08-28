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
    private static final float FLAME_SIZE = 13F;
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
        sr.drawNineSlice(gui.textures().mekanismBase, this.x, this.y, W, H, 2);
        sr.drawSprite(gui.textures().mekanismEnergyInfoTab, this.x + W - 2, this.y + 5, 26, 26);
        sr.drawSprite(gui.textures().mekanismEnergy, this.x + W + 2, this.y + 9, 18, 18);
        for (Slot slot : this.slots) sr.drawSprite(gui.textures().mekanismSlot, slot.x - 1, slot.y - 1, 18, 18);
        sr.drawRect(this.x + 61, this.y + 25, 8, 54, .10F, .10F, .10F, 1F);
        float energyHeight = 52 * this.generator.getEnergy() / (float) this.generator.getCapacity();
        float hiddenEnergy = 52 - energyHeight;
        if (energyHeight > 0) sr.drawSprite(gui.textures().mekanismVerticalPower,
                this.x + 63, this.y + 26 + hiddenEnergy, 4, energyHeight, 0, hiddenEnergy / 52F, 1, 1);
        /* flame.png contains two 13x13 states next to each other: inactive on the left,
           burning on the right. Both belong at the same position below the fuel slot. */
        float flameX = this.x + 29.5F;
        float flameY = this.y + 64F;
        sr.drawSprite(gui.textures().mekanismFlame, flameX, flameY, FLAME_SIZE, FLAME_SIZE,
                0, 0, .5F, 1);
        FlameSlice flame = flameSlice(this.generator.getBurnTime(), this.generator.getBurnDuration());
        if (flame.height > 0) {
            sr.drawSprite(gui.textures().mekanismFlame,
                    flameX, flameY + FLAME_SIZE - flame.height, FLAME_SIZE, flame.height,
                    .5F, flame.v0, 1, 1);
        }
        this.drawSlotHover(gui, mouseX, mouseY);
        sr.end();
        this.drawSlotIcons(gui, mouseX, mouseY);
        gui.font().begin(gui.vWidth(), gui.vHeight());
        gui.font().drawString(I18n.tr("gui.coal_generator.title"), this.x + 8, this.y + 7,
                GuiText.NORMAL, Colors.DARK_GRAY);
        gui.font().drawString(EnergyText.format(this.generator.getEnergy()) + " / "
                        + EnergyText.format(this.generator.getCapacity()), this.x + 82, this.y + 32,
                GuiText.SMALL, Colors.DARK_GRAY);
        gui.font().drawString(I18n.tr("gui.coal_generator.production",
                        EnergyText.format(CoalGeneratorBlockEntity.PRODUCTION)), this.x + 82, this.y + 48,
                GuiText.SMALL, Colors.DARK_GRAY);
        gui.font().drawString(I18n.tr("gui.coal_generator.output",
                        EnergyText.format(CoalGeneratorBlockEntity.MAX_OUTPUT)), this.x + 82, this.y + 61,
                GuiText.SMALL, Colors.DARK_GRAY);
        long remainingFuel = (long) this.generator.getBurnTime() * CoalGeneratorBlockEntity.PRODUCTION;
        gui.font().drawString(I18n.tr("gui.coal_generator.remaining", EnergyText.format(remainingFuel)),
                this.x + 82, this.y + 74, GuiText.TINY, Colors.DARK_GRAY);
        gui.font().end();
        this.drawTooltip(gui, mouseX, mouseY);
    }

    /** Bottom-up crop of the active (right-hand) 13x13 flame sprite. */
    static FlameSlice flameSlice(int burnTime, int burnDuration) {
        float progress = burnDuration <= 0 ? 0F
                : Math.max(0F, Math.min(1F, burnTime / (float) burnDuration));
        return new FlameSlice(FLAME_SIZE * progress, 1F - progress);
    }

    record FlameSlice(float height, float v0) {}
}
