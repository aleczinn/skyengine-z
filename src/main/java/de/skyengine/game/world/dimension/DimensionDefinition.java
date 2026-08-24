package de.skyengine.game.world.dimension;

import de.skyengine.core.i18n.I18n;
import de.skyengine.game.world.block.Identifier;

/** Registrierbare Dimension mit Standardgenerator, stabilem Seed-Salt und Darstellungsregeln. */
public record DimensionDefinition(Identifier id, Identifier defaultGenerator, int seedSalt,
                                  boolean lodAllowed) {
    public DimensionDefinition(Identifier id, Identifier defaultGenerator, int seedSalt) {
        this(id, defaultGenerator, seedSalt, true);
    }

    public String displayName() {
        return displayName(this.id);
    }

    public static String displayName(Identifier id) {
        String key = "dimension." + id.namespace() + "." + id.path().replace('/', '.');
        return I18n.has(key) ? I18n.tr(key) : id.toString();
    }
}
