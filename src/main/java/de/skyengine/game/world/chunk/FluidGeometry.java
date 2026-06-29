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
 * weitere Nachbarprüfung emittiert. Diagonale über zwei Chunk-Grenzen werden mit
 * {@code 0} (Luft) angenähert (kleiner Naht-Effekt am Chunk-Rand).
 */
public final class FluidGeometry {

    /** Sichtbare Oberkante einer Quelle (14/16, wie MC-Wasser). */
    private static final float SOURCE_HEIGHT = 0.875f;

    /** Default-Wasserfarbe (gepackt 0xRRGGBB). Später positions-/biome-abhängig. */
    private static final int WATER_TINT = 0x4076E6;

    private FluidGeometry() {}

    public static BakedQuad[] build(BlockState state,
                                    Chunk chunk, Chunk north, Chunk south, Chunk west, Chunk east,
                                    int x, int worldY, int z) {
        Block fluid = state.getBlock();
        FluidInfo info = fluid.getFluidInfo();
        if (info == null) return new BakedQuad[0];
        int still = info.stillLayer;
        int flow = info.flowLayer;
        /* Wasser wird eingefärbt (Texturen sind grau); Lava ist bereits orange → neutral. */
        int tint = info.lava ? BakedQuad.WHITE : WATER_TINT;

        boolean fluidAbove = isSameFluid(sample(chunk, north, south, west, east, x, worldY + 1, z), fluid);

        float h00 = corner(chunk, north, south, west, east, x, worldY, z, fluid, state, 0, 0, fluidAbove);
        float h10 = corner(chunk, north, south, west, east, x, worldY, z, fluid, state, 1, 0, fluidAbove);
        float h11 = corner(chunk, north, south, west, east, x, worldY, z, fluid, state, 1, 1, fluidAbove);
        float h01 = corner(chunk, north, south, west, east, x, worldY, z, fluid, state, 0, 1, fluidAbove);

        List<BakedQuad> quads = new ArrayList<>(6);

        /* Still-Textur nur für eine ruhige, flache Quelle; sonst die Flow-Textur. */
        boolean flat = h00 == h10 && h10 == h11 && h11 == h01;
        boolean stillTop = flat && state.get(Properties.LEVEL) == 0 && !state.get(Properties.FALLING);
        int topLayer = stillTop ? still : flow;

        /* TOP — nur wenn oben kein gleiches Fluid (sonst verdeckt). Bei fließendem Wasser wird die
           Flow-Textur entlang des Gefälles gedreht (UVs um die Mitte rotiert, wie Minecraft), damit
           die Animation sichtbar von der Quelle wegläuft. */
        if (!fluidAbove) {
            /* Fließrichtung (bergab) aus dem Höhen-Gradienten der Ecken: West-Ost bzw. Nord-Süd. */
            float velX = (h00 + h01) - (h10 + h11);
            float velZ = (h00 + h10) - (h01 + h11);
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
        short below = sample(chunk, north, south, west, east, x, worldY - 1, z);
        if (!isSameFluid(below, fluid) && !BlockRegistry.getState(below).isOpaqueCube()) {
            quads.add(quad(still, BlockModels.FACE_BRIGHTNESS[1], tint,
                    0, 0, 0, 0, 0,
                    1, 0, 0, 1, 0,
                    1, 0, 1, 1, 1,
                    0, 0, 1, 0, 1));
        }

        /* SEITEN — je gegen Nicht-Fluid und nicht-opaken Nachbarn, mit den beiden Kanten-Eckhöhen. */
        // north (z-): Kante z=0, Ecken h00 (x=0) / h10 (x=1)
        if (sideVisible(chunk, north, south, west, east, x, worldY, z, fluid, 0, -1)) {
            quads.add(quad(flow, BlockModels.FACE_BRIGHTNESS[2], tint,
                    1, 0, 0, 0, 1,
                    0, 0, 0, 1, 1,
                    0, h00, 0, 1, 1 - h00,
                    1, h10, 0, 0, 1 - h10));
        }
        // south (z+): Kante z=1, Ecken h01 (x=0) / h11 (x=1)
        if (sideVisible(chunk, north, south, west, east, x, worldY, z, fluid, 0, 1)) {
            quads.add(quad(flow, BlockModels.FACE_BRIGHTNESS[3], tint,
                    0, 0, 1, 0, 1,
                    1, 0, 1, 1, 1,
                    1, h11, 1, 1, 1 - h11,
                    0, h01, 1, 0, 1 - h01));
        }
        // west (x-): Kante x=0, Ecken h00 (z=0) / h01 (z=1)
        if (sideVisible(chunk, north, south, west, east, x, worldY, z, fluid, -1, 0)) {
            quads.add(quad(flow, BlockModels.FACE_BRIGHTNESS[4], tint,
                    0, 0, 0, 0, 1,
                    0, 0, 1, 1, 1,
                    0, h01, 1, 1, 1 - h01,
                    0, h00, 0, 0, 1 - h00));
        }
        // east (x+): Kante x=1, Ecken h10 (z=0) / h11 (z=1)
        if (sideVisible(chunk, north, south, west, east, x, worldY, z, fluid, 1, 0)) {
            quads.add(quad(flow, BlockModels.FACE_BRIGHTNESS[5], tint,
                    1, 0, 1, 0, 1,
                    1, 0, 0, 1, 1,
                    1, h10, 0, 1, 1 - h10,
                    1, h11, 1, 0, 1 - h11));
        }

        return quads.toArray(new BakedQuad[0]);
    }

    /** Eine Seite ist sichtbar, wenn der Nachbar weder dasselbe Fluid noch ein opaker Würfel ist. */
    private static boolean sideVisible(Chunk chunk, Chunk north, Chunk south, Chunk west, Chunk east,
                                       int x, int worldY, int z, Block fluid, int dx, int dz) {
        short id = sample(chunk, north, south, west, east, x + dx, worldY, z + dz);
        if (isSameFluid(id, fluid)) return false;
        return !BlockRegistry.getState(id).isOpaqueCube();
    }

    /**
     * Höhe einer Ecke = Mittel der Eigenhöhen der bis zu 4 am Eck zusammentreffenden
     * Fluid-Spalten. Spalte mit Fluid darüber bzw. eine Quelle „voll" → 1.0.
     */
    private static float corner(Chunk chunk, Chunk north, Chunk south, Chunk west, Chunk east,
                                int x, int worldY, int z, Block fluid, BlockState self,
                                int cornerX, int cornerZ, boolean fluidAbove) {
        if (fluidAbove) return 1.0f;

        float sum = 0f;
        int count = 0;
        boolean anyFull = false;

        for (int dx = cornerX - 1; dx <= cornerX; dx++) {
            for (int dz = cornerZ - 1; dz <= cornerZ; dz++) {
                short id = sample(chunk, north, south, west, east, x + dx, worldY, z + dz);
                if (!isSameFluid(id, fluid)) continue;
                count++;
                if (isSameFluid(sample(chunk, north, south, west, east, x + dx, worldY + 1, z + dz), fluid)) {
                    anyFull = true;
                } else {
                    sum += ownHeight(BlockRegistry.getState(id));
                }
            }
        }

        if (anyFull) return 1.0f;
        if (count == 0) return ownHeight(self); // sollte nicht vorkommen (self ist Fluid)
        return sum / count;
    }

    /** Eigenhöhe einer Fluid-Spalte aus LEVEL/FALLING (ohne Nachbarbetrachtung). */
    private static float ownHeight(BlockState s) {
        if (s.get(Properties.FALLING)) return 1.0f;
        int level = s.get(Properties.LEVEL);
        if (level <= 0) return SOURCE_HEIGHT;
        /* Reichweiten-relativ: über die ganze Reichweite von voll auf dünn. Lava (spread 3) fällt
           damit steiler/dünner ab als Wasser (spread 7). level 1 -> hoch, level spread -> dünn. */
        int spread = s.getBlock().getFluidInfo().spread;
        int amount = spread + 1 - Math.min(level, spread);
        return amount / (spread + 1.0f) * SOURCE_HEIGHT;
    }

    private static boolean isSameFluid(short id, Block fluid) {
        BlockState s = BlockRegistry.getState(id);
        return s.isFluid() && s.getBlock() == fluid;
    }

    /**
     * Block an section-lokalen x/z (dürfen -1..SIZE sein) und Welt-Y, über die 4
     * Kardinal-Nachbar-Chunks. Diagonale über zwei Grenzen → 0 (Luft, Fallback).
     */
    private static short sample(Chunk chunk, Chunk north, Chunk south, Chunk west, Chunk east,
                                int x, int y, int z) {
        int size = ChunkSection.SIZE;
        if (x < 0 || x >= size) {
            if (z < 0 || z >= size) return 0; // Diagonal-Ecke: nicht erreichbar
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
