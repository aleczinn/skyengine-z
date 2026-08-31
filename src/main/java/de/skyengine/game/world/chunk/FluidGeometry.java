package de.skyengine.game.world.chunk;

import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.archetype.FluidInfo;
import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.game.world.block.model.BlockModels;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;

import java.util.ArrayList;
import java.util.List;

/**
 * Erzeugt die Geometrie eines Fluid-Blocks (Wasser/Lava) dynamisch beim Meshen.
 * Anders als normale Blöcke hängt die Oberfläche von den Nachbar-Leveln ab
 * (Eckhöhen-Interpolation im Minecraft-Stil), darum kein vorgebackenes Modell.
 *
 * <p>Die erzeugten Quads sind bereits face-gecullt (verdeckte Flächen werden gar
 * nicht erzeugt) und tragen {@link BakedQuad#NO_CULL}, damit der Mesher sie ohne
 * weitere Nachbarprüfung emittiert. Für die Eck-Mittelung an Chunk-Ecken braucht
 * der Mesher auch die 4 Diagonal-Nachbarn — sonst berechnen die vier angrenzenden
 * Zellen die gemeinsame Ecke unterschiedlich und die Flächen klaffen auseinander.
 */
public final class FluidGeometry {

    /* Flow-Sprites besitzen in Minecraft 32x32-Frames, zeigen pro Block aber nur einen
       16x16-Ausschnitt: oben zentriert/rotiert, an den Seiten die obere linke Hälfte. */
    private static final float FLOW_TOP_UV_RADIUS = 0.25F;
    private static final float FLOW_SIDE_UV_SCALE = 0.5F;

    /** Default-Wasserfarbe (gepackt 0xRRGGBB). Später positions-/biome-abhängig. */
    public static final int WATER_TINT = 0x3F76E4;

    /**
     * Oberkante einer stillen Quelle (Level 0) nach der Formel {@code (8 - level) / 9}
     * (s. {@link #ownHeight}).
     */
    public static final float SOURCE_HEIGHT = 8F / 9F;

    /** Vanillas LiquidBlockRenderer senkt sichtbare Top-Ecken um 0,001 Block ab. */
    public static final float TOP_RENDER_EPSILON = 0.001F;

    /** Tatsächlich gerenderte lokale Y-Höhe einer flachen Fluid-Quelloberfläche. */
    public static final float SOURCE_RENDER_HEIGHT = SOURCE_HEIGHT - TOP_RENDER_EPSILON;

    /** Trennt koplanare Fluid- und Glas-/Eis-Seiten um einen Fixed-Point-Schritt. */
    public static final float TRANSLUCENT_SIDE_EPSILON = 0.001F;

    /** Geteiltes Leer-Ergebnis — vermeidet Allokationen für unsichtbare Fluid-Zellen. */
    private static final BakedQuad[] NO_QUADS = new BakedQuad[0];

    private FluidGeometry() {}

