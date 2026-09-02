package de.skyengine.graphics.gui.screens;

import de.skyengine.client.network.ServerAddress;
import de.skyengine.core.i18n.I18n;
import de.skyengine.graphics.color.Color4;
import de.skyengine.graphics.color.Colors;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.GuiText;
import de.skyengine.graphics.gui.layout.Anchor;
import de.skyengine.graphics.gui.layout.HStack;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.Button;
import de.skyengine.graphics.gui.widget.Label;
import de.skyengine.graphics.gui.widget.TextField;
import org.lwjgl.glfw.GLFW;

/** Adds or edits one persistent server favorite. */
public final class GuiEditServer extends GuiScreen {
    private static final Color4 ERROR = new Color4(1f, 0.35f, 0.35f, 1f);
    private final MultiplayerServerList servers;
    private final int index;
    private String name = "", address = "localhost";
    private TextField nameField, addressField;
    private String error;

    public GuiEditServer(GuiScreen parent, MultiplayerServerList servers, int index) {
        super(parent);
        this.servers = servers;
        this.index = index;
        if (index >= 0) {
            MultiplayerServerList.Entry entry = servers.entries().get(index);
            this.name = entry.name();
            this.address = entry.address();
        }
    }

    @Override public void init(GuiManager gui, float vW, float vH) {
        if (this.nameField != null) this.name = this.nameField.getText();
        if (this.addressField != null) this.address = this.addressField.getText();
        this.components.clear();
        Label title = new Label(I18n.tr(this.index < 0 ? "multiplayer.add_title" : "multiplayer.edit_title"),
                GuiText.TITLE).measure(gui);
        Label namePrompt = new Label(I18n.tr("multiplayer.server_name"), GuiText.NORMAL).measure(gui);
        Label addressPrompt = new Label(I18n.tr("multiplayer.address"), GuiText.NORMAL).measure(gui);
        this.nameField = new TextField(200, 20, 128, c -> c >= 32 && c != 127)
                .placeholder(I18n.tr("multiplayer.server_name_placeholder")).text(this.name);
        this.addressField = new TextField(200, 20, 512, c -> c > 32 && c != 127)
                .placeholder("localhost:25565").text(this.address);
        Button done = new Button(I18n.tr("gui.done"), 98, 20, () -> save(gui));
        Button cancel = new Button(I18n.tr("gui.cancel"), 98, 20, () -> this.goBack(gui));
        this.components.add(title.anchor(Anchor.TOP_CENTER, 0, titleTop(vH)));
        this.components.add(new VStack(4, namePrompt, this.nameField, addressPrompt, this.addressField,
                new HStack(4, done, cancel)).anchor(Anchor.CENTER));
    }

    @Override public void render(GuiManager gui, double mouseX, double mouseY) {
        super.render(gui, mouseX, mouseY);
        if (this.error == null) return;
        gui.font().begin(gui.vWidth(), gui.vHeight());
        gui.font().drawStringWithShadow(this.error,
                (gui.vWidth() - gui.font().getStringWidth(this.error, GuiText.SMALL)) / 2f,
                gui.vHeight() / 2f + 60, GuiText.SMALL, ERROR);
        gui.font().end();
    }

    @Override public boolean keyPressed(GuiManager gui, int key) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            save(gui);
            return true;
        }
        return super.keyPressed(gui, key);
    }

    private void save(GuiManager gui) {
        this.name = this.nameField.getText().trim();
        this.address = this.addressField.getText().trim();
        try {
            ServerAddress parsed = ServerAddress.parse(this.address);
            MultiplayerServerList.Entry entry = new MultiplayerServerList.Entry(this.name, parsed.display());
            if (this.index < 0) this.servers.add(entry);
            else this.servers.set(this.index, entry);
            this.goBack(gui);
        } catch (IllegalArgumentException validation) {
            this.error = I18n.tr("multiplayer.invalid_address");
        }
    }
}
