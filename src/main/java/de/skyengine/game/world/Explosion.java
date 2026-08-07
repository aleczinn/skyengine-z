package de.skyengine.game.world;

import de.skyengine.game.entity.Entity;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.entity.ItemEntity;
import de.skyengine.game.entity.ItemFrameEntity;
import de.skyengine.game.Gamemode;
import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.behavior.ExplosionBehavior;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;
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
 * <p>Als Widerstand dient {@link de.skyengine.game.world.block.Block#getResistance()} (JSON-Feld
 * {@code resistance}, ohne Angabe die Abbau-Härte); ein negativer Wert (Bedrock) ist unzerstörbar
 * und stoppt den Strahl. Getroffene Blöcke, die selbst explosiv sind (Kettenreaktion), werden
 * nicht entfernt, sondern gezündet. Blöcke mit BlockEntity laufen im Batch-Pfad durch
 * {@code onBreak}, damit z.B. Truheninhalt herausfällt.
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
        /* VOR der Zerstörung — MCs Reihenfolge (calculateExplodedPositions → hurtEntities →
           interactWithBlocks). Danach stünde die Deckung, hinter der jemand kauert, nicht mehr,
           und jede Explosion träfe voll durch die Wand, die sie gerade weggesprengt hat. */
        hurtEntities(world, cx, cy, cz, power, memo);
        applyBlast(world, toBlow, rnd, power);
        /* Eine Zeile Messbarkeit — Explosionen waren der gemeldete Lag-Spike. */
        LOGGER.debug("Explosion: power=" + power + ", " + blocks + " Bloecke, "
                + (System.nanoTime() - start) / 1_000_000 + " ms");
    }

    /**
     * Druckwelle auf Entitäten — MCs {@code ServerExplosion.hurtEntities}.
     *
     * <p>Reichweite ist {@code power · 2}; wer weiter weg ist, bleibt unberührt. Wie stark es
     * jemanden erwischt, hängt an zwei Faktoren: der Distanz und der <b>Sichtbarkeit</b>
     * ({@link #seenPercent}) — Deckung schützt, weil dann weniger Sichtstrahlen durchkommen.
     * Aus beidem wird {@code impact = (1 − dist) · seen}, daraus Schaden und Rückstoß.
     *
     * <p><b>Wasser schützt Entitäten NICHT</b> (in MC clippt der Sichtstrahl mit
     * {@code Fluid.NONE}). Eine Unterwasser-Explosion zerstört keine Blöcke — das erledigt der
     * Widerstand 100 von Wasser im Raycast oben —, tut aber genauso weh wie an Land. Das ist
     * verifiziert und kein Versehen.
     *
     * <p>Schaden nimmt nur der Spieler: andere Entitäten haben in dieser Engine keine Gesundheit.
     * Item-Drops in Reichweite werden vernichtet, gezündetes TNT und fallende Blöcke fliegen bloß
     * — genau das trägt in MC die TNT-Kanonen.
     */
    private static void hurtEntities(World world, double cx, double cy, double cz, float power,
                                     World.ChunkMemo memo) {
        double reach = power * 2.0;
        if (reach <= 0.0) return;
        /* Chunk-Fenster großzügig genug für die Reichweite (power kann weit über TNT liegen). */
        int chunkRadius = Math.max(1, (int) Math.ceil((reach + 1.0)
                / (1 << de.skyengine.game.world.chunk.ChunkSection.SHIFT)));
        world.forEachEntityNearby(cx, cz, chunkRadius,
                entity -> blastEntity(world, entity, cx, cy, cz, reach, memo));
        /* Der Spieler steht in keiner Chunk-Entity-Liste — ohne diese Zeile bliebe er heil. */
        Entity player = world.getNearestPlayer(cx, cy, cz, reach);
        if (player != null) blastEntity(world, player, cx, cy, cz, reach, memo);
    }

    /** Eine einzelne Entität: Distanz prüfen, Sichtbarkeit messen, Schaden und Rückstoß setzen. */
    private static void blastEntity(World world, Entity entity, double cx, double cy, double cz,
                                    double reach, World.ChunkMemo memo) {
        if (entity.isRemoved()) return;
        /* Zuschauer sind unantastbar (MCs ignoreExplosion) — auch kein Rückstoß. */
        if (entity instanceof EntityPlayer p && p.getGamemode() == Gamemode.SPECTATOR) return;

        double ox = entity.x - cx, oy = entity.y - cy, oz = entity.z - cz;
        double dist = Math.sqrt(ox * ox + oy * oy + oz * oz) / reach;
        if (dist > 1.0) return;

        float seen = seenPercent(world, cx, cy, cz, entity.getBoundingBox(), memo);
        if (seen <= 0.0F) return;
        double impact = (1.0 - dist) * seen;

        /* Schaden vor Rückstoß, wie in MC. Der Gamemode-Filter sitzt in damage() selbst. */
        if (entity instanceof EntityPlayer player) {
            player.damage((float) ((impact * impact + impact) / 2.0 * 7.0 * reach + 1.0));
        } else if (entity instanceof ItemEntity) {
            entity.remove();
            return;   // ein vernichtetes Item braucht keinen Rückstoß mehr
        } else if (entity instanceof ItemFrameEntity frame) {
            /* Explosionen umgehen Vanillas "erster Treffer entfernt nur den Inhalt" und brechen
               die Hanging-Entity als Ganzes. Sichtschutz/Distanz wurden oben bereits berechnet. */
            frame.breakNaturally(world);
            return;
        }

        /* Rückstoß-Richtung: vom Zentrum zur Entität. MC zielt dabei auf die Augen — ausser bei
           gezündetem TNT, das seinen Fußpunkt nimmt (sonst flöge es systematisch zu flach). */
        double ty = entity.y + (entity instanceof EntityPlayer p2 ? p2.getEyeHeight(1.0F) : 0.0);
        double nx = entity.x - cx, ny = ty - cy, nz = entity.z - cz;
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1.0E-4) {
            /* Genau im Zentrum: keine definierte Richtung, also nach oben (statt NaN). */
            nx = 0.0; ny = 1.0; nz = 0.0;
        } else {
            nx /= len; ny /= len; nz /= len;
        }
        entity.motionX += nx * impact;
        entity.motionY += ny * impact;
        entity.motionZ += nz * impact;
    }

    /**
     * Anteil der Sichtstrahlen vom Zentrum zur Bounding-Box, die nicht auf Deckung treffen
     * (MCs {@code getSeenPercent}). Abgetastet wird ein Raster über die Box; die Schrittweite
     * hängt an ihrer Größe, damit große Entitäten nicht feiner gerastert werden als nötig.
     */
    private static float seenPercent(World world, double cx, double cy, double cz, AABB box,
                                     World.ChunkMemo memo) {
        double stepX = 1.0 / ((box.maxX - box.minX) * 2.0 + 1.0);
        double stepY = 1.0 / ((box.maxY - box.minY) * 2.0 + 1.0);
        double stepZ = 1.0 / ((box.maxZ - box.minZ) * 2.0 + 1.0);
        if (stepX <= 0.0 || stepY <= 0.0 || stepZ <= 0.0) return 0.0F;

        /* Rest-Zentrierung: die Rasterpunkte liegen sonst asymmetrisch an der Box-Unterkante. */
        double offX = (1.0 - Math.floor(1.0 / stepX) * stepX) / 2.0;
        double offZ = (1.0 - Math.floor(1.0 / stepZ) * stepZ) / 2.0;

        int clear = 0, total = 0;
        for (double u = 0.0; u <= 1.0; u += stepX) {
            for (double v = 0.0; v <= 1.0; v += stepY) {
                for (double w = 0.0; w <= 1.0; w += stepZ) {
                    double px = box.minX + (box.maxX - box.minX) * u + offX;
                    double py = box.minY + (box.maxY - box.minY) * v;
                    double pz = box.minZ + (box.maxZ - box.minZ) * w + offZ;
                    if (hasLineOfSight(world, px, py, pz, cx, cy, cz, memo)) clear++;
                    total++;
                }
            }
        }
        return total == 0 ? 0.0F : (float) clear / total;
    }

    /**
     * Freie Sicht zwischen zwei Punkten mit exaktem Voxel-/Shape-Clipping. Fluide haben keine
     * Kollisionsform und blockieren deshalb nicht, entsprechend Vanillas
     * {@code ClipContext.Fluid.NONE}.
     */
    static boolean hasLineOfSight(World world, double px, double py, double pz,
                                  double cx, double cy, double cz, World.ChunkMemo memo) {
        double dx = cx - px, dy = cy - py, dz = cz - pz;
        if (dx * dx + dy * dy + dz * dz < 1.0E-12) return true;

        int bx = (int) Math.floor(px), by = (int) Math.floor(py), bz = (int) Math.floor(pz);
        int endX = (int) Math.floor(cx), endY = (int) Math.floor(cy), endZ = (int) Math.floor(cz);
        int stepX = Integer.compare(endX, bx);
        int stepY = Integer.compare(endY, by);
        int stepZ = Integer.compare(endZ, bz);

        double deltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dx);
        double deltaY = stepY == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dy);
        double deltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dz);
        double nextX = firstBoundaryTime(px, dx, bx, stepX);
        double nextY = firstBoundaryTime(py, dy, by, stepY);
        double nextZ = firstBoundaryTime(pz, dz, bz, stepZ);

        /* Exaktes Voxel-DDA statt Punktproben im Abstand STEP. Duennere Formen wie Falltueren
           und Zaunpfosten duerfen einen Explosionsstrahl weder zufaellig durchlassen noch
           faelschlich sperren; Vanilla clippt denselben Strahl ebenfalls gegen die Shapes. */
        int remaining = Math.abs(endX - bx) + Math.abs(endY - by) + Math.abs(endZ - bz) + 1;
        while (remaining-- > 0) {
            if (world.getBlockMemo(bx, by, bz, memo) != Blocks.AIR
                    && rayHitsShape(world, bx, by, bz, px, py, pz, dx, dy, dz)) {
                return false;
            }
            if (bx == endX && by == endY && bz == endZ) return true;

            double next = Math.min(nextX, Math.min(nextY, nextZ));
            /* Bei Kanten-/Ecktreffern alle gebundenen Achsen gemeinsam betreten. */
            if (nextX <= next + 1.0E-12) {
                bx += stepX;
                nextX += deltaX;
            }
            if (nextY <= next + 1.0E-12) {
                by += stepY;
                nextY += deltaY;
            }
            if (nextZ <= next + 1.0E-12) {
                bz += stepZ;
                nextZ += deltaZ;
            }
        }
        return true;
    }

    private static double firstBoundaryTime(double start, double delta, int cell, int step) {
        if (step == 0) return Double.POSITIVE_INFINITY;
        double boundary = step > 0 ? cell + 1.0 : cell;
        return (boundary - start) / delta;
    }

    /** Prueft den gesamten Segmentabschnitt [0,1] exakt gegen jede lokale Kollisionsbox. */
    private static boolean rayHitsShape(World world, int bx, int by, int bz,
                                        double px, double py, double pz,
                                        double dx, double dy, double dz) {
        for (AABB local : world.getCollisionShape(bx, by, bz).boxes()) {
            double tMin = 0.0;
            double tMax = 1.0;

            double min = bx + local.minX, max = bx + local.maxX;
            if (Math.abs(dx) < 1.0E-12) {
                if (px < min || px > max) continue;
            } else {
                double a = (min - px) / dx, b = (max - px) / dx;
                if (a > b) { double swap = a; a = b; b = swap; }
                tMin = Math.max(tMin, a);
                tMax = Math.min(tMax, b);
                if (tMax < tMin) continue;
            }

            min = by + local.minY; max = by + local.maxY;
            if (Math.abs(dy) < 1.0E-12) {
                if (py < min || py > max) continue;
            } else {
                double a = (min - py) / dy, b = (max - py) / dy;
                if (a > b) { double swap = a; a = b; b = swap; }
                tMin = Math.max(tMin, a);
                tMax = Math.min(tMax, b);
                if (tMax < tMin) continue;
            }

            min = bz + local.minZ; max = bz + local.maxZ;
            if (Math.abs(dz) < 1.0E-12) {
                if (pz < min || pz > max) continue;
            } else {
                double a = (min - pz) / dz, b = (max - pz) / dz;
                if (a > b) { double swap = a; a = b; b = swap; }
                tMin = Math.max(tMin, a);
                tMax = Math.min(tMax, b);
                if (tMax < tMin) continue;
            }
            return true;
        }
        return false;
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
                float own = resistanceOf(id);
                if (own < 0.0F) break; // unzerstörbar (Bedrock): Strahl wird geschluckt
                resistance = own;
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

    /**
     * Zerstört alle getroffenen Blöcke im Batch; explosive Blöcke werden als Primed-Entity gezündet
     * (Kettenreaktion) und droppen deshalb kein Item. Alle übrigen droppen mit Wahrscheinlichkeit
     * {@code 1/power} (MC: {@code survives_explosion} bzw. {@code explosion_decay} in den
     * Loot-Tables). Die Werkzeug-Regel gilt dabei bewusst NICHT — Vanilla-Explosionsloot kennt
     * kein Werkzeug, gesprengter Stein droppt also auch ohne Spitzhacke. Mangels Loot-Tables
     * droppt jeder Block sich selbst, genau wie im normalen Abbaupfad.
     */
    private static void applyBlast(World world, LongIntMap toBlow, ThreadLocalRandom rnd, float power) {
        float dropChance = power > 1.0F ? 1.0F / power : 1.0F;
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

        /* Drops erst NACH der Zerstörung, damit die Item-Entities nicht kurz in noch stehenden
           Blöcken sitzen (Reihenfolge wie GameContainer.breakTargetBlock). Zellen, die
           breakBlocksBatch an der Ladefront verworfen hat, droppen dabei trotzdem — seltener
           Sonderfall, für den sich ein Rückkanal aus dem Batch nicht lohnt. */
        for (int i = 0, n = toBlow.tableSize(); i < n; i++) {
            if (!toBlow.usedAt(i)) continue;
            if (rnd.nextFloat() >= dropChance) continue;
            BlockState state = Blocks.getState(toBlow.valueAt(i));
            if (state.getBlock().getBehavior(ExplosionBehavior.class) != null) continue;
            Item drop = Items.forBlock(state.getBlock()); // löst auch places_block-Items auf (Staub)
            if (drop == null) continue;
            long pos = toBlow.keyAt(i);
            world.spawnItem(BlockPos.unpackX(pos) + 0.5, BlockPos.unpackY(pos) + 0.5,
                    BlockPos.unpackZ(pos) + 0.5, new ItemStack(drop, 1));
        }
    }

    /** Explosions-Widerstand je State-ID, lazy gewachsen (Muster ChunkMesher.opaqueById). */
    private static float resistanceOf(int stateId) {
        float[] cache = resistanceById;
        if (stateId >= cache.length) {
            cache = new float[BlockRegistry.getStateCount()];
            for (int i = 0; i < cache.length; i++) {
                cache[i] = BlockRegistry.getState(i).getBlock().getResistance();
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
