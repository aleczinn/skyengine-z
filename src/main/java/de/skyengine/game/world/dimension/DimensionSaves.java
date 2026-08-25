package de.skyengine.game.world.dimension;

import de.skyengine.core.SkyEngine;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.save.LevelData;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

/** Migration und sichere Pfadauflosung dimensionsgebundener Save-Daten. */
public final class DimensionSaves {

    public record Resolved(DimensionDefinition dimension, GeneratorDefinition generator,
                           LevelData.DimensionData data, File root, File regionDir, File lodDir) {}

    public static Resolved resolve(File saveRoot, LevelData level, Identifier id) {
        WorldgenRegistries.bootstrap();
        WorldgenRegistries.validate(id);
        DimensionDefinition dimension = WorldgenRegistries.DIMENSIONS.get(id);
        if (dimension == null) throw new IllegalArgumentException("Unbekannte Dimension: " + id);
        if (level.dimensions == null) level.dimensions = new LinkedHashMap<>();

        LevelData.DimensionData data = level.dimensions.get(id.toString());
        if (data == null) {
            data = id.equals(WorldgenRegistries.OVERWORLD)
                    ? migrateOverworld(level) : create(level.seed, dimension);
            level.dimensions.put(id.toString(), data);
        }
        data.generator = normalizeGenerator(data.generator, dimension.defaultGenerator());
        Identifier generatorId = Identifier.of(data.generator);
        GeneratorDefinition generator = WorldgenRegistries.GENERATORS.get(generatorId);
        if (generator == null) {
            throw new IllegalStateException("Generator der Dimension " + id + " ist nicht registriert: " + generatorId);
        }
        if (data.generatorVersion == null) data.generatorVersion = generator.version();

        File dimensionRoot = id.equals(WorldgenRegistries.OVERWORLD)
                ? saveRoot : dimensionRoot(saveRoot, id);
        if (!id.equals(WorldgenRegistries.OVERWORLD)) {
            migrateLegacyDirectory(saveRoot, id, dimensionRoot);
        }
        if (id.equals(WorldgenRegistries.OVERWORLD)) mirrorLegacyOverworld(level, data);
        return new Resolved(dimension, generator, data, dimensionRoot,
                new File(dimensionRoot, "region"), new File(dimensionRoot, "lod"));
    }

    private static LevelData.DimensionData migrateOverworld(LevelData level) {
        LevelData.DimensionData data = new LevelData.DimensionData();
        data.seed = level.seed;
        if ("imported".equals(level.worldType)) {
            data.generator = WorldgenRegistries.MINECRAFT_IMPORT.toString();
            data.generatorVersion = level.generatorVersion != null ? level.generatorVersion : 1;
        } else {
            data.generator = normalizeGenerator(level.generator, WorldgenRegistries.ALPHA_V2);
            data.generatorVersion = level.generatorVersion != null ? level.generatorVersion : 1;
        }
        return data;
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

    private static String normalizeGenerator(String value, Identifier fallback) {
        if (value == null || value.isBlank()) return fallback.toString();
        return switch (value) {
            case "alpha_v2" -> WorldgenRegistries.ALPHA_V2.toString();
            case "minecraft_import" -> WorldgenRegistries.MINECRAFT_IMPORT.toString();
            default -> value.indexOf(':') < 0 ? "skyengine:" + value : value;
        };
    }

    private static void mirrorLegacyOverworld(LevelData level, LevelData.DimensionData data) {
        level.generator = Identifier.of(data.generator).path();
        level.generatorVersion = data.generatorVersion;
        level.worldType = WorldgenRegistries.MINECRAFT_IMPORT.toString().equals(data.generator)
                ? "imported" : "default";
    }

    /** Spiel-prefixierter Dimensionsordner, z.B. {@code dimensions/voxel_stories/mining}. */
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

    private static void migrateLegacyDirectory(File saveRoot, Identifier id, File target) {
        Path root = saveRoot.toPath().toAbsolutePath().normalize();
        Path dimensions = root.resolve("dimensions");
        Path destination = target.toPath().toAbsolutePath().normalize();
        if (Files.exists(destination)) return;

        Path oldNamespace = dimensions.resolve(id.namespace());
        for (String part : id.path().split("/")) oldNamespace = oldNamespace.resolve(part);
        Path flat = dimensions.resolve(id.path().replace('/', '-'));
        for (Path legacy : new Path[]{oldNamespace.normalize(), flat.normalize()}) {
            if (legacy.equals(destination) || !legacy.startsWith(dimensions) || !Files.exists(legacy)) continue;
            try {
                Files.createDirectories(destination.getParent());
                Files.move(legacy, destination);
                return;
            } catch (IOException e) {
                throw new IllegalStateException("Alten Dimensionsordner konnte nicht nach "
                        + destination + " migriert werden", e);
            }
        }
    }

    private DimensionSaves() {}
}
