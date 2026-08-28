package de.skyengine.game.world.block.model;

import com.google.gson.Gson;
import de.skyengine.core.resource.ResourceId;
import de.skyengine.core.resource.ResourceManager;
import de.skyengine.core.resource.Resources;
import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.block.BlockTextures;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** Schon gemeldete "Modell #ref"-Paare — die Warnung soll je Fundstelle einmal kommen. */
    private static final Set<String> WARNED_REFS = new HashSet<>();

    /** Ergebnis des Backens: Render-Quads, Form und Minecraft-{@code textures.particle}-Layer. */
    public record Baked(BakedQuad[] quads, AABB[] boxes, int particleLayer) {
        /** Kompatibilitätskonstruktor für generierte/Testmodelle ohne deklarierte Partikeltextur. */
        public Baked(BakedQuad[] quads, AABB[] boxes) {
            this(quads, boxes, quads.length == 0 ? -1 : quads[0].textureLayer());
        }
    }

    /* ---- Gson-DTOs ----
       ambientocclusion: MC-Modellfeld, Default true. Bewusst der Wrapper Boolean — bei einem
       primitiven boolean liefert GSON für ein FEHLENDES Feld false und würde AO überall
       abschalten. Kleinschreibung ohne Trennzeichen ist der MC-Feldname (wie cullface).
       Dasselbe gilt für RawElement.shade (Default true, s. fullBright). */
    public static final class RawModel {
        String parent;
        Map<String, String> textures;
        List<RawElement> elements;
        Boolean ambientocclusion;
        Map<String, RawDisplay> display;
        /* Mekanism's energy-cube loader stores independently selectable pieces in named arrays. */
        List<RawElement> frame, bottomLEDs, bottomPort, topLEDs, topPort,
                frontLEDs, frontPort, backLEDs, backPort,
                rightLEDs, rightPort, leftLEDs, leftPort;
    }
    /** MC-Display-Sektion je Kontext (gui, firstperson_righthand, ...): rotation/translation/scale. */
    public static final class RawDisplay { float[] rotation; float[] translation; float[] scale; }
    /* from/to bewusst float: MC-Modelle nutzen Halbpixel (Wandfackel 3.5/19.5) und Werte
       ausserhalb 0..16 (Ueberstaende). Ganzzahlige Bestandsmodelle parsen unveraendert. */
    public static final class RawElement { float[] from; float[] to; Map<String, RawFace> faces; boolean mirror; RawRotation rotation; Boolean shade; }
    /**
     * MC-Elementrotation um eine beliebige Achse mit beliebigem Winkel (Fackel: z/-22.5).
     * origin in 0..16-Pixeln, axis = x|y|z, angle in Grad, rescale = Aufblähen auf die alte
     * Kantenlänge (1/cos).
     */
    public static final class RawRotation { float[] origin; String axis; float angle; boolean rescale; }
    /** rotation: MC-Feld, dreht die Textur IN der Face um 0/90/180/270 Grad (Wrapper = optional). */
    /** Minecraft model UVs are floating point; several Mekanism models use half-texel values. */
    public static final class RawFace { String texture; String cullface; float[] uv; Integer rotation; }

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

    /**
     * Laedt den effektiven Modellbestand aus dem Ressourcen-Stack. Gleichnamige Dateien
     * werden bereits vom ResourceManager nach Pack-Prioritaet aufgeloest.
     */
    public static void loadResources() {
        MODELS.clear();
        CACHE.clear();
        try {
            for (Map.Entry<ResourceId, ResourceManager.Match> entry
                    : Resources.get().listResolved("models/").entrySet()) {
                ResourceId id = entry.getKey();
                if (!id.path().startsWith("models/") || !id.path().endsWith(".json")) continue;
                String rel = id.path().substring("models/".length(), id.path().length() - ".json".length());
                try (InputStreamReader reader = new InputStreamReader(entry.getValue().open(), StandardCharsets.UTF_8)) {
                    String key = id.namespace().equals(ResourceId.DEFAULT_NAMESPACE)
                            ? rel : id.namespace() + ":" + rel;
                    MODELS.put(key, GSON.fromJson(reader, RawModel.class));
                } catch (Exception e) {
                    throw new IllegalArgumentException("Modell fehlerhaft: " + id, e);
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Modelle konnten nicht aufgelistet werden", e);
        }
        LOGGER.info(MODELS.size() + " Modelle aus Ressourcen-Stack geladen");
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

    /**
     * Erzeugt aus den Blockdefinitionen die "virtuellen" Modelle: je Eintrag von
     * {@code model}/{@code models} ein synthetischer {@link RawModel} mit dem Rumpf als
     * {@code parent} und der {@code textures}-Map des Blocks. Der Name ist exakt der, den
     * variants/multipart bzw. der Auto-Default ohnehin erwarten ({@code block/<id><suffix>}) —
     * dadurch ändert sich am Bake- und Cache-Pfad nichts, es entfällt nur die Datei.
     *
     * <p>MUSS nach {@link #load} laufen (das leert MODELS und CACHE) und vor dem ersten
     * {@link #bake}. Eine noch vorhandene gleichnamige Datei gewinnt und wird gemeldet — so
     * bleibt die schrittweise Migration monoton.
     */
    public static void registerBlockModels(List<BlockDefinition> definitions) {
        int created = 0;
        for (BlockDefinition def : definitions) {
            Map<String, String> rumps = rumpsOf(def);
            if (rumps.isEmpty()) continue;
            String path = Identifier.of(def.id).path();
            for (Map.Entry<String, String> e : rumps.entrySet()) {
                String name = "block/" + path + e.getKey();
                if (MODELS.containsKey(name)) {
                    LOGGER.warning("Modell-Datei ueberdeckt die Block-Definition: " + name);
                    continue;
                }
                RawModel m = new RawModel();
                m.parent = e.getValue();
                m.textures = def.textures;
                MODELS.put(name, m);
                created++;
            }
        }
        LOGGER.info(created + " Modelle aus Block-Definitionen erzeugt");
    }

    /** Suffix -> Rumpf; {@code model} ist die Kurzform für den leeren Suffix. */
    private static Map<String, String> rumpsOf(BlockDefinition def) {
        Map<String, String> out = new LinkedHashMap<>();
        if (def.model != null) out.put("", def.model);
        if (def.models != null) out.putAll(def.models);
        return out;
    }

    /** Akzeptiert sowohl alte {@code block/foo}- als auch Minecraft-artige Namespace-IDs. */
    private static String modelKey(String name) {
        ResourceId id = ResourceId.of(name);
        String path = id.path();
        if (path.startsWith("models/")) path = path.substring("models/".length());
        if (path.endsWith(".json")) path = path.substring(0, path.length() - ".json".length());
        return id.namespace().equals(ResourceId.DEFAULT_NAMESPACE) ? path : id.namespace() + ":" + path;
    }

    /** Wandelt Modell-Textur-IDs in den vom Ressourcenmanager erwarteten Asset-Pfad um. */
    private static String texturePath(String name) {
        ResourceId id = ResourceId.of(name);
        String path = id.path();
        if (!path.startsWith("textures/")) path = "textures/" + path;
        if (!path.endsWith(".png")) path += ".png";
        return new ResourceId(id.namespace(), path).sourcePath();
    }

    public static Baked bake(String name, int xDeg, int yDeg) {
        return bake(name, xDeg, yDeg, false);
    }

    /**
     * @param uvlock Textur weltachsenfest halten statt sie mitzudrehen (MC-{@code uvlock}:
     *               Treppen und Zäune ja, Stämme nie). Muss im Cache-Key stehen — sonst gewinnt
     *               der erste Bake für beide Varianten desselben Modells.
     */
    public static Baked bake(String name, int xDeg, int yDeg, boolean uvlock) {
        String key = modelKey(name);
        return CACHE.computeIfAbsent(key + "|" + xDeg + "|" + yDeg + "|" + uvlock,
                k -> bakeUncached(key, xDeg, yDeg, uvlock));
    }

    /** Bakes one named element group used by Mekanism's dynamic energy-cube model. */
    public static Baked bakeGroup(String name, String group) {
        String key = modelKey(name);
        return CACHE.computeIfAbsent(key + "|group|" + group,
                ignored -> bakeGroupUncached(key, group));
    }

    private static Baked bakeGroupUncached(String name, String group) {
        Map<String, String> tex = new HashMap<>();
        collectTextures(name, tex, 0);
        List<RawElement> elements = collectGroup(name, group, 0);
        boolean ao = collectAmbientOcclusion(name, 0);
        List<BakedQuad[]> parts = new ArrayList<>();
        List<AABB> boxes = new ArrayList<>();
        for (RawElement el : elements) {
            if (el.rotation != null) {
                BakedQuad[] quads = rotateQuads(toBox(name, el, tex).bake(), el.rotation, 0, 0);
                if (el.shade == Boolean.FALSE) fullBright(quads);
                parts.add(quads);
                boxes.add(enclosingBox(quads));
            } else {
                BoxElement box = toBox(name, el, tex);
                BakedQuad[] quads = box.bake();
                if (!ao) stripDirection(quads);
                if (el.shade == Boolean.FALSE) fullBright(quads);
                parts.add(quads);
                boxes.add(box.toAABB());
            }
        }
        BakedQuad[] quads = BlockModels.concat(parts.toArray(new BakedQuad[0][]));
        String particlePath = resolveRef(tex, "#particle");
        int particle = particlePath != null ? BlockTextures.layerOf(texturePath(particlePath))
                : quads.length == 0 ? -1 : quads[0].textureLayer();
        return new Baked(quads, boxes.toArray(new AABB[0]), particle);
    }

    private static List<RawElement> collectGroup(String name, String group, int depth) {
        if (depth > 20) return List.of();
        RawModel model = MODELS.get(modelKey(name));
        if (model == null) return List.of();
        List<RawElement> found = switch (group) {
            case "frame" -> model.frame;
            case "bottomLEDs" -> model.bottomLEDs;
            case "bottomPort" -> model.bottomPort;
            case "topLEDs" -> model.topLEDs;
            case "topPort" -> model.topPort;
            case "frontLEDs" -> model.frontLEDs;
            case "frontPort" -> model.frontPort;
            case "backLEDs" -> model.backLEDs;
            case "backPort" -> model.backPort;
            case "rightLEDs" -> model.rightLEDs;
            case "rightPort" -> model.rightPort;
            case "leftLEDs" -> model.leftLEDs;
            case "leftPort" -> model.leftPort;
            default -> null;
        };
        if (found != null) return found;
        return model.parent == null ? List.of() : collectGroup(model.parent, group, depth + 1);
    }

    /**
     * Display-Transform eines Modells für einen Kontext ({@code firstperson_righthand},
     * {@code thirdperson_righthand}, ...), MC-Format. Rotation in Grad, Translation in Pixeln
     * (0..16), Scale als Faktor. {@code null} = kein Eintrag, Aufrufer nimmt seinen Default.
     */
    public record Display(float[] rotation, float[] translation, float[] scale) {}

    /** Erbt wie in Minecraft über die parent-Kette: der erste Treffer für den Kontext gewinnt. */
    public static Display display(String modelName, String slot) {
        RawDisplay raw = collectDisplay(modelName, slot, 0);
        if (raw == null) return null;
        return new Display(
                raw.rotation != null ? raw.rotation : new float[]{0, 0, 0},
                raw.translation != null ? raw.translation : new float[]{0, 0, 0},
                raw.scale != null ? raw.scale : new float[]{1, 1, 1});
    }

    private static RawDisplay collectDisplay(String name, String slot, int depth) {
        if (depth > 20) return null;
        RawModel m = MODELS.get(modelKey(name));
        if (m == null) return null;
        if (m.display != null && m.display.get(slot) != null) return m.display.get(slot);
        return m.parent != null ? collectDisplay(m.parent, slot, depth + 1) : null;
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
        return BlockTextures.layerOf(texturePath(path));
    }

    private static Baked bakeUncached(String name, int xDeg, int yDeg, boolean uvlock) {
        Map<String, String> tex = new HashMap<>();
        collectTextures(name, tex, 0);
        List<RawElement> elements = collectElements(name, 0);

        int xq = Math.floorMod(xDeg / 90, 4);
        int yq = Math.floorMod(yDeg / 90, 4);

        boolean ao = collectAmbientOcclusion(name, 0);
        /* uvlock ohne Drehung ist ein No-op (wie in MC) — sonst würde eine unrotierte Variante
           ihre expliziten UVs grundlos verlieren. */
        boolean lock = uvlock && (xq != 0 || yq != 0);
        if (lock) warnDroppedUv(name, elements);

        List<BakedQuad[]> parts = new ArrayList<>();
        List<AABB> boxes = new ArrayList<>();
        for (RawElement el : elements) {
            if (el.rotation != null) {
                /* Schräges Element: NICHT über BoxElement drehen — das ist strukturell
                   achsenparallel. Stattdessen die fertigen Quads affin transformieren; die UVs
                   hängen ohnehin an den Vertices und wandern damit korrekt mit.
                   uvlock greift hier bewusst NICHT: für ein gekipptes Quad gibt es keine
                   achsenparallele Box, aus der sich eine weltfeste UV ableiten ließe. */
                BakedQuad[] quads = rotateQuads(toBox(name, el, tex).bake(), el.rotation, xq, yq);
                if (el.shade == Boolean.FALSE) fullBright(quads);
                parts.add(quads);
                boxes.add(enclosingBox(quads));
            } else {
                BoxElement be = toBox(name, el, tex).rotateX(xq).rotateY(yq);
                if (lock) be = be.withoutFaceUv();
                BakedQuad[] quads = be.bake();
                if (!ao) stripDirection(quads);
                if (el.shade == Boolean.FALSE) fullBright(quads);
                parts.add(quads);
                boxes.add(be.toAABB());
            }
        }
        BakedQuad[] quads = BlockModels.concat(parts.toArray(new BakedQuad[0][]));
        String particlePath = resolveRef(tex, "#particle");
        int particleLayer = particlePath != null
                ? BlockTextures.layerOf(texturePath(particlePath))
                : quads.length == 0 ? -1 : quads[0].textureLayer();
        return new Baked(quads, boxes.toArray(new AABB[0]), particleLayer);
    }

    /**
     * uvlock leitet die UVs aus der gedrehten Box ab und verwirft dabei explizite {@code uv}- bzw.
     * {@code rotation}-Angaben des Modells. Für die heutigen uvlock-Kunden (Treppe, Zaun) sind
     * beide gar nicht gesetzt; die Warnung fängt den Tag ab, an dem jemand uvlock auf ein Modell
     * mit handgesetzten UVs schreibt und sich wundert, warum sie wirkungslos sind.
     */
    private static void warnDroppedUv(String name, List<RawElement> elements) {
        for (RawElement el : elements) {
            if (el.faces == null) continue;
            for (RawFace face : el.faces.values()) {
                if (face.uv != null || face.rotation != null) {
                    LOGGER.warning("uvlock verwirft die expliziten Face-UVs von Modell " + name);
                    return;
                }
            }
        }
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

    /**
     * Nimmt den Quads die richtungsabhängige Flächenhelligkeit ({@code shade: false}). Vanilla
     * nutzt das für kleine Modelle wie die Fackel: dort ist die Flamme auf allen Seiten gleich
     * hell, mit {@code FACE_BRIGHTNESS} fiele derselbe Flammentexel je Face unterschiedlich aus
     * (Oberseite 1.0 gegen Seiten 0.8/0.6) und der weiße Texel würde herausspringen.
     *
     * <p>Bewusst getrennt von {@link #stripDirection}: das nimmt {@code face} und schaltet damit
     * AO ab, hier bleibt {@code face} erhalten. Beides ist unabhängig steuerbar
     * ({@code shade} je Element, {@code ambientocclusion} je Modell) und darf sich überlagern.
     */
    private static void fullBright(BakedQuad[] quads) {
        for (int i = 0; i < quads.length; i++) {
            BakedQuad q = quads[i];
            /* Vertex-Array wird geteilt (nie mutiert) — nur die Helligkeit ändert sich. */
            quads[i] = new BakedQuad(q.vertices(), q.textureLayer(), q.cullFace(), q.face(),
                    1.0F, q.tint(), q.tintType());
        }
    }

    /**
     * Dreht die Vertices eines Elements: erst die Elementrotation um {@code origin}, danach die
     * Blockstate-Rotation x/y in Vierteldrehungen um die Blockmitte (dieselben Formeln wie in
     * {@link BoxElement}, damit gerade und schräge Elemente desselben Modells zusammenpassen).
     *
     * <p>Die Quads verlieren dabei {@code face} UND {@code cullFace}. Beides ist nötig: ein
     * gekipptes Quad liegt nicht mehr bündig auf der Blockgrenze, also wäre Culling gegen den
     * Nachbarn falsch, und die AO-Ecksuche des Meshers setzt eine achsenparallele Normale
     * voraus. Damit landen schräge Quads in derselben Behandlung wie Cross-Quads — der Mesher
     * gated AO über {@code face() >= 0} und den Greedy-Pass über ein gültiges cullFace.
     */
    private static BakedQuad[] rotateQuads(BakedQuad[] quads, RawRotation rot, int xq, int yq) {
        double ox = rot.origin != null && rot.origin.length == 3 ? ModelElements.px(rot.origin[0]) : 0.5;
        double oy = rot.origin != null && rot.origin.length == 3 ? ModelElements.px(rot.origin[1]) : 0.5;
        double oz = rot.origin != null && rot.origin.length == 3 ? ModelElements.px(rot.origin[2]) : 0.5;

        double rad = Math.toRadians(rot.angle);
        double cos = Math.cos(rad), sin = Math.sin(rad);
        /* Aufblähen auf die ursprüngliche Kantenlänge (MC rescale), quer zur Drehachse. */
        double scale = rot.rescale && Math.abs(cos) > 1.0E-4 ? 1.0 / Math.abs(cos) : 1.0;
        String axisName = rot.axis == null ? "" : rot.axis.toLowerCase();
        if (!axisName.equals("x") && !axisName.equals("y") && !axisName.equals("z")) {
            LOGGER.warning("Modell-Rotation ohne gueltige axis (" + rot.axis + ") — als z behandelt");
        }
        int axis = switch (axisName) {
            case "x" -> 0;
            case "y" -> 1;
            default -> 2;
        };

        BakedQuad[] out = new BakedQuad[quads.length];
        for (int q = 0; q < quads.length; q++) {
            BakedQuad src = quads[q];
            float[] v = src.vertices().clone();
            for (int i = 0; i < v.length; i += 5) {
                double x = v[i] - ox, y = v[i + 1] - oy, z = v[i + 2] - oz;
                double nx = x, ny = y, nz = z;
                switch (axis) {
                    case 0 -> { ny = y * cos - z * sin; nz = y * sin + z * cos; ny *= scale; nz *= scale; }
                    case 1 -> { nx = x * cos + z * sin; nz = -x * sin + z * cos; nx *= scale; nz *= scale; }
                    default -> { nx = x * cos - y * sin; ny = x * sin + y * cos; nx *= scale; ny *= scale; }
                }
                double px = nx + ox, py = ny + oy, pz = nz + oz;

                /* Blockstate-Rotation: x-Vierteldrehungen (x,y,z)->(x,z,1-y), danach
                   y-Vierteldrehungen (x,y,z)->(1-z,y,x) — identisch zu BoxElement. */
                for (int t = 0; t < Math.floorMod(xq, 4); t++) {
                    double ty = py;
                    py = pz;
                    pz = 1 - ty;
                }
                for (int t = 0; t < Math.floorMod(yq, 4); t++) {
                    double tx = px;
                    px = 1 - pz;
                    pz = tx;
                }
                v[i] = (float) px;
                v[i + 1] = (float) py;
                v[i + 2] = (float) pz;
            }
            out[q] = new BakedQuad(v, src.textureLayer(), BakedQuad.NO_CULL, BakedQuad.NO_DIRECTION,
                    src.brightness(), src.tint(), src.tintType());
        }
        return out;
    }

    /** Umschließende AABB der gedrehten Quads (Kollision bleibt achsenparallel). */
    private static AABB enclosingBox(BakedQuad[] quads) {
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, z0 = Double.MAX_VALUE;
        double x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
        for (BakedQuad q : quads) {
            float[] v = q.vertices();
            for (int i = 0; i < v.length; i += 5) {
                x0 = Math.min(x0, v[i]);     x1 = Math.max(x1, v[i]);
                y0 = Math.min(y0, v[i + 1]); y1 = Math.max(y1, v[i + 1]);
                z0 = Math.min(z0, v[i + 2]); z1 = Math.max(z1, v[i + 2]);
            }
        }
        return new AABB(x0, y0, z0, x1, y1, z1);
    }

    private static void collectTextures(String name, Map<String, String> out, int depth) {
        if (depth > 20) return;
        RawModel m = MODELS.get(modelKey(name));
        if (m == null) { LOGGER.warning("Modell fehlt: " + name); return; }
        if (m.parent != null) collectTextures(m.parent, out, depth + 1);
        if (m.textures != null) out.putAll(m.textures);
    }

    private static List<RawElement> collectElements(String name, int depth) {
        if (depth > 20) return List.of();
        RawModel m = MODELS.get(modelKey(name));
        if (m == null) return List.of();
        if (m.elements != null) return m.elements;
        if (m.parent != null) return collectElements(m.parent, depth + 1);
        return List.of();
    }

    /** Erbt {@code ambientocclusion} wie in Minecraft: erstes Vorkommen der Kette gewinnt, Default an. */
    private static boolean collectAmbientOcclusion(String name, int depth) {
        if (depth > 20) return true;
        RawModel m = MODELS.get(modelKey(name));
        if (m == null) return true;
        if (m.ambientocclusion != null) return m.ambientocclusion;
        if (m.parent != null) return collectAmbientOcclusion(m.parent, depth + 1);
        return true;
    }

    private static BoxElement toBox(String modelName, RawElement el, Map<String, String> tex) {
        int[] t = {BakedQuad.NO_FACE, BakedQuad.NO_FACE, BakedQuad.NO_FACE,
                   BakedQuad.NO_FACE, BakedQuad.NO_FACE, BakedQuad.NO_FACE};
        int[] c = {BakedQuad.NO_CULL, BakedQuad.NO_CULL, BakedQuad.NO_CULL,
                   BakedQuad.NO_CULL, BakedQuad.NO_CULL, BakedQuad.NO_CULL};
        /* pxEdge statt px: haelt MC-Mini-Offsets wie 0.001/15.999 von der Blockgrenze weg. */
        double bx0 = ModelElements.pxEdge(el.from[0]), by0 = ModelElements.pxEdge(el.from[1]), bz0 = ModelElements.pxEdge(el.from[2]);
        double bx1 = ModelElements.pxEdge(el.to[0]), by1 = ModelElements.pxEdge(el.to[1]), bz1 = ModelElements.pxEdge(el.to[2]);

        float[][] uv = null;
        if (el.faces != null) {
            for (Map.Entry<String, RawFace> e : el.faces.entrySet()) {
                int idx = faceIndex(e.getKey());
                if (idx < 0) continue;
                RawFace face = e.getValue();
                String path = resolveRef(tex, face.texture);
                if (path == null) {
                    /* Layer 0 ist eine gueltige Textur (die zuerst registrierte) — ohne
                       Meldung sieht ein nicht aufgeloester Ref wie eine willkuerliche
                       Fremdtextur aus statt wie ein Fehler. Genau so blieb der #lock-Ref
                       der gesperrten Verstaerker-Modelle lange unentdeckt. Einmal je
                       Modell+Ref, sonst spammt jede Rotationsvariante dieselbe Zeile. */
                    if (WARNED_REFS.add(modelName + " " + face.texture)) {
                        LOGGER.warning("Textur-Ref " + face.texture + " nicht aufloesbar in Modell " + modelName);
                    }
                }
                t[idx] = path == null ? 0 : BlockTextures.layerOf(texturePath(path));
                c[idx] = face.cullface != null ? faceIndex(face.cullface) : BakedQuad.NO_CULL;

                boolean hasRect = face.uv != null && face.uv.length == 4;
                int turns = faceRotationTurns(face.rotation, e.getKey());
                if (!hasRect && turns == 0) continue;

                /* Bei reiner rotation ohne uv-Rechteck erst die Extent-UVs materialisieren —
                   sonst bliebe die Drehung wirkungslos (cube_bottom_top_horizontal hat kein uv). */
                float[] corners = hasRect ? cornerUv(idx, face.uv)
                        : BlockModels.extentUv(idx, (float) bx0, (float) by0, (float) bz0,
                                                    (float) bx1, (float) by1, (float) bz1);
                if (uv == null) uv = new float[6][];
                uv[idx] = turns == 0 ? corners : rotateCornerUv(corners, turns);
            }
        }
        return new BoxElement(bx0, by0, bz0, bx1, by1, bz1, t, c, el.mirror, uv);
    }

    /** Wandelt das MC-Feld {@code rotation} in Vierteldrehungen; ungültige Werte werden verworfen. */
    private static int faceRotationTurns(Integer rotation, String faceName) {
        if (rotation == null) return 0;
        if (rotation % 90 != 0) {
            LOGGER.warning("Face-rotation " + rotation + " (" + faceName + ") ist kein Vielfaches von 90 — ignoriert");
            return 0;
        }
        return Math.floorMod(rotation / 90, 4);
    }

    /** Dreht die Textur in der Face: Ecke i übernimmt die UV der Ecke i+turns (zyklisch). */
    private static float[] rotateCornerUv(float[] uv, int turns) {
        float[] out = new float[8];
        for (int i = 0; i < 4; i++) {
            int src = (i + turns) % 4;
            out[i * 2] = uv[src * 2];
            out[i * 2 + 1] = uv[src * 2 + 1];
        }
        return out;
    }

    /**
     * Wandelt ein Minecraft-UV-Rechteck {@code [u0,v0,u1,v1]} (Pixel 0..16, v von oben) in die
     * vier Eck-UVs A,B,C,D (0..1) der jeweiligen Face um. Die Eckreihenfolge entspricht der in
     * {@link BlockModels#box}; für ein Voll-Face-UV deckt sich das mit dem Extent-Default.
     */
    private static float[] cornerUv(int face, float[] rect) {
        float u0 = rect[0] / 16f, v0 = rect[1] / 16f, u1 = rect[2] / 16f, v1 = rect[3] / 16f;
        return switch (face) {
            case 0 -> new float[]{u0, v0,  u0, v1,  u1, v1,  u1, v0}; // top:    A,B,C,D
            /* bottom: v läuft rückwärts (v=1-z), s. BlockModels.extentUv — die Eckreihenfolge
               A,B,C,D ist damit ein ZYKLISCHER Versatz von Vanillas 0,1,2,3 (vorher eine
               Umkehrung), weshalb rotateCornerUv hier in dieselbe Richtung dreht wie in MC. */
            case 1 -> new float[]{u0, v1,  u1, v1,  u1, v0,  u0, v0}; // bottom: A,B,C,D
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
