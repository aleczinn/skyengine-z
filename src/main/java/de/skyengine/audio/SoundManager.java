package de.skyengine.audio;

import de.skyengine.core.file.FileHandle;
import de.skyengine.core.file.FileType;
import de.skyengine.core.io.IDisposable;
import de.skyengine.core.resource.ResourceManager;
import de.skyengine.core.resource.Resources;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.game.entity.MinecartEntity;
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

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
public final class SoundManager implements IDisposable, UnderwaterAudioSink {

    /** Minecraft 26.2 {@code sounds.json}: volume=0.7 fuer entity.player.attack.weak. */
    static final float WEAK_ATTACK_VOLUME = 0.7F;

    /* 12 waren zu wenig, seit es Redstone-Maschinen gibt: der Kolben-Sound ist mit 0,65-0,92 s
       (0,552-s-Datei, gestreckt durch den MC-Pitch 0.6) der längste Effekt der Engine, und eine
       Kolbentür feuert ALLE ihre Kolben im selben Tick. Vier Kolben bei vier Schaltvorgängen pro
       Sekunde belegen rechnerisch schon ~13 Sources — Schritte und Abbaugeräusche kommen obendrauf,
       und über den Pool hinaus fällt der Sound still weg. OpenAL Soft trägt 256 Mono-Sources. */
    private static final int POOL_SIZE = 64;
    private static final int MAX_VARIANTS = 32; // u.a. 14 Spieler-Schwimmvarianten

    /* Musik ist nicht-positional und viele Tracks sind bis nahe Vollpegel gemastert. Der
       MUSIC-Regler steuert deshalb den vorgesehenen Mixbereich statt direkt OpenAL 0..1:
       100 % entsprechen rund -9 dB und bleiben damit im Verhaeltnis zu Effekten ausgewogen. */
    private static final float MUSIC_BASE_GAIN = 0.35F;

    /* Lautstärke/Pitch-Konventionen wie Minecraft. */
    private static final float STEP_GAIN = 0.15F, STEP_PITCH = 1.0F;
    private static final float HIT_GAIN = 0.25F, HIT_PITCH = 0.5F;
    private static final float DIG_GAIN = 1.0F, DIG_PITCH = 0.8F;
    /* UI-Button-Klick (wie MCs Button-Gain) */
    private static final float UI_CLICK_GAIN = 0.25F;
    /* block.comparator.click: Gain 0,3; Subtract 0,55, Compare 0,5. */
    private static final float COMPARATOR_CLICK_GAIN = 0.3F;
    private static final float COMPARATOR_SUBTRACT_PITCH = 0.55F;
    private static final float COMPARATOR_COMPARE_PITCH = 0.5F;
    /* block.lever.click: Gain 0,3; eingeschaltet 0,6, ausgeschaltet 0,5. */
    private static final float LEVER_CLICK_GAIN = 0.3F;
    private static final float LEVER_ON_PITCH = 0.6F;
    private static final float LEVER_OFF_PITCH = 0.5F;
    /* Spieler-Sounds (Hurt/Aufprall/Essen) — nicht-positional am Listener, Kanal PLAYER. */
    private static final float HURT_GAIN = 1.0F;
    private static final float FALL_GAIN = 0.5F;
    private static final float EAT_GAIN = 0.75F;
    private static final float BURP_GAIN = 0.25F; // bewusst dezenter als MCs 0.5 (User-Wunsch)
    private static final float EXPLOSION_GAIN = 4.0F;
    /* Aufsammeln: MC-Werte aus Player.take — leise, hoher Pitch mit weiter Streuung. */
    private static final float PICKUP_GAIN = 0.2F;
    private static final float PICKUP_PITCH = 2.0F, PICKUP_PITCH_SPREAD = 0.7F;
    /* Kolben (MC: block.piston.extend/contract mit Gain 0.5). */
    private static final float PISTON_GAIN = 0.5F;
    /* Durchgebrannte Redstone-Fackel (MC: block.redstone_torch.burnout mit Gain 0.5). */
    private static final float FIZZ_GAIN = 0.5F;

    private final Logger logger = LogManager.getLogger(SoundManager.class.getName());
    /** Erschöpfter Pool wird nur einmal gemeldet — sonst spammt jede Maschine das Log voll. */
    private boolean poolExhaustedReported;

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

    /* Auf-/Zu-Sounds (Tür, Truhe) — je Satz ein eigener Ordner, kein Buffer-Sharing nötig. */
    private final EnumMap<BlockOpenSound, int[]> openBuffers = new EnumMap<>(BlockOpenSound.class);
    private final EnumMap<BlockOpenSound, int[]> closeBuffers = new EnumMap<>(BlockOpenSound.class);

    private final MusicPlayer music = new MusicPlayer();
    private final Random random = new Random();

    /* Playlist: alle Lieder aus game/sounds/music in gemischter Reihenfolge (Shuffle-Bag —
       erst wenn alle dran waren, wird neu gemischt). */
    private final List<MusicTrack> playlist = new ArrayList<>();
    private int playlistIndex;
    private boolean playlistActive;
    private MusicTrack playlistCurrent; // laeuft gerade - damit es nach dem Mischen nicht direkt folgt

