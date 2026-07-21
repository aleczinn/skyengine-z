package de.skyengine.audio;

import de.skyengine.core.file.Files;
import de.skyengine.core.io.IDisposable;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.joml.Vector3d;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.ALUtil;
import org.lwjgl.openal.EnumerateAllExt;
import org.lwjgl.openal.SOFTReopenDevice;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Random;

/**
 * Zentrale Audio-Verwaltung (OpenAL): Effekt-Sounds über einen Source-Pool, Musik über den
 * {@link MusicPlayer}, Listener folgt der Kamera. Läuft komplett auf dem Render-Thread
 * (OpenAL hat keine Main-Thread-Bindung; so gibt es keinerlei Cross-Thread-Sorgen).
 *
 * <ul>
 *   <li><b>Robustheit:</b> fehlt der Sounds-Ordner oder das Audio-Gerät, deaktiviert sich das
 *       System mit einer Warnung — alle play-Methoden sind dann No-Ops (Muster Font-System).</li>
 *   <li><b>Lautstärke:</b> Master = Listener-Gain (wirkt global inkl. Musik); jeder Sound gehört
 *       zu einem {@link SoundCategory}-Kanal, dessen Faktor auf den Source-Gain multipliziert
 *       wird (Musik = MUSIC-Kanal auf der Musik-Source; effektiv master × Kanal, wie MC).</li>
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
    /* UI-Button-Klick (wie MCs Button-Gain) */
    private static final float UI_CLICK_GAIN = 0.25F;
    /* Spieler-Sounds (Hurt/Aufprall/Essen) — nicht-positional am Listener, Kanal PLAYER. */
    private static final float HURT_GAIN = 1.0F;
    private static final float FALL_GAIN = 0.5F;
    private static final float EAT_GAIN = 0.75F;
    private static final float BURP_GAIN = 0.25F; // bewusst dezenter als MCs 0.5 (User-Wunsch)

    private final Logger logger = LogManager.getLogger(SoundManager.class.getName());

    private boolean enabled;
    private long device, context;

    /* Kanal-Faktoren 0..1 (Index = SoundCategory-Ordinal), aus GameSettings via applyAudioSettings. */
    private final float[] categoryGains = new float[SoundCategory.values().length];

    private int[] pool;
    private int poolCursor;

    private final EnumMap<BlockSoundGroup, int[]> stepBuffers = new EnumMap<>(BlockSoundGroup.class);
    private final EnumMap<BlockSoundGroup, int[]> digBuffers = new EnumMap<>(BlockSoundGroup.class);
    /* Platzier-Sounds: teilen die dig-Arrays laut placeName (GLASS platziert wie Stein). */
    private final EnumMap<BlockSoundGroup, int[]> placeBuffers = new EnumMap<>(BlockSoundGroup.class);

    private final MusicPlayer music = new MusicPlayer();
    private final Random random = new Random();

    /* Lose Effekt-Sounds ohne BlockSoundGroup (null, solange die Dateien fehlen -> No-Op). */
    private int[] uiClickVariants;   // ui/click.ogg
    private int[] hurtVariants;      // damage/hit1..3
    private int[] fallSmallVariants; // damage/fallsmall.ogg
    private int[] fallBigVariants;   // damage/fallbig.ogg
    private int[] eatVariants;       // eat/eat1..3
    private int[] burpVariants;      // eat/burp.ogg

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

        Arrays.fill(this.categoryGains, 1.0F);

        /* Gespeichertes Wunsch-Gerät zuerst versuchen; weg/umbenannt -> Systemstandard. */
        String preferred = GameSettings.get().audioDevice;
        if (preferred != null && !preferred.isEmpty()) {
            this.device = ALC10.alcOpenDevice(preferred);
            if (this.device == 0) {
                this.logger.warning("Gespeichertes Audio-Gerät nicht gefunden (" + preferred
                        + ") — nutze Systemstandard.");
                GameSettings.get().audioDevice = "";
            }
        }
        if (this.device == 0) {
            this.device = ALC10.alcOpenDevice((ByteBuffer) null);
        }
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

        /* Platzieren nutzt dig-Buffer; placeName darf auf eine FREMDE dig-Basis zeigen
           (GLASS platziert wie Stein) — Arrays werden geteilt, nichts wird neu geladen. */
        for (BlockSoundGroup group : BlockSoundGroup.values()) {
            for (BlockSoundGroup donor : BlockSoundGroup.values()) {
                if (donor.digName.equals(group.placeName)) {
                    int[] buffers = this.digBuffers.get(donor);
                    if (buffers != null) this.placeBuffers.put(group, buffers);
                    break;
                }
            }
        }

        /* Lose Effekt-Sounds (UI-Klick, Hurt/Aufprall, Essen) — fehlertolerant: fehlt eine
           Datei, bleibt der jeweilige Sound stumm (Warnung im loadVariants). */
        this.uiClickVariants = this.loadVariants("ui", "click");
        this.hurtVariants = this.loadVariants("damage", "hit");
        this.fallSmallVariants = this.loadVariants("damage", "fallsmall");
        this.fallBigVariants = this.loadVariants("damage", "fallbig");
        this.eatVariants = this.loadVariants("eat", "eat");
        this.burpVariants = this.loadVariants("eat", "burp");
        loaded += count(this.uiClickVariants) + count(this.hurtVariants) + count(this.fallSmallVariants)
                + count(this.fallBigVariants) + count(this.eatVariants) + count(this.burpVariants);

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

    /**
     * Lädt lose Varianten {@code <folder>/<baseName>1..N.ogg}; gibt es keine nummerierten,
     * wird die Einzeldatei {@code <baseName>.ogg} probiert (ui/click, eat/burp). Fehlt beides:
     * Warnung + null — die zugehörige play-Methode bleibt dann stumm.
     */
    private int[] loadVariants(String folder, String baseName) {
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
            File single = new File(this.soundsDir, folder + "/" + baseName + ".ogg");
            if (single.exists()) {
                int buffer = OggLoader.load(single, true);
                if (buffer != -1) variants[count++] = buffer;
            }
        }
        if (count == 0) {
            this.logger.warning(folder + "/" + baseName + "*.ogg fehlt — Sound bleibt stumm "
                    + "(scripts/extract-mc-sounds.ps1 ausführen).");
            return null;
        }
        return Arrays.copyOf(variants, count);
    }

    private static int count(int[] variants) {
        return variants == null ? 0 : variants.length;
    }

    /* --- Gameplay-API (No-Ops, solange nicht enabled) --- */

    /** Laufgeräusch — nicht-positional am Listener. */
    public void playStep(BlockSoundGroup group) {
        this.play(this.stepBuffers.get(group), SoundCategory.PLAYER, STEP_GAIN, STEP_PITCH, true, false, 0, 0, 0);
    }

    /** Abbau-Schlag während des Minings — gedämpfte Step-Variante an der Block-Position. */
    public void playHit(BlockSoundGroup group, double x, double y, double z) {
        this.play(this.stepBuffers.get(group), SoundCategory.BLOCKS, HIT_GAIN, HIT_PITCH, true, true, x, y, z);
    }

    /** Finaler Bruch-Sound an der Block-Position. */
    public void playBreak(BlockSoundGroup group, double x, double y, double z) {
        this.play(this.digBuffers.get(group), SoundCategory.BLOCKS, DIG_GAIN, DIG_PITCH, true, true, x, y, z);
    }

    /** Platzier-Sound an der Block-Position — dig-Basis laut {@code placeName} (GLASS = Stein). */
    public void playPlace(BlockSoundGroup group, double x, double y, double z) {
        this.play(this.placeBuffers.get(group), SoundCategory.BLOCKS, DIG_GAIN, DIG_PITCH, true, true, x, y, z);
    }

    /** UI-Button-Klick — nicht-positional, FESTER Pitch (MC-Klick klingt immer identisch). */
    public void playUiClick() {
        this.play(this.uiClickVariants, SoundCategory.UI, UI_CLICK_GAIN, 1.0F, false, false, 0, 0, 0);
    }

    /** Spieler nimmt Schaden — nicht-positional (eigener Spieler). */
    public void playHurt() {
        this.play(this.hurtVariants, SoundCategory.PLAYER, HURT_GAIN, 1.0F, true, false, 0, 0, 0);
    }

    /** Aufprall bei Fallschaden; {@code big} = schwerer Sturz (MC-Grenze: ab 4 Schaden). */
    public void playFall(boolean big) {
        this.play(big ? this.fallBigVariants : this.fallSmallVariants,
                SoundCategory.PLAYER, FALL_GAIN, 1.0F, true, false, 0, 0, 0);
    }

    /** Kau-Sound während des Essens. */
    public void playEat() {
        this.play(this.eatVariants, SoundCategory.PLAYER, EAT_GAIN, 1.0F, true, false, 0, 0, 0);
    }

    /** Rülpser nach abgeschlossenem Essen. */
    public void playBurp() {
        this.play(this.burpVariants, SoundCategory.PLAYER, BURP_GAIN, 1.0F, true, false, 0, 0, 0);
    }

    /** Zufällige Variante + optional ±10 % Zufalls-Pitch auf einer freien Pool-Source. */
    private void play(int[] variants, SoundCategory category, float gain, float pitch, boolean pitchJitter,
                      boolean positional, double x, double y, double z) {
        if (!this.enabled || variants == null) return;
        int source = this.acquireSource();
        if (source == -1) return; // Pool voll: Sound verwerfen statt laufende zu stehlen

        AL10.alSourcei(source, AL10.AL_BUFFER, variants[this.random.nextInt(variants.length)]);
        AL10.alSourcef(source, AL10.AL_GAIN, gain * this.categoryGains[category.ordinal()]);
        AL10.alSourcef(source, AL10.AL_PITCH, pitchJitter ? pitch * (0.9F + this.random.nextFloat() * 0.2F) : pitch);
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

    /** Kanal-Lautstärke (zusätzlich zum Master); MUSIC greift sofort auf die laufende Musik. */
    public void setCategoryVolume(SoundCategory category, float gain) {
        if (!this.enabled) return;
        this.categoryGains[category.ordinal()] = gain;
        if (category == SoundCategory.MUSIC) this.music.setVolume(gain);
    }

    /* --- Ausgabegerät --- */

    /** Alle Ausgabegeräte (volle ALC-Namen); leer, wenn Audio aus oder Enumeration fehlt. */
    public List<String> listDevices() {
        if (!this.enabled || !ALC10.alcIsExtensionPresent(0, "ALC_ENUMERATE_ALL_EXT")) return List.of();
        List<String> devices = ALUtil.getStringList(0, EnumerateAllExt.ALC_ALL_DEVICES_SPECIFIER);
        return devices != null ? devices : List.of();
    }

    /**
     * Wechselt das Ausgabegerät im laufenden Betrieb ({@code ALC_SOFT_reopen_device}) — Context
     * und Buffer bleiben erhalten, kein Neuladen nötig. {@code name} leer = Systemstandard.
     */
    public void setDevice(String name) {
        if (!this.enabled) return;
        if (!ALC10.alcIsExtensionPresent(this.device, "ALC_SOFT_reopen_device")) {
            this.logger.warning("ALC_SOFT_reopen_device nicht verfügbar — Gerätewechsel erst nach Neustart.");
            return;
        }
        boolean ok = name == null || name.isEmpty()
                ? SOFTReopenDevice.alcReopenDeviceSOFT(this.device, (ByteBuffer) null, (IntBuffer) null)
                : SOFTReopenDevice.alcReopenDeviceSOFT(this.device, name, (IntBuffer) null);
        if (ok) {
            this.logger.info("Audio-Gerät gewechselt: " + (name == null || name.isEmpty() ? "Systemstandard" : name));
        } else {
            this.logger.warning("Audio-Gerätewechsel fehlgeschlagen (" + name + ") — Gerät unverändert.");
        }
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
        unique.addAll(this.placeBuffers.values());
        for (int[] loose : new int[][]{this.uiClickVariants, this.hurtVariants, this.fallSmallVariants,
                this.fallBigVariants, this.eatVariants, this.burpVariants}) {
            if (loose != null) unique.add(loose);
        }
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
