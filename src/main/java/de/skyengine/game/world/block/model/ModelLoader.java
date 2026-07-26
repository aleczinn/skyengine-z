package de.skyengine.game.world.block.model;

import com.google.gson.Gson;
import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.block.BlockTextures;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lädt Block-Modelle (Minecraft-Stil) aus {@code game/models/} mit {@code parent}-
 * Vererbung, {@code #ref}-Texturvariablen und {@code elements} (from/to/faces).
 * {@link #bake} erzeugt aus einem Modellnamen + Rotation gebackene Quads UND die
 * Kollisions-AABBs (eine Quelle der Wahrheit), gecacht je (name,x,y).
 */
public final class ModelLoader {

    private static final Logger LOGGER = LogManager.getLogger(ModelLoader.class.getName());
    private static final Gson GSON = new Gson();

    private static final Map<String, RawModel> MODELS = new HashMap<>();
    private static final Map<String, Baked> CACHE = new HashMap<>();

    /** Ergebnis des Backens: Render-Quads + Kollisions-/Outline-Boxen (lokale 0..1). */
    public record Baked(BakedQuad[] quads, AABB[] boxes) {}

    /* ---- Gson-DTOs ----
       ambientocclusion: MC-Modellfeld, Default true. Bewusst der Wrapper Boolean — bei einem
       primitiven boolean liefert GSON für ein FEHLENDES Feld false und würde AO überall
       abschalten. Kleinschreibung ohne Trennzeichen ist der MC-Feldname (wie cullface). */
    public static final class RawModel { String parent; Map<String, String> textures; List<RawElement> elements; Boolean ambientocclusion; }
    public static final class RawElement { int[] from; int[] to; Map<String, RawFace> faces; boolean mirror; }
    public static final class RawFace { String texture; String cullface; int[] uv; }

    public static void load(File modelsRoot) {
        MODELS.clear();
        CACHE.clear();
        if (modelsRoot == null || !modelsRoot.isDirectory()) {
            LOGGER.warning("models-Ordner nicht gefunden: " + modelsRoot);
            return;
        }
        loadDir(modelsRoot, modelsRoot);
        LOGGER.info(MODELS.size() + " Modelle geladen");
    }

    private static void loadDir(File root, File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                loadDir(root, f);
            } else if (f.getName().endsWith(".json")) {
                String rel = root.toURI().relativize(f.toURI()).getPath();
                rel = rel.substring(0, rel.length() - ".json".length()); // "block/stone"
                try (FileReader r = new FileReader(f)) {
                    MODELS.put(rel, GSON.fromJson(r, RawModel.class));
                } catch (Exception e) {
                    LOGGER.error("Modell fehlerhaft: " + rel, e);
                }
            }
        }
    }

    public static Baked bake(String name, int xDeg, int yDeg) {
        return CACHE.computeIfAbsent(name + "|" + xDeg + "|" + yDeg, k -> bakeUncached(name, xDeg, yDeg));
    }

    /** Texturlayer eines Modells (für Sonderfälle wie Cross, die nicht aus Boxen bestehen). */
    public static int textureLayer(String modelName, String key) {
        Map<String, String> tex = new HashMap<>();
        collectTextures(modelName, tex, 0);
        String path = resolveRef(tex, "#" + key);
        if (path == null) {
            LOGGER.warning("Textur '" + key + "' fehlt in Modell " + modelName);
            return 0;
        }
        return BlockTextures.layerOf(path);
    }

    private static Baked bakeUncached(String name, int xDeg, int yDeg) {
        Map<String, String> tex = new HashMap<>();
        collectTextures(name, tex, 0);
        List<RawElement> elements = collectElements(name, 0);

        int xq = Math.floorMod(xDeg / 90, 4);
        int yq = Math.floorMod(yDeg / 90, 4);

        boolean ao = collectAmbientOcclusion(name, 0);

        List<BakedQuad[]> parts = new ArrayList<>();
        List<AABB> boxes = new ArrayList<>();
        for (RawElement el : elements) {
            BoxElement be = toBox(el, tex).rotateX(xq).rotateY(yq);
            BakedQuad[] quads = be.bake();
            if (!ao) stripDirection(quads);
            parts.add(quads);
            boxes.add(be.toAABB());
        }
        return new Baked(BlockModels.concat(parts.toArray(new BakedQuad[0][])), boxes.toArray(new AABB[0]));
    }

    /**
     * Nimmt den Quads ihre geometrische Richtung ({@code ambientocclusion: false}) — der Mesher
     * gated AO über {@code face() >= 0}, damit bleiben sie voll hell. Türen sind der Vanilla-Fall:
     * in einer Nische wären sonst alle vier Ecken voll eingeschlossen und die Tür durchgehend dunkel.
     * {@code cullFace} bleibt erhalten, Culling und Greedy-Pass sind also unbeeinflusst.
     */
    private static void stripDirection(BakedQuad[] quads) {
        for (int i = 0; i < quads.length; i++) {
            BakedQuad q = quads[i];
            /* Vertex-Array wird geteilt (nie mutiert) — nur die Richtung ändert sich. */
            quads[i] = new BakedQuad(q.vertices(), q.textureLayer(), q.cullFace(), BakedQuad.NO_DIRECTION,
                    q.brightness(), q.tint(), q.tintType());
        }
    }

    private static void collectTextures(String name, Map<String, String> out, int depth) {
        if (depth > 20) return;
        RawModel m = MODELS.get(name);
        if (m == null) { LOGGER.warning("Modell fehlt: " + name); return; }
        if (m.parent != null) collectTextures(m.parent, out, depth + 1);
        if (m.textures != null) out.putAll(m.textures);
    }

    private static List<RawElement> collectElements(String name, int depth) {
        if (depth > 20) return List.of();
        RawModel m = MODELS.get(name);
        if (m == null) return List.of();
        if (m.elements != null) return m.elements;
        if (m.parent != null) return collectElements(m.parent, depth + 1);
        return List.of();
    }

    /** Erbt {@code ambientocclusion} wie in Minecraft: erstes Vorkommen der Kette gewinnt, Default an. */
    private static boolean collectAmbientOcclusion(String name, int depth) {
        if (depth > 20) return true;
        RawModel m = MODELS.get(name);
        if (m == null) return true;
        if (m.ambientocclusion != null) return m.ambientocclusion;
        if (m.parent != null) return collectAmbientOcclusion(m.parent, depth + 1);
        return true;
    }

    private static BoxElement toBox(RawElement el, Map<String, String> tex) {
        int[] t = {BakedQuad.NO_FACE, BakedQuad.NO_FACE, BakedQuad.NO_FACE,
                   BakedQuad.NO_FACE, BakedQuad.NO_FACE, BakedQuad.NO_FACE};
        int[] c = {BakedQuad.NO_CULL, BakedQuad.NO_CULL, BakedQuad.NO_CULL,
                   BakedQuad.NO_CULL, BakedQuad.NO_CULL, BakedQuad.NO_CULL};
        float[][] uv = null;
        if (el.faces != null) {
            for (Map.Entry<String, RawFace> e : el.faces.entrySet()) {
                int idx = faceIndex(e.getKey());
                if (idx < 0) continue;
                RawFace face = e.getValue();
                String path = resolveRef(tex, face.texture);
                t[idx] = path == null ? 0 : BlockTextures.layerOf(path);
                c[idx] = face.cullface != null ? faceIndex(face.cullface) : BakedQuad.NO_CULL;
                if (face.uv != null && face.uv.length == 4) {
                    if (uv == null) uv = new float[6][];
                    uv[idx] = cornerUv(idx, face.uv);
                }
            }
        }
        return new BoxElement(
                ModelElements.px(el.from[0]), ModelElements.px(el.from[1]), ModelElements.px(el.from[2]),
                ModelElements.px(el.to[0]), ModelElements.px(el.to[1]), ModelElements.px(el.to[2]), t, c, el.mirror, uv);
    }

    /**
     * Wandelt ein Minecraft-UV-Rechteck {@code [u0,v0,u1,v1]} (Pixel 0..16, v von oben) in die
     * vier Eck-UVs A,B,C,D (0..1) der jeweiligen Face um. Die Eckreihenfolge entspricht der in
     * {@link BlockModels#box}; für ein Voll-Face-UV deckt sich das mit dem Extent-Default.
     */
    private static float[] cornerUv(int face, int[] rect) {
        float u0 = rect[0] / 16f, v0 = rect[1] / 16f, u1 = rect[2] / 16f, v1 = rect[3] / 16f;
        return switch (face) {
            case 0 -> new float[]{u0, v0,  u0, v1,  u1, v1,  u1, v0}; // top:    A,B,C,D
            case 1 -> new float[]{u0, v0,  u1, v0,  u1, v1,  u0, v1}; // bottom:  A,B,C,D
            default -> new float[]{u0, v1,  u1, v1,  u1, v0,  u0, v0}; // Seiten (v0=oben)
        };
    }

    private static String resolveRef(Map<String, String> tex, String ref) {
        int guard = 0;
        while (ref != null && ref.startsWith("#") && guard++ < 20) {
            ref = tex.get(ref.substring(1));
        }
        return ref;
    }

    private static int faceIndex(String name) {
        return switch (name) {
            case "up" -> 0;
            case "down" -> 1;
            case "north" -> 2;
            case "south" -> 3;
            case "west" -> 4;
            case "east" -> 5;
            default -> -1;
        };
    }

    private ModelLoader() {}
}
