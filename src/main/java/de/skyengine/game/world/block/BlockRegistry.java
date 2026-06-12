package de.skyengine.game.world.block;

import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class BlockRegistry {

    private static final Logger LOGGER = LogManager.getLogger(BlockRegistry.class.getName());

    private static final Map<Identifier, Block> BLOCKS = new LinkedHashMap<>();

    /* volatile: wird auf dem Render-Thread gebaut, von Worker-Threads (Mesher) gelesen */
    private static volatile BlockState[] statesById = new BlockState[0];
    private static boolean baked = false;

    public static <T extends Block> T register(T block) {
        if (baked) throw new IllegalStateException("Registry ist bereits gebaked!");
        if (BLOCKS.containsKey(block.getIdentifier())) {
            throw new IllegalStateException("Block doppelt registriert: " + block.getIdentifier());
        }
        BLOCKS.put(block.getIdentifier(), block);
        return block;
    }

    public static Block get(Identifier identifier) {
        return BLOCKS.get(identifier);
    }

    /**
     * Vergibt allen BlockStates sequenzielle Runtime-IDs und backt die Modelle.
     * Muss VOR dem ersten Chunk-Zugriff laufen. Luft muss als erster Block
     * registriert sein, damit State-ID 0 = Luft gilt (Chunks sind default 0).
     */
    public static void bake() {
        List<BlockState> all = new ArrayList<>();

        for (Block block : BLOCKS.values()) {
            for (BlockState state : block.getStates()) {
                if (all.size() > 0xFFFF) {
                    throw new IllegalStateException("Mehr als 65536 BlockStates - Zeit für Paletten-Storage!");
                }
                state.setId((short) all.size());
                all.add(state);
            }
        }

        if (all.isEmpty() || !all.get(0).isAir()) {
            throw new IllegalStateException("State-ID 0 muss Luft sein - Luft zuerst registrieren!");
        }

        /* Modelle backen (registriert dabei die Texturen in BlockTextures) */
        for (BlockState state : all) {
            state.setModel(state.getBlock().bakeModel(state));
        }

        statesById = all.toArray(new BlockState[0]);
        baked = true;

        LOGGER.info("BlockRegistry gebaked: " + BLOCKS.size() + " Blöcke, " + all.size() + " States");
    }

    public static BlockState getState(short id) {
        return statesById[id & 0xFFFF];
    }

    public static int getStateCount() {
        return statesById.length;
    }

    private BlockRegistry() {}
}