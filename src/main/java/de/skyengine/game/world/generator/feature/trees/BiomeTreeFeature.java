package de.skyengine.game.world.generator.feature.trees;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.generator.biome.Biome;
import de.skyengine.game.world.generator.feature.Feature;
import de.skyengine.game.world.generator.feature.FeatureContext;

import java.util.Random;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.structure.StructurePlacement;
import de.skyengine.game.world.structure.StructureTemplate;
import de.skyengine.game.world.structure.StructureTemplateManager;
import de.skyengine.game.world.structure.StructureTransform;
import java.util.Comparator;

/**
 * Biome-abhaengige Baumplatzierung: pro Quell-Chunk eine feste Anzahl Versuche; jeder Versuch
 * wuerfelt Position, prueft die {@code treeChance} des Bioms und den passenden Traeger-Block
 * (Gras bzw. Sand am Karibikstrand) — alles rein aus RNG + purem Sampling (Scheiben-Vertrag,
 * alle RNG-Zuege in fester Reihenfolge unabhaengig vom Ausgang der Checks).
 */
public final class BiomeTreeFeature implements Feature {

    private static final int MAX_ATTEMPTS = 8;
    private static final StructurePlacement TEMPLATE_PLACEMENT = new StructurePlacement();
    private final StructureTemplateManager.Snapshot structures;
    private final java.util.concurrent.ConcurrentHashMap<String, StructureTemplate[]> templateCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    public BiomeTreeFeature() { this(null); }

    private BiomeTreeFeature(StructureTemplateManager.Snapshot structures) { this.structures = structures; }

    @Override
    public Feature withStructures(StructureTemplateManager.Snapshot structures) {
        return new BiomeTreeFeature(structures);
    }

    @Override
    public int cacheVersion() {
        return this.structures == null ? 3 : 31 * 3 + this.structures.fingerprint();
    }

    @Override
    public void place(FeatureContext placer) {
        Random rng = placer.random();

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            int x = placer.sourceMinX() + rng.nextInt(ChunkSection.SIZE);
            int z = placer.sourceMinZ() + rng.nextInt(ChunkSection.SIZE);
            float roll = rng.nextFloat();

            Biome biome = placer.biome(x, z);
            if (biome.trees.length == 0 || roll >= biome.treeChance) continue;

            /* Nur auf dem Deckblock des Bioms (schliesst Wasser, Fels- und Schneekuppen aus) */
            if (placer.surfaceBlock(x, z) != biome.surfaceBlock) continue;

            /* Kein Baum direkt an der Wasserkante: auch die 4 Nachbarn muessen trocken sein
             * (pures Sampling, kein RNG-Zug -> Scheiben-Vertrag bleibt intakt) */
            if (placer.surfaceBlock(x + 1, z) == Blocks.WATER || placer.surfaceBlock(x - 1, z) == Blocks.WATER
                    || placer.surfaceBlock(x, z + 1) == Blocks.WATER || placer.surfaceBlock(x, z - 1) == Blocks.WATER) {
                continue;
            }

            Biome.TreeEntry tree = TreeShapes.pick(biome.trees, rng);
            TreeShape shape = tree.shape();
            int baseY = placer.surfaceHeight(x, z) + 1;
            de.skyengine.game.world.structure.TreeTemplateCatalog.Group group = this.structures == null
                    ? null : this.structures.treeCatalog().group(tree.type());
            boolean useTemplate = group != null && rng.nextInt(group.templateWeight() + group.proceduralWeight())
                    < group.templateWeight();
            if (useTemplate) {
                StructureTemplate[] templates = templates(tree.type(), group.folder());
                if (templates.length > 0) {
                    StructureTemplate template = templates[rng.nextInt(templates.length)];
                    StructureTransform.Rotation rotation = StructureTransform.Rotation.values()[rng.nextInt(4)];
                    TEMPLATE_PLACEMENT.placeInFeature(template, placer, x, baseY, z,
                            new StructureTransform(rotation, StructureTransform.Mirror.NONE),
                            StructurePlacement.Rule.KEEP_EXISTING);
                } else {
                    shape.place(placer, x, baseY, z, rng);
                }
            } else {
                shape.place(placer, x, baseY, z, rng);
            }
        }
    }

    private StructureTemplate[] templates(String type, String prefix) {
        return this.templateCache.computeIfAbsent(type, ignored -> this.structures.ids().stream()
                .filter(id -> id.path().startsWith(prefix))
                .sorted(Comparator.comparing(Identifier::toString))
                .map(this.structures::get)
                .toArray(StructureTemplate[]::new));
    }
}
