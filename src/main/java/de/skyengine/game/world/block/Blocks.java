package de.skyengine.game.world.block;

import de.skyengine.core.SkyEngine;
import de.skyengine.game.world.block.content.ContentSource;
import de.skyengine.game.world.block.content.ContentSources;
import de.skyengine.game.world.block.content.FileContentSource;
import com.google.gson.JsonObject;
import de.skyengine.game.world.block.entity.BlockEntities;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.json.BlockJson;
import de.skyengine.game.world.block.json.BlockLoader;
import de.skyengine.game.world.block.model.BlockStateModels;
import de.skyengine.game.world.block.model.ModelLoader;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.loot.LootTables;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Bootstrap + bequeme Konstanten. Die ints sind die Default-State-IDs
 * der jeweiligen Blöcke (deshalb funktionieren Generator, Raycast etc.
 * weiterhin ohne Änderung an deren API).
 */
public final class Blocks {

    private static List<BlockDefinition> visualDefinitions = List.of();
    private static LinkedHashMap<String, JsonObject> defaultVisualStates = new LinkedHashMap<>();

    private static final Logger LOGGER = LogManager.getLogger(Blocks.class.getName());

    public static int AIR, BEDROCK;
    public static int STONE, COBBLESTONE, OAK_PLANKS, DIRT, GRASS_BLOCK, OBSIDIAN, SNOW, OAK_LOG;
    public static int OAK_LEAVES;
    public static int GLASS, MINING_PORTAL;
    public static int NETHER_PORTAL, NETHERRACK, SOUL_SAND, SOUL_SOIL, MAGMA, GLOWSTONE;
    public static int TNT;
    public static int PISTON, STICKY_PISTON, PISTON_HEAD, MOVING_PISTON;
    public static int FERN, SHORT_GRASS, ORANGE_TULIP;

    public static int STONE_SLAB, COBBLESTONE_SLAB;
    public static int STONE_STAIRS, COBBLESTONE_STAIRS;
    public static int OAK_FENCE, GLASS_PANE, IRON_BARS;
    public static int OAK_DOOR;
    public static int CHEST;
    public static int ENCHANTING_TABLE;

    /* Gravity Blocks */
    public static int SAND, GRAVEL;

    /* Fluids */
    public static int WATER, LAVA;

    /* Stein-Varianten + Ziegel */
    public static int DIORITE, ANDESITE, GRANITE, POLISHED_DIORITE, POLISHED_ANDESITE, POLISHED_GRANITE;
    public static int STONE_BRICKS, MOSSY_STONE_BRICKS, CRACKED_STONE_BRICKS, CHISELED_STONE_BRICKS, BRICKS;

    /* Sandstein */
    public static int SANDSTONE, CHISELED_SANDSTONE, CUT_SANDSTONE;
    public static int RED_SANDSTONE, CHISELED_RED_SANDSTONE, CUT_RED_SANDSTONE;

    /* Erze */
    public static int COAL_ORE, IRON_ORE, COPPER_ORE, GOLD_ORE, REDSTONE_ORE, LAPIS_ORE, DIAMOND_ORE, EMERALD_ORE;

    /* Wolle (16 Farben) */
    public static int WHITE_WOOL, ORANGE_WOOL, MAGENTA_WOOL, LIGHT_BLUE_WOOL, YELLOW_WOOL, LIME_WOOL, PINK_WOOL, GRAY_WOOL;
    public static int LIGHT_GRAY_WOOL, CYAN_WOOL, PURPLE_WOOL, BLUE_WOOL, BROWN_WOOL, GREEN_WOOL, RED_WOOL, BLACK_WOOL;

    /* Terracotta (Basis + 16 Farben) */
    public static int TERRACOTTA, WHITE_TERRACOTTA, ORANGE_TERRACOTTA, MAGENTA_TERRACOTTA, LIGHT_BLUE_TERRACOTTA,
            YELLOW_TERRACOTTA, LIME_TERRACOTTA, PINK_TERRACOTTA, GRAY_TERRACOTTA;
    public static int LIGHT_GRAY_TERRACOTTA, CYAN_TERRACOTTA, PURPLE_TERRACOTTA, BLUE_TERRACOTTA, BROWN_TERRACOTTA,
            GREEN_TERRACOTTA, RED_TERRACOTTA, BLACK_TERRACOTTA;

