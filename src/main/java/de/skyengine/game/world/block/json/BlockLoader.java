package de.skyengine.game.world.block.json;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.RenderLayer;
import de.skyengine.game.world.block.archetype.Archetype;
import de.skyengine.game.world.block.archetype.ArchetypeBlockFactory;
import de.skyengine.game.world.block.archetype.Archetypes;
import de.skyengine.game.world.block.registry.Registries;
import de.skyengine.game.world.item.CreativeTabs;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class BlockLoader {

    private static final Logger LOGGER = LogManager.getLogger(BlockLoader.class.getName());
    private static final Gson GSON = new Gson();

    /**
     * Registriert die von {@link BlockJson} aufgelösten Blockdefinitionen. Die Iterations-
     * reihenfolge der Map ist die alte Dateisortierung und bestimmt die Runtime-State-IDs.
     */
    public static List<BlockDefinition> load(Map<String, JsonObject> definitions) {
        List<BlockDefinition> loaded = new ArrayList<>();
        if (definitions.isEmpty()) return loaded;

        /* Archetypen bereitstellen, bevor Definitionen aufgelöst werden. */
        Archetypes.bootstrap();

        for (Map.Entry<String, JsonObject> entry : definitions.entrySet()) {
            try {
                BlockDefinition definition = GSON.fromJson(entry.getValue(), BlockDefinition.class);
                if (definition.id == null || definition.id.isBlank()) {
                    LOGGER.error("Block-Definition ohne 'id': " + entry.getKey());
                    continue;
                }
                register(definition);
                loaded.add(definition);
            } catch (Exception e) {
                LOGGER.error("Fehlerhafte Block-Definition: " + entry.getKey(), e);
            }
        }
        LOGGER.info(loaded.size() + " Block-Definitionen geladen");
        return loaded;
    }

    private static void register(BlockDefinition definition) {
        RenderLayer layer = switch (definition.layer.toLowerCase()) {
            case "cutout" -> RenderLayer.CUTOUT;
            case "translucent" -> RenderLayer.TRANSLUCENT;
            default -> RenderLayer.OPAQUE;
        };

        String archetype = definition.archetype != null ? definition.archetype : definition.type;

        boolean opaque = definition.opaque != null ? definition.opaque : layer == RenderLayer.OPAQUE;
        boolean solid = definition.solid != null ? definition.solid : !archetype.equals("cross");

        Block.Settings settings = Block.Settings.create()
                .opaque(opaque)
                .solid(solid)
                .layer(layer)
                .cullSame(definition.cull_same)
                .noLodSurface(definition.no_lod_surface)
                .leaves(definition.leaves);

        Identifier id = Identifier.of(definition.id);

        /* Datengetriebener Archetyp; unbekannter Typ fällt auf einen schlichten JSON-Block zurück. */
        Archetype arch = Registries.BLOCK_ARCHETYPE.get(Identifier.of(archetype));
        Block block = arch != null
                ? ArchetypeBlockFactory.create(arch, id, settings, definition)
                : new JsonBlock(id, settings, definition);
        BlockRegistry.register(block);

        /* Creative-Tabs: das BlockItem entsteht erst in Items.bootstrap(), trägt dann aber
           dieselbe Identifier — die Zuordnung darf deshalb schon hier gemeldet werden. */
        CreativeTabs.assign(id, CreativeTabs.parse(definition.creative_tab));
    }

    private BlockLoader() {}
}