    /**
     * @param skipMergedTop true, wenn der ChunkMesher das flach-stille Top-Face dieser Zelle
     *                      bereits in seinem gemergten Wasser-Pass emittiert hat — dann hier
     *                      NICHT nochmal (sonst doppelte Fläche). Boden/Seiten/fließende Tops
     *                      bleiben davon unberührt. Die Merge-Bedingung ist identisch mit
     *                      {@link #isMergeableFlatStillTop} (dieselben corner()-Werte).
     */
    public static BakedQuad[] build(BlockState state,
                                    Chunk chunk, Chunk north, Chunk south, Chunk west, Chunk east, Chunk[] diagonals,
                                    int x, int worldY, int z, boolean skipMergedTop) {
        Block fluid = state.getBlock();
        FluidInfo info = fluid.getFluidInfo();
        if (info == null) return NO_QUADS;

        boolean fluidAbove = isSameFluid(sample(chunk, north, south, west, east, diagonals, x, worldY + 1, z), fluid);

        /* Fast-Path: komplett von gleichem Fluid umschlossen -> garantiert 0 Quads (Top/Bottom/
           Seiten werden alle gegen gleiches Fluid geculled). Spart in Ozean-/See-Innenzellen
           die Eckhöhen-Berechnung und die Listen-Allokation pro Zelle — die Snapshot-
           Optimierung des Meshers greift für Fluid-Nachbar-Samples nicht. */
        if (fluidAbove
                && isSameFluid(sample(chunk, north, south, west, east, diagonals, x, worldY - 1, z), fluid)
                && isSameFluid(sample(chunk, north, south, west, east, diagonals, x - 1, worldY, z), fluid)
                && isSameFluid(sample(chunk, north, south, west, east, diagonals, x + 1, worldY, z), fluid)
                && isSameFluid(sample(chunk, north, south, west, east, diagonals, x, worldY, z - 1), fluid)
                && isSameFluid(sample(chunk, north, south, west, east, diagonals, x, worldY, z + 1), fluid)) {
            return NO_QUADS;
        }

        int still = info.stillLayer;
        int flow = info.flowLayer;
        /* Wasser wird eingefärbt (Texturen sind grau); Lava ist bereits orange → neutral. */
        int tint = info.lava ? BakedQuad.WHITE : WATER_TINT;

        float h00 = corner(chunk, north, south, west, east, diagonals, x, worldY, z, fluid, state, 0, 0, fluidAbove);
        float h10 = corner(chunk, north, south, west, east, diagonals, x, worldY, z, fluid, state, 1, 0, fluidAbove);
        float h11 = corner(chunk, north, south, west, east, diagonals, x, worldY, z, fluid, state, 1, 1, fluidAbove);
        float h01 = corner(chunk, north, south, west, east, diagonals, x, worldY, z, fluid, state, 0, 1, fluidAbove);

        List<BakedQuad> quads = new ArrayList<>(6);

        /* Die Eckneigung entscheidet NICHT ueber die Textur. Minecraft verwendet oben die
           Still-Textur genau dann, wenn der tatsaechliche Flow-Vektor null ist. */
        boolean flat = h00 == h10 && h10 == h11 && h11 == h01;

        /* TOP — nur wenn oben kein gleiches Fluid (sonst verdeckt). Bei fließendem Wasser wird die
           Flow-Textur entlang des Gefälles gedreht (UVs um die Mitte rotiert, wie Minecraft), damit
           die Animation sichtbar von der Quelle wegläuft. Flach-stille Quell-Tops (Meeresoberfläche)
           übernimmt der gemergte Wasser-Pass des ChunkMeshers -> hier auslassen. */
        boolean mergedTop = skipMergedTop && flatStillAtSource(state, h00, h10, h11, h01);
        int above = sample(chunk, north, south, west, east, diagonals, x, worldY + 1, z);
        boolean fullTopOccluded = BlockRegistry.getState(above).isOpaqueCube()
                && Math.min(Math.min(h00, h10), Math.min(h11, h01)) >= 1F;
        if (!fluidAbove && !mergedTop && !fullTopOccluded) {
            /* Fließrichtung wie Vanilla FlowingFluid.getFlow: pro Himmelsrichtung zieht nur
               gleiches Fluid (Level-Differenz) bzw. eine Abfall-Kante (freie Zelle mit gleichem
               Fluid eine Ebene tiefer); solide Nachbarn und leere Zellen tragen nichts bei.
               NICHT aus den Eckhöhen ableiten - die werden von Wänden/Luft geformt und kippen
               die Richtung neben Blöcken ins Diagonale. Vanillas FALLING-Zusatzterm entfällt:
               Zellen mit Fluid darüber bekommen gar kein Top-Face. Gleiche Formel Welt-basiert
               für die Entity-Strömung: FluidBehavior.flowVector. */
            float own = ownHeight(state);
            float velX = 0f, velZ = 0f;
            for (int i = 0; i < 4; i++) {
                int dx = i == 0 ? -1 : i == 1 ? 1 : 0;
                int dz = i == 2 ? -1 : i == 3 ? 1 : 0;
                int nid = sample(chunk, north, south, west, east, diagonals, x + dx, worldY, z + dz);
                float diff = 0f;
                if (isSameFluid(nid, fluid)) {
                    diff = own - ownHeight(BlockRegistry.getState(nid));
                } else if (!BlockRegistry.getState(nid).isSolid()) {
                    int bid = sample(chunk, north, south, west, east, diagonals, x + dx, worldY - 1, z + dz);
                    if (isSameFluid(bid, fluid)) { // Abfall-Kante: zieht stark bergab
                        diff = own - (ownHeight(BlockRegistry.getState(bid)) - SOURCE_HEIGHT);
                    }
                }
                velX += dx * diff;
                velZ += dz * diff;
            }
            boolean stillTop = Math.abs(velX) < 1.0e-4f && Math.abs(velZ) < 1.0e-4f;
            int topLayer = stillTop ? still : flow;
            float[] uv; // u,v je Ecke in Reihenfolge A(0,0) B(0,1) C(1,1) D(1,0)
            if (stillTop) {
                uv = new float[]{0, 0, 0, 1, 1, 1, 1, 0};
            } else {
                float angle = (float) Math.atan2(velZ, velX) - (float) (Math.PI / 2.0);
                float s = (float) Math.sin(angle) * FLOW_TOP_UV_RADIUS;
                float c = (float) Math.cos(angle) * FLOW_TOP_UV_RADIUS;
                uv = new float[]{
                        0.5f - c - s, 0.5f - c + s,
                        0.5f - c + s, 0.5f + c + s,
                        0.5f + c + s, 0.5f + c - s,
                        0.5f + c - s, 0.5f - c - s
                };
            }
            float r00 = h00 - TOP_RENDER_EPSILON;
            float r10 = h10 - TOP_RENDER_EPSILON;
            float r11 = h11 - TOP_RENDER_EPSILON;
            float r01 = h01 - TOP_RENDER_EPSILON;
            quads.add(quad(topLayer, BlockModels.FACE_BRIGHTNESS[0], tint,
                    0, r00, 0, uv[0], uv[1],
                    0, r01, 1, uv[2], uv[3],
                    1, r11, 1, uv[4], uv[5],
                    1, r10, 0, uv[6], uv[7]));
            if (shouldRenderBackwardUpFace(chunk, north, south, west, east, diagonals,
                    x, worldY, z, fluid)) {
                quads.add(quad(topLayer, BlockModels.FACE_BRIGHTNESS[0], tint,
                        1, r10, 0, uv[6], uv[7],
                        1, r11, 1, uv[4], uv[5],
                        0, r01, 1, uv[2], uv[3],
                        0, r00, 0, uv[0], uv[1]));
            }
        }

        /* BOTTOM — wenn unten weder gleiches Fluid noch ein opaker Block. */
        int below = sample(chunk, north, south, west, east, diagonals, x, worldY - 1, z);
        if (!isSameFluid(below, fluid) && !BlockRegistry.getState(below).isOpaqueCube()) {
            quads.add(quad(still, BlockModels.FACE_BRIGHTNESS[1], tint,
                    0, 0, 0, 0, 0,
                    1, 0, 0, 1, 0,
                    1, 0, 1, 1, 1,
                    0, 0, 1, 0, 1));
        }

        /* SEITEN — je gegen Nicht-Fluid und nicht-opaken Nachbarn, mit den beiden Kanten-Eckhöhen. */
        // north (z-): Kante z=0, Ecken h00 (x=0) / h10 (x=1)
        if (sideVisible(chunk, north, south, west, east, diagonals, x, worldY, z, fluid, 0, -1)) {
            float sideZ = sideInset(chunk, north, south, west, east, diagonals,
                    x, worldY, z, 0, -1);
            quads.add(quad(flow, BlockModels.FACE_BRIGHTNESS[2], tint,
                    1, 0, sideZ, 0, FLOW_SIDE_UV_SCALE,
                    0, 0, sideZ, FLOW_SIDE_UV_SCALE, FLOW_SIDE_UV_SCALE,
                    0, h00, sideZ, FLOW_SIDE_UV_SCALE, (1 - h00) * FLOW_SIDE_UV_SCALE,
                    1, h10, sideZ, 0, (1 - h10) * FLOW_SIDE_UV_SCALE));
        }
        // south (z+): Kante z=1, Ecken h01 (x=0) / h11 (x=1)
        if (sideVisible(chunk, north, south, west, east, diagonals, x, worldY, z, fluid, 0, 1)) {
            float sideZ = 1F - sideInset(chunk, north, south, west, east, diagonals,
                    x, worldY, z, 0, 1);
            quads.add(quad(flow, BlockModels.FACE_BRIGHTNESS[3], tint,
                    0, 0, sideZ, 0, FLOW_SIDE_UV_SCALE,
                    1, 0, sideZ, FLOW_SIDE_UV_SCALE, FLOW_SIDE_UV_SCALE,
                    1, h11, sideZ, FLOW_SIDE_UV_SCALE, (1 - h11) * FLOW_SIDE_UV_SCALE,
                    0, h01, sideZ, 0, (1 - h01) * FLOW_SIDE_UV_SCALE));
        }
        // west (x-): Kante x=0, Ecken h00 (z=0) / h01 (z=1)
        if (sideVisible(chunk, north, south, west, east, diagonals, x, worldY, z, fluid, -1, 0)) {
            float sideX = sideInset(chunk, north, south, west, east, diagonals,
                    x, worldY, z, -1, 0);
            quads.add(quad(flow, BlockModels.FACE_BRIGHTNESS[4], tint,
                    sideX, 0, 0, 0, FLOW_SIDE_UV_SCALE,
                    sideX, 0, 1, FLOW_SIDE_UV_SCALE, FLOW_SIDE_UV_SCALE,
                    sideX, h01, 1, FLOW_SIDE_UV_SCALE, (1 - h01) * FLOW_SIDE_UV_SCALE,
                    sideX, h00, 0, 0, (1 - h00) * FLOW_SIDE_UV_SCALE));
        }
        // east (x+): Kante x=1, Ecken h10 (z=0) / h11 (z=1)
        if (sideVisible(chunk, north, south, west, east, diagonals, x, worldY, z, fluid, 1, 0)) {
            float sideX = 1F - sideInset(chunk, north, south, west, east, diagonals,
                    x, worldY, z, 1, 0);
            quads.add(quad(flow, BlockModels.FACE_BRIGHTNESS[5], tint,
                    sideX, 0, 1, 0, FLOW_SIDE_UV_SCALE,
                    sideX, 0, 0, FLOW_SIDE_UV_SCALE, FLOW_SIDE_UV_SCALE,
                    sideX, h10, 0, FLOW_SIDE_UV_SCALE, (1 - h10) * FLOW_SIDE_UV_SCALE,
                    sideX, h11, 1, 0, (1 - h11) * FLOW_SIDE_UV_SCALE));
        }

        return quads.toArray(new BakedQuad[0]);
    }

