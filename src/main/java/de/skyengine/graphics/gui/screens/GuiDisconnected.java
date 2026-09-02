package de.skyengine.graphics.gui.screens;

import de.skyengine.core.SkyEngine;
import de.skyengine.core.i18n.I18n;
import de.skyengine.graphics.color.Color4;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.GuiText;
import de.skyengine.graphics.gui.layout.Anchor;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.Button;
import de.skyengine.graphics.gui.widget.Label;

/** Structured remote disconnect/error page that always releases the active transport. */
public final class GuiDisconnected extends GuiScreen {
    private static final Color4 MESSAGE = new Color4(0.7f, 0.7f, 0.7f, 1f);
    private final String reason;
    private final String message;

    public GuiDisconnected(GuiScreen parent, String reason, String message) {
        super(parent);
        this.reason = reason == null || reason.isBlank() ? I18n.tr("multiplayer.connection_failed") : reason;
        this.message = message == null ? "" : message;
    }

    @Override public void init(GuiManager gui, float vW, float vH) {
        SkyEngine.get().getGame().disconnectFromServer();
        this.components.clear();
        Label title = new Label(I18n.tr("multiplayer.disconnected"), GuiText.TITLE).measure(gui);
        Label reasonLabel = new Label(this.reason, GuiText.NORMAL).measure(gui);
        Label messageLabel = new Label(this.message, GuiText.SMALL, MESSAGE, false).measure(gui);
        Button back = new Button(I18n.tr("gui.back"), () -> this.goBack(gui));
        this.components.add(title.anchor(Anchor.TOP_CENTER, 0, titleTop(vH)));
        this.components.add(new VStack(8, reasonLabel, messageLabel, back).anchor(Anchor.CENTER));
    }
}
