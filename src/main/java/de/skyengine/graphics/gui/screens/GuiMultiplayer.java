package de.skyengine.graphics.gui.screens;

import de.skyengine.client.network.ServerAddress;
import de.skyengine.client.network.ServerStatusPinger;
import de.skyengine.core.SkyEngine;
import de.skyengine.core.i18n.I18n;
import de.skyengine.graphics.color.Color4;
import de.skyengine.graphics.color.Colors;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.GuiText;
import de.skyengine.graphics.gui.ScrollBar;
import de.skyengine.graphics.gui.layout.Anchor;
import de.skyengine.graphics.gui.layout.HStack;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.Button;
import de.skyengine.graphics.gui.widget.GuiComponent;
import de.skyengine.graphics.gui.widget.Label;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** Saved-server browser and entry point for remote L0 sessions. */
public final class GuiMultiplayer extends GuiScreen {
    private static final float ENTRY_W = 260, ENTRY_H = 28, ROW_GAP = 2, SCROLL_STEP = 30;
    private static final long DOUBLE_CLICK_MS = 400;
    private static final Color4 SUBTITLE = new Color4(0.65f, 0.65f, 0.65f, 1f);
    private static final Color4 ONLINE = new Color4(0.35f, 1f, 0.35f, 1f);
    private static final Color4 INCOMPATIBLE = new Color4(1f, 0.75f, 0.25f, 1f);
    private static final Color4 OFFLINE = new Color4(1f, 0.35f, 0.35f, 1f);

    private final MultiplayerServerList servers;
    private final ServerStatusPinger statusPinger = new ServerStatusPinger();
    private final List<GuiComponent> entries = new ArrayList<>();
    private final ScrollBar scrollBar = new ScrollBar();
    private VStack rows;
    private float listTop, listBottom, rowsX;
    private double scrollOffset;
    private int selected = -1;
    private long lastClickTime;
    private Button join, edit, delete;
    private List<String> lastPingedAddresses = List.of();

    public GuiMultiplayer(GuiScreen parent) {
        this(parent, new MultiplayerServerList());
    }

    GuiMultiplayer(GuiScreen parent, MultiplayerServerList servers) {
        super(parent);
        this.servers = servers;
    }

    private final class EntryComponent extends GuiComponent {
        private final int index;
        private final MultiplayerServerList.Entry entry;

        EntryComponent(int index, MultiplayerServerList.Entry entry) {
            this.index = index;
            this.entry = entry;
            this.w = ENTRY_W;
            this.h = ENTRY_H;
        }

        @Override public void renderBackground(GuiManager gui, double mx, double my) {
            gui.sprites().drawRect(this.x, this.y, this.w, this.h, 0f, 0f, 0f, 0.5f);
            if (GuiMultiplayer.this.selected == this.index) {
                gui.sprites().drawRect(this.x, this.y, this.w, 1, 1f, 1f, 1f, 1f);
                gui.sprites().drawRect(this.x, this.y + this.h - 1, this.w, 1, 1f, 1f, 1f, 1f);
                gui.sprites().drawRect(this.x, this.y, 1, this.h, 1f, 1f, 1f, 1f);
                gui.sprites().drawRect(this.x + this.w - 1, this.y, 1, this.h, 1f, 1f, 1f, 1f);
            } else if (this.hovered) {
                gui.sprites().drawRect(this.x, this.y, this.w, this.h, 1f, 1f, 1f, 0.08f);
            }
        }

        @Override public void renderText(GuiManager gui, double mx, double my) {
            gui.font().drawStringWithShadow(this.entry.name(), this.x + 4, this.y + 3,
                    GuiText.NORMAL, Colors.WHITE);
            gui.font().drawString(this.entry.address(), this.x + 4, this.y + 15,
                    GuiText.SMALL, SUBTITLE);
            ServerStatusPinger.Result result = GuiMultiplayer.this.statusPinger.result(this.entry.address());
            String status = switch (result.state()) {
                case CHECKING -> I18n.tr("multiplayer.checking");
                case ONLINE -> I18n.tr("multiplayer.online", result.onlinePlayers(), result.maxPlayers(),
                        result.latencyMillis());
                case INCOMPATIBLE -> I18n.tr("multiplayer.incompatible");
                case OFFLINE -> I18n.tr("multiplayer.offline");
            };
            Color4 color = switch (result.state()) {
                case CHECKING -> SUBTITLE;
                case ONLINE -> ONLINE;
                case INCOMPATIBLE -> INCOMPATIBLE;
                case OFFLINE -> OFFLINE;
            };
            float statusX = this.x + this.w - gui.font().getStringWidth(status, GuiText.SMALL) - 4;
            gui.font().drawString(status, statusX, this.y + 15, GuiText.SMALL, color);
        }

