package de.skyengine.graphics.gui.screens;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.i18n.I18n;
import de.skyengine.game.world.block.entity.EnergyCubeBlockEntity;
import de.skyengine.game.world.block.entity.EnergySideMode;
import de.skyengine.game.world.block.entity.EnergyStorage;
import de.skyengine.game.world.block.entity.ItemStorage;
import de.skyengine.game.world.block.entity.RelativeSide;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.graphics.color.Colors;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiText;
import de.skyengine.graphics.gui.EnergyText;
import de.skyengine.graphics.gui.Slot;
import de.skyengine.graphics.gui.SlotGroup;
import de.skyengine.graphics.gui.SpriteRenderer;
import de.skyengine.graphics.gui.Tooltip;
import de.skyengine.graphics.gui.text.RichText;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** Mekanism-style energy cube container with the original gauge and side configuration layout. */
public final class GuiEnergyCube extends GuiContainer {
    private static final int W = 176, H = 166, CONFIG_W = 156, CONFIG_H = 135;
    private static final int GAUGE_X = 55, GAUGE_Y = 18, GAUGE_W = 66, GAUGE_H = 50;
    private static final int CONFIG_TAB_X = -26, CONFIG_TAB_Y = 6, CONFIG_TAB_W = 26, CONFIG_TAB_H = 26;
    private static final RelativeSide[] SIDE_LAYOUT = {RelativeSide.TOP, RelativeSide.LEFT,
            RelativeSide.FRONT, RelativeSide.RIGHT, RelativeSide.BACK, RelativeSide.BOTTOM};
    private static final int[][] SIDE_POS = {{67, 46}, {44, 69}, {67, 69}, {90, 69}, {44, 92}, {67, 92}};

    private final EnergyCubeBlockEntity cube;
    private final ItemStorage machine, player;
    private float x, y, configX;
    private boolean configurationOpen;

    public GuiEnergyCube(EnergyCubeBlockEntity cube, ItemStorage player) {
        super(player, cube.getInventory());
        this.cube = cube;
        this.machine = cube.getInventory();
        this.player = player;
    }

    @Override
    public void init(GuiManager gui, float vW, float vH) {
        float left = this.configurationOpen ? CONFIG_W + 4 : 0;
        this.x = Math.max(left, (vW - W - left) / 2F + left);
        this.y = (vH - H) / 2F;
        this.configX = this.x - CONFIG_W - 4;
        int gx = Math.round(this.x), gy = Math.round(this.y);
        this.slots.clear();
        this.slots.add(new Slot(this.machine, 0, gx + 15, gy + 35, SlotGroup.MACHINE_INPUT, GuiEnergyCube::canDischarge));
        this.slots.add(new Slot(this.machine, 1, gx + 145, gy + 35, SlotGroup.MACHINE_OUTPUT, GuiEnergyCube::canCharge));
        for (int row = 0; row < 3; row++) for (int col = 0; col < COLS; col++)
            this.slots.add(new Slot(this.player, COLS + row * COLS + col,
                    gx + 8 + col * STEP, gy + 84 + row * STEP, SlotGroup.INVENTORY));
        for (int col = 0; col < COLS; col++) this.slots.add(new Slot(this.player, col,
                gx + 8 + col * STEP, gy + 142, SlotGroup.HOTBAR));
    }

    private static boolean canDischarge(ItemStack stack) {
        EnergyStorage energy = EnergyCubeBlockEntity.itemEnergy(stack);
        return energy != null && energy.canExtract();
    }

    private static boolean canCharge(ItemStack stack) {
        EnergyStorage energy = EnergyCubeBlockEntity.itemEnergy(stack);
        return energy != null && energy.canReceive();
    }

    @Override
    protected List<Slot> quickMoveTargets(SlotGroup from) {
        if (from == SlotGroup.MACHINE_INPUT || from == SlotGroup.MACHINE_OUTPUT) {
            List<Slot> result = new ArrayList<>(this.slotsOf(SlotGroup.HOTBAR));
            result.addAll(this.slotsOf(SlotGroup.INVENTORY));
            return result;
        }
        List<Slot> result = new ArrayList<>();
        result.addAll(this.slotsOf(SlotGroup.MACHINE_INPUT));
        result.addAll(this.slotsOf(SlotGroup.MACHINE_OUTPUT));
        result.addAll(this.slotsOf(from == SlotGroup.HOTBAR ? SlotGroup.INVENTORY : SlotGroup.HOTBAR));
        return result;
    }

