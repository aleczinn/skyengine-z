package de.skyengine.graphics.gui.screens;

import de.skyengine.client.network.ClientMultiplayerConnection;
import de.skyengine.client.network.ServerAddress;
import de.skyengine.core.SkyEngine;
import de.skyengine.core.i18n.I18n;
import de.skyengine.graphics.color.Color4;
import de.skyengine.graphics.color.Colors;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.GuiText;
import de.skyengine.graphics.gui.layout.Anchor;
import de.skyengine.graphics.gui.widget.Button;
import de.skyengine.graphics.gui.widget.Label;
import org.lwjgl.glfw.GLFW;

/** Non-blocking connection progress. The network owner is advanced by GameContainer.update(). */
public final class GuiConnecting extends GuiScreen {
    private static final Color4 SECONDARY = new Color4(0.7f, 0.7f, 0.7f, 1f);
    private final ServerAddress address;
    private boolean forwardedFailure;

    public GuiConnecting(GuiScreen parent, ServerAddress address) {
        super(parent);
        this.address = address;
    }

    @Override public boolean isClosable() { return false; }

    @Override public void init(GuiManager gui, float vW, float vH) {
        this.components.clear();
        Label title = new Label(I18n.tr("multiplayer.connecting_title"), GuiText.TITLE).measure(gui);
        Button cancel = new Button(I18n.tr("gui.cancel"), () -> cancel(gui));
        this.components.add(title.anchor(Anchor.TOP_CENTER, 0, titleTop(vH)));
        this.components.add(cancel.anchor(Anchor.BOTTOM_CENTER, 0, 20));
    }

    @Override public void render(GuiManager gui, double mouseX, double mouseY) {
        ClientMultiplayerConnection connection = SkyEngine.get().getGame().getMultiplayerConnection();
        if (!this.forwardedFailure && (connection.phase() == ClientMultiplayerConnection.Phase.FAILED
                || connection.phase() == ClientMultiplayerConnection.Phase.DISCONNECTED)) {
            this.forwardedFailure = true;
            String reason = connection.disconnectReason() == null
                    ? I18n.tr("multiplayer.connection_failed")
                    : I18n.tr("multiplayer.disconnect." + connection.disconnectReason().name().toLowerCase());
            gui.open(new GuiDisconnected(this.parent, reason, connection.detail()));
            return;
        }

        super.render(gui, mouseX, mouseY);
        String phase = I18n.tr("multiplayer.status." + connection.phase().name().toLowerCase());
        String endpoint = this.address.display();
        gui.font().begin(gui.vWidth(), gui.vHeight());
        gui.font().drawStringWithShadow(phase,
                (gui.vWidth() - gui.font().getStringWidth(phase, GuiText.MEDIUM)) / 2f,
                gui.vHeight() / 2f - 12, GuiText.MEDIUM, Colors.WHITE);
        gui.font().drawString(endpoint,
                (gui.vWidth() - gui.font().getStringWidth(endpoint, GuiText.SMALL)) / 2f,
                gui.vHeight() / 2f + 6, GuiText.SMALL, SECONDARY);
        if (connection.phase() == ClientMultiplayerConnection.Phase.PLAY) {
            String pending = I18n.tr("multiplayer.remote_world_pending");
            gui.font().drawString(pending,
                    (gui.vWidth() - gui.font().getStringWidth(pending, GuiText.SMALL)) / 2f,
                    gui.vHeight() / 2f + 20, GuiText.SMALL, SECONDARY);
        }
        gui.font().end();
    }

    @Override public boolean keyPressed(GuiManager gui, int key) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            cancel(gui);
            return true;
        }
        return true;
    }

    private void cancel(GuiManager gui) {
        SkyEngine.get().getGame().disconnectFromServer();
        if (this.parent != null) gui.open(this.parent);
        else gui.open(new GuiMainMenu());
    }
}
