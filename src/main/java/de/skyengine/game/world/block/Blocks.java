package de.skyengine.game.world.block;

import de.skyengine.game.world.block.json.BlockLoader;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.File;

/**
 * Bootstrap + bequeme Konstanten. Die shorts sind die Default-State-IDs
 * der jeweiligen Blöcke (deshalb funktionieren Generator, Raycast etc.
 * weiterhin ohne Änderung an deren API).
 */
public final class Blocks {

    private static final Logger LOGGER = LogManager.getLogger(Blocks.class.getName());

    public static short AIR;
    public static short STONE, COBBLESTONE, DIRT, GRASS_BLOCK, SAND;
    public static short GLASS;
    public static short TNT;
    public static short FERN, SHORT_GRASS, ORANGE_TULIP;

    /** Vor world.init() aufrufen! Lädt JSON-Blöcke und baked die Registry. */
    public static void bootstrap(File blockDirectory) {
        /* Luft IMMER zuerst registrieren -> State-ID 0. Chunks sind per Default 0 = leer. */
        BlockRegistry.register(new Block(Identifier.of("skyengine:air"), Block.Settings.create().air()));

        BlockLoader.load(blockDirectory);
        BlockRegistry.bake();

        AIR = idOf("skyengine:air");

        STONE = idOf("skyengine:stone");
        COBBLESTONE = idOf("skyengine:cobblestone");
        DIRT = idOf("skyengine:dirt");
        GRASS_BLOCK = idOf("skyengine:grass_block");

        SAND = idOf("skyengine:sand");
        TNT = idOf("skyengine:tnt");
        GLASS = idOf("skyengine:glass");
        FERN = idOf("skyengine:fern");
        SHORT_GRASS = idOf("skyengine:short_grass");
        ORANGE_TULIP = idOf("skyengine:orange_tulip");
    }

    private static short idOf(String id) {
        Block block = BlockRegistry.get(Identifier.of(id));
        if (block == null) {
            LOGGER.warning("Block nicht gefunden, Fallback auf Luft: " + id);
            return 0;
        }
        return block.getDefaultState().getId();
    }

    /* --- Hot-Path-Helfer (Kollision, Mesher, Raycast) --- */

    public static boolean isSolid(short stateId) {
        return BlockRegistry.getState(stateId).isSolid();
    }

    public static boolean isOpaque(short stateId) {
        return BlockRegistry.getState(stateId).isOpaqueCube();
    }

    public static BlockState getState(short stateId) {
        return BlockRegistry.getState(stateId);
    }

    private Blocks() {}
}