    @Override
    protected boolean isInsideWindow(double mx, double my) {
        return inside(mx, my, this.x + CONFIG_TAB_X, this.y + CONFIG_TAB_Y, CONFIG_TAB_W, CONFIG_TAB_H)
                || inside(mx, my, this.x, this.y, W, H)
                || this.configurationOpen && inside(mx, my, this.configX, this.y, CONFIG_W, CONFIG_H);
    }

    @Override
    public void render(GuiManager gui, double mouseX, double mouseY) {
        SpriteRenderer sr = gui.sprites();
        sr.begin(gui.vWidth(), gui.vHeight());
        this.renderBackground(gui);
        if (this.configurationOpen) this.drawConfiguration(gui, sr, mouseX, mouseY);
        sr.drawNineSlice(gui.textures().mekanismBase, this.x, this.y, W, H, 4);
        this.drawConfigurationTab(gui, sr, mouseX, mouseY);
        sr.drawSprite(gui.textures().mekanismEnergyInfoTab, this.x + W - 2, this.y + 5, 26, 26);
        sr.drawSprite(gui.textures().mekanismEnergy, this.x + W + 2, this.y + 9, 18, 18);
        for (Slot slot : this.slots) sr.drawSprite(gui.textures().mekanismSlot, slot.x - 1, slot.y - 1, 18, 18);
        sr.drawSprite(gui.textures().mekanismSlotMinus, this.x + 15, this.y + 35, 16, 16);
        sr.drawSprite(gui.textures().mekanismSlotPlus, this.x + 145, this.y + 35, 16, 16);
        this.drawEnergyGauge(gui, sr);
        this.drawSlotHover(gui, mouseX, mouseY);
        sr.end();
        this.drawSlotIcons(gui, mouseX, mouseY);

        gui.font().begin(gui.vWidth(), gui.vHeight());
        drawCentered(gui, I18n.tr("gui.energy_cube.title"), this.x, W, this.y + 7, GuiText.NORMAL);
        gui.font().drawString(I18n.tr("gui.energy_cube.inventory"), this.x + 8, this.y + 72, GuiText.SMALL, Colors.DARK_GRAY);
        if (this.configurationOpen) {
            drawCentered(gui, I18n.tr("gui.energy_cube.energy_config"), this.configX, CONFIG_W, this.y + 8, GuiText.NORMAL);
            drawCentered(gui, I18n.tr("gui.energy_cube.eject_status",
                    I18n.tr(this.cube.isAutoEject() ? "gui.on" : "gui.off")),
                    this.configX + 38, 80, this.y + 27, GuiText.SMALL, Colors.GREEN);
            drawCentered(gui, I18n.tr("gui.energy_cube.slots"), this.configX, CONFIG_W, this.y + 116, GuiText.NORMAL);
        }
        gui.font().end();

        this.drawTooltip(gui, mouseX, mouseY);
        if (this.slotAt(mouseX, mouseY) == null && (inside(mouseX, mouseY,
                this.x + GAUGE_X, this.y + GAUGE_Y, GAUGE_W, GAUGE_H)
                || inside(mouseX, mouseY, this.x + W - 2, this.y + 5, 26, 26))) {
            Tooltip.draw(gui, List.of(
                    RichText.parse(EnergyText.format(this.cube.getEnergy()) + " / " + EnergyText.format(this.cube.getCapacity())),
                    RichText.parse(I18n.tr("gui.energy_cube.input", EnergyText.format(EnergyCubeBlockEntity.TRANSFER_RATE))),
                    RichText.parse(I18n.tr("gui.energy_cube.output", EnergyText.format(EnergyCubeBlockEntity.TRANSFER_RATE))),
                    RichText.parse(I18n.tr("gui.energy_cube.unit"))), mouseX, mouseY);
        } else if (this.configurationOpen) {
            this.drawConfigurationTooltip(gui, mouseX, mouseY);
        }
    }

