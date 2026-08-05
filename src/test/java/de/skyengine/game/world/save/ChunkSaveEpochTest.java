package de.skyengine.game.world.save;

import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.entity.BlockEntities;
import de.skyengine.game.world.block.entity.ChestBlockEntity;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ChunkSaveEpochTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void acknowledgingAnOlderEpochNeverClearsANewerMutation() {
        Chunk chunk = new Chunk(0, 0);
        chunk.markModified();
        long first = chunk.modificationEpoch();
        chunk.markModified();

        chunk.markSaved(first);

        assertTrue(chunk.isModified());
        chunk.markSaved(chunk.modificationEpoch());
        assertFalse(chunk.isModified());
    }

    @Test
    void mutationDuringAsyncWriteRemainsDirtyAndIsAbsentFromOldSnapshot() throws Exception {
        Chunk chunk = new Chunk(2, -3);
        chunk.setBlock(1, 64, 1, Blocks.STONE);
        chunk.setBlock(2, 64, 2, Blocks.CHEST);
        ChestBlockEntity chest = (ChestBlockEntity) BlockEntities.CHEST.create(
                new BlockPos(chunk.chunkX * 32 + 2, 64, chunk.chunkZ * 32 + 2),
                Blocks.getState(Blocks.CHEST));
        chest.getInventory().set(0, new ItemStack(Items.get(Identifier.of("skyengine:stone")), 1));
        chunk.setBlockEntity(2, 64, 2, chest);
        chunk.grassTintCorners = new int[33 * 33];
        chunk.foliageTintCorners = new int[33 * 33];
        chunk.grassTintCorners[0] = 0x112233;
        chunk.foliageTintCorners[0] = 0x445566;
        chunk.markModified();

        try (BlockingWorldStorage storage = new BlockingWorldStorage(this.tempDir)) {
            chunk.saveQueued = true;
            storage.enqueueSave(chunk);
            assertTrue(storage.firstWriteStarted.await(2, TimeUnit.SECONDS));

            chunk.writeLock().lock();
            try {
                chunk.setBlock(1, 64, 1, Blocks.COBBLESTONE);
                chest.getInventory().get(0).setCount(9);
                chunk.grassTintCorners[0] = 0x778899;
                chunk.foliageTintCorners[0] = 0xAABBCC;
                chunk.markModified();
            } finally {
                chunk.writeLock().unlock();
            }

            storage.allowFirstWrite.countDown();
            assertTrue(storage.firstWriteFinished.await(2, TimeUnit.SECONDS));
            waitUntilIdle(storage);
            assertTrue(chunk.isModified(), "eine neuere Mutation darf nicht clean gesetzt werden");

            Chunk firstSaved = new Chunk(chunk.chunkX, chunk.chunkZ);
            ChunkSerializer.deserialize(firstSaved, storage.firstPayload, null);
            assertEquals(Blocks.STONE, firstSaved.getBlock(1, 64, 1));
            ChestBlockEntity firstChest = (ChestBlockEntity) firstSaved.getBlockEntity(2, 64, 2);
            assertEquals(1, firstChest.getInventory().get(0).getCount());
            assertEquals(0x112233, firstSaved.grassTintCorners[0]);
            assertEquals(0x445566, firstSaved.foliageTintCorners[0]);

            chunk.saveQueued = true;
            storage.enqueueSave(chunk);
            assertTrue(storage.secondWriteFinished.await(2, TimeUnit.SECONDS));
            waitUntilIdle(storage);
            assertFalse(chunk.isModified());

            Chunk secondSaved = new Chunk(chunk.chunkX, chunk.chunkZ);
            ChunkSerializer.deserialize(secondSaved, storage.secondPayload, null);
            assertEquals(Blocks.COBBLESTONE, secondSaved.getBlock(1, 64, 1));
            ChestBlockEntity secondChest = (ChestBlockEntity) secondSaved.getBlockEntity(2, 64, 2);
            assertEquals(9, secondChest.getInventory().get(0).getCount());
            assertEquals(0x778899, secondSaved.grassTintCorners[0]);
            assertEquals(0xAABBCC, secondSaved.foliageTintCorners[0]);
        }
    }

    private static void waitUntilIdle(WorldStorage storage) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (storage.hasPendingSaves() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertFalse(storage.hasPendingSaves(), "Save-Job wurde nicht rechtzeitig beendet");
    }

    private static final class BlockingWorldStorage extends WorldStorage implements AutoCloseable {
        final CountDownLatch firstWriteStarted = new CountDownLatch(1);
        final CountDownLatch allowFirstWrite = new CountDownLatch(1);
        final CountDownLatch firstWriteFinished = new CountDownLatch(1);
        final CountDownLatch secondWriteFinished = new CountDownLatch(1);
        final AtomicInteger writes = new AtomicInteger();
        volatile byte[] firstPayload;
        volatile byte[] secondPayload;

        BlockingWorldStorage(Path directory) {
            super(directory.toFile(), null, null, "test", 1, true);
        }

        @Override
        public synchronized void writeChunk(int chunkX, int chunkZ, byte[] rawPayload) throws IOException {
            int write = this.writes.incrementAndGet();
            if (write == 1) {
                this.firstPayload = rawPayload.clone();
                this.firstWriteStarted.countDown();
                try {
                    if (!this.allowFirstWrite.await(2, TimeUnit.SECONDS)) {
                        throw new IOException("Testfreigabe fuer ersten Write fehlt");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Test-Write unterbrochen", e);
                }
                this.firstWriteFinished.countDown();
            } else {
                this.secondPayload = rawPayload.clone();
                this.secondWriteFinished.countDown();
            }
        }
    }
}
