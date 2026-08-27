package de.skyengine.game.world.generator.biome;

import de.skyengine.game.world.generator.feature.trees.TreeShape;

/**
 * Reine Parameter-Daten eines Bioms (kein Verhalten) — Instanzen sind statische Konstanten
 * in {@link Biomes}, analog zum Blocks-Stil. Neue Biome brauchen nur eine neue Konstante
 * plus einen Eintrag im Lookup.
 */
public final class Biome {

    /** Gewichteter Baumtyp des Bioms (Auswahl via {@code TreeShapes.pick}). */
    public record TreeEntry(int weight, String type, TreeShape shape) {
        public TreeEntry {
            if (weight <= 0 || type == null || type.isBlank() || shape == null) {
                throw new IllegalArgumentException("Ungueltiger Baum-Eintrag");
            }
        }
    }

    /** Gewichtete Bodenpflanze (Auswahl per Hash gegen die Gewichtssumme). */
    public record PlantEntry(int weight, int blockId) {
    }

    public static final TreeEntry[] NO_TREES = new TreeEntry[0];
    public static final PlantEntry[] NO_PLANTS = new PlantEntry[0];

    /* Registrierungs-Index (Reihenfolge in Biomes.ALL) */
    public final int id;
    public final String name;

    /* Deckblock + Fuellmaterial darunter (Blocks.*-IDs) */
    public final int surfaceBlock;
    public final int fillerBlock;

    /* Gras-/Laubfarbe des Bioms (0xRRGGBB, ersetzt die Tints-Platzhalter im Mesher) */
    public final int grassTint;
    public final int foliageTint;

    /* Farbe in der Debug-Biomkarte (GeneratorMapExporter) */
    public final int debugColor;

    /* Baum-Dichte: Annahme-Wahrscheinlichkeit pro Platzierungsversuch (8 Versuche/Chunk) */
    public final float treeChance;
    public final TreeEntry[] trees;
    /* Bodenpflanzen: Basis-Wahrscheinlichkeit pro Spalte (0..1, moduliert vom Dichtefeld) */
    public final float plantDensity;
    public final PlantEntry[] plants;

    Biome(int id, String name, int surfaceBlock, int fillerBlock, int grassTint, int foliageTint,
          int debugColor, float treeChance, TreeEntry[] trees, float plantDensity, PlantEntry[] plants) {
        this.id = id;
        this.name = name;
        this.surfaceBlock = surfaceBlock;
        this.fillerBlock = fillerBlock;
        this.grassTint = grassTint;
        this.foliageTint = foliageTint;
        this.debugColor = debugColor;
        this.treeChance = treeChance;
        this.trees = trees;
        this.plantDensity = plantDensity;
        this.plants = plants;
    }
}
