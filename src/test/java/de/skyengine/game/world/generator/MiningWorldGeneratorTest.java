package de.skyengine.game.world.generator;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.generator.generators.FlatMiningWorldGenerator;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MiningWorldGeneratorTest {

    @BeforeAll
    static void bootstrap() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void createsProtectedFlatSurfaceAndUndergroundCaves() {
        Chunk chunk = new Chunk(0, 0);
        new FlatMiningWorldGenerator(71).generate(chunk);

        assertEquals(Blocks.BEDROCK, chunk.getBlock(0, 0, 0));
        assertEquals(Blocks.GRASS_BLOCK,
                chunk.getBlock(0, FlatMiningWorldGenerator.SURFACE_Y, 0));
        int undergroundAir = 0;
        for (int x = 0; x < ChunkSection.SIZE; x++) {
            for (int z = 0; z < ChunkSection.SIZE; z++) {
                for (int y = 8; y <= 88; y++) {
                    if (chunk.getBlock(x, y, z) == Blocks.AIR) undergroundAir++;
                }
            }
        }
        assertTrue(undergroundAir > 0, "Miningwelt muss unterirdische Hoehlen enthalten");
    }

    @Test
    void richProfileProducesMoreOreThanNormalProfile() {
        int seed = 9917;
        Chunk normal = new Chunk(0, 0);
        Chunk rich = new Chunk(0, 0);
        new OreGeneratingWorldGenerator(new FlatMiningWorldGenerator(seed), OreProfile.normal())
                .generate(normal);
        new OreGeneratingWorldGenerator(new FlatMiningWorldGenerator(seed), OreProfile.rich())
                .generate(rich);

        assertTrue(countOre(rich) > countOre(normal),
                "Reiches Profil muss im gleichen Terrain mehr Erz erzeugen");
    }

    private static int countOre(Chunk chunk) {
        Set<Integer> ores = Set.of(Blocks.COAL_ORE, Blocks.IRON_ORE, Blocks.COPPER_ORE,
                Blocks.GOLD_ORE, Blocks.REDSTONE_ORE, Blocks.LAPIS_ORE,
                Blocks.DIAMOND_ORE, Blocks.EMERALD_ORE);
        int count = 0;
        for (int x = 0; x < ChunkSection.SIZE; x++) {
            for (int z = 0; z < ChunkSection.SIZE; z++) {
                for (int y = 0; y < Chunk.HEIGHT; y++) {
                    if (ores.contains(chunk.getBlock(x, y, z))) count++;
                }
            }
        }
        return count;
    }
}
