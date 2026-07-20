package de.skyengine.audio;

import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.lwjgl.openal.AL10;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.libc.LibCStdlib;

import java.io.File;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

import static org.lwjgl.stb.STBVorbis.stb_vorbis_decode_filename;

/**
 * Lädt eine OGG-Datei komplett per STBVorbis in einen OpenAL-Buffer (für kurze Effekt-Sounds;
 * Musik wird gestreamt, siehe {@link MusicPlayer}).
 */
final class OggLoader {

    private static final Logger LOGGER = LogManager.getLogger(OggLoader.class.getName());

    private OggLoader() {}

    /**
     * Dekodiert die Datei und füllt einen neuen AL-Buffer; −1 bei Fehler. {@code forceMono}
     * mischt Stereo auf Mono herunter — nötig für positionale Sounds, weil OpenAL
     * Stereo-Buffer nicht räumlich abschwächt (MC-Effekte sind mono, reines Sicherheitsnetz).
     */
    static int load(File file, boolean forceMono) {
        ShortBuffer pcm;
        int channels, sampleRate;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer channelsBuf = stack.mallocInt(1);
            IntBuffer rateBuf = stack.mallocInt(1);
            pcm = stb_vorbis_decode_filename(file.getPath(), channelsBuf, rateBuf);
            if (pcm == null) {
                LOGGER.warning("OGG konnte nicht dekodiert werden: " + file.getName());
                return -1;
            }
            channels = channelsBuf.get(0);
            sampleRate = rateBuf.get(0);
        }

        try {
            if (channels == 2 && forceMono) {
                LOGGER.debug("Stereo-Effekt " + file.getName() + " wird auf Mono heruntergemischt.");
                int samples = pcm.limit() / 2;
                ShortBuffer mono = pcm.duplicate(); // In-place: Mono braucht die halbe Kapazität
                for (int i = 0; i < samples; i++) {
                    mono.put(i, (short) ((pcm.get(i * 2) + pcm.get(i * 2 + 1)) / 2));
                }
                mono.limit(samples).position(0);
                pcm = mono;
                channels = 1;
            }

            int format = channels == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
            int buffer = AL10.alGenBuffers();
            AL10.alBufferData(buffer, format, pcm, sampleRate);
            return buffer;
        } finally {
            LibCStdlib.free(pcm); // stb_vorbis_decode_filename alloziert mit malloc
        }
    }
}
