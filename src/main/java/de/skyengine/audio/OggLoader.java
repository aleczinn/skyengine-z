package de.skyengine.audio;

import de.skyengine.core.file.FileHandle;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.lwjgl.openal.AL10;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libc.LibCStdlib;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/** Laedt kurze OGG-Effekte direkt aus dem Ressourcen-Stack. */
final class OggLoader {
    private static final Logger LOGGER = LogManager.getLogger(OggLoader.class.getName());
    private OggLoader() {}

    static int load(FileHandle file, boolean forceMono) {
        ByteBuffer encoded = null;
        ShortBuffer pcm;
        int channels, sampleRate;
        try (InputStream in = file.read(); MemoryStack stack = MemoryStack.stackPush()) {
            byte[] bytes = in.readAllBytes();
            encoded = MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
            IntBuffer channelsBuf = stack.mallocInt(1), rateBuf = stack.mallocInt(1);
            pcm = STBVorbis.stb_vorbis_decode_memory(encoded, channelsBuf, rateBuf);
            if (pcm == null) {
                LOGGER.warning("OGG konnte nicht dekodiert werden: " + file.name());
                return -1;
            }
            channels = channelsBuf.get(0);
            sampleRate = rateBuf.get(0);
        } catch (Exception e) {
            LOGGER.warning("OGG konnte nicht gelesen werden: " + file.name() + " (" + e.getMessage() + ")");
            return -1;
        } finally {
            if (encoded != null) MemoryUtil.memFree(encoded);
        }
        try {
            if (channels == 2 && forceMono) {
                int samples = pcm.limit() / 2;
                ShortBuffer mono = pcm.duplicate();
                for (int i = 0; i < samples; i++) mono.put(i, (short) ((pcm.get(i * 2) + pcm.get(i * 2 + 1)) / 2));
                mono.limit(samples).position(0);
                pcm = mono;
                channels = 1;
            }
            int format = channels == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
            int buffer = AL10.alGenBuffers();
            AL10.alBufferData(buffer, format, pcm, sampleRate);
            return buffer;
        } finally {
            LibCStdlib.free(pcm);
        }
    }
}