    /* Lose Effekt-Sounds ohne BlockSoundGroup (null, solange die Dateien fehlen -> No-Op). */
    /* random/click.ogg wird von UI-Buttons, Comparator und Hebel geteilt. */
    private int[] uiClickVariants;
    private int[] hurtVariants;      // damage/hit1..3
    private int[] fallSmallVariants; // damage/fallsmall.ogg
    private int[] fallBigVariants;   // damage/fallbig.ogg
    private int[] eatVariants;       // eat/eat1..3
    private int[] burpVariants;      // eat/burp.ogg
    private int[] explosionVariants; // random/explode1..4
    private int[] fuseVariants;      // random/fuse.ogg
    private int[] fizzVariants;      // random/fizz.ogg (Fackel brennt durch)
    private int[] igniteVariants;    // random/ignite.ogg (Feuerzeug, in MC fire/ignite)
    private int[] portalAmbientVariants; // portal/ambient.ogg
    private int[] portalTriggerVariants; // portal/trigger.ogg
    private int[] portalTravelVariants;  // portal/travel.ogg
    private int[] pickupVariants;    // random/pop.ogg
    private int[] pistonOutVariants; // piston/out.ogg (Ausfahren)
    private int[] pistonInVariants;  // piston/in.ogg (Einfahren)
    private int[] minecartVariants;   // minecart/base.ogg (fahrendes Minecart)
    private int[] minecartInsideVariants; // minecart/inside.ogg (nur lokaler Insasse)
    private int[] itemFrameBreakVariants; // entity.item_frame.break, break1..3
    private int[] itemFrameRemoveItemVariants; // entity.item_frame.remove_item, remove_item1..4
    private int[] weakAttackVariants; // entity.player.attack.weak/nodamage, weak1..4
    private int[] strongAttackVariants; // entity/player/attack/strong1..6 (Entity-Treffer)
    private int[] underwaterEnterVariants;
    private int[] underwaterExitVariants;
    private int[] underwaterLoopVariants;
    private int[] swimVariants;
    private int[] splashVariants;
    private int[] heavySplashVariants;
    private int[] waterAmbientVariants; // block.water.ambient -> liquid/water.ogg
    private int[] lavaAmbientVariants;  // block.lava.ambient -> liquid/lava.ogg
    private int[] lavaPopVariants;      // block.lava.pop -> liquid/lavapop.ogg
    private VariantSet underwaterAdditions;
    private VariantSet underwaterRareAdditions;
    private VariantSet underwaterUltraRareAdditions;
    private int underwaterLoopSource = -1;
    private float underwaterLoopGain;

    /** Loop-Quellen werden über Entity-Identität geführt und nach jedem Sichtungsdurchlauf bereinigt. */
    private final IdentityHashMap<MinecartEntity, MinecartLoop> minecartLoops = new IdentityHashMap<>();
    private final IdentityHashMap<MinecartEntity, MinecartLoop> minecartInsideLoops = new IdentityHashMap<>();
    private int minecartSoundFrame;

    /* Wiederverwendet fürs Listener-Update (keine Frame-Allokationen). */
    private final Vector3d direction = new Vector3d();
    private final float[] orientation = new float[6];

