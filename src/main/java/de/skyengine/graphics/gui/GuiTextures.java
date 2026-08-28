package de.skyengine.graphics.gui;

import de.skyengine.core.file.FileHandle;
import de.skyengine.core.file.FileType;
import de.skyengine.graphics.texture.Texture;
import de.skyengine.graphics.texture.TextureFilter;

/**
 * Lädt und hält die (MC-kompatiblen) GUI-Texturen als einzelne {@link Texture} (NEAREST, ohne Mipmaps).
 * Pfade entsprechen dem Minecraft-Layout unter {@code game/textures/gui/...}.
 */
public final class GuiTextures {

    public Texture chestBackground;
    public Texture hopperBackground;
    public Texture dispenserBackground;
    public Texture inventoryBackground;
    public Texture craftingBackground;
    public Texture furnaceBackground;
    public Texture furnaceBurnProgress;
    public Texture furnaceLitProgress;
    public Texture slotFrame;
    public Texture hotbar;
    public Texture hotbarSelection;
    public Texture crosshair;

    /* Original Mekanism machine GUI sprites used by the energy system. */
    public Texture mekanismBase;
    public Texture mekanismConfiguration;
    public Texture mekanismEnergy;
    public Texture mekanismItems;
    public Texture mekanismHolderLeft;
    public Texture mekanismHolderRight;
    public Texture mekanismButton;
    public Texture mekanismInnerScreen;
    public Texture mekanismGaugeNormal;
    public Texture mekanismWideGauge;
    public Texture mekanismVerticalPower;
    public Texture mekanismSlot;
    public Texture mekanismSlotMinus;
    public Texture mekanismSlotPlus;
    public Texture mekanismAutoEject;
    public Texture mekanismClearSides;
    public Texture mekanismEnergyInfoTab;
    public Texture mekanismFlame;
    public Texture mekanismLiquidEnergy;

    /* Survival-HUD: Herzen + Hungerbalken (9×9-Einzelsprites, MC-1.20.5+-Layout) */
    public Texture heartContainer;
    public Texture heartFull;
    public Texture heartHalf;
    public Texture foodEmpty;
    public Texture foodFull;
    public Texture foodHalf;

    /* Widget-Sprites (9-Slice, Rand 3 px) */
    public Texture button;
    public Texture buttonHighlighted;
    public Texture buttonDisabled;
    public Texture slider;
    public Texture sliderHighlighted;
    public Texture sliderHandle;
    public Texture sliderHandleHighlighted;

    public Texture textField;
    public Texture textFieldHighlighted;

    /* Icons für quadratische Buttons (IconButton) — Motiv-Ausschnitt macht der IconButton. */
    public Texture iconImport;
    /** Lupe (12×12) als Icon des Such-Reiters — dieses Projekt hat kein Kompass-Item. */
    public Texture iconSearch;

    /* Creative-Inventar: die drei Fenster-Sheets (256×256, Fenster 195×136) ... */
    public Texture creativeTabItems;
    public Texture creativeTabSearch;
    public Texture creativeTabInventory;
    /* ... die Reiter-Sprites beider Reihen (26×32). MC liefert je sieben Varianten, aber die
       sieben "unselected" sind byte-identisch und von den "selected" unterscheiden sich nur die
       Randspalten 1 (links) und 7 (rechts) von der Mitte — deshalb nur vier Texturen je Reihe. */
    public Texture creativeTabTopUnselected;
    public Texture creativeTabTopSelectedLeft;
    public Texture creativeTabTopSelectedMid;
    public Texture creativeTabTopSelectedRight;
    public Texture creativeTabBottomUnselected;
    public Texture creativeTabBottomSelectedLeft;
    public Texture creativeTabBottomSelectedMid;
    public Texture creativeTabBottomSelectedRight;
    /* ... und der Scroller (12×15; die Schiene ist ins Fenster-Sheet gemalt). */
    public Texture creativeScroller;
    public Texture creativeScrollerDisabled;

