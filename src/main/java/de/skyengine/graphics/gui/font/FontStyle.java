package de.skyengine.graphics.gui.font;

/**
 * Schriftstil einer Font-Familie. Jeder Stil kommt aus einer eigenen TTF-Datei
 * ({@code <familie><suffix>.ttf} in {@code game/fonts/}); fehlt die Datei, fällt der
 * {@link FontRenderer} auf {@link #REGULAR} zurück.
 */
public enum FontStyle {

    REGULAR("-regular"),
    BOLD("-bold"),
    ITALIC("-italic");

    /** Datei-Suffix vor der Endung, z.B. {@code monocraft-bold.ttf}. */
    public final String suffix;

    FontStyle(String suffix) {
        this.suffix = suffix;
    }
}
