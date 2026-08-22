package de.skyengine.game.world.block;

import de.skyengine.game.world.block.registry.Registries;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.StateFlags;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.util.ArrayList;
import java.util.List;

public final class BlockRegistry {

    private static final Logger LOGGER = LogManager.getLogger(BlockRegistry.class.getName());

    /* volatile: wird auf dem Render-Thread gebaut, von Worker-Threads (Mesher) gelesen */
    private static volatile BlockState[] statesById = new BlockState[0];
    private static boolean baked = false;
    private static boolean hasRandomTick = false;

    public static <T extends Block> T register(T block) {
        if (baked) throw new IllegalStateException("Registry ist bereits gebaked!");
        Registries.BLOCK.register(block.getIdentifier(), block);
        return block;
    }

    public static Block get(Identifier identifier) {
        return Registries.BLOCK.get(identifier);
    }

    /**
     * Vergibt allen BlockStates sequenzielle Runtime-IDs und backt die Modelle.
     * Muss VOR dem ersten Chunk-Zugriff laufen. Luft muss als erster Block
     * registriert sein, damit State-ID 0 = Luft gilt (Chunks sind default 0).
     */
    public static void bake() {
        List<BlockState> all = new ArrayList<>();

        for (Block block : Registries.BLOCK.values()) {
            for (BlockState state : block.getStates()) {
                state.setId(all.size());
                all.add(state);
            }
        }

        if (all.isEmpty() || !all.get(0).isAir()) {
            throw new IllegalStateException("State-ID 0 muss Luft sein - Luft zuerst registrieren!");
        }

        /* Hot-Path-Flags packen + Modelle backen (registriert dabei die Texturen). */
        for (BlockState state : all) {
            int f = computeFlags(state);
            state.setFlags(f);
            if ((f & StateFlags.TICKS_RANDOMLY) != 0) hasRandomTick = true;
            state.setModel(state.getBlock().bakeModel(state));
            state.setParticleSprite(state.getBlock().bakeParticleSprite(state, state.getModel()));
            state.setOverlay(state.getBlock().bakeOverlay(state));
        }

        statesById = all.toArray(new BlockState[0]);
        Registries.BLOCK.freeze();
        baked = true;

        LOGGER.info("BlockRegistry gebaked: " + Registries.BLOCK.size() + " Blöcke, " + all.size() + " States");
    }

    /** Berechnet die gepackten {@link StateFlags} eines States aus seinem Block. */
    private static int computeFlags(BlockState state) {
        Block block = state.getBlock();
        int flags = 0;
        if (block.isOpaqueCube(state)) flags |= StateFlags.OPAQUE_CUBE;
        if (block.occludesAo(state)) flags |= StateFlags.AO_OCCLUDER;
        if (block.isRedstoneConductor(state)) flags |= StateFlags.REDSTONE_CONDUCTOR;
        if (block.isSolid(state)) flags |= StateFlags.SOLID;
        if (block.cullsSameBlock()) flags |= StateFlags.CULL_SAME;
        if (block.hasRandomOffset(state)) flags |= StateFlags.RANDOM_OFFSET;
        if (block.getBlockEntityType() != null) flags |= StateFlags.HAS_BLOCK_ENTITY;
        if (block.ticksRandomly()) flags |= StateFlags.TICKS_RANDOMLY;
        if (block.isFluid()) flags |= StateFlags.FLUID;
        if (block.isExcludedFromLodSurface()) flags |= StateFlags.NO_LOD_SURFACE;
        if (block.isLeaves()) flags |= StateFlags.LEAVES;
        flags = StateFlags.packOpacity(flags, block.getLightOpacity(state));
        flags = StateFlags.packLuminance(flags, block.getLuminance(state));
        return StateFlags.packLayer(flags, block.getRenderLayer(state));
    }

    public static BlockState getState(int id) {
        return statesById[id];
    }

    public static int getStateCount() {
        return statesById.length;
    }

    /** true, wenn mindestens ein Block am Random-Tick teilnimmt - sonst kann der Pass entfallen. */
    public static boolean hasRandomTickBlocks() {
        return hasRandomTick;
    }

    private BlockRegistry() {}
}
