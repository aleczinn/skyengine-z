package de.skyengine.game.world;

import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.behavior.ExplosionBehavior;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.utils.collect.LongIntMap;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Raybasierte Explosion nach Minecrafts {@code ServerExplosion}: von der Oberfläche eines
 * Würfelgitters werden Strahlen aus dem Zentrum nach außen geschossen. Jeder Strahl startet mit
 * einer zufällig gestreuten Stärke und verliert pro Schritt an Kraft — durch einen Grundzerfall
 * plus den Widerstand des durchquerten Blocks. Jeder Block, den ein Strahl mit Reststärke erreicht,
 * wird zerstört.
 *
 * <p>Als Widerstand dient die vorhandene Abbau-Härte ({@link
 * de.skyengine.game.world.block.Block#getHardness()}); {@code hardness < 0} (Bedrock) ist
 * unzerstörbar und stoppt den Strahl. Es gibt <b>keine</b> Item-Drops (nur Blöcke mit
 * BlockEntity laufen im Batch-Pfad durch {@code onBreak}, damit z.B. Truheninhalt herausfällt).
 * Getroffene Blöcke, die selbst explosiv sind (Kettenreaktion), werden nicht entfernt, sondern
 * gezündet.
 *
 * <p>Läuft ausschließlich auf dem Tick-Thread (einziger Aufrufer ist {@code PrimedTntEntity.tick}
 * über {@code World.tickEntities}). Die Massen-Zerstörung läuft über
 * {@link World#breakBlocksBatch} — ein Write-Lock, ein Dirty-CAS und ein Licht-Update pro
 * betroffenem Chunk statt pro Block; das Remesh ist ohnehin pro Frame gebatcht.
 */
public final class Explosion {

    private static final Logger LOGGER = LogManager.getLogger(Explosion.class.getName());

    /** Schrittweite entlang eines Strahls in Blöcken (MC: 0.3). */
    private static final float STEP = 0.3F;
    /** Grund-Stärkeverlust pro Schritt, unabhängig vom Block (MC: 0.225 = 0.3 · 0.75). */
    private static final float STEP_DECAY = 0.225F;
    /** Obergrenze der Gitter-Unterteilung — deckelt die Kosten sehr großer Explosionen. */
    private static final int MAX_SUBDIVISIONS = 96;

    /* Explosions-Widerstand je State-ID (lazy auf Registry-Größe gewachsen): der Raycast macht
       bei großen Explosionen Millionen Abfragen — der Weg über getState().getBlock().getHardness()
       wäre zwei Objekt-Dereferenzierungen pro Schritt. */
    private static float[] resistanceById = new float[0];

    private Explosion() {
    }

    /**
     * Sprengt an der Weltposition (cx, cy, cz) mit der gegebenen {@code power} (MC-TNT = 4).
     * Reichweite ≈ {@code power · 1,3} Blöcke (variiert mit Block-Widerstand).
     */
    public static void explode(World world, double cx, double cy, double cz, float power) {
        long start = System.nanoTime();
        int n = subdivisions(power);
        int max = n - 1;
        /* Getroffene Zellen dedupliziert als Long-Key -> State-ID (der Raycast hat die ID ohnehin
           gelesen — applyBlast muss sie dann nicht erneut auflösen). Kein HashSet<BlockPos>:
           das waren bei großen Explosionen Millionen transiente Records. */
        LongIntMap toBlow = new LongIntMap(4096);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        World.ChunkMemo memo = new World.ChunkMemo();

        /* Nur die Randpunkte des n³-Würfels ergeben Strahlrichtungen (die Oberfläche einer Kugel). */
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                for (int k = 0; k < n; k++) {
                    if (i != 0 && i != max && j != 0 && j != max && k != 0 && k != max) continue;

                    double dx = (double) i / max * 2.0 - 1.0;
                    double dy = (double) j / max * 2.0 - 1.0;
                    double dz = (double) k / max * 2.0 - 1.0;
                    double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    dx /= len;
                    dy /= len;
                    dz /= len;

                    castRay(world, cx, cy, cz, dx, dy, dz,
                            power * (0.7F + rnd.nextFloat() * 0.6F), toBlow, memo);
                }
            }
        }

        int blocks = toBlow.size();
        applyBlast(world, toBlow, rnd);
        /* Eine Zeile Messbarkeit — Explosionen waren der gemeldete Lag-Spike. */
        LOGGER.debug("Explosion: power=" + power + ", " + blocks + " Bloecke, "
                + (System.nanoTime() - start) / 1_000_000 + " ms");
    }

    /** Ein einzelner Strahl: läuft in {@link #STEP}-Schritten, bis die Stärke aufgezehrt ist. */
    private static void castRay(World world, double px, double py, double pz,
                                double dx, double dy, double dz, float strength,
                                LongIntMap toBlow, World.ChunkMemo memo) {
        /* Zuletzt aufgenommene Zelle — spart die Map-Zugriffe, wenn mehrere Schritte in derselben
           liegen (STEP < 1). Der Stärkeverlust wird trotzdem in JEDEM Schritt gerechnet (wie MC). */
        int lastX = Integer.MIN_VALUE, lastY = 0, lastZ = 0;

        while (strength > 0.0F) {
            int bx = (int) Math.floor(px);
            int by = (int) Math.floor(py);
            int bz = (int) Math.floor(pz);

            int id = world.getBlockMemo(bx, by, bz, memo);
            float resistance = 0.0F;
            if (id != Blocks.AIR) {
                float hardness = resistanceOf(id);
                if (hardness < 0.0F) break; // unzerstörbar (Bedrock): Strahl wird geschluckt
                resistance = hardness;
            }
            strength -= (resistance + 0.3F) * 0.3F;

            if (strength > 0.0F && id != Blocks.AIR && (bx != lastX || by != lastY || bz != lastZ)) {
                lastX = bx;
                lastY = by;
                lastZ = bz;
                toBlow.put(BlockPos.asLong(bx, by, bz), id);
            }

            px += dx * STEP;
            py += dy * STEP;
            pz += dz * STEP;
            strength -= STEP_DECAY;
        }
    }

    /** Zerstört alle getroffenen Blöcke im Batch; explosive Blöcke werden als Primed-Entity gezündet (Kettenreaktion). */
    private static void applyBlast(World world, LongIntMap toBlow, ThreadLocalRandom rnd) {
        long[] positions = new long[toBlow.size()];
        int count = 0;
        for (int i = 0, n = toBlow.tableSize(); i < n; i++) {
            if (!toBlow.usedAt(i)) continue;
            long pos = toBlow.keyAt(i);
            positions[count++] = pos;

            BlockState state = Blocks.getState(toBlow.valueAt(i));
            ExplosionBehavior explosive = state.getBlock().getBehavior(ExplosionBehavior.class);
            if (explosive != null) {
                /* Getroffenes TNT als gestaffelte Primed-Entity zünden (randomisierter Kurz-Fuse,
                   wie MC). Der Block selbst fällt mit in den Batch. */
                int chainFuse = explosive.fuse() / 8 + rnd.nextInt(explosive.fuse() / 8 + 1);
                world.spawnPrimedTnt(BlockPos.unpackX(pos) + 0.5, BlockPos.unpackY(pos),
                        BlockPos.unpackZ(pos) + 0.5, explosive.power(), chainFuse);
            }
        }
        world.breakBlocksBatch(positions, count);
    }

    /** Explosions-Widerstand (Abbau-Härte) je State-ID, lazy gewachsen (Muster ChunkMesher.opaqueById). */
    private static float resistanceOf(int stateId) {
        float[] cache = resistanceById;
        if (stateId >= cache.length) {
            cache = new float[BlockRegistry.getStateCount()];
            for (int i = 0; i < cache.length; i++) {
                cache[i] = BlockRegistry.getState(i).getBlock().getHardness();
            }
            resistanceById = cache;
        }
        return cache[stateId];
    }

    /**
     * Genug Gitter-Unterteilungen, dass die äußere Kugelschale bei der erwarteten Reichweite
     * lückenlos bleibt (Strahlabstand am Rand ≲ 1 Block). Kleine TNT bleibt bei den 16 aus MC.
     */
    private static int subdivisions(float power) {
        double reach = power * 1.3; // grobe Reichweite in Blöcken
        int n = (int) Math.ceil(1.45 * reach);
        return Math.max(16, Math.min(MAX_SUBDIVISIONS, n));
    }
}