    /** Initialisiert Gerät/Kontext und lädt alle Effekt-Sounds. Nur auf dem Render-Thread. */
    public void init() {
        try {
            if (Resources.get().listIds("sounds/").isEmpty()) {
                this.logger.warning("Keine Sounds im aktiven Ressourcen-Stack - Audio deaktiviert.");
                return;
            }
        } catch (IOException error) {
            this.logger.warning("Sound-Ressourcen konnten nicht aufgelistet werden: " + error.getMessage());
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

        /* Musik zuerst: Einige OpenAL-Treiber stellen deutlich weniger Sources als OpenAL Soft
           bereit. Eine dedizierte Musik-Source darf deshalb nicht erst nach dem Effekt-Pool
           angefordert werden. */
        boolean musicReserved = this.music.init();
        if (!musicReserved) {
            this.logger.warning("OpenAL konnte keine dedizierte Musik-Source reservieren.");
        }

        int[] requestedPool = new int[POOL_SIZE];
        int poolCount = 0;
        for (int i = 0; i < POOL_SIZE; i++) {
            while (AL10.alGetError() != AL10.AL_NO_ERROR) {
                // vorherige Fehler anderer Initialisierungsschritte leeren
            }
            int source = AL10.alGenSources();
            int error = AL10.alGetError();
            if (source == 0 || error != AL10.AL_NO_ERROR) {
                if (source != 0) AL10.alDeleteSources(source);
                break;
            }
            requestedPool[poolCount++] = source;
        }
        this.pool = Arrays.copyOf(requestedPool, poolCount);
        this.logger.info("OpenAL-Sources: " + (musicReserved ? 1 : 0)
                + " fuer Musik reserviert, " + poolCount + " fuer Effekte.");

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
        this.explosionVariants = this.loadVariants("random", "explode");
        this.fuseVariants = this.loadVariants("random", "fuse");
        this.fizzVariants = this.loadVariants("random", "fizz");
        this.igniteVariants = this.loadVariants("random", "ignite");
        this.portalAmbientVariants = this.loadVariants("portal", "ambient");
        this.portalTriggerVariants = this.loadVariants("portal", "trigger");
        this.portalTravelVariants = this.loadVariants("portal", "travel");
        this.pickupVariants = this.loadVariants("random", "pop");
        this.pistonOutVariants = this.loadVariants("piston", "out");
        this.pistonInVariants = this.loadVariants("piston", "in");
        this.minecartVariants = this.loadVariants("minecart", "base");
        this.minecartInsideVariants = this.loadVariants("minecart", "inside");
        this.itemFrameBreakVariants = this.loadVariants("item_frame", "break");
        this.itemFrameRemoveItemVariants = this.loadVariants("item_frame", "remove_item");
        this.weakAttackVariants = this.loadVariants("player_attack", "weak");
        this.strongAttackVariants = this.loadVariants("player_attack", "strong");
        this.underwaterEnterVariants = this.loadVariants("underwater", "enter");
        this.underwaterExitVariants = this.loadVariants("underwater", "exit");
        this.underwaterLoopVariants = this.loadVariants("underwater", "underwater_ambience");
        this.swimVariants = this.loadNamed("liquid", names("swim", 5, 18));
        this.splashVariants = this.loadNamed("liquid", new String[]{"splash", "splash2"});
        this.heavySplashVariants = this.loadNamed("liquid", new String[]{"heavy_splash"});
        this.waterAmbientVariants = this.loadVariants("liquid", "water");
        this.lavaAmbientVariants = this.loadVariants("liquid", "lava");
        this.lavaPopVariants = this.loadVariants("liquid", "lavapop");
        this.underwaterAdditions = this.loadNamedWithGains("underwater/additions",
                new String[]{"bubbles1", "bubbles2", "bubbles3", "bubbles4", "bubbles5", "bubbles6",
                        "water1", "water2"},
                new float[]{1, 1, 1, 1, 1, 1, 1, 1});
        this.underwaterRareAdditions = this.loadNamedWithGains("underwater/additions/rare",
                new String[]{"animal1", "bass_whale1", "bass_whale2", "crackles1", "crackles2",
                        "driplets1", "driplets2", "earth_crack"},
                new float[]{1F, 0.45F, 0.5F, 0.7F, 1F, 0.5F, 0.5F, 1F});
        this.underwaterUltraRareAdditions = this.loadNamedWithGains("underwater/additions/ultra_rare",
                new String[]{"animal2", "dark1", "dark2", "dark3", "dark4"},
                new float[]{1F, 1F, 0.7F, 1F, 1F});
        loaded += count(this.uiClickVariants) + count(this.hurtVariants) + count(this.fallSmallVariants)
                + count(this.fallBigVariants) + count(this.eatVariants) + count(this.burpVariants)
                + count(this.explosionVariants) + count(this.fuseVariants) + count(this.fizzVariants)
                + count(this.igniteVariants) + count(this.portalAmbientVariants)
                + count(this.portalTriggerVariants) + count(this.portalTravelVariants)
                + count(this.pickupVariants)
                + count(this.pistonOutVariants) + count(this.pistonInVariants)
                + count(this.minecartVariants) + count(this.minecartInsideVariants)
                + count(this.itemFrameBreakVariants) + count(this.itemFrameRemoveItemVariants)
                + count(this.weakAttackVariants) + count(this.strongAttackVariants)
                + count(this.underwaterEnterVariants)
                + count(this.underwaterExitVariants) + count(this.underwaterLoopVariants)
                + count(this.swimVariants) + count(this.splashVariants) + count(this.heavySplashVariants)
                + count(this.waterAmbientVariants) + count(this.lavaAmbientVariants)
                + count(this.lavaPopVariants)
                + count(this.underwaterAdditions) + count(this.underwaterRareAdditions)
                + count(this.underwaterUltraRareAdditions);

        /* Auf-/Zu-Sounds je Satz aus seinem eigenen Ordner; fehlt einer, bleibt nur er stumm. */
        for (BlockOpenSound sound : BlockOpenSound.values()) {
            int[] open = this.loadVariants(sound.folder, "open");
            int[] close = this.loadVariants(sound.folder, "close");
            if (open != null) this.openBuffers.put(sound, open);
            if (close != null) this.closeBuffers.put(sound, close);
            loaded += count(open) + count(close);
        }

        this.enabled = true;
        String deviceName = ALC10.alcGetString(this.device, ALC10.ALC_DEVICE_SPECIFIER);
        this.logger.info("Audio initialisiert: " + loaded + " Effekt-Sounds geladen (Gerät: " + deviceName + ")");
    }

    private FileHandle sound(String relativePath) {
        return new FileHandle("game/sounds/" + relativePath, FileType.RESOURCE);
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
            FileHandle file = this.sound(folder + "/" + baseName + i + ".ogg");
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
            FileHandle file = this.sound(folder + "/" + baseName + i + ".ogg");
            if (!file.exists()) break;
            int buffer = OggLoader.load(file, true);
            if (buffer == -1) continue;
            variants[count++] = buffer;
        }
        if (count == 0) {
            FileHandle single = this.sound(folder + "/" + baseName + ".ogg");
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

    private static int count(VariantSet variants) {
        return variants == null ? 0 : variants.buffers.length;
    }

    private static String[] names(String base, int first, int last) {
        String[] names = new String[last - first + 1];
        for (int i = first; i <= last; i++) names[i - first] = base + i;
        return names;
    }

    /** Lädt eine explizite Ereignisliste; Lücken entfernen nicht die folgenden Varianten. */
    private int[] loadNamed(String folder, String[] names) {
        int[] buffers = new int[names.length];
        int count = 0;
        for (String name : names) {
            FileHandle file = this.sound(folder + "/" + name + ".ogg");
            if (!file.exists()) continue;
            int buffer = OggLoader.load(file, true);
            if (buffer != -1) buffers[count++] = buffer;
        }
        if (count == 0) {
            this.logger.warning(folder + " Unterwasser-Sounds fehlen (scripts/extract-mc-sounds.ps1 ausführen).");
            return null;
        }
        return Arrays.copyOf(buffers, count);
    }

    private VariantSet loadNamedWithGains(String folder, String[] names, float[] gains) {
        int[] buffers = new int[names.length];
        float[] loadedGains = new float[names.length];
        int count = 0;
        for (int i = 0; i < names.length; i++) {
            FileHandle file = this.sound(folder + "/" + names[i] + ".ogg");
            if (!file.exists()) continue;
            int buffer = OggLoader.load(file, true);
            if (buffer == -1) continue;
            buffers[count] = buffer;
            loadedGains[count++] = gains[i];
        }
        return count == 0 ? null : new VariantSet(Arrays.copyOf(buffers, count), Arrays.copyOf(loadedGains, count));
    }

    /* --- Gameplay-API (No-Ops, solange nicht enabled) --- */

    /** Laufgeräusch — nicht-positional am Listener. */
    public void playStep(BlockSoundGroup group) {
        this.play(this.stepBuffers.get(group), SoundCategory.PLAYER, STEP_GAIN, STEP_PITCH, true, false, 0, 0, 0);
    }

    @Override
    public void playUnderwaterEnter() {
        this.play(this.underwaterEnterVariants, SoundCategory.AMBIENT, 0.5F, 1F,
                false, false, 0, 0, 0);
    }

    @Override
    public void playUnderwaterExit() {
        this.play(this.underwaterExitVariants, SoundCategory.AMBIENT, 0.3F, 1F,
                false, false, 0, 0, 0);
    }

    @Override
    public void playUnderwaterAddition(int rarity) {
        VariantSet variants = switch (rarity) {
            case UnderwaterAudioController.ADDITION_RARE -> this.underwaterRareAdditions;
            case UnderwaterAudioController.ADDITION_ULTRA_RARE -> this.underwaterUltraRareAdditions;
            default -> this.underwaterAdditions;
        };
        this.play(variants, SoundCategory.AMBIENT);
    }

    @Override
    public void playSwim(float volume) {
        /* Entity.playSwimSound: 1 + (rand-rand)*0,4. Die Lautstaerke wurde bereits
           aus der anisotrop gewichteten Bewegung berechnet und darf nicht nochmals
           mit der Geschwindigkeit multipliziert werden. */
        float pitch = 1F + (this.random.nextFloat() - this.random.nextFloat()) * 0.4F;
        this.play(this.swimVariants, SoundCategory.PLAYER, volume, pitch,
                false, false, 0, 0, 0);
    }

    @Override
    public void playSplash(float speed) {
        boolean heavy = speed >= 0.25F;
        float gain = Math.min(1F, Math.max(0.2F, speed * 0.2F));
        this.play(heavy ? this.heavySplashVariants : this.splashVariants,
                SoundCategory.PLAYER, gain, 1F, true, false, 0, 0, 0);
    }

    @Override
    public void setUnderwaterLoopGain(float gain) {
        this.underwaterLoopGain = Math.clamp(gain, 0F, 1F);
        if (!this.enabled || this.underwaterLoopVariants == null) return;
        if (this.underwaterLoopGain <= 0F) {
            if (this.underwaterLoopSource != -1) AL10.alSourceStop(this.underwaterLoopSource);
            this.underwaterLoopSource = -1;
            return;
        }
        if (this.underwaterLoopSource == -1) {
            int source = this.acquireSource();
            if (source == -1) return;
            this.underwaterLoopSource = source;
            AL10.alSourcei(source, AL10.AL_BUFFER, this.underwaterLoopVariants[0]);
            AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_TRUE);
            AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(source, AL10.AL_POSITION, 0, 0, 0);
            AL10.alSourcef(source, AL10.AL_PITCH, 1F);
            AL10.alSourcePlay(source);
        }
        AL10.alSourcef(this.underwaterLoopSource, AL10.AL_GAIN,
                0.65F * this.underwaterLoopGain * this.categoryGains[SoundCategory.AMBIENT.ordinal()]);
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

    /**
     * Comparator-Moduswechsel wie Vanilla {@code ComparatorBlock#useWithoutItem}: positional,
     * BLOCKS-Kanal, ohne Zufalls-Jitter. Subtract klingt mit 0,55 etwas hoeher als Compare 0,5.
     */
    public void playComparatorClick(boolean subtract, double x, double y, double z) {
        this.play(this.uiClickVariants, SoundCategory.BLOCKS, COMPARATOR_CLICK_GAIN,
                subtract ? COMPARATOR_SUBTRACT_PITCH : COMPARATOR_COMPARE_PITCH,
                false, true, x, y, z);
    }

    /**
     * Hebel-Klick wie Vanilla {@code LeverBlock#pull}: positional im BLOCKS-Kanal und ohne
     * Zufalls-Jitter. Der frisch eingeschaltete Zustand klingt mit 0,6 etwas hoeher als aus.
     */
    public void playLeverClick(boolean powered, double x, double y, double z) {
        this.play(this.uiClickVariants, SoundCategory.BLOCKS, LEVER_CLICK_GAIN,
                powered ? LEVER_ON_PITCH : LEVER_OFF_PITCH,
                false, true, x, y, z);
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

    /**
     * Item aufgesammelt — nicht-positional (der Aufsammel-Radius ist gut einen Block groß, da
     * wäre positional unhörbar). Der Pitch wird nach MCs {@code Player.take} hier gewürfelt:
     * die Streuung ist mit ±70 % viel weiter als der ±10 %-Jitter von {@link #play}.
     */
    public void playPickup() {
        float pitch = ((this.random.nextFloat() - this.random.nextFloat()) * PICKUP_PITCH_SPREAD + 1.0F)
                * PICKUP_PITCH;
        this.play(this.pickupVariants, SoundCategory.PLAYER, PICKUP_GAIN, pitch, false, false, 0, 0, 0);
    }

    /**
     * Explosions-Sound wie {@code ClientPacketListener#handleExplosion}: Gain 4 und Pitch
     * {@code (1 + (rand - rand) * 0.2) * 0.7}. Der generische +/-10-%-Jitter waere zu hoch.
     */
    public void playExplosion(double x, double y, double z) {
        float pitch = (1.0F + (this.random.nextFloat() - this.random.nextFloat()) * 0.2F) * 0.7F;
        this.play(this.explosionVariants, SoundCategory.BLOCKS, EXPLOSION_GAIN, pitch,
                false, true, x, y, z);
    }

    /** Zünd-/Fuse-Zischen (TNT) — positional beim Zünden, fester Pitch. Stumm ohne Asset. */
    public void playFuse(double x, double y, double z) {
        this.play(this.fuseVariants, SoundCategory.BLOCKS, 1.0F, 1.0F, false, true, x, y, z);
    }

    /** Kolben fährt aus — positional an der Basis; MC-Pitch 0,6 ± Streuung. Stumm ohne Asset. */
    public void playPistonExtend(double x, double y, double z) {
        this.play(this.pistonOutVariants, SoundCategory.BLOCKS, PISTON_GAIN, this.pistonPitch(), false, true, x, y, z);
    }

    /** Kolben fährt ein — Gegenstück zu {@link #playPistonExtend}. */
    public void playPistonContract(double x, double y, double z) {
        this.play(this.pistonInVariants, SoundCategory.BLOCKS, PISTON_GAIN, this.pistonPitch(), false, true, x, y, z);
    }

    /** MC-Formel {@code 0.6 + rand*0.25} — tiefer Basis-Pitch mit eigener Streuung statt ±10 %-Jitter. */
    private float pistonPitch() {
        return 0.6F + this.random.nextFloat() * 0.25F;
    }

    /**
     * Redstone-Fackel brennt durch — kurzes Zischen, positional am Block.
     * MC-Werte für {@code block.redstone_torch.burnout}: Gain 0,5 und der hohe Pitch
     * {@code 2.6 + (rand − rand) * 0.8}; die weite Streuung geht über den ±10-%-Jitter hinaus,
     * deshalb eine eigene Formel wie beim Kolben. Stumm, solange das Asset fehlt.
     */
    public void playFizz(double x, double y, double z) {
        float pitch = 1.8f + this.random.nextFloat() * (3.4f - 1.8f);
        this.play(this.fizzVariants, SoundCategory.BLOCKS, FIZZ_GAIN, pitch, false, true, x, y, z);
    }

    /** Wasser kühlt Lava zu Stein/Cobblestone/Obsidian ab (Vanillas Extinguish/Fizz-Ereignis). */
    public void playFluidExtinguish(double x, double y, double z) {
        this.playFizz(x, y, z);
    }

    /** Leises Gluckern von fließendem Wasser. */
    public void playWaterAmbient(double x, double y, double z) {
        float gain = 0.75F + this.random.nextFloat() * 0.25F;
        float pitch = 0.5F + this.random.nextFloat();
        this.play(this.waterAmbientVariants, SoundCategory.BLOCKS, gain, pitch,
                false, true, x, y, z);
    }

    /** Ruhiges Lava-Blubbern bei freier Oberfläche. */
    public void playLavaAmbient(double x, double y, double z) {
        float gain = 0.2F + this.random.nextFloat() * 0.2F;
        float pitch = 0.9F + this.random.nextFloat() * 0.15F;
        this.play(this.lavaAmbientVariants, SoundCategory.BLOCKS, gain, pitch,
                false, true, x, y, z);
    }

    /** Einzelne platzende Lavablase bei freier Oberfläche. */
    public void playLavaPop(double x, double y, double z) {
        float gain = 0.2F + this.random.nextFloat() * 0.2F;
        float pitch = 0.9F + this.random.nextFloat() * 0.15F;
        this.play(this.lavaPopVariants, SoundCategory.BLOCKS, gain, pitch,
                false, true, x, y, z);
    }

    /**
     * Feuerzeug schlägt Funken — positional am gezündeten Block. MC-Werte für
     * {@code item.flintandsteel.use}: Gain 1,0 und Pitch {@code rand * 0.4 + 0.8}.
     * Stumm, solange das Asset fehlt.
     */
    public void playIgnite(double x, double y, double z) {
        float pitch = this.random.nextFloat() * 0.4f + 0.8f;
        this.play(this.igniteVariants, SoundCategory.BLOCKS, 1.0F, pitch, false, true, x, y, z);
    }

    /** Minecraft 26.2: zufaelliger lokaler block.portal.ambient-Sound, kein Dauer-Loop. */
    public void playPortalAmbient(double x, double y, double z) {
        float pitch = this.random.nextFloat() * 0.4F + 0.8F;
        this.play(this.portalAmbientVariants, SoundCategory.BLOCKS, 0.5F, pitch,
                false, true, x, y, z, 1F, 10F);
    }

    /** Kurzer Energieschub, sobald ein vollstaendiger Obsidianrahmen aktiviert wurde. */
    public void playPortalTrigger(double x, double y, double z) {
        this.play(this.portalTriggerVariants, SoundCategory.BLOCKS, 0.9F, 1F,
                false, true, x, y, z);
    }

    /** Nicht-positionaler Uebergangssound beim Dimensionswechsel des lokalen Spielers. */
    public void playPortalTravel() {
        this.play(this.portalTravelVariants, SoundCategory.PLAYER, 0.8F, 1F,
                false, false, 0, 0, 0);
    }

    /** Dispenser/Dropper gibt ein Item aus: Vanillas Level-Event 1000, random/click bei Pitch 1,0. */
    public void playDispenserSuccess(double x, double y, double z) {
        this.play(this.uiClickVariants, SoundCategory.BLOCKS, 1.0F, 1.0F,
                false, true, x, y, z);
    }

    /** Leerer Dispenser/Dropper: Vanillas Level-Event 1001, derselbe Klick bei Pitch 1,2. */
    public void playDispenserFailure(double x, double y, double z) {
        this.play(this.uiClickVariants, SoundCategory.BLOCKS, 1.0F, 1.2F,
                false, true, x, y, z);
    }

    /** Beginnt den pro Frame ausgeführten Sichtungsdurchlauf für gebundene Minecart-Loops. */
    public void beginMinecartSounds() {
        if (this.enabled) this.minecartSoundFrame++;
    }

    /**
     * Aktualisiert Vanillas äußeres Minecart-Fahrgeräusch. Die Lautstärke folgt
     * {@code clamp(horizontalSpeed, 0, 0.5) * 0.7}; die Loop-Quelle bleibt auch im Stand mit
     * Gain 0 gebunden, damit beim erneuten Anrollen kein Sound-Neustart klickt.
     */
    public void updateMinecartSound(MinecartEntity minecart, double x, double y, double z,
                                    double horizontalSpeed, boolean localPlayerRiding) {
        if (!this.enabled || this.minecartVariants == null) return;
        MinecartLoop loop = this.minecartLoops.get(minecart);
        if (loop == null) {
            int source = this.acquireSource();
            if (source == -1) return;
            AL10.alSourcei(source, AL10.AL_BUFFER,
                    this.minecartVariants[this.random.nextInt(this.minecartVariants.length)]);
            AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_TRUE);
            AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
            AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, 4.0F);
            AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, 32.0F);
            AL10.alSourcef(source, AL10.AL_PITCH, 1.0F);
            loop = new MinecartLoop(source);
            this.minecartLoops.put(minecart, loop);
            AL10.alSourcePlay(source);
        }
        loop.seenFrame = this.minecartSoundFrame;
        float volume = (float) (Math.clamp(horizontalSpeed, 0.0, 0.5) * 0.7);
        AL10.alSourcef(loop.source, AL10.AL_GAIN,
                volume * this.categoryGains[SoundCategory.BLOCKS.ordinal()]);
        AL10.alSource3f(loop.source, AL10.AL_POSITION, (float) x, (float) y, (float) z);
        int state = AL10.alGetSourcei(loop.source, AL10.AL_SOURCE_STATE);
        if (state == AL10.AL_STOPPED || state == AL10.AL_INITIAL) AL10.alSourcePlay(loop.source);

        if (localPlayerRiding) this.updateMinecartInsideSound(minecart, horizontalSpeed);
    }

