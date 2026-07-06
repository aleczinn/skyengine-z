package de.skyengine.game.world.generator.generators;

import de.skyengine.game.world.generator.biome.Biomes;
import de.skyengine.utils.math.FastNoiseLite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministisches Quelle-zu-Muendung-Flussnetz nach dem Worley-See-Muster, eine Stufe
 * groesser: pro 2048er-Zelle werden 0-2 Quellen im Hochland gewuerfelt und von dort auf
 * einem glatten Leitfeld bergab getract, bis der Lauf das Meer, einen Worley-See oder
 * (abflusslose Senke/Maximallaenge) ein aufgeweitetes Endbecken erreicht. Jeder Lauf
 * traegt ein MONOTON FALLENDES Spiegel-Profil (laufendes Minimum ueber Traeger − Downcut):
 * Wasserwaende, buendige Ufer und tote Enden sind damit konstruktionsbedingt ausgeschlossen
 * statt (wie im alten Iso-Linien-Modell) per Gates und Klammern gedaempft.
 *
 * <p>Alles pure Funktionen von (Seed, Zelle) — der Zellen-Cache ist reine Memoization,
 * Ergebnis unabhaengig von Chunk-Reihenfolge und Threads. Die Seen duerfen dafuer NICHT
 * von Fluessen abhaengen (Ringhoehen auf flussfreiem Terrain), sonst Cache-Rekursion.
 *
 * <p>Nutzt seed+24 (Maeander) und seed+25 (Kanalbreite).
 */
public final class RiverNetwork {

    /* Zellraster; ein Lauf verlaesst den 3x3-Ring seiner Quellzelle nie:
     * MAX_STEPS*STEP + maximale Tal-/Schulterbreite (3840 + ~70) < CELL —
     * sampleAt muss daher nur die 3x3-Nachbarzellen abfragen. */
    static final int CELL = 4096;
    /* Spiegel liegt so viele Bloecke unter dem Traeger (uebernimmt RIVER_DOWNCUT) */
    static final float DOWNCUT = 3F;
    /* Carve-Zone (Tal) reicht bis VALLEY_FACTOR * Halbbreite von der Mittellinie,
     * die Uferdamm-Schulter nochmal SHOULDER_FACTOR weiter */
    static final float VALLEY_FACTOR = 2.5F;
    static final float SHOULDER_FACTOR = 1.5F;

    /* Trace: Schrittweite und Maximallaenge (80*48 = 3840 Bloecke) */
    private static final int STEP = 48;
    private static final int MAX_STEPS = 80;
    /* Quell-Kandidaten pro Zelle */
    private static final int SOURCE_TRIES = 4;
    /* Richtungswahl pro Schritt: Kandidaten im Faecher um die aktuelle Richtung. Die
     * Drehstrafe (Bloecke Leitfeld-Malus pro Radiant Abweichung) wirkt als Momentum
     * gegen Zickzack, der Maeander-Jitter verbiegt die reine Falllinie zu Boegen. */
    private static final int FAN = 7;
    private static final float FAN_SPREAD = (float) Math.toRadians(70);
    private static final float TURN_PENALTY = 1.5F;
    private static final float MEANDER_ANGLE = (float) Math.toRadians(35);
    /* Quellen nur im Hochland und klar landeinwaerts */
    private static final float SOURCE_MIN_GUIDE = 78F;
    private static final float SOURCE_MIN_CONT = Biomes.C_BEACH + 0.05F;
    private static final float SOURCE_CHANCE = 0.75F;
    /* Steigt das Leitfeld vorwaerts staerker als so viele Bloecke, sitzt der Lauf in einer
     * echten abflusslosen Senke -> Endbecken. Bewusst grosszuegig: kleine Leitfeld-Dellen
     * (Detail-Oktave, ±10) soll der Lauf als Kerbe durchschneiden (Profil bleibt ja unten),
     * nur tiefe Kontinentalwellen-Becken enden endorheisch. */
    private static final float BASIN_ASCENT = 12F;
    /* Kanal-Halbbreite: Basis + Noise-Variation + Wachstum flussabwaerts (~3..7.5) */
    private static final float HALF_MIN = 3F;
    private static final float HALF_NOISE = 2F;
    private static final float HALF_GROWTH = 2.5F;
    /* Endbecken: letzte Halbbreite aufgeweitet -> kleiner Teich statt stumpfem Ende */
    private static final float POND_FACTOR = 2.5F;

    /** Endtyp eines Laufs — fuer Sonden/Debug. */
    public static final byte END_SEA = 0;
    public static final byte END_LAKE = 1;
    public static final byte END_POND = 2;

    private final AlphaWorldGeneratorV2 gen;
    private final int seed;
    /* Maeander-Ablenkung und Breitenverlauf, beide ueber die Bogenlaenge gesampelt
     * (zweite Noise-Koordinate = Fluss-Salt, trennt die Laeufe voneinander) */
    private final FastNoiseLite meanderNoise;
    private final FastNoiseLite widthNoise;