    private void drawConfigurationTab(GuiManager gui, SpriteRenderer sr, double mouseX, double mouseY) {
        sr.drawSprite(gui.textures().mekanismHolderLeft, this.x + CONFIG_TAB_X, this.y + CONFIG_TAB_Y,
                CONFIG_TAB_W, CONFIG_TAB_H, 0, 0, 1, 1, .18F, .68F, .42F, 1);
        boolean hover = inside(mouseX, mouseY, this.x - 21, this.y + 10, 18, 18);
        drawButton(sr, gui, this.x - 21, this.y + 10, 18, 18, hover, 1, 1, 1);
        sr.drawSprite(gui.textures().mekanismConfiguration, this.x - 21, this.y + 10, 18, 18);
    }

    private void drawEnergyGauge(GuiManager gui, SpriteRenderer sr) {
        float gx = this.x + GAUGE_X, gy = this.y + GAUGE_Y;
        sr.drawNineSlice(gui.textures().mekanismGaugeNormal, gx, gy, GAUGE_W, GAUGE_H, 2);
        int fill = this.cube.getEnergy() <= 0 ? 0 : Math.max(1,
                Math.round(48 * this.cube.getEnergy() / (float) this.cube.getCapacity()));
        int frame = (int) ((System.nanoTime() / 100_000_000L) % 32);
        for (int ox = 0; ox < 64; ox += 16) {
            int drawn = 0;
            while (drawn < fill) {
                int tileHeight = Math.min(16, fill - drawn);
                float sourceTop = (frame * 16 + 16 - tileHeight) / 512F;
                float sourceBottom = (frame * 16 + 16) / 512F;
                sr.drawSprite(gui.textures().mekanismLiquidEnergy,
                        gx + 1 + ox, gy + 1 + 48 - drawn - tileHeight, 16, tileHeight,
                        0, sourceTop, 1, sourceBottom);
                drawn += tileHeight;
            }
        }
        sr.drawSprite(gui.textures().mekanismWideGauge, gx + 1, gy + 1, 64, 48);
    }

    private void drawConfiguration(GuiManager gui, SpriteRenderer sr, double mouseX, double mouseY) {
        sr.drawNineSlice(gui.textures().mekanismBase, this.configX, this.y, CONFIG_W, CONFIG_H, 4);
        sr.drawNineSlice(gui.textures().mekanismInnerScreen,
                this.configX + 38, this.y + 25, 80, 12, 4);
        for (int i = 0; i < SIDE_LAYOUT.length; i++) {
            float bx = this.configX + SIDE_POS[i][0], by = this.y + SIDE_POS[i][1];
            EnergySideMode mode = this.cube.getSideMode(SIDE_LAYOUT[i]);
            float[] tint = buttonTint(mode);
            drawButton(sr, gui, bx, by, 22, 22, inside(mouseX, mouseY, bx, by, 22, 22),
                    tint[0], tint[1], tint[2]);
        }
        float ejectX = this.configX + 136, ejectY = this.y + 6;
        drawButton(sr, gui, ejectX, ejectY, 14, 14,
                inside(mouseX, mouseY, ejectX, ejectY, 14, 14),
                this.cube.isAutoEject() ? .25F : 1, this.cube.isAutoEject() ? .82F : 1,
                this.cube.isAutoEject() ? .48F : 1);
        sr.drawSprite(gui.textures().mekanismAutoEject, ejectX, ejectY, 14, 14);
        float clearX = this.configX + 136, clearY = this.y + 95;
        drawButton(sr, gui, clearX, clearY, 14, 14,
                inside(mouseX, mouseY, clearX, clearY, 14, 14), 1, 1, 1);
        sr.drawSprite(gui.textures().mekanismClearSides, clearX, clearY, 14, 14);
    }

    private static void drawButton(SpriteRenderer sr, GuiManager gui, float x, float y, float w, float h,
                                   boolean hovered, float red, float green, float blue) {
        sr.drawNineSlice(gui.textures().mekanismButton, x, y, w, h,
                0, hovered ? 40 : 20, 200, 20, 3, red, green, blue, 1);
    }

