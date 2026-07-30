package de.skyengine.graphics.world;

import de.skyengine.game.GameContainer;
import de.skyengine.game.world.save.WorldSaves;

/**
 * Messstand für den A/B-Vergleich CPU-Cull ↔ GPU-Cull (Frustum) ↔ GPU-Cull (Frustum + Hi-Z).
 * Nur aktiv über die System-Property {@code -Dskyengine.cullbench=<Weltordner>}.
 *
 * <p>Warum überhaupt: Ein ehrlicher Vergleich der drei Pfade braucht DIESELBE Szene bei
 * DERSELBEN Kameraposition im SELBEN Prozess — die Spawn-Position variiert je Lauf, und
 * Ladewellen verfälschen jede Messung. Der Messstand lädt deshalb eine gespeicherte Welt,
 * wartet den vollständigen Ladevorgang ab, friert das Chunk-Loading ein und schaltet dann
 * ohne Kamerabewegung im festen Takt zwischen den Konfigurationen um. Die Marker-Zeilen
 * trennen die Abschnitte in der Konsolenausgabe des FrameProfilers.
 *
 * <p>Ohne die Property ist jede Methode ein No-op; der normale Spielstart bleibt unberührt.
 */
public final class CullBench {

    /** Sekunden je Konfiguration (die ersten zwei Sekunden werden bei der Auswertung verworfen). */
    private static final long PHASE_SECONDS = 12;
    /** Nach dem Einfrieren erst warten, bis die Upload-Queues leerlaufen. */
    private static final long SETTLE_SECONDS = 5;
    /** So viele Sekunden muss die LOD-Regionszahl konstant sein, bevor eingefroren wird. */
    private static final long LOD_STABIL_SEKUNDEN = 6;

    private enum Konfiguration {
        CPU("CPU-Cull", false, false),
        GPU_FRUSTUM("GPU-Cull Frustum (Single-Phase)", true, true),
        GPU_HIZ("GPU-Cull Frustum + Hi-Z (Two-Phase)", true, false);

        final String label;
        final boolean gpu;
        final boolean frustumOnly;

        Konfiguration(String label, boolean gpu, boolean frustumOnly) {
            this.label = label;
            this.gpu = gpu;
            this.frustumOnly = frustumOnly;
        }
    }

    /* Feste Messpose (Blick über Terrain + LOD-Ring, kein Himmel-Only). */
    private static final double POS_X = 40, POS_Y = 96, POS_Z = 40;
    private static final float YAW = 135F, PITCH = 8F;

    private static final String WELT = System.getProperty("skyengine.cullbench");

    private static GameContainer letzterContainer;
    private static boolean weltAngefordert;
    private static int letzteLodRegionen = -1;
    private static long lodStabilSeit;
    private static long eingefrorenSeit;
    private static int index = -1;
    private static long phaseStart;
    private static boolean bildGemacht;

    private CullBench() {
    }

    public static boolean isActive() {
        return WELT != null && !WELT.isBlank();
    }