        @Override public boolean mousePressed(double mx, double my, int button) {
            if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || !isMouseOver(mx, my)) return false;
            long now = System.currentTimeMillis();
            if (GuiMultiplayer.this.selected == this.index
                    && now - GuiMultiplayer.this.lastClickTime <= DOUBLE_CLICK_MS) {
                GuiMultiplayer.this.joinSelected();
                return true;
            }
            GuiMultiplayer.this.lastClickTime = now;
            GuiMultiplayer.this.select(this.index);
            return true;
        }
    }

    @Override public void init(GuiManager gui, float vW, float vH) {
        this.components.clear();
        this.entries.clear();
        List<MultiplayerServerList.Entry> saved = this.servers.entries();
        List<String> addresses = saved.stream().map(MultiplayerServerList.Entry::address).toList();
        if (!addresses.equals(this.lastPingedAddresses)) refreshStatuses(addresses);
        if (this.selected >= saved.size()) this.selected = -1;

        Label title = new Label(I18n.tr("multiplayer.title"), GuiText.TITLE).measure(gui);
        title.layoutAt((vW - title.width()) / 2f, 6);
        this.join = new Button(I18n.tr("multiplayer.join"), 128, 20, this::joinSelected);
        Button direct = new Button(I18n.tr("multiplayer.direct"), 128, 20,
                () -> gui.open(new GuiDirectConnect(this)));
        Button add = new Button(I18n.tr("multiplayer.add"), 128, 20,
                () -> gui.open(new GuiEditServer(this, this.servers, -1)));
        this.edit = new Button(I18n.tr("multiplayer.edit"), 128, 20, () -> {
            if (this.selected >= 0) gui.open(new GuiEditServer(this, this.servers, this.selected));
        });
        this.delete = new Button(I18n.tr("multiplayer.delete"), 128, 20, () -> {
            if (this.selected < 0) return;
            MultiplayerServerList.Entry entry = this.servers.entries().get(this.selected);
            gui.open(new GuiConfirm(this, I18n.tr("multiplayer.delete_title"),
                    I18n.tr("multiplayer.delete_message", entry.name()), () -> {
                this.servers.remove(this.selected);
                this.selected = -1;
            }));
        });
        Button refresh = new Button(I18n.tr("multiplayer.refresh"), 84, 20,
                () -> refreshStatuses(this.servers.entries().stream()
                        .map(MultiplayerServerList.Entry::address).toList()));
        this.delete.w = 84;
        Button back = new Button(I18n.tr("gui.back"), 84, 20, () -> this.goBack(gui));

        this.components.add(title);
        this.components.add(new VStack(4,
                new HStack(4, this.join, direct),
                new HStack(4, add, this.edit),
                new HStack(4, this.delete, refresh, back)
        ).anchor(Anchor.BOTTOM_CENTER, 0, 6));
        select(this.selected);

        this.listTop = 6 + GuiText.TITLE + 6;
        this.listBottom = vH - 76;
        this.rows = new VStack(ROW_GAP);
        for (int i = 0; i < saved.size(); i++) {
            EntryComponent entry = new EntryComponent(i, saved.get(i));
            this.entries.add(entry);
            this.rows.add(entry);
        }
        if (saved.isEmpty()) {
            Label empty = new Label(I18n.tr("multiplayer.empty"), GuiText.NORMAL, SUBTITLE, false).measure(gui);
            this.entries.add(empty);
            this.rows.add(empty);
        }
        this.rowsX = (vW - ENTRY_W) / 2f;
        this.scrollBar.layout(this.rowsX + ENTRY_W + 4, this.listTop, this.listBottom - this.listTop);
        applyScroll();
    }

    private void select(int index) {
        this.selected = index;
        boolean has = index >= 0;
        if (this.join != null) this.join.enabled = has;
        if (this.edit != null) this.edit.enabled = has;
        if (this.delete != null) this.delete.enabled = has;
    }

    private void refreshStatuses(List<String> addresses) {
        this.lastPingedAddresses = List.copyOf(addresses);
        this.statusPinger.refresh(addresses);
    }

    private void joinSelected() {
        if (this.selected < 0) return;
        connect(this.servers.entries().get(this.selected).address());
    }

    void connect(String addressText) {
        GuiManager gui = SkyEngine.get().getGame().getGuiManager();
        try {
            ServerAddress address = ServerAddress.parse(addressText);
            SkyEngine.get().getGame().connectToServer(address);
            gui.open(new GuiConnecting(this, address));
        } catch (IllegalArgumentException error) {
            gui.open(new GuiDisconnected(this, I18n.tr("multiplayer.invalid_address"), error.getMessage()));
        }
    }

    private void applyScroll() {
        double max = Math.max(0, this.rows.height() - (this.listBottom - this.listTop));
        this.scrollOffset = Math.clamp(this.scrollOffset, 0, max);
        this.rows.layoutAt(this.rowsX, (float) (this.listTop - this.scrollOffset));
    }

    private boolean inViewport(double y) { return y >= this.listTop && y < this.listBottom; }

    @Override public void render(GuiManager gui, double mouseX, double mouseY) {
        float vW = gui.vWidth(), vH = gui.vHeight();
        gui.sprites().begin(vW, vH);
        renderBackground(gui);
        for (GuiComponent component : this.leaves) {
            component.updateHover(mouseX, mouseY);
            component.renderBackground(gui, mouseX, mouseY);
        }
        gui.enableScissor(0, this.listTop, vW, this.listBottom - this.listTop);
        for (GuiComponent component : this.entries) {
            component.updateHover(inViewport(mouseY) ? mouseX : -1, mouseY);
            component.renderBackground(gui, mouseX, mouseY);
        }
        gui.disableScissor();
        this.scrollBar.draw(gui, this.rows.height(), this.scrollOffset);
        gui.sprites().end();

        gui.font().begin(vW, vH);
        for (GuiComponent component : this.leaves) component.renderText(gui, mouseX, mouseY);
        gui.font().end();
        gui.enableScissor(0, this.listTop, vW, this.listBottom - this.listTop);
        gui.font().begin(vW, vH);
        for (GuiComponent component : this.entries) component.renderText(gui, mouseX, mouseY);
        gui.font().end();
        gui.disableScissor();
    }

    @Override public boolean mousePressed(GuiManager gui, double mouseX, double mouseY, int button) {
        double barOffset = this.scrollBar.mousePressed(mouseX, mouseY, this.rows.height(), this.scrollOffset);
        if (barOffset >= 0) {
            this.scrollOffset = barOffset;
            applyScroll();
            return true;
        }
        if (super.mousePressed(gui, mouseX, mouseY, button)) return true;
        if (inViewport(mouseY)) {
            for (GuiComponent component : this.entries) {
                if (component.mousePressed(mouseX, mouseY, button)) return true;
            }
        }
        return false;
    }

    @Override public void mouseDragged(GuiManager gui, double mouseX, double mouseY, int button) {
        double barOffset = this.scrollBar.mouseDragged(mouseY, this.rows.height());
        if (barOffset >= 0) {
            this.scrollOffset = barOffset;
            applyScroll();
        } else super.mouseDragged(gui, mouseX, mouseY, button);
    }

    @Override public void mouseReleased(GuiManager gui, double mouseX, double mouseY, int button) {
        this.scrollBar.mouseReleased();
        super.mouseReleased(gui, mouseX, mouseY, button);
    }

    @Override public boolean mouseScrolled(GuiManager gui, double mouseX, double mouseY, double amount) {
        this.scrollOffset -= amount * SCROLL_STEP;
        applyScroll();
        return true;
    }
}
