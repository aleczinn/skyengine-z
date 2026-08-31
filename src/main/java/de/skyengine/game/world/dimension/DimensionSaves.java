package de.skyengine.game.world.dimension;

import de.skyengine.core.SkyEngine;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.save.LevelData;

import java.io.File;
import java.nio.file.Path;

/** Metadatenanlage und sichere Pfadauflosung dimensionsgebundener Save-Daten. */
public final class DimensionSaves {

    public record Resolved(DimensionDefinition dimension, GeneratorDefinition generator,
                           LevelData.DimensionData data, File root, File regionDir) {}

    public static Resolved resolve(File saveRoot, LevelData level, Identifier id) {
        WorldgenRegistries.bootstrap();
        WorldgenRegistries.validate(id);
        DimensionDefinition dimension = WorldgenRegistries.DIMENSIONS.get(id);
        if (dimension == null) throw new IllegalArgumentException("Unbekannte Dimension: " + id);
        LevelData.DimensionData data = level.dimensions.get(id.toString());
        if (data == null) {
            data = create(level.seed, dimension);
            level.dimensions.put(id.toString(), data);
        }
        if (data.generator == null || data.generator.isBlank()) {
            data.generator = dimension.defaultGenerator().toString();
        }
        Identifier generatorId = Identifier.of(data.generator);
        GeneratorDefinition generator = WorldgenRegistries.GENERATORS.get(generatorId);
        if (generator == null) {
            throw new IllegalStateException("Generator der Dimension " + id + " ist nicht registriert: " + generatorId);
        }
        if (data.generatorVersion == null) data.generatorVersion = generator.version();

        File dimensionRoot = id.equals(WorldgenRegistries.OVERWORLD)
                ? saveRoot : dimensionRoot(saveRoot, id);
        return new Resolved(dimension, generator, data, dimensionRoot,
                new File(dimensionRoot, "region"));
    }

    private static LevelData.DimensionData create(int worldSeed, DimensionDefinition dimension) {
        GeneratorDefinition generator = WorldgenRegistries.GENERATORS.get(dimension.defaultGenerator());
        if (generator == null) throw new IllegalStateException("Standardgenerator fehlt: " + dimension.defaultGenerator());
        LevelData.DimensionData data = new LevelData.DimensionData();
        data.seed = WorldgenRegistries.dimensionSeed(worldSeed, dimension);
        data.generator = generator.id().toString();
        data.generatorVersion = generator.version();
        return data;
    }

    /** Spiel-prefixierter Dimensionsordner, z.B. {@code dimensions/voxelstories/mining}. */
    static File dimensionRoot(File saveRoot, Identifier id) {
        Path root = saveRoot.toPath().toAbsolutePath().normalize();
        Path dimensions = root.resolve("dimensions");
        Path child = dimensions.resolve(SkyEngine.GAME_PREFIX);
        for (String part : id.path().split("/")) child = child.resolve(part);
        child = child.normalize();
        if (!child.startsWith(dimensions) || child.equals(dimensions)) {
            throw new IllegalArgumentException("Dimensionspfad verlaesst das Savegame: " + id);
        }
        return child.toFile();
    }

    private DimensionSaves() {}
}