    /* Zellen-Cache: pure Memoization (siehe Klassen-Doku), analog lakeCache */
    private final ConcurrentHashMap<Long, River[]> cache = new ConcurrentHashMap<>();
    private static final River[] NONE = new River[0];

    public RiverNetwork(AlphaWorldGeneratorV2 gen, int seed) {
        this.gen = gen;
        this.seed = seed;

        this.meanderNoise = new FastNoiseLite(seed + 24);
        this.meanderNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        this.meanderNoise.SetFrequency(0.004F);

        this.widthNoise = new FastNoiseLite(seed + 25);
        this.widthNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        this.widthNoise.SetFrequency(0.002F);
    }

    /** Ein fertig getracter Lauf: Mittellinien-Knoten mit monoton fallendem Spiegel. */
    public static final class River {
        private final float[] x, z, surf, half;
        private final byte end;
        /* Bbox inkl. Tal + Uferdamm-Schulter — billiger Vorab-Reject in sampleAt */
        private final float minX, minZ, maxX, maxZ;

        private River(float[] x, float[] z, float[] surf, float[] half, byte end) {
            this.x = x;
            this.z = z;
            this.surf = surf;
            this.half = half;
            this.end = end;
            float margin = 1F, maxHalf = 0F;
            float minX = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (int i = 0; i < x.length; i++) {
                minX = Math.min(minX, x[i]);
                maxX = Math.max(maxX, x[i]);
                minZ = Math.min(minZ, z[i]);
                maxZ = Math.max(maxZ, z[i]);
                maxHalf = Math.max(maxHalf, half[i]);
            }
            margin += maxHalf * VALLEY_FACTOR * SHOULDER_FACTOR;
            this.minX = minX - margin;
            this.maxX = maxX + margin;
            this.minZ = minZ - margin;
            this.maxZ = maxZ + margin;
        }

        public int nodes() {
            return this.x.length;
        }

        public float x(int i) {
            return this.x[i];
        }

        public float z(int i) {
            return this.z[i];
        }

        public float surf(int i) {
            return this.surf[i];
        }

        public float half(int i) {
            return this.half[i];
        }

        public byte endType() {
            return this.end;
        }
    }

    /**
     * Abfrage-Ergebnis fuer eine Spalte: Abstand zur Mittellinie, interpolierter Spiegel
     * und Kanal-Halbbreite an der naechsten Stelle des dominanten Laufs.
     */
    public record Sample(float dist, float surf, float half) {
    }