    /** Eine Seite ist sichtbar, wenn der Nachbar weder dasselbe Fluid noch ein opaker Würfel ist. */
    private static boolean sideVisible(Chunk chunk, Chunk north, Chunk south, Chunk west, Chunk east, Chunk[] diagonals,
                                       int x, int worldY, int z, Block fluid, int dx, int dz) {
        int id = sample(chunk, north, south, west, east, diagonals, x + dx, worldY, z + dz);
        if (isSameFluid(id, fluid)) return false;
        return !BlockRegistry.getState(id).isOpaqueCube();
    }

    private static float sideInset(Chunk chunk, Chunk north, Chunk south, Chunk west, Chunk east, Chunk[] diagonals,
                                   int x, int worldY, int z, int dx, int dz) {
        BlockState neighbor = BlockRegistry.getState(sample(chunk, north, south, west, east, diagonals,
                x + dx, worldY, z + dz));
        return neighbor.isSolid() && !neighbor.isFluid()
                && neighbor.getRenderLayer() == de.skyengine.game.world.block.RenderLayer.TRANSLUCENT
                ? TRANSLUCENT_SIDE_EPSILON : 0F;
    }

    /**
     * Höhe einer Ecke im Minecraft-Stil (LiquidBlockRenderer): gewichtetes Mittel der
     * Sichthöhen von Selbst-Spalte, den beiden Kardinal-Nachbarn am Eck und der Diagonale.
     * Luft zählt mit Höhe 0 (zieht die Ecke herunter → steile Schräge), solide Blöcke
     * zählen gar nicht (Ecke bleibt hoch), hohe Spalten (Quelle/fallend, ≥ 0.8) zählen
     * 10-fach. Die Diagonale zählt nur, wenn einer der beiden Kardinal-Nachbarn selbst
     * Fluid ist (Höhe > 0) — Fluid „sieht" nicht um eine solide Ecke herum.
     */
    private static float corner(Chunk chunk, Chunk north, Chunk south, Chunk west, Chunk east, Chunk[] diagonals,
                                int x, int worldY, int z, Block fluid, BlockState self,
                                int cornerX, int cornerZ, boolean fluidAbove) {
        if (fluidAbove) return 1.0f;

        int dx = cornerX == 0 ? -1 : 1;
        int dz = cornerZ == 0 ? -1 : 1;
        float a = columnHeight(chunk, north, south, west, east, diagonals, x + dx, worldY, z, fluid);
        float b = columnHeight(chunk, north, south, west, east, diagonals, x, worldY, z + dz, fluid);
        if (a >= 1.0f || b >= 1.0f) return 1.0f;

        float d = -1f;
        if (a > 0f || b > 0f) {
            d = columnHeight(chunk, north, south, west, east, diagonals, x + dx, worldY, z + dz, fluid);
            if (d >= 1.0f) return 1.0f;
        }

        float sum = 0f;
        float weight = 0f;
        for (float h : new float[]{ownHeight(self), a, b, d}) {
            if (h >= 0.8f) {
                sum += h * 10f;
                weight += 10f;
            } else if (h >= 0f) {
                sum += h;
                weight += 1f;
            }
        }
        return sum / weight; // weight >= 1: die Selbst-Spalte zählt immer
    }

