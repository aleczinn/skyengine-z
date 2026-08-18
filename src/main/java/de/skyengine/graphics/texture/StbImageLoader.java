package de.skyengine.graphics.texture;

import de.skyengine.core.file.FileHandle;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryUtil;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/** Dekodiert Bilder aus Dateien, Classpath-Ressourcen und ZIP-Packs. */
public final class StbImageLoader {
    public static ByteBuffer load(FileHandle file, IntBuffer width, IntBuffer height,
                                  IntBuffer channels, int desiredChannels) {
        if (!file.exists()) return null;
        ByteBuffer encoded = null;
        try (InputStream in = file.read()) {
            byte[] bytes = in.readAllBytes();
            encoded = MemoryUtil.memAlloc(bytes.length);
            encoded.put(bytes).flip();
            return STBImage.stbi_load_from_memory(encoded, width, height, channels, desiredChannels);
        } catch (Exception e) {
            return null;
        } finally {
            if (encoded != null) MemoryUtil.memFree(encoded);
        }
    }

    private StbImageLoader() {}
}
