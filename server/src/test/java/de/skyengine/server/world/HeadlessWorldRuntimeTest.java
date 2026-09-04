package de.skyengine.server.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadlessWorldRuntimeTest {
    @TempDir Path temporaryDirectory;

    @Test
    void exclusiveWorldLockPreventsConcurrentAuthoritativeServers() throws Exception {
        Path world = this.temporaryDirectory.resolve("world");
        try (HeadlessWorldRuntime first = new HeadlessWorldRuntime(world)) {
            assertThrows(Exception.class, () -> new HeadlessWorldRuntime(world));
            first.tick(1);
            first.autosave(1);
        }
        try (HeadlessWorldRuntime ignored = new HeadlessWorldRuntime(world)) {
            // Lock is released on a clean shutdown.
        }
    }

    @Test
    void generatedColumnsAreRenderableAndSeedSurvivesRestart() throws Exception {
        Path world = this.temporaryDirectory.resolve("generated");
        long seed;
        try (HeadlessWorldRuntime runtime = new HeadlessWorldRuntime(world, 2)) {
            seed = runtime.seed();
            var snapshot = runtime.requestChunkSnapshot(HeadlessWorldRuntime.OVERWORLD, 0, 0)
                    .toCompletableFuture().get().orElseThrow();
            assertFalse(snapshot.sections().isEmpty());
            assertTrue(snapshot.sections().stream().mapToInt(section -> section.nonAir()).sum() > 0);
            assertTrue(snapshot.height(0) > 1);
            assertEquals("voxelstories:air",
                    runtime.registryMappings().getFirst().identifiers().getFirst());
            assertTrue(runtime.requestChunkSnapshot("skyengine:missing", 0, 0)
                    .toCompletableFuture().get().isEmpty());
        }
        try (HeadlessWorldRuntime reopened = new HeadlessWorldRuntime(world, 1)) {
            assertEquals(seed, reopened.seed());
        }
    }
}