    /**
     * Sichthöhe einer Spalte für die Eck-Mittelung: gleiches Fluid → Eigenhöhe (bzw. 1.0
     * mit Fluid darüber), nicht-solide Blöcke (Luft, Pflanzen) → 0, solide Blöcke → -1
     * (zählen nicht mit).
     */
    private static float columnHeight(Chunk chunk, Chunk north, Chunk south, Chunk west, Chunk east, Chunk[] diagonals,
                                      int x, int y, int z, Block fluid) {
        int id = sample(chunk, north, south, west, east, diagonals, x, y, z);
        if (isSameFluid(id, fluid)) {
            if (isSameFluid(sample(chunk, north, south, west, east, diagonals, x, y + 1, z), fluid)) return 1.0f;
            return ownHeight(BlockRegistry.getState(id));
        }
        return BlockRegistry.getState(id).isSolid() ? -1f : 0f;
    }

    /**
     * Eigenhöhe einer Fluid-Spalte aus LEVEL/FALLING (ohne Nachbarbetrachtung), nach der
     * Minecraft-Formel {@code amount / 9}: Quelle und fallende Säule haben Amount 8 → 8/9;
     * horizontales Level 1..7 entspricht Amount 7..1. Voll (1.0) wird eine Zelle erst durch
     * gleiches Fluid darüber. Fluid-unabhängig; Lava wirkt nur „klobiger", weil sie pro Block
     * 2 Level verliert (dropOff 2: Level 2, 4, 6).
     */
    private static float ownHeight(BlockState s) {
        if (s.get(Properties.FALLING)) return SOURCE_HEIGHT;
        int level = Math.min(s.get(Properties.LEVEL), 7);
        return (8 - level) / 9.0f;
    }