    /** Voll aufgeladener erfolgreicher Spielerangriff; Minecarts besitzen keinen eigenen Bruchton. */
    public void playStrongAttack() {
        this.play(this.strongAttackVariants, SoundCategory.PLAYER, 0.7F, 1.0F,
                false, false, 0, 0, 0);
    }

    /** Minecrafts entity.player.attack.weak/nodamage: einmal pro normalem Angriffsklick. */
    public void playSwingAttack() {
        /* Minecraft 26.2 sounds.json setzt fuer alle vier weak-Varianten volume=0.7. */
        this.play(this.weakAttackVariants, SoundCategory.PLAYER, WEAK_ATTACK_VOLUME, 1.0F,
                false, false, 0, 0, 0);
    }

    /** Inhalt aus einem Item Frame schlagen: entity.item_frame.remove_item. */
    public void playItemFrameRemoveItem(double x, double y, double z) {
        this.play(this.itemFrameRemoveItemVariants, SoundCategory.BLOCKS, 1.0F, 1.0F,
                false, true, x, y, z);
    }

    /** Leeren Item Frame beziehungsweise seine Aufhaengung zerstoeren. */
    public void playItemFrameBreak(double x, double y, double z) {
        this.play(this.itemFrameBreakVariants, SoundCategory.BLOCKS, 1.0F, 1.0F,
                false, true, x, y, z);
    }

