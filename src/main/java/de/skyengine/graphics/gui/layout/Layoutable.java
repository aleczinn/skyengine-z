package de.skyengine.graphics.gui.layout;

/**
 * Minimaler Layout-Vertrag: etwas mit Maßen, das an eine Position gesetzt werden kann.
 * Erfüllt von {@code GuiComponent} sowie von {@link VStack}/{@link HStack} selbst —
 * damit lassen sich Stacks eine Ebene verschachteln (z.B. VStack aus HStack-Zeilen).
 */
public interface Layoutable {

    float width();

    float height();

    void layoutAt(float x, float y);
}
