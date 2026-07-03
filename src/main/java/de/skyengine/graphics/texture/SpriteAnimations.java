package de.skyengine.graphics.texture;

import com.google.gson.Gson;
import de.skyengine.core.file.Files;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.FileReader;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Verwaltet alle animierten Sprites einer Texturmenge. Erkennt animierte Texturen über
 * eine {@code <textur>.png.mcmeta}-Sidecar-Datei, lädt deren Frames und tauscht pro Tick
 * den aktuellen Frame im {@link TextureArray} aus. Statische Texturen bleiben unberührt.
 */
public final class SpriteAnimations {

    private static final Logger LOGGER = LogManager.getLogger(SpriteAnimations.class.getName());
    private static final Gson GSON = new Gson();

    private final List<AnimatedSprite> sprites;

    private SpriteAnimations(List<AnimatedSprite> sprites) {
        this.sprites = sprites;
    }

    /** Scannt die Pfade (Index = Layer) nach .mcmeta-Sidecars und lädt deren Frames. */
    public static SpriteAnimations build(String[] paths, int size) {
        List<AnimatedSprite> list = new ArrayList<>();
        for (int layer = 0; layer < paths.length; layer++) {
            java.io.File meta = new java.io.File(Files.RESOURCES_PATH + paths[layer] + ".mcmeta");
            if (!meta.exists()) continue;

            AnimatedSprite sprite = load(layer, paths[layer], meta, size);
            if (sprite != null) list.add(sprite);
        }
        if (!list.isEmpty()) LOGGER.info(list.size() + " animierte Texturen geladen");
        return new SpriteAnimations(list);
    }

    /** Layer, die animiert sind — das TextureArray überspringt deren statisches Laden. */
    public Set<Integer> animatedLayers() {
        Set<Integer> layers = new HashSet<>();
        for (AnimatedSprite s : this.sprites) layers.add(s.layer());
        return layers;
    }

    /** Schreibt den ersten Frame jedes Sprites (einmalig nach dem TextureArray-Aufbau). */
    public void uploadInitial(TextureArray textures) {
        for (AnimatedSprite s : this.sprites) textures.updateLayer(s.layer(), s.initialFrame());
    }

    /** Pro Frame aufrufen (Render-Thread): rückt Animationen um dt Sekunden vor. */
    public void tick(TextureArray textures, double dt) {
        for (AnimatedSprite s : this.sprites) {
            ByteBuffer frame = s.advance(dt);
            if (frame != null) textures.updateLayer(s.layer(), frame);
        }
    }

    public void dispose() {
        for (AnimatedSprite s : this.sprites) s.dispose();
        this.sprites.clear();
    }

    private static AnimatedSprite load(int layer, String path, java.io.File metaFile, int size) {
        TextureAnimationMeta meta;
        try (FileReader reader = new FileReader(metaFile)) {
            meta = GSON.fromJson(reader, TextureAnimationMeta.class);
        } catch (Exception e) {
            LOGGER.error("Fehlerhafte .mcmeta: " + metaFile.getName(), e);
            return null;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1), c = stack.mallocInt(1);
            ByteBuffer pixels = STBImage.stbi_load(Files.RESOURCES_PATH + path, w, h, c, 4);

            int imgW = w.get(0), imgH = h.get(0);
            if (pixels == null || imgW % size != 0 || imgH % size != 0) {
                if (pixels != null) STBImage.stbi_image_free(pixels);
                LOGGER.warning("Animierte Textur hat falsche Maße (Breite & Höhe Vielfache von " + size + "): " + path);
                return null;
            }

            /* Textur wird als Raster aus size×size-Kacheln gelesen; jede Kachel ist ein Frame.
               Reihenfolge spaltenweise: linke Spalte oben→unten, dann nächste Spalte (16-breit =
               eine Spalte = bisheriges Verhalten). Kacheln werden unverändert übernommen. */
            int cols = imgW / size, rows = imgH / size;
            int frameCount = cols * rows;
            int rowBytes = size * 4;
            ByteBuffer[] frames = new ByteBuffer[frameCount];
            int f = 0;
            for (int cx = 0; cx < cols; cx++) {
                for (int ry = 0; ry < rows; ry++) {
                    ByteBuffer fb = MemoryUtil.memAlloc(size * size * 4);
                    for (int py = 0; py < size; py++) {
                        int srcByte = (((ry * size + py) * imgW) + cx * size) * 4;
                        int dstByte = py * rowBytes;
                        for (int bx = 0; bx < rowBytes; bx++) fb.put(dstByte + bx, pixels.get(srcByte + bx));
                    }
                    frames[f++] = fb;
                }
            }
            STBImage.stbi_image_free(pixels);

            int frametime = meta != null && meta.animation != null && meta.animation.frametime != null
                    ? meta.animation.frametime : 1;
            int[] sequence = resolveSequence(meta, frameCount);
            return new AnimatedSprite(layer, frames, sequence, frametime);
        }
    }

    private static int[] resolveSequence(TextureAnimationMeta meta, int frameCount) {
        if (meta != null && meta.animation != null && meta.animation.frames != null && meta.animation.frames.length > 0) {
            int[] requested = meta.animation.frames;
            List<Integer> valid = new ArrayList<>(requested.length);
            for (int f : requested) if (f >= 0 && f < frameCount) valid.add(f);
            if (!valid.isEmpty()) {
                int[] seq = new int[valid.size()];
                for (int i = 0; i < seq.length; i++) seq[i] = valid.get(i);
                return seq;
            }
        }
        int[] seq = new int[frameCount];
        for (int i = 0; i < frameCount; i++) seq[i] = i;
        return seq;
    }
}