    /**
     * Naechster Fluss an (x, z) — oder null ausserhalb jeder Tal-/Schulterzone. Bei sich
     * kreuzenden Laeufen gewinnt der mit der kleinsten normierten Distanz (dominanter
     * Kanal); bewusst kein Junction-Merging in v1.
     */
    public Sample sampleAt(int x, int z) {
        int cellX = Math.floorDiv(x, CELL);
        int cellZ = Math.floorDiv(z, CELL);
        Sample best = null;
        float bestNorm = Float.MAX_VALUE;
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                for (River r : this.riversFor(cellX + i, cellZ + j)) {
                    if (x < r.minX || x > r.maxX || z < r.minZ || z > r.maxZ) continue;
                    for (int s = 0; s < r.x.length - 1; s++) {
                        float dx = r.x[s + 1] - r.x[s];
                        float dz = r.z[s + 1] - r.z[s];
                        float t = ((x - r.x[s]) * dx + (z - r.z[s]) * dz) / (dx * dx + dz * dz);
                        t = Math.clamp(t, 0F, 1F);
                        float px = x - (r.x[s] + dx * t);
                        float pz = z - (r.z[s] + dz * t);
                        float dist = (float) Math.sqrt(px * px + pz * pz);
                        float half = lerp(r.half[s], r.half[s + 1], t);
                        if (dist > half * VALLEY_FACTOR * SHOULDER_FACTOR) continue;
                        float norm = dist / half;
                        if (norm < bestNorm) {
                            bestNorm = norm;
                            best = new Sample(dist, lerp(r.surf[s], r.surf[s + 1], t), half);
                        }
                    }
                }
            }
        }
        return best;
    }

    /** Alle Laeufe mit Quelle in dieser Zelle (gecacht) — auch fuer Sonden/Debug-Karten. */
    public River[] riversFor(int cellX, int cellZ) {
        long key = ((long) cellX << 32) ^ (cellZ & 0xFFFFFFFFL);
        return this.cache.computeIfAbsent(key, k -> this.computeCell(cellX, cellZ));
    }

    private River[] computeCell(int cellX, int cellZ) {
        List<River> list = null;
        for (int i = 0; i < SOURCE_TRIES; i++) {
            if (AlphaWorldGeneratorV2.hash01(cellX, cellZ, this.seed, 0xF10 + i) >= SOURCE_CHANCE) continue;
            float sx = cellX * CELL + AlphaWorldGeneratorV2.hash01(cellX, cellZ, this.seed, 0xF20 + i) * CELL;
            float sz = cellZ * CELL + AlphaWorldGeneratorV2.hash01(cellX, cellZ, this.seed, 0xF30 + i) * CELL;
            float salt = AlphaWorldGeneratorV2.hash01(cellX, cellZ, this.seed, 0xF40 + i) * 8192F;
            River river = this.trace(sx, sz, salt);
            if (river == null) continue;
            if (list == null) list = new ArrayList<>(2);
            list.add(river);
        }
        return (list == null) ? NONE : list.toArray(NONE);
    }

    /** Bergab-Trace von einer Quellposition; null, wenn die Quell-Gates nicht erfuellt sind. */
    private River trace(float sx, float sz, float salt) {
        int ix = Math.round(sx), iz = Math.round(sz);
        float guide = this.gen.riverGuide(ix, iz);
        if (guide < SOURCE_MIN_GUIDE) return null;
        if (this.gen.continentalnessAt(ix, iz) < SOURCE_MIN_CONT) return null;
        if (this.gen.insideLake(ix, iz)) return null;
        float surf = this.gen.riverCarrier(ix, iz) - DOWNCUT;
        /* Traeger-Gate: das Leitfeld enthaelt den Gebirgs-Bonus, der Spiegel folgt aber dem
         * Traeger — ohne dieses Gate starten Bergzonen-Quellen mit Spiegel unterm Meer */
        if (surf < AlphaWorldGeneratorV2.SEA_LEVEL + 6F) return null;

        float[] xs = new float[MAX_STEPS + 1];
        float[] zs = new float[MAX_STEPS + 1];
        float[] surfs = new float[MAX_STEPS + 1];
        float[] halfs = new float[MAX_STEPS + 1];
        xs[0] = sx;
        zs[0] = sz;
        surfs[0] = surf;
        halfs[0] = this.halfWidth(0F, salt);
        int n = 1;

        /* Startrichtung: steilster Abstieg aus 8 Proben auf dem vollen Kreis */
        float heading = 0F;
        float bestStart = Float.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            float a = (float) (2 * Math.PI * i / 8);
            float g = this.guideAt(sx + (float) Math.cos(a) * STEP, sz + (float) Math.sin(a) * STEP);
            if (g < bestStart) {
                bestStart = g;
                heading = a;
            }
        }

        float x = sx, z = sz;
        byte end = END_POND;
        for (int step = 1; step <= MAX_STEPS; step++) {
            /* Wunschrichtung = Heading + Maeander-Ablenkung; Kandidaten im Faecher darum */
            float wander = this.meanderNoise.GetNoise(step * STEP, salt) * MEANDER_ANGLE;
            float bestAngle = heading;
            float bestGuide = Float.MAX_VALUE;
            float bestScore = Float.MAX_VALUE;
            for (int i = 0; i < FAN; i++) {
                float off = FAN_SPREAD * (i / (FAN - 1F) * 2F - 1F);
                float a = heading + wander + off;
                float g = this.guideAt(x + (float) Math.cos(a) * STEP, z + (float) Math.sin(a) * STEP);
                float score = g + Math.abs(off) * TURN_PENALTY;
                if (score < bestScore) {
                    bestScore = score;
                    bestGuide = g;
                    bestAngle = a;
                }
            }
            /* Abflusslose Senke: vorwaerts geht es nur noch bergauf -> Endbecken hier */
            if (bestGuide > guide + BASIN_ASCENT) break;

            heading = bestAngle;
            x += (float) Math.cos(bestAngle) * STEP;
            z += (float) Math.sin(bestAngle) * STEP;
            guide = bestGuide;
            surf = Math.min(surf, this.gen.riverCarrier(Math.round(x), Math.round(z)) - DOWNCUT);
            xs[n] = x;
            zs[n] = z;
            surfs[n] = surf;
            halfs[n] = this.halfWidth(step * STEP, salt);
            n++;

            if (guide < AlphaWorldGeneratorV2.SEA_LEVEL) {
                end = END_SEA;
                break;
            }
            if (this.gen.insideLake(Math.round(x), Math.round(z))) {
                end = END_LAKE;
                break;
            }
        }
        if (n < 2) return null;
        if (end == END_POND) halfs[n - 1] *= POND_FACTOR;

        return new River(Arrays.copyOf(xs, n), Arrays.copyOf(zs, n),
                Arrays.copyOf(surfs, n), Arrays.copyOf(halfs, n), end);
    }

    private float guideAt(float x, float z) {
        return this.gen.riverGuide(Math.round(x), Math.round(z));
    }

    /** Kanal-Halbbreite an einer Bogenlaenge: Noise-Variation + Wachstum flussabwaerts. */
    private float halfWidth(float arcLen, float salt) {
        float noise = (this.widthNoise.GetNoise(arcLen, salt) + 1F) * 0.5F;
        return HALF_MIN + noise * HALF_NOISE + arcLen / (MAX_STEPS * STEP) * HALF_GROWTH;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
