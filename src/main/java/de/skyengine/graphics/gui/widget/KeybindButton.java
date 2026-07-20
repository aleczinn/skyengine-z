package de.skyengine.graphics.gui.widget;

import de.skyengine.core.input.KeyNames;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.graphics.color.Color4;

import java.util.Map;

/**
 * Button einer Tastenbelegung: zeigt die gebundene Taste; Klick startet die Aufnahme
 * („> Taste... <"), die nächste Taste bindet (ESC bricht ab — behandelt der KeybindsScreen).
 * Kollidiert die Taste mit einer anderen Aktion, wird der Text rot (erlaubt, nur markiert).
 */
public final class KeybindButton extends Button {

    private static final Color4 CAPTURING = new Color4(1f, 1f, 0.4f, 1f);
    private static final Color4 CONFLICT = new Color4(1f, 0.35f, 0.35f, 1f);

    private final String action;
    private boolean capturing;

    public KeybindButton(String action, float w, float h) {
        super("", w, h, null);
        this.action = action;
        this.refresh();
    }

    public String action() {
        return this.action;
    }

    public boolean isCapturing() {
        return this.capturing;
    }

    /** Bindet die Taste und beendet die Aufnahme. */
    public void bind(int key) {
        GameSettings.get().keyBindings.put(this.action, key);
        this.capturing = false;
        this.refresh();
    }

    public void cancelCapture() {
        this.capturing = false;
        this.refresh();
    }

    public void refresh() {
        this.setLabel(this.capturing ? "> Taste... <" : KeyNames.name(GameSettings.get().key(this.action)));
    }

    @Override
    protected void onPress() {
        this.capturing = true;
        this.refresh();
    }

    private boolean hasConflict() {
        int key = GameSettings.get().key(this.action);
        for (Map.Entry<String, Integer> e : GameSettings.get().keyBindings.entrySet()) {
            if (!e.getKey().equals(this.action) && e.getValue() == key) return true;
        }
        return false;
    }

    @Override
    protected Color4 textColor() {
        if (this.capturing) return CAPTURING;
        if (this.hasConflict()) return CONFLICT;
        return super.textColor();
    }
}
