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

    /** Default-Wasserfarbe (gepackt 0xRRGGBB). Später positions-/biome-abhängig. Auch vom LOD genutzt. */
    public static final int WATER_TINT = 0x4076E6;

    private FluidGeometry() {}

    public static BakedQuad[] build(BlockState state,
                                    Chunk chunk, Chunk north, Chunk south, Chunk west, Chunk east, Chunk[] diagonals,
                                    int x, int worldY, int z) {
        Block fluid = state.getBlock();
        FluidInfo info = fluid.getFluidInfo();
        if (info == null) return new BakedQuad[0];
        int still = info.stillLayer;
        int flow = info.flowLayer;
        /* Wasser wird eingefärbt (Texturen sind grau); Lava ist bereits orange → neutral. */
        int tint = info.lava ? BakedQuad.WHITE : WATER_TINT;

        boolean fluidAbove = isSameFluid(sample(chunk, north, south, west, east, diagonals, x, worldY + 1, z), fluid);

        float h00 = corner(chunk, north, south, west, east, diagonals, x, worldY, z, fluid, state, 0, 0, fluidAbove);
        float h10 = corner(chunk, north, south, west, east, diagonals, x, worldY, z, fluid, state, 1, 0, fluidAbove);
        float h11 = corner(chunk, north, south, west, east, diagonals, x, worldY, z, fluid, state, 1, 1, fluidAbove);
        float h01 = corner(chunk, north, south, west, east, diagonals, x, worldY, z, fluid, state, 0, 1, fluidAbove);

        List<BakedQuad> quads = new ArrayList<>(6);

        /* Still-Textur nur für eine ruhige, flache Quelle; sonst die Flow-Textur. */
        boolean flat = h00 == h10 && h10 == h11 && h11 == h01;
        boolean stillTop = flat && state.get(Properties.LEVEL) == 0 && !state.get(Properties.FALLING);
        int topLayer = stillTop ? still : flow;

        /* TOP — nur wenn oben kein gleiches Fluid (sonst verdeckt). Bei fließendem Wasser wird die
           Flow-Textur entlang des Gefälles gedreht (UVs um die Mitte rotiert, wie Minecraft), damit
           die Animation sichtbar von der Quelle wegläuft. */
        if (!fluidAbove) {
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
                        diff = own - (ownHeight(BlockRegistry.getState(bid)) - 8f / 9f);
                    }
                }
                velX += dx * diff;
                velZ += dz * diff;
            }
            float[] uv; // u,v je Ecke in Reihenfolge A(0,0) B(0,1) C(1,1) D(1,0)
            if (stillTop || (Math.abs(velX) < 1.0e-4f && Math.abs(velZ) < 1.0e-4f)) {
                uv = new float[]{0, 0, 0, 1, 1, 1, 1, 0};
            } else {
                float angle = (float) Math.atan2(velZ, velX) - (float) (Math.PI / 2.0);
                float s = (float) Math.sin(angle) * 0.5f;
                float c = (float) Math.cos(angle) * 0.5f;
                uv = new float[]{
                        0.5f - c - s, 0.5f - c + s,
                        0.5f - c + s, 0.5f + c + s,
                        0.5f + c + s, 0.5f + c - s,
                        0.5f + c - s, 0.5f - c - s
                };
            }
            quads.add(quad(topLayer, BlockModels.FACE_BRIGHTNESS[0], tint,
                    0, h00, 0, uv[0], uv[1],
                    0, h01, 1, uv[2], uv[3],
                    1, h11, 1, uv[4], uv[5],
                    1, h10, 0, uv[6], uv[7]));
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
            quads.add(quad(flow, BlockModels.FACE_BRIGHTNESS[2], tint,
                    1, 0, 0, 0, 1,
                    0, 0, 0, 1, 1,
                    0, h00, 0, 1, 1 - h00,
                    1, h10, 0, 0, 1 - h10));
        }
        // south (z+): Kante z=1, Ecken h01 (x=0) / h11 (x=1)
        if (sideVisible(chunk, north, south, west, east, diagonals, x, worldY, z, fluid, 0, 1)) {
            quads.add(quad(flow, BlockModels.FACE_BRIGHTNESS[3], tint,
                    0, 0, 1, 0, 1,
                    1, 0, 1, 1, 1,
                    1, h11, 1, 1, 1 - h11,
                    0, h01, 1, 0, 1 - h01));
        }
        // west (x-): Kante x=0, Ecken h00 (z=0) / h01 (z=1)
        if (sideVisible(chunk, north, south, west, east, diagonals, x, worldY, z, fluid, -1, 0)) {
            quads.add(quad(flow, BlockModels.FACE_BRIGHTNESS[4], tint,
                    0, 0, 0, 0, 1,
                    0, 0, 1, 1, 1,
                    0, h01, 1, 1, 1 - h01,
                    0, h00, 0, 0, 1 - h00));
        }
        // east (x+): Kante x=1, Ecken h10 (z=0) / h11 (z=1)
        if (sideVisible(chunk, north, south, west, east, diagonals, x, worldY, z, fluid, 1, 0)) {
            quads.add(quad(flow, BlockModels.FACE_BRIGHTNESS[5], tint,
                    1, 0, 1, 0, 1,
                    1, 0, 0, 1, 1,
                    1, h10, 0, 1, 1 - h10,
                    1, h11, 1, 0, 1 - h11));
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
     * Minecraft-Formel {@code (8 - level) / 9}: Quelle (Level 0) → 8/9, Level 7 → 1/9, fallende
     * Säule → voller Block. Fluid-unabhängig; Lava wirkt nur „klobiger", weil sie pro Block
     * 2 Level verliert (dropOff 2: Level 2, 4, 6).
     */
    private static float ownHeight(BlockState s) {
        if (s.get(Properties.FALLING)) return 1.0f;
        int level = Math.min(s.get(Properties.LEVEL), 7);
        return (8 - level) / 9.0f;
    }

    /** Sichtbare Oberkante (0..1) einer Fluid-Spalte aus LEVEL/FALLING – für Swim-/Höhenchecks. */
    public static float fluidHeight(BlockState s) {
        return ownHeight(s);
    }

    private static boolean isSameFluid(int id, Block fluid) {
        BlockState s = BlockRegistry.getState(id);
        return s.isFluid() && s.getBlock() == fluid;
    }

    /**
     * Block an section-lokalen x/z (dürfen -1..SIZE sein) und Welt-Y, über die 4
     * Kardinal- und 4 Diagonal-Nachbar-Chunks ({@code diagonals} in Reihenfolge NW, NE, SW, SE
     * — so liefert sie der ChunkManager).
     */
    private static int sample(Chunk chunk, Chunk north, Chunk south, Chunk west, Chunk east, Chunk[] diagonals,
                                int x, int y, int z) {
        int size = ChunkSection.SIZE;
        if (x < 0 || x >= size) {
            if (z < 0 || z >= size) { // Diagonal-Ecke über zwei Chunk-Grenzen
                Chunk c = diagonals[(z < 0 ? 0 : 2) + (x < 0 ? 0 : 1)];
                return c != null ? c.getBlock(x < 0 ? size - 1 : 0, y, z < 0 ? size - 1 : 0) : 0;
            }
            Chunk c = x < 0 ? west : east;
            return c != null ? c.getBlock(x < 0 ? size - 1 : 0, y, z) : 0;
        }
        if (z < 0 || z >= size) {
            Chunk c = z < 0 ? north : south;
            return c != null ? c.getBlock(x, y, z < 0 ? size - 1 : 0) : 0;
        }
        return chunk.getBlock(x, y, z);
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
