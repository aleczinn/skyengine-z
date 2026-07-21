package de.skyengine.graphics.gui.widget;

/**
 * Unsichtbarer Abstandshalter für Stacks: der Stack-{@code gap} bleibt der Grundabstand,
 * ein Spacer setzt gezielt größere Lücken (z.B. Logo → Buttons, Optionsraster → „Fertig").
 * Web-Analogon: gap + spacer statt margin.
 */
public final class Spacer extends GuiComponent {

    public Spacer(float w, float h) {
        this.w = w;
        this.h = h;
    }
}
