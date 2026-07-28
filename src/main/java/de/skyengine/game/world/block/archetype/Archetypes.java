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
        register("pane", new PaneArchetype());
        register("pillar", new PillarArchetype());
        register("fluid", new FluidArchetype());
        register("door", new DoorArchetype());
        register("chest", new ChestArchetype());
        register("attached", new AttachedArchetype());
        register("custom", new CustomArchetype());
    }

    private static void register(String name, Archetype archetype) {
        Registries.BLOCK_ARCHETYPE.register(Identifier.of(name), archetype);
    }

    private Archetypes() {}
}
