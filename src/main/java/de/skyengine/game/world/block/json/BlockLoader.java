package de.skyengine.game.world.block.json;

import com.google.gson.Gson;
import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.RenderLayer;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.File;
import java.io.FileReader;
import java.util.Arrays;
import java.util.Comparator;

public final class BlockLoader {

    private static final Logger LOGGER = LogManager.getLogger(BlockLoader.class.getName());
    private static final Gson GSON = new Gson();

    /** Lädt und registriert alle *.json-Blockdefinitionen aus dem Ordner. */
    public static void load(File directory) {
        if (!directory.exists() || !directory.isDirectory()) {
            LOGGER.warning("Block-Ordner nicht gefunden: " + directory.getAbsolutePath());
            return;
        }

        File[] files = directory.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null || files.length == 0) {
            LOGGER.warning("Keine Block-Definitionen in " + directory.getAbsolutePath());
            return;
        }

        /* Deterministische Reihenfolge -> stabile Runtime-IDs innerhalb einer Version */
        Arrays.sort(files, Comparator.comparing(File::getName));

        int loaded = 0;
        for (File file : files) {
            try (FileReader reader = new FileReader(file)) {
                BlockDefinition definition = GSON.fromJson(reader, BlockDefinition.class);
                if (definition.id == null || definition.id.isBlank()) {
                    LOGGER.error("Block-Definition ohne 'id': " + file.getName());
                    continue;
                }
                register(definition);
                loaded++;
            } catch (Exception e) {
                LOGGER.error("Fehlerhafte Block-Definition: " + file.getName(), e);
            }
        }
        LOGGER.info(loaded + " Block-Definitionen geladen");
    }

    private static void register(BlockDefinition definition) {
        RenderLayer layer = switch (definition.layer.toLowerCase()) {
            case "cutout" -> RenderLayer.CUTOUT;
            case "translucent" -> RenderLayer.TRANSLUCENT;
            default -> RenderLayer.OPAQUE;
        };

        boolean opaque = definition.opaque != null ? definition.opaque : layer == RenderLayer.OPAQUE;
        boolean solid = definition.solid != null ? definition.solid : !definition.type.equals("cross");

        Block.Settings settings = Block.Settings.create()
                .opaque(opaque)
                .solid(solid)
                .layer(layer)
                .cullSame(definition.cull_same);

        Identifier id = Identifier.of(definition.id);
        Block block = switch (definition.type) {
            case "cross" -> new CrossBlock(id, settings, definition);
            case "slab" -> new SlabBlock(id, settings, definition);
            case "stairs" -> new StairsBlock(id, settings, definition);
            case "fence" -> new FenceBlock(id, settings, definition);
            case "pane" -> new PaneBlock(id, settings, definition);
            default -> new JsonBlock(id, settings, definition);
        };
        BlockRegistry.register(block);
    }

    private BlockLoader() {}
}