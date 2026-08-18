package de.skyengine.graphics.gui.screens;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.file.GameDirectory;
import de.skyengine.core.i18n.I18n;
import de.skyengine.core.resource.ResourcePack;
import de.skyengine.core.resource.Resources;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.graphics.color.Color4;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.GuiText;
import de.skyengine.graphics.gui.layout.HStack;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.Button;
import de.skyengine.graphics.gui.widget.GuiComponent;
import de.skyengine.graphics.gui.widget.Label;
import de.skyengine.graphics.gui.widget.Spacer;
import de.skyengine.graphics.texture.Texture;
import de.skyengine.utils.logging.LogManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Auswahl und Priorisierung der Ressourcenpakete. */
public final class GuiResourcePacks extends GuiOptionsScreen {
    private static final Color4 HINT = new Color4(0.7f, 0.7f, 0.7f, 1f);
    private static final Color4 ERROR = new Color4(1f, 0.35f, 0.35f, 1f);
    private static final Color4 WHITE = new Color4(1f, 1f, 1f, 1f);
    private static final float NAME_W = 172, SMALL = 34;

    private final List<String> draft = new ArrayList<>(GameSettings.get().resourcePacks);
    private final Map<String, Texture> icons = new HashMap<>();
    private List<ResourcePack> discovered = List.of();
    private String applyError;

    public GuiResourcePacks(GuiScreen parent) {
        super(parent);
        this.refresh();
    }

    @Override protected String title() { return I18n.tr("resourcepacks.title"); }

    @Override
    protected void buildContent(GuiManager gui, VStack content) {
        content.add(new Label(I18n.tr("resourcepacks.selected"), GuiText.NORMAL).measure(gui));
        if (this.draft.isEmpty()) {
            content.add(new Label(I18n.tr("resourcepacks.selected_none"), GuiText.COMPACT, HINT, true).measure(gui));
        } else {
            for (int i = 0; i < this.draft.size(); i++) this.addSelected(gui, content, i);
        }
        content.add(new Spacer(0, 8));
        content.add(new Label(I18n.tr("resourcepacks.available"), GuiText.NORMAL).measure(gui));
        Set<String> selected = new HashSet<>(this.draft);
        int available = 0;
        for (ResourcePack pack : this.discovered) {
            if (selected.contains(pack.sourceName())) continue;
            available++;
            this.addAvailable(gui, content, pack);
        }
        if (available == 0) content.add(new Label(I18n.tr("resourcepacks.none"), GuiText.COMPACT, HINT, true).measure(gui));
        content.add(new Spacer(0, 5));
        if (this.applyError != null) {
            content.add(new Label(I18n.tr("resourcepacks.error", this.applyError), GuiText.SMALL, ERROR, true).measure(gui));
        }
        content.add(new Label(I18n.tr("resourcepacks.priority_hint"), GuiText.SMALL, HINT, true).measure(gui));
        content.add(new Label(I18n.tr("resourcepacks.folder", packsDir().getPath()), GuiText.SMALL, HINT, true).measure(gui));
    }

    private void addSelected(GuiManager gui, VStack content, int index) {
        String source = this.draft.get(index);
        ResourcePack pack = this.pack(source);
        String name = pack != null ? pack.displayName() : source + " (missing)";
        Label label = new Label(name, GuiText.COMPACT, pack == null || !pack.valid() ? ERROR : WHITE, true).measure(gui);
        label.w = NAME_W;
        Button remove = new Button("X", SMALL, 20, () -> { this.draft.remove(source); gui.relayoutCurrent(); });
        Button up = new Button("Up", SMALL, 20, () -> {
            int current = this.draft.indexOf(source);
            if (current > 0) { java.util.Collections.swap(this.draft, current, current - 1); gui.relayoutCurrent(); }
        });
        Button down = new Button("Dn", SMALL, 20, () -> {
            int current = this.draft.indexOf(source);
            if (current >= 0 && current + 1 < this.draft.size()) {
                java.util.Collections.swap(this.draft, current, current + 1); gui.relayoutCurrent();
            }
        });
        content.add(new HStack(3, new PackIcon(this.iconFor(pack)), label, remove, up, down));
        if (pack != null && !pack.description().isBlank()) {
            content.add(new Label(pack.description(), GuiText.SMALL, HINT, true).measure(gui));
        }
    }

