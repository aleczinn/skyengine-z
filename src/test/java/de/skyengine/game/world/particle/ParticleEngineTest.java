package de.skyengine.game.world.particle;

import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.world.block.BlockTextures;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.FloatBuffer;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ParticleEngineTest {

    private final GameSettings.ParticleQuality previousQuality = GameSettings.get().particleQuality;

    @BeforeAll
    static void bootstrapBlocks() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @AfterEach
    void restoreSettings() {
        GameSettings.get().particleQuality = this.previousQuality;
    }

    @Test
    void poolNeverExceedsConfiguredCapacityAndParticlesExpire() {
        GameSettings.get().particleQuality = GameSettings.ParticleQuality.ALL;
        ParticleEngine engine = new ParticleEngine();

        for (int i = 0; i < ParticleEngine.MAX_PARTICLES + 500; i++) {
            engine.smoke(0, 1, -2, false, ParticlePriority.NORMAL);
        }

        assertEquals(ParticleEngine.MAX_PARTICLES, engine.count());
        for (int i = 0; i < 50; i++) engine.tick();
        assertEquals(0, engine.count());
    }

    @Test
    void loweringQualityTrimsAnExistingPoolOnTheNextTick() {
        GameSettings.get().particleQuality = GameSettings.ParticleQuality.ALL;
        ParticleEngine engine = new ParticleEngine();
        for (int i = 0; i < 3_000; i++) {
            engine.smoke(0, 1, -2, false, ParticlePriority.NORMAL);
        }

        GameSettings.get().particleQuality = GameSettings.ParticleQuality.MINIMAL;
        engine.tick();

        assertTrue(engine.count() <= GameSettings.ParticleQuality.MINIMAL.capacity);
    }

    @Test
    void ambientParticlesCannotDisplaceAFullCriticalPool() {
        GameSettings.get().particleQuality = GameSettings.ParticleQuality.ALL;
        ParticleEngine engine = new ParticleEngine();
        for (int i = 0; i < ParticleEngine.MAX_PARTICLES; i++) {
            engine.smoke(0, 1, -2, false, ParticlePriority.CRITICAL);
        }
        long rejectedBefore = engine.rejected();

        engine.smoke(0, 1, -2, false, ParticlePriority.AMBIENT);

        assertEquals(ParticleEngine.MAX_PARTICLES, engine.count());
        assertEquals(rejectedBefore + 1, engine.rejected());
    }

    @Test
    void grassBlockUsesItsDeclaredUntintedDirtParticle() {
        var sprite = Blocks.getState(Blocks.GRASS_BLOCK).getParticleSprite();

        assertEquals(BlockTextures.layerOf("game/textures/block/dirt.png"), sprite.textureLayer());
        assertEquals(BakedQuad.WHITE, sprite.tint());
        assertEquals(BakedQuad.TINT_NONE, sprite.tintType());
    }

    @Test
    void blockBreakUsesGridSpriteDarkeningAndZeroRotation() {
        GameSettings.get().particleQuality = GameSettings.ParticleQuality.ALL;
        ParticleEngine engine = new ParticleEngine(new Random(0x5EED));
        engine.blockBreak(0, 0, -3, Blocks.getState(Blocks.DIRT));

        assertEquals(64, engine.count());
        Camera camera = new Camera();
        camera.update(1.0);
        FloatBuffer instances = FloatBuffer.allocate(ParticleEngine.MAX_PARTICLES
                * ParticleEngine.INSTANCE_FLOATS);
        assertEquals(64, engine.writeInstances(instances, camera, 0F, false));
        assertEquals(0F, instances.get(4));
        assertEquals(Blocks.getState(Blocks.DIRT).getParticleSprite().textureLayer(),
                (int) instances.get(9));
        assertEquals(0.6F, instances.get(10), 0.005F);
        assertEquals(0.6F, instances.get(11), 0.005F);
        assertEquals(0.6F, instances.get(12), 0.005F);

        float minSize = Float.POSITIVE_INFINITY;
        float maxSize = Float.NEGATIVE_INFINITY;
        float initialAverageY = 0F;
        for (int i = 0; i < 64; i++) {
            int base = i * ParticleEngine.INSTANCE_FLOATS;
            float particleSize = instances.get(base + 3);
            minSize = Math.min(minSize, particleSize);
            maxSize = Math.max(maxSize, particleSize);
            initialAverageY += instances.get(base + 1);
            assertEquals(0.25F, instances.get(base + 7) - instances.get(base + 5), 0.00001F);
            assertEquals(0.25F, instances.get(base + 8) - instances.get(base + 6), 0.00001F);
        }
        assertTrue(minSize >= 0.05F);
        assertTrue(maxSize < 0.10F);
        assertTrue(maxSize - minSize > 0.02F);

        engine.tick();
        FloatBuffer moved = FloatBuffer.allocate(ParticleEngine.MAX_PARTICLES
                * ParticleEngine.INSTANCE_FLOATS);
        assertEquals(64, engine.writeInstances(moved, camera, 1F, false));
        float movedAverageY = 0F;
        for (int i = 0; i < 64; i++) {
            movedAverageY += moved.get(i * ParticleEngine.INSTANCE_FLOATS + 1);
        }
        assertTrue(movedAverageY > initialAverageY,
                "Minecrafts +0.1-Impuls muss die Partikelwolke anfangs anheben");

        for (int i = 1; i < 40; i++) engine.tick();
        assertEquals(0, engine.count());
    }

    @Test
    void blockBreakKeepsMinecraftGridAtMinimalQuality() {
        GameSettings.get().particleQuality = GameSettings.ParticleQuality.MINIMAL;
        ParticleEngine engine = new ParticleEngine(new Random(7));

        engine.blockBreak(0, 0, -3, Blocks.getState(Blocks.DIRT));

        assertEquals(64, engine.count());
    }

    @Test
    void multipartBlockBreakParticlesStayInsideIndividualShapeBoxes() {
        GameSettings.get().particleQuality = GameSettings.ParticleQuality.ALL;
        ParticleEngine engine = new ParticleEngine(new Random(9));
        var state = Blocks.getState(Blocks.STONE_STAIRS);
        engine.blockBreak(0, 0, -3, state);

        Camera camera = new Camera();
        camera.update(1.0);
        FloatBuffer instances = FloatBuffer.allocate(ParticleEngine.MAX_PARTICLES
                * ParticleEngine.INSTANCE_FLOATS);
        int written = engine.writeInstances(instances, camera, 0F, false);
        assertTrue(written > 0);
        assertTrue(state.getOutlineShape().boxes().length > 1);
        for (int i = 0; i < written; i++) {
            int base = i * ParticleEngine.INSTANCE_FLOATS;
            double x = instances.get(base) + camera.getPosition().x;
            double y = instances.get(base + 1) + camera.getPosition().y;
            double z = instances.get(base + 2) + camera.getPosition().z + 3.0;
            boolean inside = false;
            for (var box : state.getOutlineShape().boxes()) {
                if (x >= box.minX && x <= box.maxX && y >= box.minY && y <= box.maxY
                        && z >= box.minZ && z <= box.maxZ) {
                    inside = true;
                    break;
                }
            }
            assertTrue(inside, "Partikel darf nicht in der Luecke einer zusammengesetzten Form liegen");
        }
    }
}
