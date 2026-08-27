package de.skyengine.graphics.gui.screens;

import de.skyengine.core.SkyEngine;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.entity.ItemStorage;
import de.skyengine.game.world.recipe.CraftingMenu;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.Slot;
import de.skyengine.graphics.gui.SlotGroup;
import de.skyengine.graphics.gui.SpriteRenderer;

import java.util.ArrayList;
import java.util.List;

/** Gemeinsamer Screen fuer 3x3 sowie datengetriebene Crafting-Raster bis 9x9. */
public final class GuiCraftingStation extends GuiContainer {

    private final int gridWidth;
    private final int gridHeight;
    private final ItemStorage playerInv;
    private final CraftingMenu crafting;
    private int width, height;
    private float guiX, guiY;

    public GuiCraftingStation(int gridWidth, int gridHeight, Identifier recipeType, ItemStorage playerInv) {
        super(playerInv);
        this.gridWidth = gridWidth;
        this.gridHeight = gridHeight;
        this.playerInv = playerInv;
        this.crafting = new CraftingMenu(gridWidth, gridHeight, recipeType, playerInv,
                stack -> SkyEngine.get().getGame().dropFromGui(stack));
    }

    @Override
    public void init(GuiManager gui, float vW, float vH) {
        boolean vanilla = this.gridWidth == 3 && this.gridHeight == 3;
        boolean sideLayout = this.gridWidth > 5 || this.gridHeight > 5;
        this.width = vanilla ? 176 : sideLayout ? this.gridWidth * STEP + 238 : 176;
        this.height = vanilla ? 166 : sideLayout ? Math.max(198, this.gridHeight * STEP + 36)
                : Math.max(166, this.gridHeight * STEP + 108);
        this.guiX = (vW - this.width) / 2F;
        this.guiY = (vH - this.height) / 2F;
        int gx = Math.round(this.guiX), gy = Math.round(this.guiY);
        this.slots.clear();

        int gridX = vanilla ? 30 : 10;
        int gridY = vanilla ? 17 : 10;
        for (int row = 0; row < this.gridHeight; row++) for (int col = 0; col < this.gridWidth; col++) {
            this.slots.add(new Slot(this.crafting.input(), row * this.gridWidth + col,
                    gx + gridX + col * STEP, gy + gridY + row * STEP, SlotGroup.CRAFT_INPUT));
        }
        int resultX = vanilla ? 124 : gridX + this.gridWidth * STEP + 18;
        int resultY = vanilla ? 35 : gridY + Math.max(0, (this.gridHeight * STEP - SLOT) / 2);
        this.slots.add(new Slot(this.crafting.output(), 0, gx + resultX, gy + resultY,
                SlotGroup.CRAFT_RESULT, stack -> false));

        int playerX = vanilla ? 8 : sideLayout ? resultX + 48 : 8;
        int playerY = vanilla ? 84 : sideLayout ? 40 : gridY + this.gridHeight * STEP + 14;
        for (int row = 0; row < 3; row++) for (int col = 0; col < COLS; col++) {
            this.slots.add(new Slot(this.playerInv, COLS + row * COLS + col,
                    gx + playerX + col * STEP, gy + playerY + row * STEP, SlotGroup.INVENTORY));
        }
        for (int col = 0; col < COLS; col++) {
            this.slots.add(new Slot(this.playerInv, col, gx + playerX + col * STEP,
                    gy + playerY + 58, SlotGroup.HOTBAR));
        }
    }

    @Override protected boolean isInsideWindow(double mx, double my) {
        return mx >= this.guiX && mx < this.guiX + this.width && my >= this.guiY && my < this.guiY + this.height;
    }

    @Override
    protected int quickMove(Slot from, int amount) {
        if (from.group == SlotGroup.CRAFT_RESULT) return this.crafting.craftAll();
        return super.quickMove(from, amount);
    }

    @Override
    protected List<Slot> quickMoveTargets(SlotGroup from) {
        if (from == SlotGroup.CRAFT_INPUT) {
            List<Slot> targets = new ArrayList<>(this.slotsOf(SlotGroup.HOTBAR));
            targets.addAll(this.slotsOf(SlotGroup.INVENTORY));
            return targets;
        }
        if (from == SlotGroup.CRAFT_RESULT) return List.of();
        return this.slotsOf(from == SlotGroup.HOTBAR ? SlotGroup.INVENTORY : SlotGroup.HOTBAR);
    }

    @Override
    public void render(GuiManager gui, double mouseX, double mouseY) {
        SpriteRenderer sprites = gui.sprites();
        sprites.begin(gui.vWidth(), gui.vHeight());
        this.renderBackground(gui);
        if (this.gridWidth == 3 && this.gridHeight == 3) {
            sprites.drawSprite(gui.textures().craftingBackground, this.guiX, this.guiY, 176, 166,
                    0, 0, 176 / 256F, 166 / 256F);
        } else {
            sprites.drawRect(this.guiX, this.guiY, this.width, this.height, 0.78F, 0.78F, 0.78F, 1F);
            for (Slot slot : this.slots) {
                sprites.drawSprite(gui.textures().slotFrame, slot.x - 1, slot.y - 1, 18, 18);
            }
        }
        this.drawSlotHover(gui, mouseX, mouseY);
        sprites.end();
        this.drawSlotIcons(gui, mouseX, mouseY);
        this.drawTooltip(gui, mouseX, mouseY);
    }

    @Override public void onClose() {
        this.crafting.close();
        super.onClose();
    }
}
