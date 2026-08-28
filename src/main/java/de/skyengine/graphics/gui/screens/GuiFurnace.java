package de.skyengine.graphics.gui.screens;

import de.skyengine.game.world.block.entity.FurnaceBlockEntity;
import de.skyengine.game.world.block.entity.ItemStorage;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.recipe.RecipeManager;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.Slot;
import de.skyengine.graphics.gui.SlotGroup;
import de.skyengine.graphics.gui.SpriteRenderer;
import de.skyengine.graphics.texture.Texture;

import java.util.ArrayList;
import java.util.List;

/** Standardofen-GUI mit Flammen- und Fortschrittsanzeige. */
public final class GuiFurnace extends GuiContainer {

    private static final int W = 176, H = 166;
    private static final float TEX = 256F;
    private final FurnaceBlockEntity furnace;
    private final ItemStorage furnaceInv;
    private final ItemStorage playerInv;
    private float guiX, guiY;

    public GuiFurnace(FurnaceBlockEntity furnace, ItemStorage playerInv) {
        super(playerInv, furnace.getInventory());
        this.furnace = furnace;
        this.furnaceInv = furnace.getInventory();
        this.playerInv = playerInv;
    }

    @Override public void init(GuiManager gui, float vW, float vH) {
        this.guiX = (vW - W) / 2F;
        this.guiY = (vH - H) / 2F;
        int gx = Math.round(this.guiX), gy = Math.round(this.guiY);
        this.slots.clear();
        this.slots.add(new Slot(this.furnaceInv, FurnaceBlockEntity.INPUT, gx + 56, gy + 17,
                SlotGroup.MACHINE_INPUT, stack -> validInput(stack)));
        this.slots.add(new Slot(this.furnaceInv, FurnaceBlockEntity.FUEL, gx + 56, gy + 53,
                SlotGroup.MACHINE_FUEL, stack -> validFuel(stack)));
        this.slots.add(new Slot(this.furnaceInv, FurnaceBlockEntity.OUTPUT, gx + 116, gy + 35,
                SlotGroup.MACHINE_OUTPUT, stack -> false));
        for (int row = 0; row < 3; row++) for (int col = 0; col < COLS; col++) {
            this.slots.add(new Slot(this.playerInv, COLS + row * COLS + col,
                    gx + 8 + col * STEP, gy + 84 + row * STEP, SlotGroup.INVENTORY));
        }
        for (int col = 0; col < COLS; col++) {
            this.slots.add(new Slot(this.playerInv, col, gx + 8 + col * STEP, gy + 142, SlotGroup.HOTBAR));
        }
    }

    private static boolean validInput(ItemStack stack) {
        return RecipeManager.get().findProcessing(RecipeManager.FURNACE, List.of(stack)) != null;
    }

    private static boolean validFuel(ItemStack stack) {
        return RecipeManager.get().fuels().burnTime(RecipeManager.SOLID_FUEL, stack) > 0;
    }

    @Override protected List<Slot> quickMoveTargets(SlotGroup from) {
        if (from == SlotGroup.MACHINE_INPUT || from == SlotGroup.MACHINE_FUEL || from == SlotGroup.MACHINE_OUTPUT) {
            List<Slot> targets = new ArrayList<>(this.slotsOf(SlotGroup.HOTBAR));
            targets.addAll(this.slotsOf(SlotGroup.INVENTORY));
            return targets;
        }
        List<Slot> targets = new ArrayList<>(this.slotsOf(SlotGroup.MACHINE_INPUT));
        targets.addAll(this.slotsOf(SlotGroup.MACHINE_FUEL));
        targets.addAll(this.slotsOf(from == SlotGroup.HOTBAR ? SlotGroup.INVENTORY : SlotGroup.HOTBAR));
        return targets;
    }

    @Override protected boolean isInsideWindow(double mx, double my) {
        return mx >= this.guiX && mx < this.guiX + W && my >= this.guiY && my < this.guiY + H;
    }

    @Override public void render(GuiManager gui, double mouseX, double mouseY) {
        SpriteRenderer sprites = gui.sprites();
        Texture texture = gui.textures().furnaceBackground;
        sprites.begin(gui.vWidth(), gui.vHeight());
        this.renderBackground(gui);
        sprites.drawSprite(texture, this.guiX, this.guiY, W, H, 0, 0, W / TEX, H / TEX);
        if (this.furnace.getBurnTime() > 0) {
            int flame = Math.max(1, 14 * this.furnace.getBurnTime() / Math.max(1, this.furnace.getBurnDuration()));
            int hidden = 14 - flame;
            sprites.drawSprite(gui.textures().furnaceLitProgress, this.guiX + 56, this.guiY + 36 + hidden,
                    14, flame, 0, hidden / 14F, 1, 1);
        }
        if (this.furnace.getCookProgress() > 0) {
            int arrow = Math.min(24, 24 * this.furnace.getCookProgress() / Math.max(1, this.furnace.getCookDuration()));
            sprites.drawSprite(gui.textures().furnaceBurnProgress, this.guiX + 79, this.guiY + 34,
                    arrow, 16, 0, 0, arrow / 24F, 1);
        }
        this.drawSlotHover(gui, mouseX, mouseY);
        sprites.end();
        this.drawSlotIcons(gui, mouseX, mouseY);
        this.drawTooltip(gui, mouseX, mouseY);
    }
}