    /* Gekachelter Hintergrund für Titel-/Ladebildschirm (32er-Kacheln) */
    public Texture menuBackground;
    /* Optionales Vollbild-Hintergrundbild fürs Hauptmenü (object-cover); null, wenn die
       Datei fehlt -> Kachel-Fallback. */
    public Texture menuBackgroundImage;
    /** Frei verwendbarer 16:9-Bildhintergrund fuer GuiScreens. */
    public Texture imageBackground;
    /** Vollbild-Vignette mit transparentem Zentrum. */
    public Texture vignette;
    /* Optionales Logo fürs Hauptmenü; null, wenn die Datei fehlt -> Text-Titel-Fallback. */
    public Texture logo;

    public void init() {
        this.chestBackground = load("game/textures/gui/container/generic_54.png");
        this.hopperBackground = load("game/textures/gui/container/hopper.png");
        this.dispenserBackground = load("game/textures/gui/container/dispenser.png");
        this.inventoryBackground = load("game/textures/gui/container/inventory.png");
        this.craftingBackground = load("game/textures/gui/container/crafting_table.png");
        this.furnaceBackground = load("game/textures/gui/container/furnace.png");
        this.furnaceBurnProgress = load("game/textures/gui/sprites/container/furnace/burn_progress.png");
        this.furnaceLitProgress = load("game/textures/gui/sprites/container/furnace/lit_progress.png");
        this.slotFrame = load("game/textures/gui/sprites/widget/slot_frame.png");
        this.hotbar = load("game/textures/gui/sprites/hud/hotbar.png");
        this.hotbarSelection = load("game/textures/gui/sprites/hud/hotbar_selection.png");
        this.crosshair = load("game/textures/gui/sprites/hud/crosshair.png");

        String mekanism = "game/textures/gui/mekanism/";
        this.mekanismBase = load(mekanism + "base.png");
        this.mekanismConfiguration = load(mekanism + "configuration.png");
        this.mekanismEnergy = load(mekanism + "energy.png");
        this.mekanismItems = load(mekanism + "items.png");
        this.mekanismHolderLeft = load(mekanism + "holder_left.png");
        this.mekanismHolderRight = load(mekanism + "holder_right.png");
        this.mekanismButton = load(mekanism + "button.png");
        this.mekanismInnerScreen = load(mekanism + "inner_screen.png");
        this.mekanismGaugeNormal = load(mekanism + "gauge/normal.png");
        this.mekanismWideGauge = load(mekanism + "gauge/wide.png");
        this.mekanismVerticalPower = load(mekanism + "bar/vertical_power.png");
        this.mekanismSlot = load(mekanism + "slot/normal.png");
        this.mekanismSlotMinus = load(mekanism + "slot/overlay_minus.png");
        this.mekanismSlotPlus = load(mekanism + "slot/overlay_plus.png");
        this.mekanismAutoEject = load(mekanism + "button/auto_eject.png");
        this.mekanismClearSides = load(mekanism + "button/clear_sides.png");
        this.mekanismEnergyInfoTab = load(mekanism + "tabs/energy_info.png");
        this.mekanismFlame = load(mekanism + "progress/flame.png");
        this.mekanismLiquidEnergy = load("game/textures/liquid/mekanism/energy.png");

        this.heartContainer = load("game/textures/gui/sprites/hud/heart/container.png");
        this.heartFull = load("game/textures/gui/sprites/hud/heart/full.png");
        this.heartHalf = load("game/textures/gui/sprites/hud/heart/half.png");
        this.foodEmpty = load("game/textures/gui/sprites/hud/food_empty.png");
        this.foodFull = load("game/textures/gui/sprites/hud/food_full.png");
        this.foodHalf = load("game/textures/gui/sprites/hud/food_half.png");

        this.button = load("game/textures/gui/sprites/widget/button.png");
        this.buttonHighlighted = load("game/textures/gui/sprites/widget/button_highlighted.png");
        this.buttonDisabled = load("game/textures/gui/sprites/widget/button_disabled.png");
        this.slider = load("game/textures/gui/sprites/widget/slider.png");
        this.sliderHighlighted = load("game/textures/gui/sprites/widget/slider_highlighted.png");
        this.sliderHandle = load("game/textures/gui/sprites/widget/slider_handle.png");
        this.sliderHandleHighlighted = load("game/textures/gui/sprites/widget/slider_handle_highlighted.png");
        this.textField = load("game/textures/gui/sprites/widget/text_field.png");
        this.textFieldHighlighted = load("game/textures/gui/sprites/widget/text_field_highlighted.png");
        this.menuBackground = load("game/textures/gui/menu_background.png");

        /* Globus (40×20); der Globus-Kern liegt bei x12..27, y2..17 — Zuschnitt im IconButton. */
        this.iconImport = load("game/textures/gui/sprites/icon/new_realm.png");
        this.iconSearch = load("game/textures/gui/sprites/icon/search.png");

        String creative = "game/textures/gui/container/creative_inventory/";
        this.creativeTabItems = load(creative + "tab_items.png");
        this.creativeTabSearch = load(creative + "tab_item_search.png");
        this.creativeTabInventory = load(creative + "tab_inventory.png");

        String tabs = "game/textures/gui/sprites/container/creative_inventory/";
        this.creativeTabTopUnselected = load(tabs + "tab_top_unselected_1.png");
        this.creativeTabTopSelectedLeft = load(tabs + "tab_top_selected_1.png");
        this.creativeTabTopSelectedMid = load(tabs + "tab_top_selected_2.png");
        this.creativeTabTopSelectedRight = load(tabs + "tab_top_selected_7.png");
        this.creativeTabBottomUnselected = load(tabs + "tab_bottom_unselected_1.png");
        this.creativeTabBottomSelectedLeft = load(tabs + "tab_bottom_selected_1.png");
        this.creativeTabBottomSelectedMid = load(tabs + "tab_bottom_selected_2.png");
        this.creativeTabBottomSelectedRight = load(tabs + "tab_bottom_selected_7.png");
        this.creativeScroller = load(tabs + "scroller.png");
        this.creativeScrollerDisabled = load(tabs + "scroller_disabled.png");

        /* Fehlertolerant: Bild + Logo sind optionale User-Assets. Mipmaps + trilinear,
           weil beide beim Zeichnen stark herunterskaliert werden. */
        this.menuBackgroundImage = loadOptional("game/textures/menu/main_menu_v0.0.7.png");
        this.logo = loadOptional("game/textures/ui/logo.png");

        this.imageBackground = loadHighResolution("game/textures/ui/background-2x.png");
        this.vignette = loadHighResolution("game/textures/ui/vignette.png");
    }

