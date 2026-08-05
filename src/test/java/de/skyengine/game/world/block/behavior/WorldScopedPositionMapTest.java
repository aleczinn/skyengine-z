package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.save.LevelData;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class WorldScopedPositionMapTest {

    @Test
    void samePositionIsIsolatedBetweenWorlds() throws Exception {
        TestWorld first = new TestWorld("first");
        TestWorld second = new TestWorld("second");
        first.install(new Chunk(0, 0));
        second.install(new Chunk(0, 0));
        WorldScopedPositionMap<String> states = new WorldScopedPositionMap<>();

        states.put(first, 3, 64, 7, "erste Welt");
        states.put(second, 3, 64, 7, "zweite Welt");

        assertEquals("erste Welt", states.get(first, 3, 64, 7));
        assertEquals("zweite Welt", states.get(second, 3, 64, 7));
    }

    @Test
    void replacementChunkCannotInheritStateAtTheSameCoordinates() throws Exception {
        TestWorld world = new TestWorld("replacement");
        world.install(new Chunk(2, -1));
        WorldScopedPositionMap<Integer> states = new WorldScopedPositionMap<>();
        states.put(world, 65, 80, -1, 42);

        world.install(new Chunk(2, -1));

        assertNull(states.get(world, 65, 80, -1));
    }

    @Test
    void worldPrunesRegisteredStatesAfterChunkRemoval() throws Exception {
        TestWorld world = new TestWorld("unload");
        Chunk chunk = new Chunk(-2, 4);
        world.install(chunk);
        WorldScopedPositionMap<Integer> states = new WorldScopedPositionMap<>();
        states.put(world, -33, 90, 128, 7);
        Map<Long, ?> diagnosticView = states.diagnosticEntries(world);
        assertEquals(1, diagnosticView.size());

        world.removeAndBumpVersion(chunk);
        world.pruneTransientPositionStates();

        assertEquals(0, diagnosticView.size());
    }

    private static final class TestWorld extends World {
        private static final Field CHUNKS_FIELD;
        private static final Field REMOVAL_VERSION_FIELD;
        private static final Method PRUNE_TRANSIENT;

        static {
            try {
                CHUNKS_FIELD = ChunkManager.class.getDeclaredField("chunks");
                CHUNKS_FIELD.setAccessible(true);
                REMOVAL_VERSION_FIELD = ChunkManager.class.getDeclaredField("chunkRemovalVersion");
                REMOVAL_VERSION_FIELD.setAccessible(true);
                PRUNE_TRANSIENT = World.class.getDeclaredMethod("pruneTransientPositionStates");
                PRUNE_TRANSIENT.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private final ChunkManager manager;

        TestWorld(String name) throws ReflectiveOperationException {
            super("__world_scoped_state_" + name, level(name), null, null);
            Field field = World.class.getDeclaredField("chunkManager");
            field.setAccessible(true);
            this.manager = (ChunkManager) field.get(this);
        }

        @SuppressWarnings("unchecked")
        void install(Chunk chunk) throws IllegalAccessException {
            ((Map<Long, Chunk>) CHUNKS_FIELD.get(this.manager))
                    .put(Chunk.key(chunk.chunkX, chunk.chunkZ), chunk);
        }

        @SuppressWarnings("unchecked")
        void removeAndBumpVersion(Chunk chunk) throws IllegalAccessException {
            ((Map<Long, Chunk>) CHUNKS_FIELD.get(this.manager))
                    .remove(Chunk.key(chunk.chunkX, chunk.chunkZ));
            REMOVAL_VERSION_FIELD.setInt(this.manager, this.manager.getChunkRemovalVersion() + 1);
        }

        void pruneTransientPositionStates() throws ReflectiveOperationException {
            PRUNE_TRANSIENT.invoke(this);
        }

        private static LevelData level(String name) {
            LevelData level = new LevelData();
            level.name = name;
            level.seed = 1;
            level.worldType = "imported";
            return level;
        }
    }
}
