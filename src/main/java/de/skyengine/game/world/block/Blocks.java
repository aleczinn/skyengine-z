package de.skyengine.game.world.block;

import de.skyengine.game.world.block.content.ContentSource;
import de.skyengine.game.world.block.content.ContentSources;
import de.skyengine.game.world.block.content.FileContentSource;
import de.skyengine.game.world.block.json.BlockLoader;
import de.skyengine.game.world.block.model.BlockStateModels;
import de.skyengine.game.world.block.model.ModelLoader;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.File;

/**
 * Bootstrap + bequeme Konstanten. Die ints sind die Default-State-IDs
 * der jeweiligen Blöcke (deshalb funktionieren Generator, Raycast etc.
 * weiterhin ohne Änderung an deren API).
 */
public final class Blocks {

    private static final Logger LOGGER = LogManager.getLogger(Blocks.class.getName());

    public static int AIR;
    public static int BEDROCK;
    public static int STONE, OAK_PLANKS, COBBLESTONE, DIRT, GRASS_BLOCK, SAND;
    public static int OAK_LEAVES;
    public static int GLASS;
    public static int TNT;
    public static int FERN, SHORT_GRASS, ORANGE_TULIP;

    /* Phase 2: Custom Models */
    public static int STONE_SLAB, COBBLESTONE_SLAB;
    public static int STONE_STAIRS, COBBLESTONE_STAIRS;
    public static int OAK_FENCE, GLASS_PANE, IRON_BARS;
    public static int OAK_DOOR;
    public static int CHEST;
    public static int ENCHANTING_TABLE;

    /* Fluids + Reaktionsprodukt */
    public static int WATER, LAVA, OBSIDIAN;

    /** Vor world.init() aufrufen! Lädt JSON-Blöcke und baked die Registry. */
    public static void bootstrap(File blockDirectory) {
        /* Luft IMMER zuerst registrieren -> State-ID 0. Chunks sind per Default 0 = leer. */
        BlockRegistry.register(new Block(Identifier.of("skyengine:air"), Block.Settings.create().air()));

        /* BlockEntity-Typen registrieren, bevor Blöcke ihren block_entity-Verweis auflösen. */
        de.skyengine.game.world.block.entity.BlockEntities.bootstrap();

        /* Engine-Inhaltsquelle registrieren; Mods/Packs können vorher weitere hinzufügen. */
        File gameDir = blockDirectory.getParentFile();
        ContentSources.register(new FileContentSource("skyengine", gameDir));

        /* Inhalte aus allen Quellen laden (Blöcke, dann Modelle + Blockstates vor dem Bake).
           Die variants/multipart-Render-Sektion steckt in derselben Block-Datei. */
        for (ContentSource source : ContentSources.all()) BlockLoader.load(source.blocks());
        for (ContentSource source : ContentSources.all()) ModelLoader.load(source.models());
        for (ContentSource source : ContentSources.all()) BlockStateModels.load(source.blocks());

        BlockRegistry.bake();
        de.skyengine.game.world.item.Items.bootstrap();

        AIR = idOf("skyengine:air");
        BEDROCK = idOf("skyengine:bedrock");

        STONE = idOf("skyengine:stone");
        COBBLESTONE = idOf("skyengine:cobblestone");
        DIRT = idOf("skyengine:dirt");
        GRASS_BLOCK = idOf("skyengine:grass_block");
        OAK_PLANKS = idOf("skyengine:oak_planks");
        OAK_LEAVES = idOf("skyengine:oak_leaves");

        SAND = idOf("skyengine:sand");
        TNT = idOf("skyengine:tnt");
        GLASS = idOf("skyengine:glass");
        FERN = idOf("skyengine:fern");
        SHORT_GRASS = idOf("skyengine:short_grass");
        ORANGE_TULIP = idOf("skyengine:orange_tulip");

        STONE_SLAB = idOf("skyengine:stone_slab");
        COBBLESTONE_SLAB = idOf("skyengine:cobblestone_slab");
        STONE_STAIRS = idOf("skyengine:stone_stairs");
        COBBLESTONE_STAIRS = idOf("skyengine:cobblestone_stairs");
        OAK_FENCE = idOf("skyengine:oak_fence");
        GLASS_PANE = idOf("skyengine:glass_pane");
        IRON_BARS = idOf("skyengine:iron_bars");
        OAK_DOOR = idOf("skyengine:oak_door");
        CHEST = idOf("skyengine:chest");
        ENCHANTING_TABLE = idOf("skyengine:enchanting_table");

        WATER = idOf("skyengine:water");
        LAVA = idOf("skyengine:lava");
        OBSIDIAN = idOf("skyengine:obsidian");
    }

    private static int idOf(String id) {
        Block block = BlockRegistry.get(Identifier.of(id));
        if (block == null) {
            LOGGER.warning("Block nicht gefunden, Fallback auf Luft: " + id);
            return 0;
        }
        return block.getDefaultState().getId();
    }

    /* --- Hot-Path-Helfer (Kollision, Mesher, Raycast) --- */

    public static boolean isSolid(int stateId) {
        return BlockRegistry.getState(stateId).isSolid();
    }

    public static boolean isOpaque(int stateId) {
        return BlockRegistry.getState(stateId).isOpaqueCube();
    }

    public static BlockState getState(int stateId) {
        return BlockRegistry.getState(stateId);
    }

    /** true, wenn ein fallender Block diese Zelle einnehmen darf (Luft oder Fluid). */
    public static boolean canFallInto(int stateId) {
        return stateId == AIR || getState(stateId).isFluid();
    }

    private Blocks() {}
}