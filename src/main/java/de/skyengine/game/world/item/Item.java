package de.skyengine.game.world.item;

import de.skyengine.core.i18n.I18n;
import de.skyengine.game.world.block.Identifier;

/**
 * Ein registrierbarer Gegenstand. Basisklasse - Blöcke bekommen automatisch ein {@link BlockItem}
 * mit derselben {@link Identifier}. Eigene Items (Werkzeuge, Materialien) erben hiervon.
 */
public class Item {

    public static final int DEFAULT_MAX_STACK = 64;

    private final Identifier id;
    private final int maxStackSize;

    public Item(Identifier id) {
        this(id, DEFAULT_MAX_STACK);
    }

    public Item(Identifier id, int maxStackSize) {
        this.id = id;
        this.maxStackSize = maxStackSize;
    }

    public Identifier getId() {
        return id;
    }

    public int getMaxStackSize() {
        return maxStackSize;
    }

    /** i18n-Key des Anzeigenamens ({@code item.<namespace>.<pfad>}; BlockItems überschreiben auf {@code block.}). */
    public String translationKey() {
        return "item." + this.id.namespace() + "." + this.id.path();
    }

    /** Übersetzter Anzeigename (I18n prettifiziert ungepflegte Keys aus dem Pfad). */
    public String getDisplayName() {
        return I18n.tr(this.translationKey());
    }

    /**
     * Texturpfad für ein flaches 2D-Icon (Nicht-Block-Items wie Eimer). {@code null} bei Block-Items,
     * die als 3D-Würfel/aus {@code icon_flat} gerendert werden. Der Pfad muss vor dem TextureArray-Bau
     * über {@code BlockTextures.layerOf} registriert sein.
     */
    public String getIconTexture() {
        return null;
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