    /** Sichtbare Oberkante (0..1) einer Fluid-Spalte aus LEVEL/FALLING – für Swim-/Höhenchecks. */
    public static float fluidHeight(BlockState s) {
        return ownHeight(s);
    }

    /**
     * True, wenn dieses Fluid-Top eine flache, stille Quell-Oberfläche auf voller Quellhöhe ist —
     * also greedy-fähig (mehrere solche Zellen sind koplanar und texturgleich). Bedingung: Quelle
     * (Level 0), nicht fallend und alle vier Eckhöhen auf {@link #SOURCE_HEIGHT}. Eine bloß
     * symmetrisch abgesenkte (aber flache) Uferzelle erfüllt das NICHT — sie darf nicht mit der
     * Quell-Ebene verschmelzen (Höhensprung/Z-Fighting).
     *
     * <p>Toleranz-Vergleich statt {@code ==}: {@link #corner} mittelt gewichtet, wodurch eine
     * echte Innenozean-Ecke ≈ 8/9 herauskommt, aber wegen Float-Rundung nicht bit-gleich mit
     * {@link #SOURCE_HEIGHT}. Die Rundung liegt bei ~1e-6, die nächst-niedrigere echte Eckhöhe
     * (eine Luft-/Schwächer-Fluid-Spalte am Eck) fällt um ≥ ~0,004 ab — {@code EPS} trennt sauber.
     */
    private static final float SOURCE_EPS = 1.0e-3f;

    private static boolean flatStillAtSource(BlockState state, float h00, float h10, float h11, float h01) {
        if (state.get(Properties.LEVEL) != 0 || state.get(Properties.FALLING)) return false;
        return atSourceHeight(h00) && atSourceHeight(h10) && atSourceHeight(h11) && atSourceHeight(h01);
    }

    private static boolean atSourceHeight(float h) {
        return Math.abs(h - SOURCE_HEIGHT) < SOURCE_EPS;
    }