    /**
     * Einmal pro Sekunde aus dem Statusblock der Game-Loop aufrufen (Render-Thread).
     * Übernimmt Welt-Eintritt, Einfrieren und das Durchschalten der Konfigurationen.
     */
    public static void tick(GameContainer game) {
        if (!isActive()) return;
        letzterContainer = game;

        if (game.getWorld() == null) {
            if (!weltAngefordert) {
                weltAngefordert = true;
                WorldSaves.list().stream()
                        .filter(s -> s.dirName().equalsIgnoreCase(WELT))
                        .findFirst()
                        .ifPresentOrElse(
                                save -> {
                                    System.out.println("[CullBench] Lade Welt '" + save.dirName() + "'");
                                    game.enterWorld(save);
                                },
                                () -> System.out.println("[CullBench] Welt '" + WELT
                                        + "' nicht gefunden — Messstand bleibt untätig"));
            }
            return;
        }

        var chunkManager = game.getWorld().getChunkManager();
        if (!chunkManager.isInitialLoadComplete()) return;

        long jetzt = System.currentTimeMillis() / 1000;

        /* Chunks fertig heißt NICHT LOD fertig: der Ring baut sich danach noch über Sekunden auf
           (erster Lauf maß versehentlich 375 statt ~3400 Regionen). Erst einfrieren, wenn die
           Regionszahl steht — sonst misst man eine halb aufgebaute Szene. */
        if (eingefrorenSeit == 0) {
            int regionen = game.getWorld().getChunkRenderer().getLodRegionCount();
            if (regionen != letzteLodRegionen) {
                letzteLodRegionen = regionen;
                lodStabilSeit = jetzt;
                return;
            }
            if (jetzt - lodStabilSeit < LOD_STABIL_SEKUNDEN) return;

            eingefrorenSeit = jetzt;
            chunkManager.setLoadingPaused(true);
            /* FESTE POSE. Ohne sie sind Läufe nicht vergleichbar: der Spieler fällt beim Laden,
               der Autosave schreibt die Landeposition zurück, und der nächste Lauf startet
               woanders — gemessen wurden so 795 gegen 909 FPS im selben CPU-Pfad, allein durch
               die Blickrichtung. Sichtbarer Beweis waren unterschiedliche Draw-Zahlen. */
            posiereSpieler(game);
            System.out.println("[CullBench] Laden fertig (" + regionen
                    + " LOD-Regionen stabil), Chunk-Loading eingefroren, Pose fixiert — "
                    + SETTLE_SECONDS + " s Beruhigung");
            return;
        }
        if (jetzt - eingefrorenSeit < SETTLE_SECONDS) return;

        if (index < 0) {
            starteKonfiguration(0, jetzt);
            return;
        }
        /* Mitten in jeder Phase einen Screenshot: identische Pose, verschiedene Cull-Pfade —
           ein Pixelvergleich deckt fehlende Geometrie (falsche Verdikte) sofort auf. */
        if (!bildGemacht && jetzt - phaseStart >= PHASE_SECONDS / 2) {
            bildGemacht = true;
            letzterContainer.requestScreenshot();
            System.out.println("[CullBench] Screenshot: " + Konfiguration.values()[index].label);
        }
        if (jetzt - phaseStart >= PHASE_SECONDS) {
            int naechster = index + 1;
            if (naechster >= Konfiguration.values().length) {
                System.out.println("[CullBench] Durchlauf beendet — beginnt erneut (Gegenprobe)");
                naechster = 0;
            }
            starteKonfiguration(naechster, jetzt);
        }
    }

    /**
     * Feste Pose: Flugmodus (sonst fällt der Spieler während der Messung und die Szene wandert),
     * feste Koordinaten und Blickrichtung. Wird bei jedem Konfigurationswechsel erneut gesetzt,
     * damit auch minimale Restbewegung die Vergleichbarkeit nicht bricht.
     */
    private static void posiereSpieler(GameContainer game) {
        var spieler = game.getPlayer();
        if (spieler == null) return;
        spieler.setFlying(true);
        spieler.x = POS_X;
        spieler.y = POS_Y;
        spieler.z = POS_Z;
        spieler.yaw = YAW;
        spieler.pitch = PITCH;
        spieler.motionX = 0;
        spieler.motionY = 0;
        spieler.motionZ = 0;
    }

    private static void starteKonfiguration(int neu, long jetzt) {
        index = neu;
        phaseStart = jetzt;
        bildGemacht = false;
        posiereSpieler(letzterContainer);
        Konfiguration k = Konfiguration.values()[neu];
        GpuCull.FRUSTUM_ONLY = k.frustumOnly;
        GpuCull.ENABLED = k.gpu;
        System.out.println("[CullBench] ===== " + k.label + " =====");
    }
}
