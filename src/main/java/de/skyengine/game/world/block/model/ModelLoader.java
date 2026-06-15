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

    /* ---- Gson-DTOs ---- */
    public static final class RawModel { String parent; Map<String, String> textures; List<RawElement> elements; }
    public static final class RawElement { int[] from; int[] to; Map<String, RawFace> faces; }
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

        List<BakedQuad[]> parts = new ArrayList<>();
        List<AABB> boxes = new ArrayList<>();
        for (RawElement el : elements) {
            BoxElement be = toBox(el, tex).rotateX(xq).rotateY(yq);
            parts.add(be.bake());
            boxes.add(be.toAABB());
        }
        return new Baked(BlockModels.concat(parts.toArray(new BakedQuad[0][])), boxes.toArray(new AABB[0]));
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

    private static BoxElement toBox(RawElement el, Map<String, String> tex) {
        int[] t = {BakedQuad.NO_FACE, BakedQuad.NO_FACE, BakedQuad.NO_FACE,
                   BakedQuad.NO_FACE, BakedQuad.NO_FACE, BakedQuad.NO_FACE};
        int[] c = {BakedQuad.NO_CULL, BakedQuad.NO_CULL, BakedQuad.NO_CULL,
                   BakedQuad.NO_CULL, BakedQuad.NO_CULL, BakedQuad.NO_CULL};
        if (el.faces != null) {
            for (Map.Entry<String, RawFace> e : el.faces.entrySet()) {
                int idx = faceIndex(e.getKey());
                if (idx < 0) continue;
                RawFace face = e.getValue();
                String path = resolveRef(tex, face.texture);
                t[idx] = path == null ? 0 : BlockTextures.layerOf(path);
                c[idx] = face.cullface != null ? faceIndex(face.cullface) : BakedQuad.NO_CULL;
            }
        }
        return new BoxElement(
                ModelElements.px(el.from[0]), ModelElements.px(el.from[1]), ModelElements.px(el.from[2]),
                ModelElements.px(el.to[0]), ModelElements.px(el.to[1]), ModelElements.px(el.to[2]), t, c);
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
