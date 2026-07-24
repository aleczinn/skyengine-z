package de.skyengine.game.world;

import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.behavior.ExplosionBehavior;
import de.skyengine.game.world.block.state.BlockState;

import java.util.HashSet;
import java.util.Set;
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
 * unzerstörbar und stoppt den Strahl. Es gibt <b>keine</b> Item-Drops. Getroffene Blöcke, die
 * selbst explosiv sind (Kettenreaktion), werden nicht entfernt, sondern gezündet.
 *
 * <p>Läuft ausschließlich auf dem Tick-Thread (aus {@link ExplosionBehavior#scheduledTick}), wo
 * {@code world.setBlock} unter Write-Lock sicher ist. Die Massen-Zerstörung nutzt {@code
 * setBlock(..., false)} — das ändert nur das Section-Array und markiert die Section dirty; das
 * Remesh läuft ohnehin gebatcht einmal pro Frame (kein Remesh pro Block, keine Nachbar-Kaskade).
 */
public final class Explosion {

    /** Schrittweite entlang eines Strahls in Blöcken (MC: 0.3). */
    private static final float STEP = 0.3F;
    /** Grund-Stärkeverlust pro Schritt, unabhängig vom Block (MC: 0.225 = 0.3 · 0.75). */
    private static final float STEP_DECAY = 0.225F;
    /** Obergrenze der Gitter-Unterteilung — deckelt die Kosten sehr großer Explosionen. */
    private static final int MAX_SUBDIVISIONS = 96;

    private Explosion() {
    }

    /**
     * Sprengt an der Weltposition (cx, cy, cz) mit der gegebenen {@code power} (MC-TNT = 4).
     * Reichweite ≈ {@code power · 1,3} Blöcke (variiert mit Block-Widerstand).
     */
    public static void explode(World world, double cx, double cy, double cz, float power) {
        int n = subdivisions(power);
        int max = n - 1;
        Set<BlockPos> toBlow = new HashSet<>();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

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

                    castRay(world, cx, cy, cz, dx, dy, dz, power * (0.7F + rnd.nextFloat() * 0.6F), toBlow);
                }
            }
        }

        applyBlast(world, toBlow, rnd);
    }

    /** Ein einzelner Strahl: läuft in {@link #STEP}-Schritten, bis die Stärke aufgezehrt ist. */
    private static void castRay(World world, double px, double py, double pz,
                                double dx, double dy, double dz, float strength, Set<BlockPos> toBlow) {
        /* Zuletzt aufgenommene Zelle — spart Allokationen, wenn mehrere Schritte in derselben liegen
           (STEP < 1). Der Stärkeverlust wird trotzdem in JEDEM Schritt gerechnet (wie MC). */
        int lastX = Integer.MIN_VALUE, lastY = 0, lastZ = 0;

        while (strength > 0.0F) {
            int bx = (int) Math.floor(px);
            int by = (int) Math.floor(py);
            int bz = (int) Math.floor(pz);

            int id = world.getBlock(bx, by, bz);
            float resistance = 0.0F;
            if (id != Blocks.AIR) {
                float hardness = Blocks.getState(id).getBlock().getHardness();
                if (hardness < 0.0F) break; // unzerstörbar (Bedrock): Strahl wird geschluckt
                resistance = hardness;
            }
            strength -= (resistance + 0.3F) * 0.3F;

            if (strength > 0.0F && id != Blocks.AIR && (bx != lastX || by != lastY || bz != lastZ)) {
                lastX = bx;
                lastY = by;
                lastZ = bz;
                toBlow.add(new BlockPos(bx, by, bz));
            }

            px += dx * STEP;
            py += dy * STEP;
            pz += dz * STEP;
            strength -= STEP_DECAY;
        }
    }

    /** Zerstört alle getroffenen Blöcke; explosive Blöcke werden stattdessen als Primed-Entity gezündet (Kettenreaktion). */
    private static void applyBlast(World world, Set<BlockPos> toBlow, ThreadLocalRandom rnd) {
        for (BlockPos pos : toBlow) {
            int id = world.getBlock(pos.x(), pos.y(), pos.z());
            if (id == Blocks.AIR) continue;
            BlockState state = Blocks.getState(id);
            ExplosionBehavior explosive = state.getBlock().getBehavior(ExplosionBehavior.class);
            if (explosive != null) {
                /* Getroffenes TNT als gestaffelte Primed-Entity zünden (randomisierter Kurz-Fuse, wie MC). */
                world.setBlock(pos.x(), pos.y(), pos.z(), Blocks.AIR, false);
                int chainFuse = explosive.fuse() / 8 + rnd.nextInt(explosive.fuse() / 8 + 1);
                world.spawnPrimedTnt(pos.x() + 0.5, pos.y(), pos.z() + 0.5, explosive.power(), chainFuse);
            } else {
                world.setBlock(pos.x(), pos.y(), pos.z(), Blocks.AIR, false);
            }
        }
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