    /**
     * Erkennt ein von {@link #build} erzeugtes, horizontales Quell-Top. Diese Quads tragen
     * absichtlich keine Face-Richtung, weil sie doppelseitig sein können; deshalb wird die
     * gemeinsame Y-Ebene aller sechs Dreiecksvertices geprüft. Seiten, Böden und geneigte
     * Strömungsoberflächen erfüllen die Bedingung nicht.
     */
    public static boolean isFlatSourceTop(BakedQuad quad) {
        float[] vertices = quad.vertices();
        for (int i = 1; i < vertices.length; i += 5) {
            if (Math.abs(vertices[i] - SOURCE_RENDER_HEIGHT) >= SOURCE_EPS) return false;
        }
        return true;
    }

    /**
     * Ob die Zelle ein zum Mergen geeignetes flach-stilles Top-Face hat (sichtbar, d.h. kein
     * gleiches Fluid darüber). Der ChunkMesher nutzt das für seinen gemergten Wasser-Pass und
     * gibt anschließend {@code skipMergedTop=true} an {@link #build} — die Merge-Bedingung ist
     * dort identisch (dieselben {@link #corner}-Werte, deterministisch pro Worker).
     */
    public static boolean isMergeableFlatStillTop(BlockState state,
                                                  Chunk chunk, Chunk north, Chunk south, Chunk west, Chunk east, Chunk[] diagonals,
                                                  int x, int worldY, int z) {
        Block fluid = state.getBlock();
        if (fluid.getFluidInfo() == null) return false;
        if (state.get(Properties.LEVEL) != 0 || state.get(Properties.FALLING)) return false;
        if (isSameFluid(sample(chunk, north, south, west, east, diagonals, x, worldY + 1, z), fluid)) {
            return false; // Top verdeckt -> nichts zu mergen
        }
        float h00 = corner(chunk, north, south, west, east, diagonals, x, worldY, z, fluid, state, 0, 0, false);
        float h10 = corner(chunk, north, south, west, east, diagonals, x, worldY, z, fluid, state, 1, 0, false);
        float h11 = corner(chunk, north, south, west, east, diagonals, x, worldY, z, fluid, state, 1, 1, false);
        float h01 = corner(chunk, north, south, west, east, diagonals, x, worldY, z, fluid, state, 0, 1, false);
        return flatStillAtSource(state, h00, h10, h11, h01);
    }

    /** Minecrafts 3x3-Test fuer die von unten sichtbare Rueckseite des Fluid-Tops. */
    public static boolean shouldRenderBackwardUpFace(Chunk chunk, Chunk north, Chunk south,
                                                     Chunk west, Chunk east, Chunk[] diagonals,
                                                     int x, int worldY, int z, Block fluid) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int id = sample(chunk, north, south, west, east, diagonals,
                        x + dx, worldY + 1, z + dz);
                if (!isSameFluid(id, fluid) && !BlockRegistry.getState(id).isOpaqueCube()) return true;
            }
        }
        return false;
    }

    private static boolean isSameFluid(int id, Block fluid) {
        BlockState s = BlockRegistry.getState(id);
        return s.isFluid() && s.getBlock() == fluid;
    }

    /**
     * Block an section-lokalen x/z (dürfen -1..SIZE sein) und Welt-Y — geteilte Auflösung
     * mit dem ChunkMesher (Diagonal-Konvention!), siehe {@link NeighborSampler}.
     */
    private static int sample(Chunk chunk, Chunk north, Chunk south, Chunk west, Chunk east, Chunk[] diagonals,
                                int x, int y, int z) {
        return NeighborSampler.sample(chunk, north, south, west, east, diagonals, x, y, z);
    }

    /** Baut ein Quad aus 4 Eckpunkten (A,B,C,D, CCW von außen) zu 6 Vertices (A,B,C,C,D,A). */
    private static BakedQuad quad(int layer, float brightness, int tint,
                                  float ax, float ay, float az, float au, float av,
                                  float bx, float by, float bz, float bu, float bv,
                                  float cx, float cy, float cz, float cu, float cv,
                                  float dx, float dy, float dz, float du, float dv) {
        float[] v = {
                ax, ay, az, au, av, bx, by, bz, bu, bv, cx, cy, cz, cu, cv,
                cx, cy, cz, cu, cv, dx, dy, dz, du, dv, ax, ay, az, au, av
        };
        return new BakedQuad(v, layer, BakedQuad.NO_CULL, brightness, tint);
    }
}
