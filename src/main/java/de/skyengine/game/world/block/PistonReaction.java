package de.skyengine.game.world.block;

/**
 * Wie ein Block auf Kolben-Schub reagiert (JSON-Feld {@code piston_reaction}):
 * NORMAL wird geschoben, DESTROY zerbricht mit Drop (Fackel, Staub, Pflanzen),
 * BLOCK stoppt den Kolben (Obsidian; automatisch: Härte &lt; 0 und BlockEntity-Blöcke —
 * s. {@code Block.getPistonReaction}).
 */
public enum PistonReaction {
    NORMAL,
    DESTROY,
    BLOCK;

    /** Name aus der JSON (case-insensitiv); unbekannt/null = NORMAL. */
    public static PistonReaction byName(String name) {
        if (name == null) return NORMAL;
        return switch (name.toLowerCase()) {
            case "destroy" -> DESTROY;
            case "block" -> BLOCK;
            default -> NORMAL;
        };
    }
}
