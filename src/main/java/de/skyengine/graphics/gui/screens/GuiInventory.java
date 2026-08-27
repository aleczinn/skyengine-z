package de.skyengine.graphics.gui.screens;

import de.skyengine.game.world.block.entity.ItemStorage;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.recipe.CraftingMenu;
import de.skyengine.game.world.recipe.RecipeManager;
import de.skyengine.core.SkyEngine;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.Slot;
import de.skyengine.graphics.gui.SlotGroup;
import de.skyengine.graphics.gui.SpriteRenderer;
import de.skyengine.graphics.player.HeldItemMeshes;
import de.skyengine.graphics.player.PlayerRenderer;
import de.skyengine.graphics.texture.Texture;

import java.util.function.Supplier;

/**
 * Spielerinventar (Taste E) mit der MC-Textur {@code container/inventory.png} (176×166):
 * 27 Haupt-Slots + 9 Hotbar-Slots im Standardlayout. Der Crafting-/Rüstungs-Bereich der
 * Textur bleibt vorerst funktionslos (Phase 2).
 */
public final class GuiInventory extends GuiContainer {

    private static final int W = 176, H = 166;
    private static final float TEX = 256f;

    private final ItemStorage playerInv;
    private final PlayerRenderer playerRenderer;
    private final HeldItemMeshes heldItemMeshes;
    private final Supplier<ItemStack> heldItem;   // ausgewählter Hotbar-Slot (fürs Modell in der Hand)
    private float guiX, guiY;
    private final CraftingMenu crafting;

    public GuiInventory(ItemStorage playerInv, PlayerRenderer playerRenderer,
                        HeldItemMeshes heldItemMeshes, Supplier<ItemStack> heldItem) {
        super(playerInv);
        this.playerInv = playerInv;
        this.playerRenderer = playerRenderer;
        this.heldItemMeshes = heldItemMeshes;
        this.heldItem = heldItem;
        this.crafting = new CraftingMenu(2, 2, RecipeManager.CRAFTING, playerInv,
                stack -> SkyEngine.get().getGame().dropFromGui(stack));
    }

    @Override
    public void init(GuiManager gui, float vW, float vH) {
        this.guiX = (vW - W) / 2f;
        this.guiY = (vH - H) / 2f;
        int gx = Math.round(this.guiX), gy = Math.round(this.guiY);

        this.slots.clear();
        for (int r = 0; r < 2; r++) for (int c = 0; c < 2; c++) {
            this.slots.add(new Slot(this.crafting.input(), r * 2 + c,
                    gx + 98 + c * STEP, gy + 18 + r * STEP, SlotGroup.CRAFT_INPUT));
        }
        this.slots.add(new Slot(this.crafting.output(), 0, gx + 154, gy + 28,
                SlotGroup.CRAFT_RESULT, stack -> false));
        /* Hauptinventar (Indizes 9..35). */
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < COLS; c++)
                this.slots.add(new Slot(this.playerInv, COLS + r * COLS + c, gx + 8 + c * STEP,
                        gy + 84 + r * STEP, SlotGroup.INVENTORY));
        /* Hotbar (Indizes 0..8). */
        for (int c = 0; c < COLS; c++)
            this.slots.add(new Slot(this.playerInv, c, gx + 8 + c * STEP, gy + 142, SlotGroup.HOTBAR));
    }

    @Override
    protected int quickMove(Slot from, int amount) {
        if (from.group == SlotGroup.CRAFT_RESULT) return this.crafting.craftAll();
        return super.quickMove(from, amount);
    }

    @Override
    protected java.util.List<Slot> quickMoveTargets(SlotGroup from) {
        if (from == SlotGroup.CRAFT_INPUT) {
            java.util.List<Slot> targets = new java.util.ArrayList<>(this.slotsOf(SlotGroup.HOTBAR));
            targets.addAll(this.slotsOf(SlotGroup.INVENTORY));
            return targets;
        }
        if (from == SlotGroup.CRAFT_RESULT) return java.util.List.of();
        return this.slotsOf(from == SlotGroup.HOTBAR ? SlotGroup.INVENTORY : SlotGroup.HOTBAR);
    }

    @Override
    protected boolean isInsideWindow(double mx, double my) {
        return mx >= this.guiX && mx < this.guiX + W && my >= this.guiY && my < this.guiY + H;
    }

    @Override
    public void render(GuiManager gui, double mouseX, double mouseY) {
        float vW = gui.vWidth(), vH = gui.vHeight();
        SpriteRenderer sr = gui.sprites();
        Texture bg = gui.textures().inventoryBackground;

        sr.begin(vW, vH);
        this.renderBackground(gui);
        sr.drawSprite(bg, this.guiX, this.guiY, W, H, 0, 0, W / TEX, H / TEX);
        this.drawSlotHover(gui, mouseX, mouseY);
        sr.end();

        /* Spieler-Vorschau im freien Bereich oben links (folgt der Maus, MC-Stil). */
        this.playerRenderer.renderPreview(this.guiX + 51.5f, this.guiY + 75, 30,
                mouseX, mouseY, vW, vH, this.heldItemMeshes, this.heldItem.get());

        this.drawSlotIcons(gui, mouseX, mouseY);
        this.drawTooltip(gui, mouseX, mouseY);
    }

    @Override
    public void onClose() {
        this.crafting.close();
        super.onClose();
    }
}
