package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.registry.Registries;

/**
 * Registriert die mitgelieferten Archetypen. Muss vor dem Laden der Block-JSONs laufen.
 * Weitere Archetypen (fence/wall/pane/... ) kommen mit den jeweiligen Phasen hinzu.
 */
public final class Archetypes {

    private static boolean registered;

    public static void bootstrap() {
        if (registered) return;
        registered = true;

        register("cube", new CubeArchetype());
        register("slab", new SlabArchetype());
        register("stairs", new StairsArchetype());
        register("cross", new CrossArchetype());
        register("tall_cross", new TallCrossArchetype());
        register("fence", new FenceArchetype());
        register("fence_gate", new FenceGateArchetype());
        register("pane", new PaneArchetype());
        register("pillar", new PillarArchetype());
        register("fluid", new FluidArchetype());
        register("door", new DoorArchetype());
        register("trapdoor", new TrapdoorArchetype());
        register("chest", new ChestArchetype());
        register("attached", new AttachedArchetype());
        register("button", new ButtonArchetype());
        register("pressure_plate", new PressurePlateArchetype());
        register("lever", new LeverArchetype());
        register("redstone_lamp", new RedstoneLampArchetype());
        register("redstone_wire", new RedstoneWireArchetype());
        register("redstone_torch", new RedstoneTorchArchetype());
        register("repeater", new RepeaterArchetype());
        register("piston", new PistonArchetype(false));
        register("sticky_piston", new PistonArchetype(true));
        register("piston_head", new PistonHeadArchetype());
        register("moving_piston", new MovingPistonArchetype());
        register("observer", new ObserverArchetype());
        register("hopper", new HopperArchetype());
        register("comparator", new ComparatorArchetype());
        register("dispenser", new DispenserArchetype(false));
        register("dropper", new DispenserArchetype(true));
        register("custom", new CustomArchetype());
    }

    private static void register(String name, Archetype archetype) {
        Registries.BLOCK_ARCHETYPE.register(Identifier.of(name), archetype);
    }

    private Archetypes() {}
}