    private void addAvailable(GuiManager gui, VStack content, ResourcePack pack) {
        Label label = new Label(pack.displayName(), GuiText.COMPACT, pack.valid() ? WHITE : ERROR, true).measure(gui);
        label.w = NAME_W + SMALL * 2 + 6;
        Button add = new Button("+", SMALL, 20, () -> {
            if (pack.valid()) { this.draft.addFirst(pack.sourceName()); gui.relayoutCurrent(); }
        });
        add.enabled = pack.valid();
        content.add(new HStack(3, new PackIcon(this.iconFor(pack)), label, add));
        String detail = pack.valid() ? pack.description() : pack.error();
        if (detail != null && !detail.isBlank()) {
            content.add(new Label(detail, GuiText.SMALL, pack.valid() ? HINT : ERROR, true).measure(gui));
        }
    }

    @Override
    protected GuiComponent buildFooter(GuiManager gui) {
        Button open = new Button(I18n.tr("resourcepacks.open_folder"), 105, 20, GuiResourcePacks::openPacksFolder);
        Button refresh = new Button(I18n.tr("resourcepacks.refresh"), 75, 20, () -> { this.refresh(); gui.relayoutCurrent(); });
        Button done = new Button(I18n.tr("gui.done"), 105, 20, () -> {
            if (this.draft.equals(GameSettings.get().resourcePacks)) { this.goBack(gui); return; }
            this.applyError = SkyEngine.get().getGame().reloadResourcePacks(this.draft);
            if (this.applyError == null) this.goBack(gui); else gui.relayoutCurrent();
        });
        return new HStack(5, open, refresh, done);
    }

    private void refresh() {
        this.disposeIcons();
        this.discovered = Resources.repository().refresh();
    }

    private Texture iconFor(ResourcePack pack) {
        if (pack == null) return null;
        if (this.icons.containsKey(pack.sourceName())) return this.icons.get(pack.sourceName());
        Texture texture = null;
        byte[] image = pack.readIcon();
        if (image != null) {
            try {
                texture = new Texture(image, false);
            } catch (RuntimeException error) {
                LogManager.getLogger(GuiResourcePacks.class.getName())
                        .warning("pack.png konnte nicht geladen werden (" + pack.sourceName() + "): "
                                + error.getMessage());
            }
        }
        this.icons.put(pack.sourceName(), texture);
        return texture;
    }

    private void disposeIcons() {
        for (Texture texture : this.icons.values()) if (texture != null) texture.dispose();
        this.icons.clear();
    }

    @Override public void onClose() { this.disposeIcons(); }
    private ResourcePack pack(String source) {
        for (ResourcePack pack : this.discovered) if (pack.sourceName().equals(source)) return pack;
        return null;
    }
    private static File packsDir() {
        File dir = GameDirectory.resolve("resourcepacks");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }
    private static void openPacksFolder() {
        try {
            new ProcessBuilder("explorer.exe", packsDir().getAbsolutePath()).start();
        } catch (IOException e) {
            LogManager.getLogger(GuiResourcePacks.class.getName())
                    .warning("Paketordner konnte nicht geoeffnet werden: " + e.getMessage());
        }
    }

    private static final class PackIcon extends GuiComponent {
        private final Texture texture;

        PackIcon(Texture texture) {
            this.texture = texture;
            this.w = this.h = 20;
        }

        @Override
        public void renderBackground(GuiManager gui, double mx, double my) {
            gui.sprites().drawRect(this.x, this.y, this.w, this.h, 0.12F, 0.12F, 0.12F, 1F);
            if (this.texture != null) gui.sprites().drawSprite(this.texture, this.x, this.y, this.w, this.h);
        }
    }
}
