package de.skyengine.game.world.generator.feature;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;

import java.util.Random;

/**
 * PLATZHALTER zum Verifizieren der Feature-Infrastruktur — wird im nächsten Schritt durch
 * echte Baum-Algorithmen ersetzt. Setzt vereinzelt große "Bäume" (Stamm + Kugel-Krone),
 * deren Kronen bewusst oft Chunk-Grenzen kreuzen, damit das Scheiben-Modell sichtbar
 * getestet wird (Baum muss auf beiden Seiten der Grenze vollständig sein).
 */
public final class TestTreeFeature implements Feature {

    @Override
    public void place(FeaturePlacer placer) {
        Random rng = placer.random();

        /* ~jeder 2. Chunk bekommt einen Baum; alle RNG-Züge in fester Reihenfolge */
        if (rng.nextBoolean()) return;

        int wx = placer.sourceMinX() + rng.nextInt(ChunkSection.SIZE);
        int wz = placer.sourceMinZ() + rng.nextInt(ChunkSection.SIZE);
        int trunkHeight = 6 + rng.nextInt(7); // 6..12
        int radius = 4 + rng.nextInt(13);     // 4..16 -> Kronen kreuzen regelmäßig Grenzen

        /* Nur auf Grasflächen (pures Sampling — keine Chunk-Reads für die Platzierung!) */
        if (placer.surfaceBlock(wx, wz) != Blocks.GRASS_BLOCK) return;

        int ground = placer.surfaceHeight(wx, wz);
        int trunkTop = ground + trunkHeight;
        if (trunkTop + radius >= Chunk.HEIGHT) return; // Krone muss unter die Weltdecke passen

        /* Stamm */
        for (int y = ground + 1; y <= trunkTop; y++) {
            placer.set(wx, y, wz, Blocks.OAK_LOG);
        }

        /* Kugel-Krone um die Stammspitze; nur in Luft, damit Stamm/Terrain erhalten bleiben */
        int r2 = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz > r2) continue;
                    placer.setIfAir(wx + dx, trunkTop + dy, wz + dz, Blocks.OAK_LEAVES);
                }
            }
        }
    }
}