    /** Laedt eine hochaufloesende, beim Zeichnen typischerweise verkleinerte UI-Textur. */
    private static Texture loadHighResolution(String path) {
        Texture texture = new Texture(new FileHandle(path, FileType.RESOURCE), true);
        texture.setFilter(TextureFilter.MIPMAP, TextureFilter.LINEAR);
        return texture;
    }

    /** Lädt eine optionale hochauflösende Textur (null, wenn die Datei fehlt). */
    private static Texture loadOptional(String path) {
        FileHandle handle = new FileHandle(path, FileType.RESOURCE);
        if (!handle.exists()) return null;
        Texture texture = new Texture(handle, true);
        texture.setFilter(TextureFilter.MIPMAP, TextureFilter.LINEAR);
        return texture;
    }

    private static Texture load(String path) {
        return new Texture(new FileHandle(path, FileType.RESOURCE), false);
    }

    public void dispose() {
        if (this.chestBackground != null) this.chestBackground.dispose();
        if (this.hopperBackground != null) this.hopperBackground.dispose();
        if (this.dispenserBackground != null) this.dispenserBackground.dispose();
        if (this.inventoryBackground != null) this.inventoryBackground.dispose();
        if (this.craftingBackground != null) this.craftingBackground.dispose();
        if (this.furnaceBackground != null) this.furnaceBackground.dispose();
        if (this.furnaceBurnProgress != null) this.furnaceBurnProgress.dispose();
        if (this.furnaceLitProgress != null) this.furnaceLitProgress.dispose();
        if (this.slotFrame != null) this.slotFrame.dispose();
        if (this.hotbar != null) this.hotbar.dispose();
        if (this.hotbarSelection != null) this.hotbarSelection.dispose();
        if (this.crosshair != null) this.crosshair.dispose();
        if (this.mekanismBase != null) this.mekanismBase.dispose();
        if (this.mekanismConfiguration != null) this.mekanismConfiguration.dispose();
        if (this.mekanismEnergy != null) this.mekanismEnergy.dispose();
        if (this.mekanismItems != null) this.mekanismItems.dispose();
        if (this.mekanismHolderLeft != null) this.mekanismHolderLeft.dispose();
        if (this.mekanismHolderRight != null) this.mekanismHolderRight.dispose();
        if (this.mekanismButton != null) this.mekanismButton.dispose();
        if (this.mekanismInnerScreen != null) this.mekanismInnerScreen.dispose();
        if (this.mekanismGaugeNormal != null) this.mekanismGaugeNormal.dispose();
        if (this.mekanismWideGauge != null) this.mekanismWideGauge.dispose();
        if (this.mekanismVerticalPower != null) this.mekanismVerticalPower.dispose();
        if (this.mekanismSlot != null) this.mekanismSlot.dispose();
        if (this.mekanismSlotMinus != null) this.mekanismSlotMinus.dispose();
        if (this.mekanismSlotPlus != null) this.mekanismSlotPlus.dispose();
        if (this.mekanismAutoEject != null) this.mekanismAutoEject.dispose();
        if (this.mekanismClearSides != null) this.mekanismClearSides.dispose();
        if (this.mekanismEnergyInfoTab != null) this.mekanismEnergyInfoTab.dispose();
        if (this.mekanismFlame != null) this.mekanismFlame.dispose();
        if (this.mekanismLiquidEnergy != null) this.mekanismLiquidEnergy.dispose();
        if (this.heartContainer != null) this.heartContainer.dispose();
        if (this.heartFull != null) this.heartFull.dispose();
        if (this.heartHalf != null) this.heartHalf.dispose();
        if (this.foodEmpty != null) this.foodEmpty.dispose();
        if (this.foodFull != null) this.foodFull.dispose();
        if (this.foodHalf != null) this.foodHalf.dispose();
        if (this.button != null) this.button.dispose();
        if (this.buttonHighlighted != null) this.buttonHighlighted.dispose();
        if (this.buttonDisabled != null) this.buttonDisabled.dispose();
        if (this.slider != null) this.slider.dispose();
        if (this.sliderHighlighted != null) this.sliderHighlighted.dispose();
        if (this.sliderHandle != null) this.sliderHandle.dispose();
        if (this.sliderHandleHighlighted != null) this.sliderHandleHighlighted.dispose();
        if (this.textField != null) this.textField.dispose();
        if (this.textFieldHighlighted != null) this.textFieldHighlighted.dispose();
        if (this.iconImport != null) this.iconImport.dispose();
        if (this.iconSearch != null) this.iconSearch.dispose();
        if (this.creativeTabItems != null) this.creativeTabItems.dispose();
        if (this.creativeTabSearch != null) this.creativeTabSearch.dispose();
        if (this.creativeTabInventory != null) this.creativeTabInventory.dispose();
        if (this.creativeTabTopUnselected != null) this.creativeTabTopUnselected.dispose();
        if (this.creativeTabTopSelectedLeft != null) this.creativeTabTopSelectedLeft.dispose();
        if (this.creativeTabTopSelectedMid != null) this.creativeTabTopSelectedMid.dispose();
        if (this.creativeTabTopSelectedRight != null) this.creativeTabTopSelectedRight.dispose();
        if (this.creativeTabBottomUnselected != null) this.creativeTabBottomUnselected.dispose();
        if (this.creativeTabBottomSelectedLeft != null) this.creativeTabBottomSelectedLeft.dispose();
        if (this.creativeTabBottomSelectedMid != null) this.creativeTabBottomSelectedMid.dispose();
        if (this.creativeTabBottomSelectedRight != null) this.creativeTabBottomSelectedRight.dispose();
        if (this.creativeScroller != null) this.creativeScroller.dispose();
        if (this.creativeScrollerDisabled != null) this.creativeScrollerDisabled.dispose();
        if (this.menuBackground != null) this.menuBackground.dispose();
        if (this.menuBackgroundImage != null) this.menuBackgroundImage.dispose();
        if (this.imageBackground != null) this.imageBackground.dispose();
        if (this.vignette != null) this.vignette.dispose();
        if (this.logo != null) this.logo.dispose();
    }
}