    /** Vanillas separater {@code entity.minecart.inside}-Loop direkt am Listener. */
    private void updateMinecartInsideSound(MinecartEntity minecart, double horizontalSpeed) {
        if (this.minecartInsideVariants == null) return;
        MinecartLoop loop = this.minecartInsideLoops.get(minecart);
        if (loop == null) {
            int source = this.acquireSource();
            if (source == -1) return;
            AL10.alSourcei(source, AL10.AL_BUFFER, this.minecartInsideVariants[
                    this.random.nextInt(this.minecartInsideVariants.length)]);
            AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_TRUE);
            AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(source, AL10.AL_POSITION, 0, 0, 0);
            AL10.alSourcef(source, AL10.AL_PITCH, 1.0F);
            loop = new MinecartLoop(source);
            this.minecartInsideLoops.put(minecart, loop);
            AL10.alSourcePlay(source);
        }
        loop.seenFrame = this.minecartSoundFrame;
        float volume = (float) (Math.clamp(horizontalSpeed, 0.0, 1.0) * 0.75);
        AL10.alSourcef(loop.source, AL10.AL_GAIN,
                volume * this.categoryGains[SoundCategory.BLOCKS.ordinal()]);
        int state = AL10.alGetSourcei(loop.source, AL10.AL_SOURCE_STATE);
        if (state == AL10.AL_STOPPED || state == AL10.AL_INITIAL) AL10.alSourcePlay(loop.source);
    }

    /** Stoppt Loops von zerstörten Minecarts und solchen aus inzwischen entladenen Chunks. */
    public void endMinecartSounds() {
        if (!this.enabled) return;
        this.removeUnseenMinecartLoops(this.minecartLoops);
        this.removeUnseenMinecartLoops(this.minecartInsideLoops);
    }

    private void removeUnseenMinecartLoops(IdentityHashMap<MinecartEntity, MinecartLoop> active) {
        Iterator<MinecartLoop> loops = active.values().iterator();
        while (loops.hasNext()) {
            MinecartLoop loop = loops.next();
            if (loop.seenFrame == this.minecartSoundFrame) continue;
            this.releaseMinecartLoop(loop);
            loops.remove();
        }
    }

    /** Räumt beim Weltwechsel alle weltgebundenen Loop-Quellen sofort auf. */
    public void stopMinecartSounds() {
        if (!this.enabled) return;
        for (MinecartLoop loop : this.minecartLoops.values()) this.releaseMinecartLoop(loop);
        for (MinecartLoop loop : this.minecartInsideLoops.values()) this.releaseMinecartLoop(loop);
        this.minecartLoops.clear();
        this.minecartInsideLoops.clear();
    }

    private void releaseMinecartLoop(MinecartLoop loop) {
        AL10.alSourceStop(loop.source);
        AL10.alSourcei(loop.source, AL10.AL_LOOPING, AL10.AL_FALSE);
        AL10.alSourcei(loop.source, AL10.AL_BUFFER, 0);
    }

    /** Tür/Truhe geht auf — positional an der Block-Position. Lautstärke/Pitch aus dem Satz. */
    public void playBlockOpen(BlockOpenSound sound, double x, double y, double z) {
        if (sound == null) return;
        this.play(this.openBuffers.get(sound), SoundCategory.BLOCKS, sound.gain, 1.0F,
                sound.pitchJitter, true, x, y, z);
    }

    /** Tür/Truhe geht zu — Gegenstück zu {@link #playBlockOpen}. */
    public void playBlockClose(BlockOpenSound sound, double x, double y, double z) {
        if (sound == null) return;
        this.play(this.closeBuffers.get(sound), SoundCategory.BLOCKS, sound.gain, 1.0F,
                sound.pitchJitter, true, x, y, z);
    }

    /** Zufällige Variante + optional ±10 % Zufalls-Pitch auf einer freien Pool-Source. */
    private void play(int[] variants, SoundCategory category, float gain, float pitch, boolean pitchJitter,
                      boolean positional, double x, double y, double z) {
        this.play(variants, category, gain, pitch, pitchJitter, positional, x, y, z, 4F, 32F);
    }

    private void play(int[] variants, SoundCategory category, float gain, float pitch, boolean pitchJitter,
                      boolean positional, double x, double y, double z,
                      float referenceDistance, float maxDistance) {
        if (!this.enabled || variants == null) return;
        int source = this.acquireSource();
        if (source == -1) {
            /* Sound verwerfen statt eine laufende Source zu stehlen. EINMAL melden: still
               verworfen war diese Fehlerklasse unsichtbar — genau daran fehlte der Kolben-Sound. */
            if (!this.poolExhaustedReported) {
                this.poolExhaustedReported = true;
                this.logger.warning("Sound-Pool erschoepft (" + (this.pool == null ? 0 : this.pool.length)
                        + " Sources belegt) — Sounds fallen aus. Wird nur einmal gemeldet.");
            }
            return;
        }

        AL10.alSourcei(source, AL10.AL_BUFFER, variants[this.random.nextInt(variants.length)]);
        AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_FALSE);
        AL10.alSourcef(source, AL10.AL_GAIN, gain * this.categoryGains[category.ordinal()]);
        AL10.alSourcef(source, AL10.AL_PITCH, pitchJitter ? pitch * (0.9F + this.random.nextFloat() * 0.2F) : pitch);
        if (positional) {
            AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
            AL10.alSource3f(source, AL10.AL_POSITION, (float) x, (float) y, (float) z);
            AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, referenceDistance);
            AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, maxDistance);
            AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 1.0F);
        } else {
            AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(source, AL10.AL_POSITION, 0, 0, 0);
        }
        AL10.alSourcePlay(source);
    }

    private void play(VariantSet variants, SoundCategory category) {
        if (!this.enabled || variants == null) return;
        int selected = this.random.nextInt(variants.buffers.length);
        this.play(new int[]{variants.buffers[selected]}, category, variants.gains[selected], 1F,
                false, false, 0, 0, 0);
    }

    /**
     * Round-Robin über den Pool; −1, wenn keine Source frei ist. „Frei" heißt ausdrücklich
     * STOPPED oder INITIAL und nicht bloß „spielt nicht": eine **pausierte** Source (Pausenmenü)
     * gehört ihrem Sound weiterhin. Würde man sie neu vergeben, wäre die pausierte Wiedergabe
     * verloren und {@code alSourcei(src, AL_BUFFER, …)} liefe laut Spec in AL_INVALID_OPERATION.
     */
    private int acquireSource() {
        if (this.pool == null || this.pool.length == 0) return -1;
        for (int i = 0; i < this.pool.length; i++) {
            int source = this.pool[(this.poolCursor + i) % this.pool.length];
            int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
            if (state == AL10.AL_STOPPED || state == AL10.AL_INITIAL) {
                this.poolCursor = (this.poolCursor + i + 1) % this.pool.length;
                return source;
            }
        }
        return -1;
    }

    /* --- Pause (Pausenmenü) --- */

    /**
     * Hält alle laufenden Sounds an — und die Musik nur, wenn {@code pauseMusicInMenus} gesetzt
     * ist (sonst spielt sie im Menü weiter). Nur AL_PLAYING wird pausiert — gestoppte Sources
     * dürfen nicht angefasst werden, sonst starteten sie beim Fortsetzen von vorn.
     *
     * <p>Neue Sounds bleiben möglich: die Klick-Sounds der Pausenmenü-Buttons holen sich eine
     * der freien Sources (wie in MC).
     */
    public void pauseAll() {
        if (!this.enabled) return;
        for (int source : this.pool) {
            if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING) {
                AL10.alSourcePause(source);
            }
        }
        this.applyMusicPause(true);
    }

    /**
     * Hält die Musik an oder lässt sie laufen — je nach {@code pauseMusicInMenus}. Wird auch vom
     * Sound-Optionen-Screen gerufen, wenn der Schalter bei offenem Pausenmenü umgelegt wird.
     */
    public void applyMusicPause(boolean gamePaused) {
        if (!this.enabled) return;
        if (gamePaused && GameSettings.get().pauseMusicInMenus) {
            this.music.pause();
        } else {
            this.music.resume();
        }
    }

    /** Gegenstück zu {@link #pauseAll}: setzt genau die pausierten Sources fort. */
    public void resumeAll() {
        if (!this.enabled) return;
        for (int source : this.pool) {
            if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_PAUSED) {
                AL10.alSourcePlay(source);
            }
        }
        this.music.resume();
    }

    /* --- Musik --- */

    /**
     * Startet die Musik-Playlist: alle {@code .ogg}/{@code .wav} aus {@code game/sounds/music}
     * werden gemischt und nacheinander ohne Pause gespielt; ist die Tasche leer, wird neu
     * gemischt (Shuffle-Bag — kein Lied wiederholt sich, bevor die anderen dran waren).
     * Der Ordner ist die einzige Quelle: neue Lieder hineinkopieren genügt.
     */
    public void startMusicPlaylist() {
        if (!this.enabled) return;

        this.playlist.clear();
        try {
            this.playlist.addAll(loadMusicTracks(Resources.get()));
        } catch (IOException error) {
            this.logger.warning("Musik-Ressourcen konnten nicht geladen werden: " + error.getMessage());
        }
        if (this.playlist.isEmpty()) {
            this.logger.warning("Keine Musik in sounds/music gefunden (.ogg/.wav) - es laeuft keine Musik.");
            this.playlistActive = false;
            return;
        }

        this.logger.info("Musik-Playlist: " + this.playlist.size() + " Lied(er) gefunden.");
        this.playlistCurrent = null;
        this.playlistIndex = this.playlist.size(); // erzwingt das Mischen in playNextTrack
        this.playlistActive = true;
        this.playNextTrack();
    }

    /** Sichtbarer Test-Seam fuer die Ressourcenauflistung; OpenAL ist hier noch nicht beteiligt. */
    static List<MusicTrack> loadMusicTracks(ResourceManager resources) throws IOException {
        List<MusicTrack> tracks = new ArrayList<>();
        var resolved = resources.listResolved("sounds/music/");
        for (var entry : resolved.entrySet().stream()
                .sorted(java.util.Comparator
                        .comparing((Map.Entry<de.skyengine.core.resource.ResourceId,
                                ResourceManager.Match> value) -> value.getKey().namespace())
                        .thenComparing(value -> value.getKey().path()))
                .toList()) {
            var id = entry.getKey();
            String path = id.path();
            String lower = path.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".ogg") && !lower.endsWith(".wav")) continue;
            try (InputStream in = entry.getValue().open()) {
                tracks.add(new MusicTrack(id.namespace() + ":" + path, in.readAllBytes()));
            }
        }
        return tracks;
    }

    /** Beendet die Musik dauerhaft (die Playlist rückt danach nicht mehr nach). */
    public void stopMusic() {
        if (!this.enabled) return;
        this.playlistActive = false;
        this.music.stop();
    }

    /** Spielt den nächsten Eintrag; defekte Dateien fliegen dabei aus der Playlist. */
    private void playNextTrack() {
        for (int attempt = this.playlist.size(); attempt > 0; attempt--) {
            if (this.playlist.isEmpty()) break;
            if (this.playlistIndex >= this.playlist.size()) this.reshuffle();

            MusicTrack track = this.playlist.get(this.playlistIndex++);
            if (this.music.play(track, false)) {
                this.playlistCurrent = track;
                return;
            }
            /* Nicht abspielbar (Warnung kam aus dem MusicPlayer): raus aus der Playlist. */
            this.playlist.remove(--this.playlistIndex);
        }
        this.playlistActive = false;
        this.logger.warning("Kein abspielbares Lied in der Playlist — es läuft keine Musik.");
    }

    /** Neue Runde: mischen und dabei verhindern, dass das eben gespielte Lied direkt folgt. */
    private void reshuffle() {
        Collections.shuffle(this.playlist, this.random);
        if (this.playlist.size() > 1 && this.playlist.get(0).equals(this.playlistCurrent)) {
            Collections.swap(this.playlist, 0, 1 + this.random.nextInt(this.playlist.size() - 1));
        }
        this.playlistIndex = 0;
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

    /** Pro Frame: Musik-Streaming nachfüllen und am Liedende das nächste starten. */
    public void update() {
        if (!this.enabled) return;
        this.music.update();
        /* Pausiert (Pausenmenü) zählt als „läuft" — dort rückt die Playlist absichtlich nicht vor. */
        if (this.playlistActive && !this.music.isPlaying()) this.playNextTrack();
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
        if (category == SoundCategory.MUSIC) this.music.setVolume(gain * MUSIC_BASE_GAIN);
        if (category == SoundCategory.AMBIENT && this.underwaterLoopSource != -1) {
            AL10.alSourcef(this.underwaterLoopSource, AL10.AL_GAIN,
                    0.65F * this.underwaterLoopGain * gain);
        }
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

    /** Baut alle OpenAL-Ressourcen aus dem aktuell aktiven Pack-Stack neu auf. */
    public void reloadResources() {
        this.dispose();
        this.clearLoadedResources();
        this.init();
    }

    private void clearLoadedResources() {
        this.stepBuffers.clear();
        this.digBuffers.clear();
        this.placeBuffers.clear();
        this.openBuffers.clear();
        this.closeBuffers.clear();
        this.playlist.clear();
        this.playlistCurrent = null;
        this.playlistActive = false;
        this.pool = null;
        this.poolCursor = 0;
        this.poolExhaustedReported = false;
        this.uiClickVariants = this.hurtVariants = this.fallSmallVariants = this.fallBigVariants = null;
        this.eatVariants = this.burpVariants = this.explosionVariants = this.fuseVariants = null;
        this.fizzVariants = this.igniteVariants = this.pickupVariants = null;
        this.portalAmbientVariants = this.portalTriggerVariants = this.portalTravelVariants = null;
        this.pistonOutVariants = this.pistonInVariants = this.minecartVariants = null;
        this.minecartInsideVariants = this.itemFrameBreakVariants = this.itemFrameRemoveItemVariants = null;
        this.weakAttackVariants = this.strongAttackVariants = null;
        this.underwaterEnterVariants = this.underwaterExitVariants = this.underwaterLoopVariants = null;
        this.swimVariants = this.splashVariants = this.heavySplashVariants = null;
        this.waterAmbientVariants = this.lavaAmbientVariants = this.lavaPopVariants = null;
        this.underwaterAdditions = this.underwaterRareAdditions = this.underwaterUltraRareAdditions = null;
        this.underwaterLoopSource = -1;
        this.underwaterLoopGain = 0F;
    }

    @Override
    public void dispose() {
        if (this.device == 0) return;
        this.setUnderwaterLoopGain(0F);
        this.stopMinecartSounds();
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
        unique.addAll(this.openBuffers.values());
        unique.addAll(this.closeBuffers.values());
        for (int[] loose : new int[][]{this.uiClickVariants, this.hurtVariants, this.fallSmallVariants,
                this.fallBigVariants, this.eatVariants, this.burpVariants, this.explosionVariants,
                this.fuseVariants, this.fizzVariants, this.igniteVariants, this.portalAmbientVariants,
                this.portalTriggerVariants, this.portalTravelVariants, this.pickupVariants, this.pistonOutVariants,
                this.pistonInVariants, this.minecartVariants, this.minecartInsideVariants,
                this.itemFrameBreakVariants, this.itemFrameRemoveItemVariants,
                this.weakAttackVariants, this.strongAttackVariants,
                this.underwaterEnterVariants, this.underwaterExitVariants,
                this.underwaterLoopVariants, this.swimVariants, this.splashVariants,
                this.heavySplashVariants, this.waterAmbientVariants, this.lavaAmbientVariants,
                this.lavaPopVariants}) {
            if (loose != null) unique.add(loose);
        }
        for (VariantSet variants : new VariantSet[]{this.underwaterAdditions,
                this.underwaterRareAdditions, this.underwaterUltraRareAdditions}) {
            if (variants != null) unique.add(variants.buffers);
        }
        for (int[] variants : unique) {
            for (int buffer : variants) AL10.alDeleteBuffers(buffer);
        }
        ALC10.alcMakeContextCurrent(0);
        ALC10.alcDestroyContext(this.context);
        ALC10.alcCloseDevice(this.device);
        this.device = 0;
        this.context = 0;
        this.enabled = false;
    }

    private static final class MinecartLoop {
        final int source;
        int seenFrame;

        MinecartLoop(int source) {
            this.source = source;
        }
    }

    private record VariantSet(int[] buffers, float[] gains) {}
}
