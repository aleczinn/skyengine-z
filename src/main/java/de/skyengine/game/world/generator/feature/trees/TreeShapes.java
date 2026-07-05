package de.skyengine.game.world.generator.feature.trees;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.generator.biome.Biome;
import de.skyengine.game.world.generator.feature.FeaturePlacer;

import java.util.Random;

/**
 * Statische Baumformen fuer den Feature-Pass. Alle Formen halten den Scheiben-Vertrag ein
 * (pure Funktion aus Position + RNG, Blaetter nur via setIfAir) und bleiben unter dem
 * 32-Block-Overreach-Limit (groesster Kronenradius: Redwood 6).
 *
 * <p>Platzhalter-Texturen (bewusst, bis eigene Assets existieren): Redwood nutzt Spruce-Bloecke,
 * Palme nutzt Jungle-Bloecke.
 */
public final class TreeShapes {

    /** Klassische Eiche: Stamm 4-6, Kugelkrone 5x5 unten / 3x3 oben. */
    public static final TreeShape OAK = (placer, x, y, z, rng) ->
            classicTree(placer, x, y, z, rng, Blocks.OAK_LOG, Blocks.OAK_LEAVES, 4 + rng.nextInt(3));

    /** Birke: gleiche Form wie Eiche, etwas hoeher. */
    public static final TreeShape BIRCH = (placer, x, y, z, rng) ->
            classicTree(placer, x, y, z, rng, Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES, 5 + rng.nextInt(3));

    /** Fichte: konische Krone aus abwechselnd breiten Blattringen, Spitze ueber dem Stamm. */
    public static final TreeShape SPRUCE = (placer, x, y, z, rng) -> {
        int height = 7 + rng.nextInt(4);
        if (!fits(y, height + 2)) return;
        for (int i = 0; i < height; i++) placer.set(x, y + i, z, Blocks.SPRUCE_LOG);

        /* Blattringe von unten (radius 2) nach oben (radius 1/0), alternierend */
        int start = y + 2 + rng.nextInt(2);
        for (int ly = start; ly < y + height; ly++) {
            int radius = ((y + height - ly) % 2 == 0) ? 1 : 2;
            disk(placer, x, ly, z, radius, Blocks.SPRUCE_LEAVES, false);
        }
        placer.setIfAir(x, y + height, z, Blocks.SPRUCE_LEAVES);
        placer.setIfAir(x, y + height + 1, z, Blocks.SPRUCE_LEAVES);
    };

    /** Akazie: kurzer Stamm, flache Schirm-Krone (2 duenne Scheiben radius 3/2). */
    public static final TreeShape ACACIA = (placer, x, y, z, rng) -> {
        int height = 4 + rng.nextInt(3);
        if (!fits(y, height + 2)) return;
        for (int i = 0; i < height; i++) placer.set(x, y + i, z, Blocks.ACACIA_LOG);
        disk(placer, x, y + height, z, 3, Blocks.ACACIA_LEAVES, true);
        disk(placer, x, y + height + 1, z, 2, Blocks.ACACIA_LEAVES, true);
    };

    /** Jungle-Baum: hoher schlanker Stamm, kompakte Krone nahe der Spitze. */
    public static final TreeShape JUNGLE = (placer, x, y, z, rng) -> {
        int height = 8 + rng.nextInt(5);
        if (!fits(y, height + 3)) return;
        for (int i = 0; i < height; i++) placer.set(x, y + i, z, Blocks.JUNGLE_LOG);
        disk(placer, x, y + height - 1, z, 2, Blocks.JUNGLE_LEAVES, true);
        disk(placer, x, y + height, z, 2, Blocks.JUNGLE_LEAVES, true);
        disk(placer, x, y + height + 1, z, 1, Blocks.JUNGLE_LEAVES, false);
    };

