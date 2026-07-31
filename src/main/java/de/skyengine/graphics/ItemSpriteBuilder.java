package de.skyengine.graphics;

import de.skyengine.core.file.FileHandle;
import de.skyengine.core.file.FileType;
import de.skyengine.game.world.block.BlockTextures;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * Baut aus einem flachen Item-Icon die Geometrie eines 1 px dicken 3D-Sprites (Vorbild MCs
 * {@code ItemModelGenerator}): Vorder- und Rückseite über die volle Fläche, dazu eine Seitenwand
 * an jeder Alpha-Kante des PNG. Geliefert wird das interleaved Vertex-Array — die VAO-Hülle
 * bleibt Sache des jeweiligen Renderers, die haben jeweils ihre eigene.
 *
 * <p>Geteilt von der gehaltenen Hand ({@code HeldItemMeshes}) und den gedroppten Items
 * ({@code EntityRenderer}). Die vier Helligkeiten sind Parameter, weil sie POSE-ABHÄNGIG sind:
 * in der First-Person-Haltung dominiert die große flache Fläche den Blick und wird bewusst
 * abgedunkelt, am rotierenden Boden-Item gilt dagegen die normale Block-Konvention.
 *
 * <p>Die Texturpfade sind dieselben wie bei den Icons, {@link BlockTextures#layerOf} schlägt hier
 * also nur bereits registrierte Layer nach — es kommt keine neue Textur dazu (das wäre nach dem
 * Bau des TextureArrays ein kaputter Layer-Index).
 */
public final class ItemSpriteBuilder {

    /** Vertex-Layout beider Nutzer: pos3 + texCoord3(u,v,layer) + rgb3. */
    public static final int FLOATS_PER_VERTEX = 9;

    /* 1 px Dicke um z=0.5 (Sprite liegt in x/y bei 0..1). */
    private static final float Z_BACK = 0.5F - 0.5F / 16F;
    private static final float Z_FRONT = 0.5F + 0.5F / 16F;

    private ItemSpriteBuilder() {}

    /**
     * Extrudiertes Sprite aus {@code path}. Ist das PNG nicht lesbar, fällt die Methode auf ein
     * doppelseitiges flaches Quad zurück ({@link #flat}).
     *
     * @param front Helligkeit der großen Vorder-/Rückseite
     * @param side  Helligkeit der linken/rechten Wand
     * @param top   Helligkeit der oberen Wand
     * @param bottom Helligkeit der unteren Wand
     */
    public static float[] extrude(String path, int tint, float front, float side, float top, float bottom) {
        int layer = BlockTextures.layerOf(path);
        float r = ((tint >> 16) & 0xFF) / 255F;
        float g = ((tint >> 8) & 0xFF) / 255F;
        float b = (tint & 0xFF) / 255F;

        FileHandle file = new FileHandle(path, FileType.RESOURCE);
        int w, h;
        ByteBuffer pixels;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer wB = stack.mallocInt(1), hB = stack.mallocInt(1), cB = stack.mallocInt(1);
            pixels = file.exists() ? STBImage.stbi_load(file.path(), wB, hB, cB, 4) : null;
            if (pixels == null) {
                return flat(new String[]{path}, tint, front);
            }
            w = wB.get(0);
            h = hB.get(0);
        }

        /* Wände zählen (ein Quad je Alpha-Kante), dann exakt allokieren. */
        int walls = 0;
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                if (!opaque(pixels, w, h, px, py)) continue;
                if (!opaque(pixels, w, h, px - 1, py)) walls++;
                if (!opaque(pixels, w, h, px + 1, py)) walls++;
                if (!opaque(pixels, w, h, px, py - 1)) walls++;
                if (!opaque(pixels, w, h, px, py + 1)) walls++;
            }
        }

        float[] data = new float[(2 + walls) * 6 * FLOATS_PER_VERTEX];
        int p = 0;
        /* Vorderseite (volle UV; Transparenz macht der discard) + gespiegelte Rückseite.
           Mesh-y 0 = unten = Textur-v 1 (Pixelzeile h-1). */
        p = quad(data, p,
                0, 0, Z_FRONT, 1, 0, Z_FRONT, 1, 1, Z_FRONT, 0, 1, Z_FRONT,
                0, 1, 1, 1, 1, 0, 0, 0, layer, r * front, g * front, b * front);
        p = quad(data, p,
                1, 0, Z_BACK, 0, 0, Z_BACK, 0, 1, Z_BACK, 1, 1, Z_BACK,
                1, 1, 0, 1, 0, 0, 1, 0, layer, r * front, g * front, b * front);

        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                if (!opaque(pixels, w, h, px, py)) continue;
                float x0 = px / (float) w, x1 = (px + 1) / (float) w;
                float yT = 1F - py / (float) h, yB = 1F - (py + 1) / (float) h;
                float u = (px + 0.5F) / w, v = (py + 0.5F) / h;   // Kanten-Farbe = Texel-Zentrum
                if (!opaque(pixels, w, h, px - 1, py)) {          // linke Wand (west)
                    p = wall(data, p, x0, yB, Z_BACK, x0, yB, Z_FRONT, x0, yT, Z_FRONT, x0, yT, Z_BACK, u, v, layer, r * side, g * side, b * side);
                }
                if (!opaque(pixels, w, h, px + 1, py)) {          // rechte Wand (ost)
                    p = wall(data, p, x1, yB, Z_FRONT, x1, yB, Z_BACK, x1, yT, Z_BACK, x1, yT, Z_FRONT, u, v, layer, r * side, g * side, b * side);
                }
                if (!opaque(pixels, w, h, px, py - 1)) {          // obere Wand (oben)
                    p = wall(data, p, x0, yT, Z_FRONT, x1, yT, Z_FRONT, x1, yT, Z_BACK, x0, yT, Z_BACK, u, v, layer, r * top, g * top, b * top);
                }
                if (!opaque(pixels, w, h, px, py + 1)) {          // untere Wand (unten)
                    p = wall(data, p, x0, yB, Z_BACK, x1, yB, Z_BACK, x1, yB, Z_FRONT, x0, yB, Z_FRONT, u, v, layer, r * bottom, g * bottom, b * bottom);
                }
            }
        }
        STBImage.stbi_image_free(pixels);
        return data;
    }

    /** Quad-Stapel x/y 0..1 bei z=0.5, jede Lage vorder- UND rückseitig (Tür = 2 Lagen). */
    public static float[] flat(String[] paths, int tint, float front) {
        float r = ((tint >> 16) & 0xFF) / 255F;
        float g = ((tint >> 8) & 0xFF) / 255F;
        float b = (tint & 0xFF) / 255F;
        int n = paths.length;
        float[] data = new float[n * 12 * FLOATS_PER_VERTEX];
        int p = 0;
        for (int i = 0; i < n; i++) {
            int layer = BlockTextures.layerOf(paths[i]);
            float ya = (float) i / n, yb = (float) (i + 1) / n;
            p = quad(data, p,
                    0, ya, 0.5F, 1, ya, 0.5F, 1, yb, 0.5F, 0, yb, 0.5F,
                    0, 1, 1, 1, 1, 0, 0, 0, layer, r * front, g * front, b * front);
            p = quad(data, p,
                    1, ya, 0.5F, 0, ya, 0.5F, 0, yb, 0.5F, 1, yb, 0.5F,
                    1, 1, 0, 1, 0, 0, 1, 0, layer, r * front, g * front, b * front);
        }
        return data;
    }

    private static boolean opaque(ByteBuffer pixels, int w, int h, int px, int py) {
        if (px < 0 || py < 0 || px >= w || py >= h) return false;
        return (pixels.get((py * w + px) * 4 + 3) & 0xFF) > 0;
    }

    /** Quad mit per-Vertex-UV (Front/Rückseite). */
    private static int quad(float[] d, int p,
                            float ax, float ay, float az, float bx, float by, float bz,
                            float cx, float cy, float cz, float dx, float dy, float dz,
                            float au, float av, float bu, float bv, float cu, float cv, float du, float dv,
                            int layer, float r, float g, float b) {
        p = vert(d, p, ax, ay, az, au, av, layer, r, g, b);
        p = vert(d, p, bx, by, bz, bu, bv, layer, r, g, b);
        p = vert(d, p, cx, cy, cz, cu, cv, layer, r, g, b);
        p = vert(d, p, ax, ay, az, au, av, layer, r, g, b);
        p = vert(d, p, cx, cy, cz, cu, cv, layer, r, g, b);
        p = vert(d, p, dx, dy, dz, du, dv, layer, r, g, b);
        return p;
    }

    /** Seitenwand-Quad mit konstantem UV (Texel-Zentrum der Kante). */
    private static int wall(float[] d, int p,
                            float ax, float ay, float az, float bx, float by, float bz,
                            float cx, float cy, float cz, float dx, float dy, float dz,
                            float u, float v, int layer, float r, float g, float b) {
        return quad(d, p, ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz,
                u, v, u, v, u, v, u, v, layer, r, g, b);
    }

    private static int vert(float[] d, int p, float x, float y, float z, float u, float v, int layer,
                            float r, float g, float b) {
        d[p++] = x; d[p++] = y; d[p++] = z; d[p++] = u; d[p++] = v; d[p++] = layer;
        d[p++] = r; d[p++] = g; d[p++] = b;
        return p;
    }
}
