package de.skyengine.audio;

import de.skyengine.core.file.Files;
import de.skyengine.core.io.IDisposable;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.joml.Vector3d;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.EnumMap;
import java.util.Random;

/**
 * Zentrale Audio-Verwaltung (OpenAL): Effekt-Sounds über einen Source-Pool, Musik über den
 * {@link MusicPlayer}, Listener folgt der Kamera. Läuft komplett auf dem Render-Thread
 * (OpenAL hat keine Main-Thread-Bindung; so gibt es keinerlei Cross-Thread-Sorgen).
 *
 * <ul>
 *   <li><b>Robustheit:</b> fehlt der Sounds-Ordner oder das Audio-Gerät, deaktiviert sich das
 *       System mit einer Warnung — alle play-Methoden sind dann No-Ops (Muster Font-System).</li>
 *   <li><b>Lautstärke:</b> Master = Listener-Gain (wirkt global inkl. Musik),
 *       Musik = Source-Gain der Musik-Source (effektiv master × music, wie MC).</li>
 *   <li>Effekt-Sounds werden beim Init komplett vorgeladen (wenige MB); Musik wird gestreamt.</li>
 * </ul>
 */
public final class SoundManager implements IDisposable {

    private static final int POOL_SIZE = 12;
    private static final int MAX_VARIANTS = 8; // Varianten 1..N je Gruppe, solange die Datei existiert

    /* Lautstärke/Pitch-Konventionen wie Minecraft. */
    private static final float STEP_GAIN = 0.15F, STEP_PITCH = 1.0F;
    private static final float HIT_GAIN = 0.25F, HIT_PITCH = 0.5F;
    private static final float DIG_GAIN = 1.0F, DIG_PITCH = 0.8F;

    private final Logger logger = LogManager.getLogger(SoundManager.class.getName());

    private boolean enabled;
    private long device, context;

    private int[] pool;
    private int poolCursor;

    private final EnumMap<BlockSoundGroup, int[]> stepBuffers = new EnumMap<>(BlockSoundGroup.class);
    private final EnumMap<BlockSoundGroup, int[]> digBuffers = new EnumMap<>(BlockSoundGroup.class);

    private final MusicPlayer music = new MusicPlayer();
    private final Random random = new Random();

    /* Wiederverwendet fürs Listener-Update (keine Frame-Allokationen). */
    private final Vector3d direction = new Vector3d();
    private final float[] orientation = new float[6];

    private final File soundsDir = new File(Files.RESOURCES_PATH, "game/sounds");

    /** Initialisiert Gerät/Kontext und lädt alle Effekt-Sounds. Nur auf dem Render-Thread. */
    public void init() {
        if (!this.soundsDir.isDirectory()) {
            this.logger.warning("Sound-Ordner fehlt (" + this.soundsDir.getPath()
                    + ") — scripts/extract-mc-sounds.ps1 ausführen. Audio deaktiviert.");
            return;
        }

        this.device = ALC10.alcOpenDevice((ByteBuffer) null);
        if (this.device == 0) {
            this.logger.warning("Kein OpenAL-Audio-Gerät gefunden — Audio deaktiviert.");
            return;
        }
        ALCCapabilities deviceCaps = ALC.createCapabilities(this.device);
        this.context = ALC10.alcCreateContext(this.device, (IntBuffer) null);
        ALC10.alcMakeContextCurrent(this.context);
        AL.createCapabilities(deviceCaps);

        this.pool = new int[POOL_SIZE];
        for (int i = 0; i < POOL_SIZE; i++) {
            this.pool[i] = AL10.alGenSources();
        }

        int loaded = 0;
        for (BlockSoundGroup group : BlockSoundGroup.values()) {
            loaded += this.preload(this.stepBuffers, group, "step", group.stepName);
            loaded += this.preload(this.digBuffers, group, "dig", group.digName);
        }

        this.enabled = true;
        String deviceName = ALC10.alcGetString(this.device, ALC10.ALC_DEVICE_SPECIFIER);
        this.logger.info("Audio initialisiert: " + loaded + " Effekt-Sounds geladen (Gerät: " + deviceName + ")");
    }

    /** Lädt die Varianten {@code <baseName>1..N.ogg} einer Gruppe; GLASS teilt sich z.B. die Stein-Steps. */
    private int preload(EnumMap<BlockSoundGroup, int[]> target, BlockSoundGroup group, String folder, String baseName) {
        /* Gruppen mit gleichem Basisnamen (GLASS.step = "stone") teilen sich die AL-Buffer. */
        for (var entry : target.entrySet()) {
            String otherBase = folder.equals("step") ? entry.getKey().stepName : entry.getKey().digName;
            if (otherBase.equals(baseName)) {
                target.put(group, entry.getValue());
                return 0;
            }
        }

        int[] variants = new int[MAX_VARIANTS];
        int count = 0;
        for (int i = 1; i <= MAX_VARIANTS; i++) {
            File file = new File(this.soundsDir, folder + "/" + baseName + i + ".ogg");
            if (!file.exists()) break;
            int buffer = OggLoader.load(file, true);
            if (buffer == -1) continue;
            variants[count++] = buffer;
        }
        if (count == 0) {
            this.logger.warning("Keine " + folder + "-Sounds für Gruppe " + group + " (" + baseName + "*.ogg) — Gruppe bleibt stumm.");
            return 0;
        }
        int[] trimmed = new int[count];
        System.arraycopy(variants, 0, trimmed, 0, count);
        target.put(group, trimmed);
        return count;
    }

