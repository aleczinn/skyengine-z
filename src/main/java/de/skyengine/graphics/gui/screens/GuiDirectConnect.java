package de.skyengine.graphics.gui.screens;

import de.skyengine.core.i18n.I18n;
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

/** One-shot address entry. Successful addresses are intentionally not added to favorites. */
public final class GuiDirectConnect extends GuiScreen {
    private final GuiMultiplayer multiplayer;
    private String address = "localhost";
    private TextField addressField;

    public GuiDirectConnect(GuiMultiplayer parent) {
        super(parent);
        this.multiplayer = parent;
    }

    @Override public void init(GuiManager gui, float vW, float vH) {
        if (this.addressField != null) this.address = this.addressField.getText();
        this.components.clear();
        Label title = new Label(I18n.tr("multiplayer.direct_title"), GuiText.TITLE).measure(gui);
        Label prompt = new Label(I18n.tr("multiplayer.address"), GuiText.NORMAL).measure(gui);
        this.addressField = new TextField(200, 20, 512, c -> c > 32 && c != 127)
                .placeholder("localhost:25565").text(this.address);
        Button connect = new Button(I18n.tr("multiplayer.join"), 98, 20, this::connect);
        Button cancel = new Button(I18n.tr("gui.cancel"), 98, 20, () -> this.goBack(gui));
        this.components.add(title.anchor(Anchor.TOP_CENTER, 0, titleTop(vH)));
        this.components.add(new VStack(5, prompt, this.addressField,
                new HStack(4, connect, cancel)).anchor(Anchor.CENTER));
    }

    @Override public boolean keyPressed(GuiManager gui, int key) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            connect();
            return true;
        }
        return super.keyPressed(gui, key);
    }

    private void connect() {
        this.address = this.addressField.getText();
        this.multiplayer.connect(this.address);
    }
}