    /* Basalt */
    public static int BASALT, POLISHED_BASALT, SMOOTH_BASALT;

    /* Holz: Logs (+ stripped), Planks, Leaves, Slabs, Stairs, Fences */
    public static int BIRCH_LOG, SPRUCE_LOG, SPRUCE_WOOD, DARK_OAK_LOG, ACACIA_LOG, JUNGLE_LOG, MANGROVE_LOG, PALE_OAK_LOG;
    public static int STRIPPED_BIRCH_LOG, STRIPPED_SPRUCE_LOG, STRIPPED_DARK_OAK_LOG, STRIPPED_ACACIA_LOG,
            STRIPPED_JUNGLE_LOG, STRIPPED_MANGROVE_LOG, STRIPPED_PALE_OAK_LOG;
    public static int BIRCH_PLANKS, SPRUCE_PLANKS, DARK_OAK_PLANKS, ACACIA_PLANKS, JUNGLE_PLANKS, MANGROVE_PLANKS, PALE_OAK_PLANKS;
    public static int BIRCH_LEAVES, SPRUCE_LEAVES, DARK_OAK_LEAVES, ACACIA_LEAVES, JUNGLE_LEAVES, MANGROVE_LEAVES, PALE_OAK_LEAVES;
    public static int BIRCH_SLAB, SPRUCE_SLAB, DARK_OAK_SLAB, ACACIA_SLAB, JUNGLE_SLAB, MANGROVE_SLAB, PALE_OAK_SLAB;
    public static int BIRCH_STAIRS, SPRUCE_STAIRS, DARK_OAK_STAIRS, ACACIA_STAIRS, JUNGLE_STAIRS, MANGROVE_STAIRS, PALE_OAK_STAIRS;
    public static int BIRCH_FENCE, SPRUCE_FENCE, DARK_OAK_FENCE, ACACIA_FENCE, JUNGLE_FENCE, MANGROVE_FENCE, PALE_OAK_FENCE;

    /* Vegetation */
    public static int RED_TULIP, WHITE_TULIP, PINK_TULIP, DANDELION, AZURE_BLUET, POPPY, CORNFLOWER;
    public static int TALL_GRASS, DEAD_BUSH, CACTUS;

    /* Sonstiges */
    public static int CLAY;