    /* --- Gameplay-API (No-Ops, solange nicht enabled) --- */

    /** Laufgeräusch — nicht-positional am Listener. */
    public void playStep(BlockSoundGroup group) {
        this.play(this.stepBuffers.get(group), STEP_GAIN, STEP_PITCH, false, 0, 0, 0);
    }

    /** Abbau-Schlag während des Minings — gedämpfte Step-Variante an der Block-Position. */
    public void playHit(BlockSoundGroup group, double x, double y, double z) {
        this.play(this.stepBuffers.get(group), HIT_GAIN, HIT_PITCH, true, x, y, z);
    }

    /** Finaler Bruch-Sound an der Block-Position. */
    public void playBreak(BlockSoundGroup group, double x, double y, double z) {
        this.play(this.digBuffers.get(group), DIG_GAIN, DIG_PITCH, true, x, y, z);
    }

    /** Platzier-Sound (gleiche Gruppe wie der Bruch) an der Block-Position. */
    public void playPlace(BlockSoundGroup group, double x, double y, double z) {
        this.play(this.digBuffers.get(group), DIG_GAIN, DIG_PITCH, true, x, y, z);
    }

    /** Zufällige Variante + ±10 % Zufalls-Pitch auf einer freien Pool-Source. */
    private void play(int[] variants, float gain, float pitch, boolean positional, double x, double y, double z) {
        if (!this.enabled || variants == null) return;
        int source = this.acquireSource();
        if (source == -1) return; // Pool voll: Sound verwerfen statt laufende zu stehlen

        AL10.alSourcei(source, AL10.AL_BUFFER, variants[this.random.nextInt(variants.length)]);
        AL10.alSourcef(source, AL10.AL_GAIN, gain);
        AL10.alSourcef(source, AL10.AL_PITCH, pitch * (0.9F + this.random.nextFloat() * 0.2F));
        if (positional) {
            AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
            AL10.alSource3f(source, AL10.AL_POSITION, (float) x, (float) y, (float) z);
            AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, 4.0F);
            AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, 32.0F);
        } else {
            AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(source, AL10.AL_POSITION, 0, 0, 0);
        }
        AL10.alSourcePlay(source);
    }

    /** Round-Robin über den Pool; −1, wenn alle Sources noch spielen. */
    private int acquireSource() {
        for (int i = 0; i < POOL_SIZE; i++) {
            int source = this.pool[(this.poolCursor + i) % POOL_SIZE];
            if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
                this.poolCursor = (this.poolCursor + i + 1) % POOL_SIZE;
                return source;
            }
        }
        return -1;
    }

    /* --- Musik --- */

    /** Startet Musik aus {@code game/sounds/<relPath>} (z.B. "music/minecraft.ogg"). */
    public void playMusic(String relPath, boolean loop) {
        if (!this.enabled) return;
        this.music.play(new File(this.soundsDir, relPath), loop);
    }

    public void stopMusic() {
        if (!this.enabled) return;
        this.music.stop();
    }

    /* --- Frame-Updates --- */

    /** Pro Frame: Listener auf die Kamera setzen (Position + Blickrichtung, Up = +Y). */
    public void updateListener(Camera camera) {
        if (!this.enabled) return;
        Vector3d position = camera.getPosition();
        AL10.alListener3f(AL10.AL_POSITION, (float) position.x, (float) position.y, (float) position.z);
        camera.getDirection(this.direction);
        this.orientation[0] = (float) this.direction.x;
        this.orientation[1] = (float) this.direction.y;
        this.orientation[2] = (float) this.direction.z;
        this.orientation[3] = 0;
        this.orientation[4] = 1;
        this.orientation[5] = 0;
        AL10.alListenerfv(AL10.AL_ORIENTATION, this.orientation);
    }

    /** Pro Frame: Musik-Streaming nachfüllen. */
    public void update() {
        if (!this.enabled) return;
        this.music.update();
    }

    /* --- Lautstärke (aus GameSettings, 0..1) --- */

    /** Master-Lautstärke = Listener-Gain (wirkt auf alles inkl. Musik). */
    public void setMasterVolume(float gain) {
        if (!this.enabled) return;
        AL10.alListenerf(AL10.AL_GAIN, gain);
    }

    /** Musik-Lautstärke (zusätzlich zum Master). */
    public void setMusicVolume(float gain) {
        if (!this.enabled) return;
        this.music.setVolume(gain);
    }

    @Override
    public void dispose() {
        if (this.device == 0) return;
        this.music.dispose();
        if (this.pool != null) {
            for (int source : this.pool) {
                AL10.alSourceStop(source);
                AL10.alDeleteSources(source);
            }
        }
        /* Gruppen teilen sich Buffer-Arrays (GLASS/STONE) — über Identität deduplizieren. */
        java.util.HashSet<int[]> unique = new java.util.HashSet<>();
        unique.addAll(this.stepBuffers.values());
        unique.addAll(this.digBuffers.values());
        for (int[] variants : unique) {
            for (int buffer : variants) AL10.alDeleteBuffers(buffer);
        }
        ALC10.alcMakeContextCurrent(0);
        ALC10.alcDestroyContext(this.context);
        ALC10.alcCloseDevice(this.device);
        this.device = 0;
        this.enabled = false;
    }
}