    private static float[] buttonTint(EnergySideMode mode) {
        return switch (mode) {
            case INPUT -> new float[]{.72F, .12F, .18F};
            case OUTPUT -> new float[]{.16F, .30F, .68F};
            case DISABLED -> new float[]{.72F, .72F, .72F};
        };
    }

    private void drawConfigurationTooltip(GuiManager gui, double mouseX, double mouseY) {
        for (int i = 0; i < SIDE_LAYOUT.length; i++) {
            float bx = this.configX + SIDE_POS[i][0], by = this.y + SIDE_POS[i][1];
            if (!inside(mouseX, mouseY, bx, by, 22, 22)) continue;
            RelativeSide side = SIDE_LAYOUT[i];
            String sideName = I18n.tr("gui.energy.side." + side.name().toLowerCase());
            String modeName = I18n.tr("gui.energy.mode." + this.cube.getSideMode(side).name().toLowerCase());
            Tooltip.draw(gui, List.of(RichText.parse(sideName + ": " + modeName)), mouseX, mouseY);
            return;
        }
        if (inside(mouseX, mouseY, this.configX + 136, this.y + 6, 14, 14)) {
            Tooltip.draw(gui, List.of(RichText.parse(I18n.tr("gui.energy_cube.auto_eject"))), mouseX, mouseY);
        } else if (inside(mouseX, mouseY, this.configX + 136, this.y + 95, 14, 14)) {
            Tooltip.draw(gui, List.of(RichText.parse(I18n.tr("gui.energy_cube.clear_sides"))), mouseX, mouseY);
        }
    }

    private static void drawCentered(GuiManager gui, String text, float left, float width, float y, float size) {
        drawCentered(gui, text, left, width, y, size, Colors.DARK_GRAY);
    }

    private static void drawCentered(GuiManager gui, String text, float left, float width, float y, float size,
                                     de.skyengine.graphics.color.Color4 color) {
        float textWidth = gui.font().getStringWidth(text, size);
        gui.font().drawString(text, left + (width - textWidth) / 2F, y, size, color);
    }

    @Override
    public boolean mousePressed(GuiManager gui, double mouseX, double mouseY, int button) {
        if (inside(mouseX, mouseY, this.x + CONFIG_TAB_X, this.y + CONFIG_TAB_Y, CONFIG_TAB_W, CONFIG_TAB_H)) {
            this.configurationOpen = !this.configurationOpen;
            this.init(gui, gui.vWidth(), gui.vHeight());
            gui.sound().playUiClick();
            return true;
        }
        if (this.configurationOpen) {
            for (int i = 0; i < SIDE_LAYOUT.length; i++) if (inside(mouseX, mouseY,
                    this.configX + SIDE_POS[i][0], this.y + SIDE_POS[i][1], 22, 22)) {
                this.cube.cycleSideMode(SIDE_LAYOUT[i], button == GLFW.GLFW_MOUSE_BUTTON_RIGHT);
                gui.sound().playUiClick();
                return true;
            }
            if (inside(mouseX, mouseY, this.configX + 136, this.y + 6, 14, 14)) {
                this.cube.setAutoEject(!this.cube.isAutoEject());
                gui.sound().playUiClick();
                return true;
            }
            if (inside(mouseX, mouseY, this.configX + 136, this.y + 95, 14, 14)) {
                EnergySideMode mode = EnergySideMode.DISABLED;
                if (!SkyEngine.get().getInput().isShiftDown()) {
                    mode = this.commonMode();
                    mode = button == GLFW.GLFW_MOUSE_BUTTON_RIGHT ? mode.previous() : mode.next();
                }
                this.cube.setAllSideModes(mode);
                gui.sound().playUiClick();
                return true;
            }
        }
        return super.mousePressed(gui, mouseX, mouseY, button);
    }

    private EnergySideMode commonMode() {
        EnergySideMode first = this.cube.getSideMode(RelativeSide.FRONT);
        for (RelativeSide side : RelativeSide.values())
            if (this.cube.getSideMode(side) != first) return EnergySideMode.DISABLED;
        return first;
    }

    private static boolean inside(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