    /** Redwood: 2x2-Stamm, 25-40 hoch, konische Krone im oberen Drittel (Platzhalter: Spruce). */
    public static final TreeShape REDWOOD = (placer, x, y, z, rng) -> {
        int height = 25 + rng.nextInt(16);
        if (!fits(y, height + 3)) return;
        for (int i = 0; i < height; i++) {
            placer.set(x, y + i, z, Blocks.SPRUCE_LOG);
            placer.set(x + 1, y + i, z, Blocks.SPRUCE_LOG);
            placer.set(x, y + i, z + 1, Blocks.SPRUCE_LOG);
            placer.set(x + 1, y + i, z + 1, Blocks.SPRUCE_LOG);
        }

        /* Krone: Ringe um das 2x2-Zentrum, unten radius ~5, nach oben schmaler */
        int crownStart = y + height * 2 / 3;
        for (int ly = crownStart; ly < y + height; ly++) {
            float t = (ly - crownStart) / (float) (y + height - crownStart); // 0 unten .. 1 oben
            int radius = Math.max(1, Math.round(5F * (1F - t)));
            disk2x2(placer, x, ly, z, radius, Blocks.SPRUCE_LEAVES);
        }
        disk2x2(placer, x, y + height, z, 1, Blocks.SPRUCE_LEAVES);
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                placer.setIfAir(x + dx, y + height + 1, z + dz, Blocks.SPRUCE_LEAVES);
            }
        }
    };

    /** Palme: leicht gebogener Stamm, Blattfaecher mit haengenden Spitzen (Platzhalter: Jungle). */
    public static final TreeShape PALM = (placer, x, y, z, rng) -> {
        int height = 6 + rng.nextInt(4);
        if (!fits(y, height + 2)) return;

        /* Biegung: obere Haelfte kippt 1-2 Bloecke in eine zufaellige Richtung */
        int dirX = rng.nextInt(3) - 1;
        int dirZ = (dirX == 0) ? (rng.nextBoolean() ? 1 : -1) : 0;
        int lean = 1 + rng.nextInt(2);
        int tx = x, tz = z;
        for (int i = 0; i < height; i++) {
            if (i > height / 2 && (tx - x) * dirX + (tz - z) * dirZ < lean) {
                tx += dirX;
                tz += dirZ;
            }
            placer.set(tx, y + i, tz, Blocks.JUNGLE_LOG);
        }

        /* Faecher: 4 Wedel als Kreuz (Laenge 3), Spitzen haengen einen Block herab */
        int topY = y + height;
        placer.setIfAir(tx, topY, tz, Blocks.JUNGLE_LEAVES);
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            for (int len = 1; len <= 2; len++) {
                placer.setIfAir(tx + d[0] * len, topY, tz + d[1] * len, Blocks.JUNGLE_LEAVES);
            }
            placer.setIfAir(tx + d[0] * 3, topY - 1, tz + d[1] * 3, Blocks.JUNGLE_LEAVES);
        }
        /* Diagonale Fuellung direkt am Zentrum */
        placer.setIfAir(tx + 1, topY, tz + 1, Blocks.JUNGLE_LEAVES);
        placer.setIfAir(tx - 1, topY, tz - 1, Blocks.JUNGLE_LEAVES);
        placer.setIfAir(tx + 1, topY, tz - 1, Blocks.JUNGLE_LEAVES);
        placer.setIfAir(tx - 1, topY, tz + 1, Blocks.JUNGLE_LEAVES);
    };

    /** Gewichtete Auswahl eines Baumtyps (ein RNG-Zug, feste Reihenfolge). */
    public static TreeShape pick(Biome.TreeEntry[] trees, Random rng) {
        int total = 0;
        for (Biome.TreeEntry entry : trees) total += entry.weight();
        int roll = rng.nextInt(total);
        for (Biome.TreeEntry entry : trees) {
            roll -= entry.weight();
            if (roll < 0) return entry.shape();
        }
        return trees[trees.length - 1].shape(); // unerreichbar
    }

    /** Eiche/Birke: Stamm + 5x5-Kronenscheiben unter der Spitze, 3x3 oben (Ecken zufaellig). */
    private static void classicTree(FeaturePlacer placer, int x, int y, int z, Random rng,
                                    int log, int leaves, int height) {
        if (!fits(y, height + 2)) return;
        for (int i = 0; i < height; i++) placer.set(x, y + i, z, log);

        int top = y + height;
        disk(placer, x, top - 2, z, 2, leaves, true);
        disk(placer, x, top - 1, z, 2, leaves, true);
        disk(placer, x, top, z, 1, leaves, true);
        disk(placer, x, top + 1, z, 1, leaves, false);
    }

    /**
     * Quadratische Blattscheibe (Radius r) um (cx, y, cz); withCorners = false laesst die
     * 4 Eckbloecke weg (rundere Silhouette). Deterministisch — KEINE RNG-Zuege hier, damit
     * die Zug-Reihenfolge der Formen unabhaengig von bereits gesetzten Bloecken bleibt.
     */
    private static void disk(FeaturePlacer placer, int cx, int y, int cz, int r, int leaves, boolean withCorners) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (!withCorners && Math.abs(dx) == r && Math.abs(dz) == r) continue;
                placer.setIfAir(cx + dx, y, cz + dz, leaves);
            }
        }
    }

    /** Wie {@link #disk}, aber um ein 2x2-Zentrum (Redwood-Stamm). */
    private static void disk2x2(FeaturePlacer placer, int cx, int y, int cz, int r, int leaves) {
        for (int dx = -r; dx <= r + 1; dx++) {
            for (int dz = -r; dz <= r + 1; dz++) {
                boolean cornerX = dx == -r || dx == r + 1;
                boolean cornerZ = dz == -r || dz == r + 1;
                if (cornerX && cornerZ) continue;
                placer.setIfAir(cx + dx, y, cz + dz, leaves);
            }
        }
    }

    /** true, wenn Stamm + Krone unter die Weltdecke passen. */
    private static boolean fits(int y, int totalHeight) {
        return y + totalHeight < Chunk.HEIGHT;
    }

    private TreeShapes() {
    }
}