    /** Vor world.init() aufrufen! Lädt JSON-Blöcke und baked die Registry. */
    public static void bootstrap(File blockDirectory) {
        /* Gameplay/Kollision immer gegen den unveraenderten Default aufbauen. */
        List<String> configuredPacks = de.skyengine.core.resource.Resources.get().activePackNames();
        if (!configuredPacks.isEmpty()) de.skyengine.core.resource.Resources.activate(List.of());
        /* Luft IMMER zuerst registrieren -> State-ID 0. Chunks sind per Default 0 = leer. */
        BlockRegistry.register(new Block(Identifier.of("air"), Block.Settings.create().air()));

        /* BlockEntity-Typen registrieren, bevor Blöcke ihren block_entity-Verweis auflösen. */
        BlockEntities.bootstrap();

        /* Engine-Inhaltsquelle registrieren; Mods/Packs können vorher weitere hinzufügen. */
        File gameDir = blockDirectory.getParentFile();
        ContentSources.register(new FileContentSource(SkyEngine.GAME_PREFIX, gameDir));

        /* Inhalte aus allen Quellen laden (Blöcke, dann Modelle + Blockstates vor dem Bake).
           Die variants/multipart-Render-Sektion steckt in derselben Block-Datei — deshalb wird
           jede Quelle EINMAL aufgelöst (parent-Vererbung + ${var}) und dieselbe Map an beide
           Leser gegeben; so können Definition und Render-Sektion nicht auseinanderlaufen. */
        List<LinkedHashMap<String, JsonObject>> blockJson = new ArrayList<>();
        for (ContentSource source : ContentSources.all()) blockJson.add(BlockJson.load(source.blocks(), source.namespace()));

        List<BlockDefinition> definitions = new ArrayList<>();
        for (int i = 0; i < blockJson.size(); i++) {
            definitions.addAll(BlockLoader.load(blockJson.get(i), ContentSources.all().get(i).namespace()));
        }

        /* Reihenfolge ist zwingend: ModelLoader.load leert MODELS und CACHE, die virtuellen
           Modelle aus den Block-Definitionen müssen also danach kommen — und vor dem ersten bake. */
        visualDefinitions = List.copyOf(definitions);
        ModelLoader.loadResources();
        ModelLoader.registerBlockModels(visualDefinitions);
        preloadDynamicModelGroups();

        defaultVisualStates = new LinkedHashMap<>();
        for (LinkedHashMap<String, JsonObject> defs : blockJson) defaultVisualStates.putAll(defs);
        BlockStateModels.loadResources(defaultVisualStates);

        BlockRegistry.bake();
        de.skyengine.game.world.block.shape.Shapes.freezeDefaultModelShapes();

        /* Erst jetzt duerfen Packs die reine Darstellung ersetzen. */
        if (!configuredPacks.isEmpty()) {
            try {
                de.skyengine.core.resource.Resources.activate(configuredPacks);
                reloadVisuals();
            } catch (RuntimeException e) {
                de.skyengine.utils.logging.LogManager.getLogger(Blocks.class.getName())
                        .error("Ressourcenpakete beim Start fehlerhaft; Default bleibt aktiv", e);
                de.skyengine.core.resource.Resources.activate(List.of());
                reloadVisuals();
            }
        }
        de.skyengine.game.world.item.Items.bootstrap();
        de.skyengine.game.world.recipe.RecipeManager.bootstrap();
        LootTables.bootstrap();

        /* Abbau-Riss-Texturen in den Block-Atlas aufnehmen (vor dem TextureArray-Bau in
           ChunkRenderer.init) — der CrackRenderer holt sich die Layer-Indizes später idempotent. */
        for (int i = 0; i < 10; i++) {
            BlockTextures.layerOf("game/textures/block/destroy_stage_" + i + ".png");
        }

        AIR = idOf("air");
        BEDROCK = idOf("bedrock");

        STONE = idOf("stone");
        COBBLESTONE = idOf("cobblestone");
        DIRT = idOf("dirt");
        GRASS_BLOCK = idOf("grass_block");
        OAK_LOG = idOf("oak_log");
        OAK_PLANKS = idOf("oak_planks");
        SNOW = idOf("snow");


        OAK_LEAVES = idOf("oak_leaves");

        SAND = idOf("sand");
        GRAVEL = idOf("gravel");
        TNT = idOf("tnt");
        GLASS = idOf("glass");
        FERN = idOf("fern");
        SHORT_GRASS = idOf("short_grass");
        ORANGE_TULIP = idOf("orange_tulip");

        STONE_SLAB = idOf("stone_slab");
        COBBLESTONE_SLAB = idOf("cobblestone_slab");
        STONE_STAIRS = idOf("stone_stairs");
        COBBLESTONE_STAIRS = idOf("cobblestone_stairs");
        OAK_FENCE = idOf("oak_fence");
        GLASS_PANE = idOf("glass_pane");
        IRON_BARS = idOf("iron_bars");
        OAK_DOOR = idOf("oak_door");
        CHEST = idOf("chest");
        ENCHANTING_TABLE = idOf("enchanting_table");

        WATER = idOf("water");
        LAVA = idOf("lava");
        OBSIDIAN = idOf("obsidian");

        PISTON = idOf("piston");
        STICKY_PISTON = idOf("sticky_piston");
        PISTON_HEAD = idOf("piston_head");
        MOVING_PISTON = idOf("moving_piston");

        DIORITE = idOf("diorite");
        ANDESITE = idOf("andesite");
        GRANITE = idOf("granite");
        POLISHED_DIORITE = idOf("polished_diorite");
        POLISHED_ANDESITE = idOf("polished_andesite");
        POLISHED_GRANITE = idOf("polished_granite");
        STONE_BRICKS = idOf("stone_bricks");
        MOSSY_STONE_BRICKS = idOf("mossy_stone_bricks");
        CRACKED_STONE_BRICKS = idOf("cracked_stone_bricks");
        CHISELED_STONE_BRICKS = idOf("chiseled_stone_bricks");
        BRICKS = idOf("bricks");

        SANDSTONE = idOf("sandstone");
        CHISELED_SANDSTONE = idOf("chiseled_sandstone");
        CUT_SANDSTONE = idOf("cut_sandstone");
        RED_SANDSTONE = idOf("red_sandstone");
        CHISELED_RED_SANDSTONE = idOf("chiseled_red_sandstone");
        CUT_RED_SANDSTONE = idOf("cut_red_sandstone");

        COAL_ORE = idOf("coal_ore");
        IRON_ORE = idOf("iron_ore");
        COPPER_ORE = idOf("copper_ore");
        GOLD_ORE = idOf("gold_ore");
        REDSTONE_ORE = idOf("redstone_ore");
        LAPIS_ORE = idOf("lapis_ore");
        DIAMOND_ORE = idOf("diamond_ore");
        EMERALD_ORE = idOf("emerald_ore");

        WHITE_WOOL = idOf("white_wool");
        ORANGE_WOOL = idOf("orange_wool");
        MAGENTA_WOOL = idOf("magenta_wool");
        LIGHT_BLUE_WOOL = idOf("light_blue_wool");
        YELLOW_WOOL = idOf("yellow_wool");
        LIME_WOOL = idOf("lime_wool");
        PINK_WOOL = idOf("pink_wool");
        GRAY_WOOL = idOf("gray_wool");
        LIGHT_GRAY_WOOL = idOf("light_gray_wool");
        CYAN_WOOL = idOf("cyan_wool");
        PURPLE_WOOL = idOf("purple_wool");
        BLUE_WOOL = idOf("blue_wool");
        BROWN_WOOL = idOf("brown_wool");
        GREEN_WOOL = idOf("green_wool");
        RED_WOOL = idOf("red_wool");
        BLACK_WOOL = idOf("black_wool");

        TERRACOTTA = idOf("terracotta");
        WHITE_TERRACOTTA = idOf("white_terracotta");
        ORANGE_TERRACOTTA = idOf("orange_terracotta");
        MAGENTA_TERRACOTTA = idOf("magenta_terracotta");
        LIGHT_BLUE_TERRACOTTA = idOf("light_blue_terracotta");
        YELLOW_TERRACOTTA = idOf("yellow_terracotta");
        LIME_TERRACOTTA = idOf("lime_terracotta");
        PINK_TERRACOTTA = idOf("pink_terracotta");
        GRAY_TERRACOTTA = idOf("gray_terracotta");
        LIGHT_GRAY_TERRACOTTA = idOf("light_gray_terracotta");
        CYAN_TERRACOTTA = idOf("cyan_terracotta");
        PURPLE_TERRACOTTA = idOf("purple_terracotta");
        BLUE_TERRACOTTA = idOf("blue_terracotta");
        BROWN_TERRACOTTA = idOf("brown_terracotta");
        GREEN_TERRACOTTA = idOf("green_terracotta");
        RED_TERRACOTTA = idOf("red_terracotta");
        BLACK_TERRACOTTA = idOf("black_terracotta");

        BASALT = idOf("basalt");
        POLISHED_BASALT = idOf("polished_basalt");
        SMOOTH_BASALT = idOf("smooth_basalt");
        NETHERRACK = idOf("netherrack");
        SOUL_SAND = idOf("soul_sand");
        SOUL_SOIL = idOf("soul_soil");
        MAGMA = idOf("magma_block");
        GLOWSTONE = idOf("glowstone");

        BIRCH_LOG = idOf("birch_log");
        SPRUCE_LOG = idOf("spruce_log");
        SPRUCE_WOOD = idOf("spruce_wood");
        DARK_OAK_LOG = idOf("dark_oak_log");
        ACACIA_LOG = idOf("acacia_log");
        JUNGLE_LOG = idOf("jungle_log");
        MANGROVE_LOG = idOf("mangrove_log");
        PALE_OAK_LOG = idOf("pale_oak_log");
        STRIPPED_BIRCH_LOG = idOf("stripped_birch_log");
        STRIPPED_SPRUCE_LOG = idOf("stripped_spruce_log");
        STRIPPED_DARK_OAK_LOG = idOf("stripped_dark_oak_log");
        STRIPPED_ACACIA_LOG = idOf("stripped_acacia_log");
        STRIPPED_JUNGLE_LOG = idOf("stripped_jungle_log");
        STRIPPED_MANGROVE_LOG = idOf("stripped_mangrove_log");
        STRIPPED_PALE_OAK_LOG = idOf("stripped_pale_oak_log");
        BIRCH_PLANKS = idOf("birch_planks");
        SPRUCE_PLANKS = idOf("spruce_planks");
        DARK_OAK_PLANKS = idOf("dark_oak_planks");
        ACACIA_PLANKS = idOf("acacia_planks");
        JUNGLE_PLANKS = idOf("jungle_planks");
        MANGROVE_PLANKS = idOf("mangrove_planks");
        PALE_OAK_PLANKS = idOf("pale_oak_planks");
        BIRCH_LEAVES = idOf("birch_leaves");
        SPRUCE_LEAVES = idOf("spruce_leaves");
        DARK_OAK_LEAVES = idOf("dark_oak_leaves");
        ACACIA_LEAVES = idOf("acacia_leaves");
        JUNGLE_LEAVES = idOf("jungle_leaves");
        MANGROVE_LEAVES = idOf("mangrove_leaves");
        PALE_OAK_LEAVES = idOf("pale_oak_leaves");
        BIRCH_SLAB = idOf("birch_slab");
        SPRUCE_SLAB = idOf("spruce_slab");
        DARK_OAK_SLAB = idOf("dark_oak_slab");
        ACACIA_SLAB = idOf("acacia_slab");
        JUNGLE_SLAB = idOf("jungle_slab");
        MANGROVE_SLAB = idOf("mangrove_slab");
        PALE_OAK_SLAB = idOf("pale_oak_slab");
        BIRCH_STAIRS = idOf("birch_stairs");
        SPRUCE_STAIRS = idOf("spruce_stairs");
        DARK_OAK_STAIRS = idOf("dark_oak_stairs");
        ACACIA_STAIRS = idOf("acacia_stairs");
        JUNGLE_STAIRS = idOf("jungle_stairs");
        MANGROVE_STAIRS = idOf("mangrove_stairs");
        PALE_OAK_STAIRS = idOf("pale_oak_stairs");
        BIRCH_FENCE = idOf("birch_fence");
        SPRUCE_FENCE = idOf("spruce_fence");
        DARK_OAK_FENCE = idOf("dark_oak_fence");
        ACACIA_FENCE = idOf("acacia_fence");
        JUNGLE_FENCE = idOf("jungle_fence");
        MANGROVE_FENCE = idOf("mangrove_fence");
        PALE_OAK_FENCE = idOf("pale_oak_fence");

        RED_TULIP = idOf("red_tulip");
        WHITE_TULIP = idOf("white_tulip");
        PINK_TULIP = idOf("pink_tulip");
        DANDELION = idOf("dandelion");
        AZURE_BLUET = idOf("azure_bluet");
        POPPY = idOf("poppy");
        CORNFLOWER = idOf("cornflower");
        TALL_GRASS = idOf("tall_grass");
        DEAD_BUSH = idOf("dead_bush");
        CACTUS = idOf("cactus");

        CLAY = idOf("clay");

        MINING_PORTAL = idOf("mining_portal");
        NETHER_PORTAL = idOf("nether_portal");
        de.skyengine.game.world.dimension.WorldgenRegistries.bootstrap();
    }

    /** Laedt nur Modelle/Blockstates neu; Registry, IDs, Regeln und Kollision bleiben bestehen. */
    public static void reloadVisuals() {
        ModelLoader.loadResources();
        ModelLoader.registerBlockModels(visualDefinitions);
        preloadDynamicModelGroups();
        BlockStateModels.loadResources(defaultVisualStates);
        for (int id = 0; id < BlockRegistry.getStateCount(); id++) {
            var state = BlockRegistry.getState(id);
            state.setModel(state.getBlock().bakeModel(state));
            state.setParticleSprite(state.getBlock().bakeParticleSprite(state, state.getModel()));
            state.setOverlay(state.getBlock().bakeOverlay(state));
        }
    }

    /** Registers textures used only by block-entity model groups before the atlas is built. */
    private static void preloadDynamicModelGroups() {
        ModelLoader.preloadGroups("block/mekanism/energy_cube_basic",
                "frame",
                "bottomLEDs", "bottomPort",
                "topLEDs", "topPort",
                "frontLEDs", "frontPort",
                "backLEDs", "backPort",
                "leftLEDs", "leftPort",
                "rightLEDs", "rightPort");
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
