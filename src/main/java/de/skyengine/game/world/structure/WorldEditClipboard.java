package de.skyengine.game.world.structure;

/**
 * Spielerbezogenes Clipboard. Sein Ursprung ist bewusst vom nativen Structure-Anker getrennt
 * und darf ausserhalb des Template-Quaders liegen (WorldEdit-//copy relativ zum Spieler).
 */
public record WorldEditClipboard(StructureTemplate template, int originX, int originY, int originZ,
                                 StructureTransform transform) {
    public WorldEditClipboard {
        if (template == null) throw new IllegalArgumentException("Clipboard-Template fehlt");
        if (transform == null) transform = StructureTransform.IDENTITY;
    }

    public static WorldEditClipboard fromTemplate(StructureTemplate template) {
        return new WorldEditClipboard(template, template.anchorX(), template.anchorY(),
                template.anchorZ(), StructureTransform.IDENTITY);
    }

    public WorldEditClipboard withTransform(StructureTransform value) {
        return new WorldEditClipboard(template, originX, originY, originZ, value);
    }
}
