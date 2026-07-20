package de.skyengine.graphics.gui.screens;

import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.GuiScreen;
import de.skyengine.graphics.gui.layout.Anchor;
import de.skyengine.graphics.gui.layout.HStack;
import de.skyengine.graphics.gui.layout.VStack;
import de.skyengine.graphics.gui.widget.Button;
import de.skyengine.graphics.gui.widget.Label;

/**
 * Generische Ja/Nein-Abfrage („Welt wirklich löschen?"). „Ja" führt die Aktion aus und kehrt
 * zum Eltern-GuiScreen zurück; „Nein"/ESC kehrt nur zurück.
 */
public final class GuiConfirm extends GuiScreen {

    private final String title;
    private final String message;
    private final Runnable onConfirm;

    public GuiConfirm(GuiScreen parent, String title, String message, Runnable onConfirm) {
        super(parent);
        this.title = title;
        this.message = message;
        this.onConfirm = onConfirm;
    }

    @Override
    public boolean pausesGame() {
        return this.parent != null && this.parent.pausesGame();
    }

    @Override
    public void init(GuiManager gui, float vW, float vH) {
        this.components.clear();

        Label titleLabel = new Label(this.title, 14).measure(gui);
        Label messageLabel = new Label(this.message, 10).measure(gui);
        Button yes = new Button("Ja", 100, 20, () -> {
            this.onConfirm.run();
            this.goBack(gui);
        });
        Button no = new Button("Nein", 100, 20, () -> this.goBack(gui));

        this.components.add(titleLabel);
        this.components.add(messageLabel);
        this.components.add(yes);
        this.components.add(no);

        VStack stack = new VStack(8)
                .add(titleLabel)
                .add(messageLabel)
                .add(new HStack(8).add(yes).add(no));
        stack.layoutAnchored(vW, vH, Anchor.CENTER, 0, 0);
    }
}
