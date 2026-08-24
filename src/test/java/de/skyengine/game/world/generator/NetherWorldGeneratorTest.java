package de.skyengine.game.world.generator;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.generator.generators.NetherWorldGenerator;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NetherWorldGeneratorTest {

    @BeforeAll
    static void bootstrap() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void generatesDeterministicClosedCavernsWithLava() {
        Chunk first = new Chunk(0, 0);
        Chunk second = new Chunk(0, 0);
        NetherWorldGenerator generator = new NetherWorldGenerator(7419);
        generator.generate(first);
        generator.generate(second);

        int air = 0;
        int lava = 0;
        for (int x = 0; x < ChunkSection.SIZE; x++) {
            for (int z = 0; z < ChunkSection.SIZE; z++) {
                assertEquals(Blocks.BEDROCK, first.getBlock(x, 0, z));
                assertEquals(Blocks.BEDROCK, first.getBlock(x, 127, z));
                for (int y = 0; y < Chunk.HEIGHT; y++) {
                    int state = first.getBlock(x, y, z);
                    assertEquals(state, second.getBlock(x, y, z));
                    if (state == Blocks.AIR) air++;
                    if (state == Blocks.LAVA) lava++;
                    if (y >= NetherWorldGenerator.ACTIVE_HEIGHT) assertEquals(Blocks.AIR, state);
                }
            }
        }
        assertTrue(air > 0, "Der Nether muss offene Hoehlen enthalten");
        assertTrue(lava > 0, "Unterhalb des Lavapegels muss Lava entstehen");
    }
}
