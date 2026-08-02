package de.skyengine.game;

import de.skyengine.core.EngineConfig;
import de.skyengine.core.SkyEngine;
import de.skyengine.core.file.Files;
import de.skyengine.core.input.Input;
import de.skyengine.core.io.*;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.entity.ItemEntity;
import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.BlockRaycast;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.ChestBlockEntity;
import de.skyengine.game.world.block.entity.SimpleItemStorage;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.ChestType;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.state.SlabType;
import de.skyengine.game.world.item.BlockItem;
import de.skyengine.game.world.item.BucketItem;
import de.skyengine.game.world.item.FoodItem;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;
import de.skyengine.game.world.item.ToolItem;
import de.skyengine.graphics.world.ChunkRenderer;
import de.skyengine.graphics.world.CrackRenderer;
import de.skyengine.core.i18n.I18n;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.core.settings.KeyBindings;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.FluidGeometry;
import de.skyengine.graphics.DebugFlags;
import de.skyengine.graphics.FrameProfiler;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.audio.SoundCategory;
import de.skyengine.audio.SoundManager;
import de.skyengine.graphics.blockentity.BlockEntityRenderDispatcher;
import de.skyengine.graphics.blockentity.ChestRenderer;
import de.skyengine.graphics.blockentity.EnchantingTableRenderer;
import de.skyengine.graphics.gui.BootProgress;
import de.skyengine.graphics.gui.screens.GuiChest;
import de.skyengine.graphics.gui.screens.GuiCreativeInventory;
import de.skyengine.graphics.gui.screens.GuiInventory;
import de.skyengine.graphics.gui.DebugOverlay;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.SaveToast;
import de.skyengine.graphics.gui.SpriteRenderer;
import de.skyengine.graphics.gui.screens.GuiIngameMenu;
import de.skyengine.graphics.gui.screens.GuiDeathScreen;
import de.skyengine.graphics.gui.screens.GuiMainMenu;
import de.skyengine.graphics.gui.screens.GuiWorldLoading;
import de.skyengine.game.entity.PlayerAnimationState;
import de.skyengine.graphics.camera.CameraPerspective;
import de.skyengine.graphics.player.FirstPersonHandRenderer;
import de.skyengine.graphics.player.HeldItemMeshes;
import de.skyengine.graphics.player.PlayerRenderer;
import de.skyengine.graphics.texture.BlockTextureAtlas;
import de.skyengine.game.world.block.entity.BlockEntities;
import de.skyengine.game.world.block.entity.DataTag;
import de.skyengine.game.world.generator.generators.AlphaWorldGeneratorV2;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.game.world.save.PlayerIO;
import de.skyengine.game.world.save.WorldSaves;
import org.lwjgl.opengl.GL11;
import de.skyengine.graphics.post.PostProcessor;
import de.skyengine.graphics.world.ChunkBorderRenderer;
import de.skyengine.graphics.world.SelectionBoxRenderer;
import de.skyengine.utils.Utils;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3d;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.util.UUID;
import java.util.function.Supplier;

/* Kein IInitializable mehr: der Boot läuft zweistufig über initBoot()/initStaged() (Ladebildschirm). */
public class GameContainer implements IResizeable, IDisposable {

    private final Logger logger = LogManager.getLogger(GameContainer.class.getName());

    private Camera camera;
    private EntityPlayer player;
    private World world;
    private SelectionBoxRenderer selectionBoxRenderer;
    private ChunkBorderRenderer chunkBorderRenderer;
    private CrackRenderer crackRenderer;
    private GuiManager guiManager;

    /* Welt-unabhängige Engine-Ressourcen (Boot-Init, leben bis zum Exit): Welt-Ein-/Austritte
       erzeugen sie nicht neu — Layer-Indizes des Atlas stecken in den gebackenen Modellen. */
    private final BlockTextureAtlas atlas = new BlockTextureAtlas();
    private final BlockEntityRenderDispatcher blockEntityRenderers = new BlockEntityRenderDispatcher();

    private final GameSettings settings = GameSettings.get();

    /* TAA-Jitter des Frames (wiederverwendet, s. renderWorld) */
    private final Vector2f taaJitter = new Vector2f();
    /* Zuletzt angewandter Textur-LOD-Bias (TAA aktiv → taaMipBias, sonst 0) */
    private float appliedMipBias;

    private static final double REACH = 6.0;

    /** Reichweite (Blöcke), in der der Spieler gedroppte Items aufsammelt. */
    private static final double PICKUP_RANGE = 1.4;

    /* Interaktions-Takte 1:1 aus Minecraft (Minecraft/MultiPlayerGameMode). Alles zählt TICKS:
       die Eingabe wird wie dort im Tick verarbeitet, damit Takt und Arm-Schwung (ebenfalls
       tickbasiert) exakt zusammenpassen. */
    private static final int RIGHT_CLICK_DELAY = 4;   // Minecraft.startUseItem
    private static final int DESTROY_DELAY = 5;       // MultiPlayerGameMode: nach jedem Blockbruch
    private static final int MISS_TIME = 10;          // Minecraft.startAttack: Schlag ins Leere
    private int rightClickDelay = 0;
    private int destroyDelay = 0;
    private int missTime = 0;

    /* Gepufferte Klick-Flanken (MCs KeyMapping.consumeClick): der Frame sammelt, der Tick leert.
       isBindPressed gilt nur einen Frame — bei 20 TPS würde der Tick Klicks sonst verschlucken. */
    private int attackClicks = 0;
    private int useClicks = 0;
    private int pickClicks = 0;
    private int dropClicks = 0;

    /* Abbau am aktuellen Ziel-Block (MultiPlayerGameMode: destroyProgress/destroyTicks, pro Tick).
       Zielwechsel oder Loslassen setzt zurück; Creative bricht instant. */
    private int miningX = Integer.MIN_VALUE, miningY, miningZ;
    private float miningProgress = 0F;
    private float destroyTicks = 0F;
    private boolean isDestroying = false;

    /* Doppel-Leertaste schaltet das Fliegen um (wie Minecraft): zweiter Tipp binnen 300ms. */
    private static final long DOUBLE_TAP_MS = 300;
    private long lastSpacePressTime = 0;

    /* F3+X-Kombi während des Haltens benutzt → unterdrückt den Overlay-Toggle beim Loslassen. */
    private boolean f3ComboUsed = false;

    /* F3-Debug-Overlay (FPS/Position/Biome/...), Toggle in handleGlobalHotkeys. */
    private final DebugOverlay debugOverlay = new DebugOverlay();

    /* Audio: Effekt-Sounds + Musik (OpenAL, komplett auf dem Render-Thread). */
    private final SoundManager soundManager = new SoundManager();

    /* Spielermodell (Skin): Inventar-Vorschau + Third-Person. Welt-unabhängig (Engine-Lebensdauer). */
    private final PlayerRenderer playerRenderer = new PlayerRenderer();
    private final HeldItemMeshes heldItemMeshes = new HeldItemMeshes();
    private final FirstPersonHandRenderer handRenderer = new FirstPersonHandRenderer();
    /* Bob-/Hurt-Effektmatrix des Frames (View-Vorsatz für Kamera UND First-Person-Hand). */
    private final Matrix4f viewEffect = new Matrix4f();
    /* Animations-Zustand (Limb-Swing/Arm-Schwung/Hurt/Bob) + Kamera-Perspektive (F5-Zyklus). */
    private final PlayerAnimationState animState = new PlayerAnimationState();
    private CameraPerspective perspective = CameraPerspective.FIRST_PERSON;
    private static final double THIRD_PERSON_DISTANCE = 4.0;
    /* Augenpunkt/-richtung des Frames: Interaktions-/Mining-Strahl zielt auch in Third-Person
       vom Auge, nie von der versetzten Kamera. */
    private final Vector3d eyePosition = new Vector3d();
    private final Vector3d eyeDirection = new Vector3d();
    private final Vector3d camRayDirection = new Vector3d();
    /* Laufgeräusche: zurückgelegte Distanz seit dem letzten Schritt (MC-Kadenz ~1.6 Blöcke). */
    private static final double STEP_INTERVAL = 1.6;
    private double stepDistance = 0;

    /* Wird per F2 gesetzt und von SkyEngine nach dem fertigen Frame abgeholt. */
    private boolean screenshotRequested = false;

    /* Pausenzustand (Pausenmenü). partialTick friert beim Pausieren auf seinem letzten Wert ein
       — siehe updatePaused. */
    private boolean paused = false;
    private float pausedPartialTick = 0F;

    private BlockRaycast.Hit hit = null;

    /* Spieler-Inventar (36 Slots: 0..8 Hotbar, 9..35 Hauptinventar). Auswahl per Zahlentasten 1..9. */
    private final SimpleItemStorage playerInventory = new SimpleItemStorage(36);
    private int hotbarIndex = 0;
    /* Spieler-UUID (player.dat, Multiplayer-Vorbereitung): beim Betreten geladen oder neu erzeugt. */
    private UUID playerUuid;
    /* Slot-Wechsel-Zeitpunkt für die Itemnamen-Einblendung über der Hotbar (reine Anzeige). */
    private static final long ITEM_NAME_HOLD_MS = 2000, ITEM_NAME_FADE_MS = 500;
    private long itemNameShownAt = 0;
    /* Ess-Fortschritt in Ticks (Rechtsklick halten auf ein FoodItem, MC: 32 Ticks = 1,6 s).
       Public: auch Zeitbasis der Ess-Animation (FirstPersonHandRenderer). */
    public static final int EAT_TICKS = 32;
    private int eatingTicks = 0;

    /* Aktives Savegame (null im Hauptmenü) — Ziel für saveCurrentWorld beim Austritt/Beenden. */
    private WorldSaves.WorldSave currentSave;

    /** Autosave-Intervall in Ticks (60 s bei 20 TPS). */
    private static final int AUTOSAVE_INTERVAL = 1200;
    /* „Spiel gespeichert" erscheint erst, wenn der IO-Thread fertig ist — bis dahin steht das
       Flag. Gesetzt nur von den beiden sichtbaren Auslösern (Pausenmenü, Autosave), NICHT vom
       Exit-Save: dort verschwindet die Welt ohnehin. */
    private final SaveToast saveToast = new SaveToast();
    private boolean notifyOnSaveDone = false;

    public GameContainer() {
        /* Sprache VOR dem ersten Screen/Boot-Frame laden — alle GUI-Texte laufen über I18n. */
        I18n.load(this.settings.language);
        this.camera = new Camera();
        /* world/player sind lazy: sie entstehen erst beim Welt-Eintritt (enterWorld) und
           sterben bei der Rückkehr ins Hauptmenü (exitToTitle). */
        this.selectionBoxRenderer = new SelectionBoxRenderer();
        this.chunkBorderRenderer = new ChunkBorderRenderer();
        this.crackRenderer = new CrackRenderer();
    }

    /**
     * Früher Boot-Anteil (VOR dem Anzeigen des Fensters): nur GuiManager mit Sprite-/Font-
     * Renderer, damit der Boot-Ladebildschirm zeichnen kann.
     */
    public void initBoot() {
        this.guiManager = new GuiManager(SkyEngine.get().getInput(), this.soundManager);
        this.guiManager.initEarly();
    }

    /**
     * Gestaffelter Boot (NACH dem Anzeigen des Fensters): lädt die Engine-Ressourcen in
     * Etappen und zeichnet dazwischen je einen Fortschritts-Frame.
     */
    public void initStaged(BootProgress progress) {
        progress.frame(I18n.tr("boot.blocks"), 0.05f);
        Blocks.bootstrap(new File(Files.RESOURCES_PATH, "game/blocks"));

        progress.frame(I18n.tr("boot.textures"), 0.45f);
        this.atlas.init();

        progress.frame(I18n.tr("boot.renderer"), 0.65f);
        this.camera.setInverseDepth(SkyEngine.get().getWindow().getProperties().isUseInverseDepth());
        this.selectionBoxRenderer.init();
        this.chunkBorderRenderer.init();
        this.crackRenderer.init(this.atlas.textures());
        this.blockEntityRenderers.register(BlockEntities.CHEST, new ChestRenderer());
        this.blockEntityRenderers.register(BlockEntities.ENCHANTING_TABLE, new EnchantingTableRenderer());
        this.blockEntityRenderers.register(BlockEntities.PISTON_MOVING,
                new de.skyengine.graphics.blockentity.PistonMovingRenderer(this.atlas.textures()));
        this.blockEntityRenderers.init();
        this.playerRenderer.init();
        this.heldItemMeshes.init(this.atlas.textures(), this.blockEntityRenderers);
        this.guiManager.initLate(this.atlas.textures(), this.blockEntityRenderers);

        progress.frame(I18n.tr("boot.sound"), 0.85f);
        this.soundManager.init();
        this.applySettings();
        this.soundManager.playMusic("music/minecraft.ogg", true);

        progress.frame(I18n.tr("boot.done"), 1f);
        /* Start im Hauptmenü — Cursor sichtbar (syncCursor), Welt kommt über den Menü-Flow. */
        this.guiManager.open(new GuiMainMenu());
    }

    /**
     * Betritt eine Welt aus einem Savegame (Render-Thread, aus einem GuiScreen-Callback): baut
     * World + Spieler auf (Position/Inventar aus level.json, sonst Spawn + Start-Inventar),
     * wendet die Welt-Einstellungen an und zeigt den Welt-Ladebildschirm.
     */
    public void enterWorld(WorldSaves.WorldSave save) {
        this.currentSave = save;
        this.world = new World(save.dirName(), save.level(), this.atlas, this.blockEntityRenderers);
        this.world.setSoundManager(this.soundManager); // Sounds aus der Welt-Logik (z.B. TNT-Explosion)
        this.world.init();

        this.player = new EntityPlayer();
        this.playerUuid = null;
        /* Spielerzustand: player.dat ist die Quelle; Alt-Saves ohne player.dat werden einmalig
           aus den level.json-Feldern migriert (beim nächsten Speichern genullt). */
        DataTag playerTag = PlayerIO.read(new File(WorldSaves.dir(save.dirName()), "player/player.dat"));
        LevelData.PlayerData saved = save.level().player;
        if (playerTag != null) {
            this.player.setPosition(playerTag.getDouble("x", 0.5),
                    playerTag.getDouble("y", 80), playerTag.getDouble("z", 0.5));
            this.player.yaw = (float) playerTag.getDouble("yaw", 0);
            this.player.pitch = (float) playerTag.getDouble("pitch", 0);
            try {
                this.player.setGamemode(Gamemode.valueOf(playerTag.getString("gamemode", "")));
            } catch (Exception ignored) { /* unbekannter Modus -> Default */ }
            this.player.setFlying(playerTag.getBoolean("flying", false));
            /* Fehlende Keys -> Defaults des frischen Spielers (volle Vitals). */
            this.player.setHealth((float) playerTag.getDouble("health", this.player.getHealth()));
            this.player.setFoodLevel(playerTag.getInt("foodLevel", this.player.getFoodLevel()));
            this.player.setSaturation((float) playerTag.getDouble("saturation", this.player.getSaturation()));
            long most = playerTag.getLong("uuidMost", 0);
            long least = playerTag.getLong("uuidLeast", 0);
            if (most != 0 || least != 0) this.playerUuid = new UUID(most, least);
        } else if (saved != null) {
            this.player.setPosition(saved.x, saved.y, saved.z);
            this.player.yaw = saved.yaw;
            this.player.pitch = saved.pitch;
            try {
                this.player.setGamemode(Gamemode.valueOf(saved.gamemode));
            } catch (Exception ignored) { /* unbekannter Modus -> Default */ }
            this.player.setFlying(saved.flying);
            /* Vitals nur übernehmen, wenn vorhanden — alte level.json ohne die Felder liefert
               null (Boxed-Typen), sonst wäre GSON-Default 0 = sofort tot. */
            if (saved.health != null) this.player.setHealth(saved.health);
            if (saved.foodLevel != null) this.player.setFoodLevel(saved.foodLevel);
            if (saved.saturation != null) this.player.setSaturation(saved.saturation);
        } else {
            this.placeAtWorldSpawn(this.player);
        }
        if (this.playerUuid == null) this.playerUuid = UUID.randomUUID();

        this.clearInventory();
        this.hotbarIndex = 0;
        DataTag savedInventory = playerTag != null ? playerTag.getTag("inventory") : null;
        if (savedInventory != null) {
            this.playerInventory.load(savedInventory);
            this.hotbarIndex = Math.clamp(playerTag.getInt("selectedSlot", 0), 0, 8);
        } else if (!save.level().inventory.isEmpty()) {
            this.loadInventory(save.level().inventory);
        }
        this.animState.reset();
        this.perspective = CameraPerspective.FIRST_PERSON;

        this.applySettings(); // Welt-Anteile (Render-/Sim-Distanz, farPlane) greifen jetzt

        this.guiManager.open(new GuiWorldLoading());
    }

    /** Setzt den Spieler an den Weltspawn (deterministisch: Terrainhöhe bei 0,0 + 2). */
    private void placeAtWorldSpawn(EntityPlayer player) {
        int spawnY = this.world.getGenerator().sampleHeight(0, 0) + 2;
        player.setPosition(0.5, spawnY, 0.5);
    }

    /**
     * Respawn nach dem Tod (Todesscreen-Button): zurück an den Weltspawn mit vollen Vitals;
     * das Inventar bleibt unangetastet (kein Item-Drop, User-Entscheid).
     */
    public void respawnPlayer() {
        if (this.world == null || this.player == null) return;
        this.guiManager.close();
        this.animState.reset();
        this.placeAtWorldSpawn(this.player);
        this.player.motionX = 0;
        this.player.motionY = 0;
        this.player.motionZ = 0;
        this.player.resetVitals();
        this.player.snapPrevToCurrent();
    }

    /**
     * Verlässt die Welt zurück ins Hauptmenü (Render-Thread): erst den GuiScreen schließen
     * (getragene Stapel landen im Inventar), dann speichern, dann die Welt abbauen.
     * glFinish stellt sicher, dass kein In-Flight-Draw mehr auf den GL-Ressourcen der Welt
     * liegt, bevor sie sterben (ein einmaliger Stall beim Menü-Wechsel ist unkritisch).
     */
    public void exitToTitle() {
        this.guiManager.close();
        this.saveCurrentWorld(true);
        GL11.glFinish();
        this.world.dispose();
        this.world = null;
        this.player = null;
        this.currentSave = null;
        this.hit = null;
        this.notifyOnSaveDone = false; // sonst quittiert die nächste Welt einen fremden Save
        this.resetMining();
        this.guiManager.open(new GuiMainMenu());
    }

    /**
     * Speichert die Welt: level.json (nur Welt-Metadaten), player/player.dat
     * (Zustand + Inventar, binäres DataTag) und reiht alle modifizierten Chunks ein
     * (den Flush garantiert storage.close() in world.dispose()).
     *
     * <p>{@code materializeFalling} nur beim Welt-Austritt: der periodische Autosave darf
     * fallende Blöcke nicht materialisieren, sie würden sichtbar in der Luft einrasten.
     *
     * @return Anzahl der eingereihten Chunks (0 = nichts zu schreiben oder keine Welt)
     */
    private int saveCurrentWorld(boolean materializeFalling) {
        if (this.currentSave == null || this.world == null || this.player == null) return 0;

        LevelData level = this.currentSave.level();
        level.lastPlayed = System.currentTimeMillis();
        /* Save-Layout-Defaults für Alt-Welten nachziehen; die alten Spieler-Felder werden
           genullt — die Migration nach player.dat ist damit abgeschlossen. */
        if (level.formatVersion == null) level.formatVersion = 1;
        if (level.worldType == null) level.worldType = "default";
        if (level.generator == null) level.generator = "alpha_v2";
        if (level.generatorVersion == null) level.generatorVersion = AlphaWorldGeneratorV2.VERSION;
        level.player = null;
        level.inventory.clear();
        WorldSaves.save(this.currentSave);

        DataTag tag = new DataTag();
        tag.putLong("uuidMost", this.playerUuid.getMostSignificantBits());
        tag.putLong("uuidLeast", this.playerUuid.getLeastSignificantBits());
        tag.putDouble("x", this.player.x);
        tag.putDouble("y", this.player.y);
        tag.putDouble("z", this.player.z);
        tag.putDouble("yaw", this.player.yaw);
        tag.putDouble("pitch", this.player.pitch);
        tag.putString("gamemode", this.player.getGamemode().name());
        tag.putBoolean("flying", this.player.isFlying());
        tag.putDouble("health", this.player.getHealth());
        tag.putInt("foodLevel", this.player.getFoodLevel());
        tag.putDouble("saturation", this.player.getSaturation());
        tag.putInt("selectedSlot", this.hotbarIndex);
        DataTag inventory = new DataTag();
        this.playerInventory.save(inventory);
        tag.putTag("inventory", inventory);
        PlayerIO.write(new File(WorldSaves.dir(this.currentSave.dirName()), "player/player.dat"), tag);

        int chunks = this.world.saveModifiedChunks(materializeFalling);
        this.logger.info("Welt gespeichert: " + this.currentSave.dirName() + " (" + chunks + " Chunks)");
        return chunks;
    }

    /** Stellt das Inventar aus level.json wieder her (unbekannte Item-IDs -> Slot bleibt leer). */
    private void loadInventory(java.util.List<LevelData.ItemEntry> entries) {
        for (LevelData.ItemEntry entry : entries) {
            if (entry.slot < 0 || entry.slot >= this.playerInventory.size()) continue;
            Item item = Items.get(Identifier.of(entry.id));
            if (item == null) continue;
            ItemStack stack = new ItemStack(item, entry.count);
            stack.setDamage(entry.damage);
            this.playerInventory.set(entry.slot, stack);
        }
    }

    /** Übernimmt die persistenten Einstellungen in die laufenden Systeme (auch vom Optionsmenü genutzt). */
    public void applySettings() {
        /* Welt-Anteile nur mit Welt (Optionen sind auch aus dem Hauptmenü erreichbar);
           beim nächsten enterWorld laufen sie ohnehin erneut. */
        if (this.world != null) {
            this.world.getChunkManager().setRenderDistance(this.settings.renderDistance);
            this.world.setSimulationDistance(this.settings.simulationDistance);
        }
        this.camera.setFov(this.settings.fov);
        this.camera.setFarPlane(this.computeFarPlane());
        this.guiManager.setScale(this.settings.guiScaleLevel);
        /* Über das Window setzen, damit dessen Zustand (config.isVSync) authoritativ bleibt -
           der FPS-Limiter im gameLoop liest window.isVSync(). Läuft auf dem Render-Thread,
           wo der GL-Kontext aktiv ist (glfwSwapInterval gehört dorthin, nicht auf den Main-Thread). */
        SkyEngine.get().getWindow().setVsync(this.settings.vsync);
        this.applyAudioSettings();
    }

    /** Nur die Lautstärken übernehmen (Options-Slider, live beim Ziehen). */
    public void applyAudioSettings() {
        this.soundManager.setMasterVolume(this.settings.masterVolume / 100F);
        for (SoundCategory category : SoundCategory.values()) {
            this.soundManager.setCategoryVolume(category, this.settings.soundVolume(category) / 100F);
        }
    }

    public void update(Input input) {
        if (this.world == null || this.player == null) return; // Hauptmenü: nichts zu ticken

        /* Gespeichert-Meldung erst, wenn der IO-Thread durch ist. Bewusst VOR dem Pause-Zweig:
           das Speichern beim Öffnen des Pausenmenüs läuft ja gerade dann fertig. */
        if (this.notifyOnSaveDone && !this.world.hasPendingSaves()) {
            this.notifyOnSaveDone = false;
            this.saveToast.show();
        }

        /* Der Spieler tickt nicht, wenn das Spiel pausiert. Ausnahme Ladebildschirm: der pausiert
           NICHT (Chunks laden über world.update), aber der Spieler darf nicht in die ungeladene
           Welt fallen (ungeladene Chunks kollidieren als Luft). */
        boolean loading = this.guiManager.current() instanceof GuiWorldLoading;
        if (this.guiManager.pausesGame() || loading) {
            /* prev=current NUR für den Ladebildschirm: dort läuft partialTick weiter, ohne den
               Snap würde Camera.follow zwischen zwei Tick-Positionen oszillieren. In der echten
               Pause erledigt das der eingefrorene partialTick (siehe updatePaused) — und zwar
               besser: der Snap läuft im Tick VOR dem einfrierenden Frame und würde die Kamera
               beim Pausieren noch auf die letzte Tick-Position springen lassen. */
            if (loading) {
                this.player.snapPrevToCurrent();
                this.animState.snapPrev();
            }
            /* Ess-Animation nicht in der Pause weiterwackeln lassen (ihr Kau-Nicken hängt am
               partialTick); das Essen bricht beim Fortsetzen ohnehin ab (Maustaste ist los). */
            this.animState.clearEating();
        } else {
            /* Offenes Container-GUI (Inventar/Truhe): Physik läuft weiter (fallen/Strömung),
               aber ohne Tasten — wie in MC gehen die Eingaben ans GUI. */
            this.player.update(this.guiManager.isOpen() ? Input.EMPTY : input, this.world);

            /* Reihenfolge wie Minecraft.tick: erst die Eingabe (Schwung-/Hand-Trigger), dann die
               Hand-Höhe (ItemInHandRenderer.tick), dann der Entity-Tick mit dem Schwung-Zähler —
               so wirkt ein Klick noch im selben Tick. Sperre runterzählen wie dort; bei offenem
               GUI wird die Schlagsperre hochgesetzt (continueAttack löst sie beim Loslassen). */
            if (this.rightClickDelay > 0) this.rightClickDelay--;
            if (this.guiManager.isOpen()) {
                this.missTime = 10000;
                this.clearInteractionClicks();
            } else {
                this.handleBlockInteraction(input);
                if (this.missTime > 0) this.missTime--;
            }
            this.animState.tickHeldItem(this.playerInventory.get(this.hotbarIndex));
            this.animState.tick(this.player);

            this.updateStepSounds();
            this.updateHurtSounds();
            this.updateEating(input);
            /* Tod (z.B. Fallschaden — auch mit offenem Container-GUI möglich): Todesscreen
               öffnen; open() schließt ein offenes Inventar/eine Truhe sauber über onClose. */
            if (this.player.isDead() && !(this.guiManager.current() instanceof GuiDeathScreen)) {
                this.guiManager.open(new GuiDeathScreen());
            }
        }
        /* Pause-Menü hält die Welt komplett an (wie MC-Singleplayer); Container-GUIs
           (Truhe) lassen sie weiterticken. */
        if (!this.guiManager.pausesGame()) {
            this.world.update(input, this.player);
            this.pickupItems();
            /* Autosave. Die Modulo-Prüfung gehört IN diesen Zweig: gameTime steht bei Pause
               still, außerhalb würde sie dann jeden Tick erneut feuern. */
            if (this.world.getGameTime() % AUTOSAVE_INTERVAL == 0 && this.saveCurrentWorld(false) > 0) {
                this.notifyOnSaveDone = true; // beim Autosave nur melden, wenn es wirklich etwas gab
            }
        }
    }

    /**
     * Sammelt gedroppte Items in Reichweite ins Spielerinventar. Läuft nach dem Welt-Tick; setzt nur
     * das removed-Flag (die Welt räumt die Liste selbst auf) - daher keine Mutation der Liste hier.
     */
    private void pickupItems() {
        double px = this.player.x;
        double py = this.player.y + 0.9; // grob Körpermitte
        double pz = this.player.z;
        this.world.forEachEntityNearby(px, pz, 1, entity -> {
            if (!(entity instanceof ItemEntity item) || item.isRemoved() || item.getPickupDelay() > 0) return;
            double dx = item.x - px;
            double dy = item.y - py;
            double dz = item.z - pz;
            if (dx * dx + dy * dy + dz * dz > PICKUP_RANGE * PICKUP_RANGE) return;

            int before = item.getStack().getCount();
            ItemStack remaining = this.playerInventory.insert(item.getStack());
            if (remaining.isEmpty()) {
                item.remove();
            } else {
                item.getStack().setCount(remaining.getCount());
            }
            /* Nur bei echter Aufnahme klingeln: bei vollem Inventar gibt insert() den ganzen
               Stapel zurück, und der Sound liefe sonst jeden Tick. */
            if (remaining.getCount() < before) this.soundManager.playPickup();
        });
    }

    /**
     * Blockabhängige Laufgeräusche (pro Tick): Distanz-Akkumulator wie in MC — ein Schritt
     * pro {@link #STEP_INTERVAL} zurückgelegten Blöcken (Sprint = automatisch schnellere
     * Kadenz). Kein Sound in der Luft, beim Fliegen, Sneaken (lautlos wie MC) oder im Fluid.
     */
    private void updateStepSounds() {
        if (this.player.isFlying() || this.player.isSneaking()
                || this.player.isTouchingFluid(this.world)) {
            return;
        }
        /* Distanz auch in der Luft akkumulieren (MC: walkDist) — so überschreitet ein
           Sprint-Sprung die Schwelle sofort bei der Landung -> Schritt bei jedem Aufkommen. */
        double dx = this.player.x - this.player.lastX;
        double dz = this.player.z - this.player.lastZ;
        this.stepDistance += Math.sqrt(dx * dx + dz * dz);
        if (!this.player.onGround || this.stepDistance < STEP_INTERVAL) return;
        this.stepDistance = 0;

        int bx = (int) Math.floor(this.player.x);
        int by = (int) Math.floor(this.player.y - 0.2); // Fußpunkt leicht abgesenkt: trifft auch Slabs/Stufen
        int bz = (int) Math.floor(this.player.z);
        int ground = this.world.getBlock(bx, by, bz);
        if (ground == Blocks.AIR || Blocks.getState(ground).isFluid()) {
            ground = this.world.getBlock(bx, by - 1, bz); // Kantenlauf: eine Zelle tiefer probieren
            if (ground == Blocks.AIR || Blocks.getState(ground).isFluid()) return;
        }
        this.soundManager.playStep(Blocks.getState(ground).getBlock().getSoundGroup());
    }

    /** Hurt-/Aufprall-Sounds aus den EntityPlayer-Flanken (der Schaden entsteht tief in der Physik). */
    private void updateHurtSounds() {
        float fall = this.player.consumeFallDamage();
        if (fall > 0) this.soundManager.playFall(fall >= 4); // MC-Grenze: ab 4 Schaden „big"
        if (this.player.consumeHurt()) {
            this.soundManager.playHurt();
            this.animState.hurt(); // Kamera-Roll (Hurt-Tilt)
        }
    }

    /**
     * Pflegt den Pausenzustand und liefert den partialTick, mit dem dieser Frame rendern soll.
     *
     * <p><b>Warum das hier zentral passiert:</b> im Pausenmenü tickt die Welt nicht mehr
     * ({@link #update} überspringt {@code world.update}), der Tick-Akkumulator in
     * {@code SkyEngine} läuft aber normal weiter — {@code partialTick} sägt also unverändert
     * 0→1. Jedes System mit {@code prev}/{@code last}-Feldern interpoliert dann 20× pro Sekunde
     * zwischen zwei eingefrorenen, ungleichen Werten hin und her (sichtbar am zappelnden
     * Truhendeckel). Friert man stattdessen den partialTick ein, stehen ALLE interpolierten
     * Systeme gleichzeitig still — Truhe, Verzauberungstisch, Entities, Spieler, Kamera, Hand,
     * View-Bobbing. Eine neue Animation braucht deshalb <b>keine</b> eigene Pause-Behandlung.
     * Minecraft löst das identisch ({@code Minecraft.pausePartialTick}).
     *
     * <p>Eingefroren wird auf dem Wert des Pausierungs-Frames, nicht auf 1.0 — so gibt es im
     * Moment des Pausierens keinen sichtbaren Sprung. {@code guiManager.pausesGame()} genügt als
     * Bedingung: im Hauptmenü ist es false, der Zustand löst sich beim Verlassen der Welt also
     * von selbst wieder auf.
     */
    private float updatePaused(float partialTick) {
        boolean now = this.guiManager.pausesGame();
        if (now != this.paused) {
            this.paused = now;
            if (now) {
                this.pausedPartialTick = partialTick;
                this.soundManager.pauseAll();
            } else {
                this.soundManager.resumeAll();
            }
        }
        return now ? this.pausedPartialTick : partialTick;
    }

    /**
     * Welt-Anteil des Frames (Input/Kamera/Raycast + Welt, 3D-Overlays, Fluid-Tint) — zeichnet
     * in das gebundene Szene-Target (HDR). Die GUI folgt getrennt in {@link #renderGui} NACH
     * der Post-Kette (SkyEngine.onRender), damit HUD/Text nie durch Grading/AA laufen.
     */
    public void renderWorld(Input input, int width, int height, float rawPartialTick) {
        this.handleGlobalHotkeys(input);   // immer: Fullscreen, GUI-Scale, Render-Distanz

        /* Musik-Streaming läuft auch im Hauptmenü weiter. */
        this.soundManager.update();

        /* Pause: Audio anhalten UND den partialTick einfrieren. Ab hier zählt nur noch dieser
           Wert — er geht an Kamera, Welt, BlockEntities, Entities und Hand. */
        final float partialTick = this.updatePaused(rawPartialTick);

        /* Menü-Blur: nur mit Welt UND blur-wolligem Screen (Pause + Unterseiten);
           der Pass animiert die Stärke selbst (Ein-/Ausblenden). */
        SkyEngine.get().getPostProcessor().setMenuBlur(
                this.world != null && this.guiManager.blursBackground());

        /* Hauptmenü (keine Welt): nur GUI-Eingaben routen, gezeichnet wird in renderGui. */
        if (this.world == null) {
            this.guiManager.handleInput();
            return;
        }

        boolean guiOpen = this.guiManager.isOpen();

        /* Maus-Blick + Hotbar + Gameplay-Debug nur ohne offenes GUI. */
        if (!guiOpen) {
            /* ESC öffnet das Pause-Menü (Beenden geht über dessen Button) und speichert dabei,
               wie in alten Minecraft-Versionen. Der Hook sitzt HIER und nicht in
               GuiIngameMenu.init() — das ist kein Öffnen-Hook, es läuft auch bei jeder Fenster-
               und GuiScale-Änderung erneut (siehe GuiManager.render). */
            if (input.isKeyPressed(GLFW.GLFW_KEY_ESCAPE)) {
                this.saveCurrentWorld(false);
                this.notifyOnSaveDone = true; // bewusste Aktion: Quittung auch ohne dreckige Chunks
                this.guiManager.open(new GuiIngameMenu());
            }
            /* Inventar-Taste (Default E) öffnet das Spielerinventar; dieselbe Taste schließt es
               wieder (closesOnInventoryKey im GuiManager-Routing). Im Creative tritt das
               Creative-Inventar mit seinen Reitern an dessen Stelle (wie in MC). */
            if (input.isBindPressed(this.settings.key(KeyBindings.OPEN_INVENTORY))) {
                Supplier<ItemStack> held = () -> this.playerInventory.get(this.hotbarIndex);
                this.guiManager.open(this.player.getGamemode() == Gamemode.CREATIVE
                        ? new GuiCreativeInventory(this.playerInventory, this.playerRenderer,
                                this.heldItemMeshes, held)
                        : new GuiInventory(this.playerInventory, this.playerRenderer,
                                this.heldItemMeshes, held));
            }
            this.handleGameplayHotkeys(input);
            this.handleHotbarInput(input);
            if (input.isBindPressed(this.settings.key(KeyBindings.TOGGLE_PERSPECTIVE))) {
                this.perspective = this.perspective.next();
            }
            /* Blick nur drehen, wenn der Cursor auch PHYSISCH gefangen ist. Der Moduswechsel läuft
               deferiert auf dem Window-Thread — direkt nach dem Schließen eines GUIs ist er noch
               frei, und seine Bewegung soll die Kamera nicht mitziehen. */
            if (input.isCursorGrabbed()) {
                double sens = this.settings.mouseSensitivity;
                this.player.turn(input.getDeltaMouseX() * sens, input.getDeltaMouseY() * sens);
            }
        }

        /* TAA-Subpixel-Jitter (0,0 wenn TAA aus) VOR dem Matrix-Update, Kameradaten für
           die Reprojektion DANACH in den Post-Context — Reihenfolge ist tragend. */
        PostProcessor post = SkyEngine.get().getPostProcessor();
        post.nextJitter(this.taaJitter, width, height);
        this.camera.setJitter(this.taaJitter.x, this.taaJitter.y);

        /* TAA-Mip-Bias des Block-TextureArrays: nur bei Zustandswechsel setzen
           (aaMode-Wechsel oder taaMipBias-Tuning), kein glTexParameter pro Frame. */
        float wantedBias = post.getSettings().isTemporalAa()
                ? post.getSettings().getTaaMipBias() : 0F;
        if (wantedBias != this.appliedMipBias) {
            this.world.getChunkRenderer().getTextureArray().setLodBias(wantedBias);
            this.appliedMipBias = wantedBias;
            this.logger.debug("Textur-LOD-Bias: " + wantedBias);
        }

        this.camera.follow(this.player, partialTick);
        /* Augenpunkt/-richtung VOR dem Third-Person-Versatz sichern — Interaktion, Mining und
           der Eimer-Strahl zielen immer vom Auge, egal wo die Kamera hängt. */
        this.eyePosition.set(this.camera.getPosition());
        this.camera.getDirection(this.eyeDirection);
        this.applyPerspective();
        this.updateViewEffect(partialTick);
        this.camera.setViewEffect(this.viewEffect);
        this.camera.update((double) width / height);
        post.updateTaaCamera(this.camera);

        /* Audio pro Frame: Listener auf die interpolierte Kamera (Streaming läuft oben). */
        this.soundManager.updateListener(this.camera);

        this.hit = BlockRaycast.raycast(this.world, this.eyePosition, this.eyeDirection, REACH);

        if (guiOpen) {
            this.guiManager.handleInput();       // Schließen + Slot-Klicks (kann den GuiScreen schließen)
            /* Ein GuiScreen-Callback (GuiIngameMenu „Hauptmenü") kann exitToTitle() ausgelöst haben —
               dann ist die Welt weg und der Rest des Frames darf sie nicht mehr anfassen. */
            if (this.world == null) return;
        } else {
            /* Nur Klick-Flanken einsammeln — die Interaktion selbst läuft wie in MC im Tick. */
            this.pollInteractionClicks(input);
        }

        /* Wireframe (F6) gilt NUR für die Welt-Geometrie: der Line-Mode ist globaler GL-State und
           würde sonst auch das Fullscreen-Dreieck der Post-Kette (und die GUI-Quads) zu Linien
           machen — dann bliebe der Default-Framebuffer unbeschrieben ("eingefrorenes" Bild). */
        if (DebugFlags.wireframe) Utils.enableWireframe();
        if (this.perspective.isFirstPerson()) {
            this.world.render(this.camera, partialTick);
        } else {
            /* Licht der Spielerzelle — der Spieler steht IN seinem Block, also die Füße-Zelle. */
            float playerLight = this.lightAt(this.player.x, this.player.y, this.player.z);
            this.world.render(this.camera, partialTick, () ->
                    this.playerRenderer.renderThirdPerson(this.player, this.animState, this.camera, partialTick,
                            this.heldItemMeshes, this.playerInventory.get(this.hotbarIndex), playerLight));
        }
        if (DebugFlags.wireframe) Utils.disableWireframe();

        FrameProfiler.cpuStart(FrameProfiler.Cpu.OVL);

        if (this.hit != null && !this.guiManager.isOpen() && this.player.getGamemode().interactsWithWorld()) {
            this.selectionBoxRenderer.render(this.camera, this.hit.x(), this.hit.y(), this.hit.z(),
                    Blocks.getState(this.hit.block()).getOutlineShape());

            /* Abbau-Risse (Survival): nur solange das Raycast-Ziel dem Mining-Ziel entspricht. */
            if (this.miningProgress > 0F && this.hit.x() == this.miningX
                    && this.hit.y() == this.miningY && this.hit.z() == this.miningZ) {
                int stage = Math.min(9, (int) (this.miningProgress * 10F));
                this.crackRenderer.render(this.camera, this.miningX, this.miningY, this.miningZ,
                        Blocks.getState(this.hit.block()).getOutlineShape(), stage);
            }
        }

        /* Chunk-Grenzen (F3+G) um die nahen Chunks — nach dem Welt-Draw, mit gültiger Kamera. */
        if (DebugFlags.chunkBorders != 0 && this.world != null) {
            int ccx = ((int) Math.floor(this.player.x)) >> ChunkSection.SHIFT;
            int ccz = ((int) Math.floor(this.player.z)) >> ChunkSection.SHIFT;
            this.chunkBorderRenderer.render(this.camera, this.world.getChunkManager(),
                    ccx, ccz, DebugFlags.chunkBorders);
        }

        this.renderFluidOverlay();

        /* First-Person-Hand ins Szene-Target (läuft durch die Post-Kette), eigener Depth-Clear. */
        if (this.perspective.isFirstPerson() && this.player.getGamemode() != Gamemode.SPECTATOR) {
            /* Licht der AUGEN-Zelle (nicht der Füße): die Hand hängt vor dem Gesicht, und in
               einem 1 Block hohen Kriechgang unterscheiden sich beide sichtbar. */
            float handLight = this.lightAt(this.player.x,
                    this.player.y + this.player.getEyeHeight(partialTick), this.player.z);
            this.handRenderer.render(this.playerRenderer, this.heldItemMeshes, this.player,
                    this.animState, (float) width / height, partialTick, this.viewEffect, handLight);
        }

        FrameProfiler.cpuStop(FrameProfiler.Cpu.OVL);
    }

    /** Licht-Faktor an einer Weltposition, Himmel und Block (Kurve wie im Terrain-Shader). */
    private float lightAt(double x, double y, double z) {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        return ChunkRenderer.lightFactor(this.world.getSkyLight(bx, by, bz),
                this.world.getBlockLight(bx, by, bz));
    }

    /**
     * Bob-/Hurt-Effektmatrix des Frames (MC bobHurt + bobView): Hurt-Roll beim Schaden
     * (vereinfacht ohne Angreifer-Richtung) und View-Bobbing beim Laufen. Beides über
     * GameSettings abschaltbar; Bobbing nur First-Person und nicht beim Fliegen. Die Matrix
     * geht identisch an Kamera UND First-Person-Hand (Hand wackelt mit, wie MC).
     */
    private void updateViewEffect(float partialTick) {
        this.viewEffect.identity();
        if (this.settings.damageTilt && this.animState.getHurtTime() > 0) {
            float f = (this.animState.getHurtTime() - partialTick) / 10F;
            float roll = (float) Math.sin(f * f * f * f * Math.PI) * 14F;
            this.viewEffect.rotateZ((float) Math.toRadians(-roll));
        }
        if (this.settings.viewBobbing && this.perspective.isFirstPerson() && !this.player.isFlying()) {
            float f1 = -this.animState.getWalkDistExtrapolated(partialTick);
            float f2 = this.animState.getBob(partialTick);
            float sin = (float) Math.sin(f1 * Math.PI);
            float cos = (float) Math.cos(f1 * Math.PI);
            this.viewEffect
                    .translate(sin * f2 * 0.5F, -Math.abs(cos * f2), 0F)
                    .rotateZ((float) Math.toRadians(sin * f2 * 3F))
                    .rotateX((float) Math.toRadians(Math.abs((float) Math.cos(f1 * Math.PI - 0.2F) * f2) * 5F));
        }
    }

    /**
     * Third-Person: Kamera vom Augenpunkt nach hinten (BACK) bzw. vorn (FRONT) versetzen,
     * per Block-Raycast abgeschnitten (Kamera nie in der Wand). FRONT blickt zum Spieler
     * zurück. Läuft zwischen {@code camera.follow} und {@code camera.update}.
     */
    private void applyPerspective() {
        if (this.perspective.isFirstPerson()) return;
        boolean front = this.perspective == CameraPerspective.THIRD_PERSON_FRONT;
        this.camRayDirection.set(this.eyeDirection);
        if (!front) this.camRayDirection.negate();

        double dist = THIRD_PERSON_DISTANCE;
        BlockRaycast.Hit blocked = BlockRaycast.raycast(this.world, this.eyePosition, this.camRayDirection, dist);
        if (blocked != null) {
            double dx = blocked.hitX() - this.eyePosition.x;
            double dy = blocked.hitY() - this.eyePosition.y;
            double dz = blocked.hitZ() - this.eyePosition.z;
            dist = Math.max(0.3, Math.sqrt(dx * dx + dy * dy + dz * dz) - 0.1);
        }
        /* getPosition() ist die Live-Referenz der Kamera — bewusst in-place versetzen. */
        this.camera.getPosition().fma(dist, this.camRayDirection);
        if (front) {
            this.camera.setRotation(this.player.yaw + 180F, -this.player.pitch);
        }
    }

    /**
     * GUI-Anteil des Frames — zeichnet in den Default-Framebuffer, NACH der Post-Kette
     * (pixelgenau, kein Grading/AA). Zentrale GUI-Verwaltung: HUD (kein GuiScreen) bzw.
     * GuiScreen-Overlay + Cursor-Sync.
     */
    public void renderGui(int width, int height) {
        /* Spectator zeigt wie MC keine Hotbar (Crosshair bleibt); ohne Spieler/Welt gibt es keine. */
        boolean showHotbar = this.player != null && this.player.getGamemode() != Gamemode.SPECTATOR;
        FrameProfiler.cpuStart(FrameProfiler.Cpu.GUI);
        /* Im Hauptmenü kein HUD (Inventar null -> GuiManager überspringt Hotbar/Crosshair);
           Crosshair nur in First Person (in den F5-Ansichten zielt man nicht über die Bildmitte). */
        this.guiManager.render(width, height, this.world != null ? this.playerInventory : null,
                this.hotbarIndex, showHotbar, this.perspective.isFirstPerson(), this.itemNameAlpha(), this.player);
        if (this.debugOverlay.isVisible() && this.world != null) {
            this.debugOverlay.render(this.guiManager, this.world, this.player);
        }
        /* Gespeichert-Meldung NACH dem GuiScreen: sie soll auch über dem abgedunkelten
           Pausenmenü lesbar sein (im Hud läge sie unter dessen Dim). */
        if (this.world != null) this.saveToast.render(this.guiManager);
        FrameProfiler.cpuStop(FrameProfiler.Cpu.GUI);
    }

    /**
     * Essen (tick-basiert, 20 TPS): Rechtsklick auf ein FoodItem halten füllt nach
     * {@link #EAT_TICKS} Ticks Hunger + Sättigung und verbraucht ein Item. Nur im SURVIVAL,
     * nur bei nicht vollem Hungerbalken, ohne offenes GUI; Loslassen oder Slot-Wechsel
     * (handleHotbarInput) setzt den Fortschritt zurück. FoodItems sind keine BlockItems —
     * der Platzierungs-Pfad in handleBlockInteraction ignoriert sie ohnehin.
     */
    private void updateEating(Input input) {
        ItemStack held = this.playerInventory.get(this.hotbarIndex);
        if (this.guiManager.isOpen()
                || this.player.getGamemode() != Gamemode.SURVIVAL
                || this.player.isDead()
                || !(held.getItem() instanceof FoodItem food)
                || this.player.getFoodLevel() >= EntityPlayer.MAX_FOOD
                || !input.isBindDown(this.settings.key(KeyBindings.USE))) {
            this.eatingTicks = 0;
            this.animState.clearEating();
            return;
        }
        if (++this.eatingTicks >= EAT_TICKS) {
            this.eatingTicks = 0;
            this.animState.clearEating();
            this.player.eat(food.getNutrition(), food.getSaturation());
            this.soundManager.playBurp();
            held.setCount(held.getCount() - 1);
            if (held.getCount() <= 0) {
                this.playerInventory.set(this.hotbarIndex, ItemStack.EMPTY);
            }
        } else {
            this.animState.setEating(EAT_TICKS - this.eatingTicks); // zählt runter wie MC
            /* Kau-Sounds erst ab 7 verstrichenen Ticks (Vanilla: remaining <= duration-7) —
               dann ist das Item in der FP-Animation am Gesicht angekommen; danach 4er-Takt. */
            if (this.eatingTicks > 7 && this.eatingTicks % 4 == 0) {
                this.soundManager.playEat();
            }
        }
    }

    /** Einblend-Alpha des Hotbar-Itemnamens: 2 s voll, dann 0,5 s linear ausblenden. */
    private float itemNameAlpha() {
        long since = System.currentTimeMillis() - this.itemNameShownAt;
        if (this.itemNameShownAt == 0 || since >= ITEM_NAME_HOLD_MS + ITEM_NAME_FADE_MS) return 0f;
        if (since <= ITEM_NAME_HOLD_MS) return 1f;
        return (ITEM_NAME_HOLD_MS + ITEM_NAME_FADE_MS - since) / (float) ITEM_NAME_FADE_MS;
    }

    /**
     * Fullscreen-Tint, wenn das Kamera-Auge in einem Fluid steckt (wie Minecraft):
     * Wasser -> blau/leicht, Lava -> orange/dicht. Gezeichnet zwischen Welt und HUD.
     */
    private void renderFluidOverlay() {
        Vector3d eye = this.camera.getPosition();
        int bx = (int) Math.floor(eye.x);
        int by = (int) Math.floor(eye.y);
        int bz = (int) Math.floor(eye.z);
        BlockState state = Blocks.getState(this.world.getBlock(bx, by, bz));
        if (!state.isFluid()) return;

        /* Zelle zählt als voll, wenn darüber dasselbe Fluid steht (wie FluidGeometry),
           sonst gilt die sichtbare Oberkante aus LEVEL/FALLING. */
        float height = 1.0f;
        BlockState above = Blocks.getState(this.world.getBlock(bx, by + 1, bz));
        if (!(above.isFluid() && above.getBlock() == state.getBlock())) {
            height = FluidGeometry.fluidHeight(state);
        }
        if (eye.y - by >= height) return;

        SpriteRenderer sr = this.guiManager.sprites();
        sr.begin(1, 1); // Ortho 0..1 -> Fullscreen-Rect unabhängig vom GUI-Scale
        if (state.getBlock().getFluidInfo().lava) {
            sr.drawRect(0, 0, 1, 1, 0.6f, 0.1f, 0.0f, 0.8f);    // Lava: dicht, orange-rot
        } else {
            sr.drawRect(0, 0, 1, 1, 0.25f, 0.46f, 0.9f, 0.35f); // Wasser (an 0x4076E6 angelehnt)
        }
        sr.end();
    }

    @Override
    public void resize(int width, int height) {

    }

    @Override
    public void dispose() {
        this.settings.save();
        this.saveCurrentWorld(true); // Welt-Zustand auch beim direkten Beenden aus dem Spiel sichern
        if (this.world != null) {
            this.world.dispose();
        }
        this.selectionBoxRenderer.dispose();
        if (this.chunkBorderRenderer != null) this.chunkBorderRenderer.dispose();
        if (this.crackRenderer != null) this.crackRenderer.dispose();
        this.playerRenderer.dispose();
        this.heldItemMeshes.dispose();
        if (this.guiManager != null) this.guiManager.dispose();
        this.blockEntityRenderers.dispose();
        this.atlas.dispose();
        this.soundManager.dispose();
    }

    /**
     * Abbau-Fortschritt pro Tick am anvisierten Block (Vorbild
     * {@code MultiPlayerGameMode.getDestroyProgress}/{@code Block.getDestroyProgress}):
     * {@code speed / hardness / (harvestbar ? 30 : 100)}, in der Luft nochmal ein Fünftel.
     * Härte &lt; 0 (Bedrock) liefert 0 — der Block wird nie fertig.
     */
    private float getDestroyProgress(BlockState state) {
        float hardness = state.getBlock().getHardness();
        if (hardness < 0F) return 0F;
        if (hardness == 0F) return 1F;

        ItemStack held = this.playerInventory.get(this.hotbarIndex);
        float speed = 1F;
        if (held.getItem() instanceof ToolItem tool && tool.getType() == state.getBlock().getToolType()) {
            speed = tool.getTier().speed();
        }
        float perTick = speed / hardness / (isHarvestable(state, held) ? 30F : 100F);
        /* MC-Malus: Abbau in der Luft (fallend/springend) ist 5x langsamer. */
        if (!this.player.onGround) perTick /= 5F;
        return perTick;
    }

    /**
     * Beginnt den Abbau am anvisierten Block (Vorbild {@code MultiPlayerGameMode.startDestroyBlock}).
     * Creative zerstört sofort und sperrt {@link #DESTROY_DELAY} Ticks; Survival setzt den
     * Fortschritt neu an bzw. bricht sofort, wenn ein Tick schon reicht (Pflanzen, TNT).
     */
    private void startDestroyBlock() {
        BlockState state = Blocks.getState(this.hit.block());
        if (this.player.getGamemode().isInstantBreak()) {
            this.breakTargetBlock(state, false);
            this.destroyDelay = DESTROY_DELAY;
            return;
        }
        if (!this.isDestroying || !this.sameDestroyTarget()) {
            if (this.getDestroyProgress(state) >= 1F) {
                this.breakTargetBlock(state, false);
            } else {
                this.isDestroying = true;
                this.miningX = this.hit.x();
                this.miningY = this.hit.y();
                this.miningZ = this.hit.z();
                this.miningProgress = 0F;
                this.destroyTicks = 0F;
            }
        }
    }

    /**
     * Ein Abbau-Tick (Vorbild {@code MultiPlayerGameMode.continueDestroyBlock}). Der Rückgabewert
     * steuert den Arm-Schwung: {@code true} heißt „es wird gehackt" — auch während der Sperre nach
     * einem Blockbruch, damit der Arm wie in MC durchschwingt.
     */
    private boolean continueDestroyBlock() {
        if (this.destroyDelay > 0) {
            this.destroyDelay--;
            return true;
        }
        if (this.player.getGamemode().isInstantBreak()) {
            this.destroyDelay = DESTROY_DELAY;
            this.breakTargetBlock(Blocks.getState(this.hit.block()), false);
            return true;
        }
        if (!this.sameDestroyTarget()) {
            this.startDestroyBlock();
            return true;
        }

        BlockState state = Blocks.getState(this.hit.block());
        this.miningProgress += this.getDestroyProgress(state);
        if (this.destroyTicks % 4F == 0F) {
            this.soundManager.playHit(state.getBlock().getSoundGroup(),
                    this.miningX + 0.5, this.miningY + 0.5, this.miningZ + 0.5);
        }
        this.destroyTicks++;

        if (this.miningProgress >= 1F) {
            this.isDestroying = false;
            this.breakTargetBlock(state, true);
            this.miningProgress = 0F;
            this.destroyTicks = 0F;
            this.destroyDelay = DESTROY_DELAY;
        }
        return true;
    }

    /** Abbau abgebrochen (Taste los, kein Block im Visier) — wie MC inkl. Ticker-Reset. */
    private void stopDestroyBlock() {
        if (this.isDestroying) {
            this.isDestroying = false;
            this.miningProgress = 0F;
            this.animState.resetSwapTicker();
        }
    }

    private boolean sameDestroyTarget() {
        return this.hit != null && this.hit.x() == this.miningX
                && this.hit.y() == this.miningY && this.hit.z() == this.miningZ;
    }

    /**
     * Baut den Ziel-Block ({@code this.hit}) ab: onBreak + AIR setzen, Drop nur bei
     * dropsItems UND passendem Tool (MC-Harvest-Regel), optional Tool-Abnutzung.
     */
    private void breakTargetBlock(BlockState broken, boolean applyDurability) {
        this.soundManager.playBreak(broken.getBlock().getSoundGroup(),
                this.hit.x() + 0.5, this.hit.y() + 0.5, this.hit.z() + 0.5);
        broken.getBlock().onBreak(this.world, this.hit.x(), this.hit.y(), this.hit.z(), broken);
        this.world.setBlock(this.hit.x(), this.hit.y(), this.hit.z(), Blocks.AIR);

        ItemStack held = this.playerInventory.get(this.hotbarIndex);
        /* Drops nur im Survival UND nur, wenn Tool-Klasse + Tier passen. */
        if (this.player.getGamemode().dropsItems() && isHarvestable(broken, held)) {
            Item drop = Items.forBlock(broken.getBlock()); // löst auch places_block-Items auf (Staub)
            if (drop != null) {
                this.world.spawnItem(this.hit.x() + 0.5, this.hit.y() + 0.5, this.hit.z() + 0.5, new ItemStack(drop, 1));
            }
        }

        /* Tool-Abnutzung (nur Survival bei Härte > 0): zerbricht bei erreichter Haltbarkeit. */
        if (applyDurability && held.getItem() instanceof ToolItem tool) {
            held.setDamage(held.getDamage() + 1);
            if (held.getDamage() >= tool.getTier().durability()) {
                this.playerInventory.set(this.hotbarIndex, ItemStack.EMPTY);
            }
        }

        this.resetMining();
    }

    /** MC-Harvest-Regel: ohne Tool-Anforderung droppt alles; sonst passende Klasse + Mindest-Tier. */
    private static boolean isHarvestable(BlockState state, ItemStack held) {
        de.skyengine.game.world.item.ToolType required = state.getBlock().getToolType();
        if (required == null) return true;
        if (!(held.getItem() instanceof ToolItem tool) || tool.getType() != required) return false;
        return tool.getTier().level() >= state.getBlock().getHarvestLevel();
    }

    private void resetMining() {
        this.miningProgress = 0F;
        this.miningX = Integer.MIN_VALUE;
        this.destroyTicks = 0F;
        this.isDestroying = false;
    }

    /**
     * Sammelt die Klick-Flanken im Frame ein (Vorbild {@code KeyMapping.consumeClick}): die
     * Interaktion läuft wie in MC im Tick, {@code isBindPressed} gilt aber nur einen Frame —
     * ohne diesen Puffer würde der 20-TPS-Tick Klicks verschlucken.
     */
    private void pollInteractionClicks(Input input) {
        if (input.isBindPressed(this.settings.key(KeyBindings.ATTACK))) this.attackClicks++;
        if (input.isBindPressed(this.settings.key(KeyBindings.USE))) this.useClicks++;
        if (input.isBindPressed(this.settings.key(KeyBindings.PICK_BLOCK))) this.pickClicks++;
        if (input.isBindPressed(this.settings.key(KeyBindings.DROP))) this.dropClicks++;
    }

    /** Verwirft gepufferte Klicks (offenes GUI, Spectator) — sie dürfen nicht nachträglich feuern. */
    private void clearInteractionClicks() {
        this.attackClicks = 0;
        this.useClicks = 0;
        this.pickClicks = 0;
        this.dropClicks = 0;
    }

    /**
     * Block-Interaktion pro Tick, Ablauf verbatim {@code Minecraft.handleKeybinds}: gepufferte
     * Klicks abarbeiten, dann der Halte-Pfad fürs Benutzen, zum Schluss {@link #continueAttack}.
     */
    private void handleBlockInteraction(Input input) {
        /* Spectator kann nicht abbauen/platzieren/nutzen (auch keine Truhe öffnen). */
        if (!this.player.getGamemode().interactsWithWorld()) {
            this.clearInteractionClicks();
            return;
        }
        /* Wegwerfen VOR dem Essen-Zweig: der leert die Klick-Puffer, und MC blockt den Drop
           beim Essen ebenfalls nicht (Minecraft.handleKeybinds, eigene consumeClick-Schleife). */
        while (this.dropClicks > 0) {
            this.dropClicks--;
            this.dropSelectedItem(input.isCtrlDown());
        }

        boolean usingItem = this.animState.isEating(); // MC: player.isUsingItem()

        boolean instantAttack = false;
        if (usingItem) {
            this.clearInteractionClicks();
        } else {
            while (this.attackClicks > 0) {
                this.attackClicks--;
                instantAttack |= this.startAttack();
            }
            while (this.useClicks > 0) {
                this.useClicks--;
                this.startUseItem();
            }
            while (this.pickClicks > 0) {
                this.pickClicks--;
                this.pickBlock();
            }
        }

        if (input.isBindDown(this.settings.key(KeyBindings.USE)) && this.rightClickDelay == 0 && !usingItem) {
            this.startUseItem();
        }

        this.continueAttack(!instantAttack && input.isBindDown(this.settings.key(KeyBindings.ATTACK)));
    }

    /**
     * Wirft aus dem aktiven Hotbar-Slot (Vorbild MC {@code Player.drop}): ein Item, mit STRG den
     * ganzen Stapel. Geschwungen wird nur bei Erfolg — auf einem leeren Slot passiert nichts.
     */
    private void dropSelectedItem(boolean fullStack) {
        ItemStack held = this.playerInventory.get(this.hotbarIndex);
        if (held.isEmpty()) return;
        int amount = fullStack ? held.getCount() : 1;
        this.world.throwItem(this.player, this.playerInventory.extract(this.hotbarIndex, amount));
        this.animState.swing();
    }

    /**
     * Wirft einen Stapel aus einem offenen Container-GUI in die Welt ({@code GuiContainer}):
     * Drop-Taste im GUI, Klick neben das Fenster und das Auswerfen beim Schließen laufen alle
     * hier durch. Geschwungen wird wie beim normalen Drop — sonst fehlt dem Wurf das Feedback.
     */
    public void dropFromGui(ItemStack stack) {
        if (this.world == null || this.player == null) return;
        this.world.throwItem(this.player, stack);
        this.animState.swing();
    }

    /** MC {@code MultiPlayerGameMode.hasMissTime}: im Creative gibt es keine Schlagsperre. */
    private boolean hasMissTime() {
        return this.player.getGamemode() != Gamemode.CREATIVE;
    }

    /**
     * Ein Angriffs-Klick (Vorbild {@code Minecraft.startAttack}). Rückgabe {@code true}, wenn der
     * Block sofort zerbrach — dann unterdrückt der Aufrufer den Dauer-Abbau in diesem Tick.
     * Geschwungen wird IMMER, auch ins Leere.
     */
    private boolean startAttack() {
        if (this.missTime > 0) return false;

        boolean endAttack = false;
        if (this.hit != null) {
            this.startDestroyBlock();
            if (Blocks.getState(this.world.getBlock(this.hit.x(), this.hit.y(), this.hit.z())).isAir()) {
                endAttack = true;
            }
        } else {
            /* MISS: Sperre (außer Creative) + Ticker-Reset, die Hand sinkt dadurch kurz ab. */
            if (this.hasMissTime()) this.missTime = MISS_TIME;
            this.animState.resetSwapTicker();
        }
        this.animState.swing();
        return endAttack;
    }

    /** Dauer-Abbau pro Tick (Vorbild {@code Minecraft.continueAttack}). */
    private void continueAttack(boolean down) {
        if (!down) this.missTime = 0;
        if (this.missTime > 0 || this.animState.isEating()) return;

        if (down && this.hit != null) {
            if (this.continueDestroyBlock()) this.animState.swing();
        } else {
            this.stopDestroyBlock();
        }
    }

    /**
     * Ein Benutzen-Klick (Vorbild {@code Minecraft.startUseItem}): Sperre IMMER neu setzen, bei
     * Erfolg schwingen und die Hand nur dann senken, wenn der Stapel sich verändert hat oder der
     * Spieler unbegrenztes Material hat (Creative) — Tür/Truhe im Survival senken sie also nicht.
     */
    private void startUseItem() {
        if (this.isDestroying) return;
        this.rightClickDelay = RIGHT_CLICK_DELAY;

        ItemStack held = this.playerInventory.get(this.hotbarIndex);
        Item beforeItem = held.getItem();
        int beforeCount = held.getCount();

        if (!this.useItemOn()) return;

        this.animState.swing();
        ItemStack after = this.playerInventory.get(this.hotbarIndex);
        boolean stackChanged = after.getItem() != beforeItem || after.getCount() != beforeCount;
        if (beforeItem != null && (stackChanged || this.player.getGamemode() == Gamemode.CREATIVE)) {
            this.animState.itemUsed();
        }
    }

    /**
     * Die eigentliche Rechtsklick-Aktion auf den anvisierten Block (Vorbild
     * {@code MultiPlayerGameMode.useItemOn}); {@code true} = etwas ist passiert.
     */
    private boolean useItemOn() {
        /* Eimer vor der hit==null-Prüfung: Der LEERE Eimer nutzt einen eigenen fluid-bewussten
           Strahl (Fluids sind im Normal-Raycast unsichtbar) und funktioniert auch ohne this.hit.
           Der gefüllte Eimer platziert wie ein Block über this.hit (siehe handleBucket). */
        ItemStack held = this.playerInventory.get(this.hotbarIndex);
        if (held.getItem() instanceof BucketItem bucket && this.handleBucket(bucket)) return true;

        if (this.hit == null) return false;

        /* Rechtsklick-Interaktion des getroffenen Blocks (z.B. Tür auf/zu) hat Vorrang. */
        BlockState hitState = Blocks.getState(this.hit.block());
        if (hitState.getBlock().onUse(this.world, this.hit.x(), this.hit.y(), this.hit.z(), hitState)) {
            return true;
        }

        /* Truhe: Rechtsklick öffnet das Truhen-GUI (Deckel geht auf). Sneaken mit einem Block in
           der Hand überspringt die Interaktion und platziert stattdessen — wie in MC, und die
           einzige Möglichkeit, eine Truhe an die Seite einer anderen zu setzen. */
        boolean placingWhileSneaking = this.player.isSecondaryUseActive()
                && held.getItem() != null && held.getItem().getPlacedBlock() != null;
        if (!placingWhileSneaking && this.tryOpenChest()) return true;

        /* Ausgewählter Hotbar-Slot muss einen platzierbaren Block enthalten — neben BlockItems
           auch Material-Items mit places_block (Redstone-Staub). */
        if (held.isEmpty()) return false;
        Block block = held.getItem().getPlacedBlock();
        if (block == null) return false;

        /* Slab auf vorhandene gleiche Slab -> Doppel-Slab */
        if (this.tryMergeSlab(block)) return true;

        /* Platzieren: an der getroffenen Seite (Fluids zählen als Luft, this.hit ignoriert sie). */
        int[] t = this.placementTarget();
        if (t == null) return false;
        int px = t[0], py = t[1], pz = t[2];

        double relHitX = this.hit.hitX() - px;
        double relHitY = this.hit.hitY() - py;
        double relHitZ = this.hit.hitZ() - pz;
        BlockState place = block.getPlacementState(this.world, px, py, pz,
                this.hit.faceX(), this.hit.faceY(), this.hit.faceZ(),
                relHitX, relHitY, relHitZ, this.player.yaw, this.player.pitch,
                this.player.isSecondaryUseActive());

        /* place == null: ein Behavior lehnt ab (z.B. Tür ohne Platz). Sonst nicht in den
           eigenen Körper bauen - gegen die ECHTE Kollisionsform testen, damit dünne Blöcke
           (Panes, Zäune) neben einem platzierbar bleiben. */
        if (place == null || this.collidesWithPlayer(place, px, py, pz)
                || this.collidesWithEntities(place, px, py, pz)) {
            return false;
        }
        this.world.placeBlock(px, py, pz, place);
        this.soundManager.playPlace(place.getBlock().getSoundGroup(), px + 0.5, py + 0.5, pz + 0.5);
        /* Survival verbraucht den Block (Creative baut unbegrenzt, wie MC). */
        if (this.player.getGamemode() == Gamemode.SURVIVAL) this.consumeHeld(null);
        return true;
    }

    /**
     * Pick Block (nur Creative): legt den anvisierten Block in den ausgewählten Hotbar-Slot.
     */
    private void pickBlock() {
        if (this.player.getGamemode() != Gamemode.CREATIVE || this.hit == null) return;
        Block picked = Blocks.getState(this.hit.block()).getBlock();
        Item item = Items.forBlock(picked); // BlockItem bzw. places_block-Item (Staub); null bei Air/Fluid
        if (item != null) {
            this.playerInventory.set(this.hotbarIndex, new ItemStack(item, 1));
            this.itemNameShownAt = System.currentTimeMillis(); // Namens-Einblendung wie bei Slot-Wechsel
        }
    }

    /**
     * Gemeinsame Zielzelle fürs Platzieren (Block ODER gefüllter Eimer) aus {@code this.hit}.
     * Fluids zählen als Luft, weil der Normal-Raycast sie ignoriert. Ersetzbare Pflanzen
     * (Gras/Farn, {@link Block#isReplaceable()}) werden direkt überbaut: Zielzelle = getroffene
     * Zelle statt Nachbarzelle. Liefert {@code null}, wenn kein gültiges Ziel: kein Treffer,
     * Kamera im Block ({@code face == 0,0,0}) oder die Zielzelle ist nicht überbaubar.
     */
    private int[] placementTarget() {
        if (this.hit == null) return null;
        if (this.hit.faceX() == 0 && this.hit.faceY() == 0 && this.hit.faceZ() == 0) return null;
        /* Ersetzbare Pflanzen (Gras/Farn): direkt in die getroffene Zelle bauen, wie MC. */
        if (Blocks.getState(this.hit.block()).getBlock().isReplaceable()) {
            return new int[]{this.hit.x(), this.hit.y(), this.hit.z()};
        }
        int px = this.hit.x() + this.hit.faceX();
        int py = this.hit.y() + this.hit.faceY();
        int pz = this.hit.z() + this.hit.faceZ();
        if (!this.isReplaceable(this.world.getBlock(px, py, pz))) return null;
        return new int[]{px, py, pz};
    }

    /** Klick auf eine vorhandene Slab mit derselben Slab-Sorte -> Doppel-Slab. */
    private boolean tryMergeSlab(Block block) {
        if (!block.getDefaultState().getValues().containsKey(Properties.SLAB_TYPE)) return false;
        BlockState target = Blocks.getState(this.hit.block());
        if (target.getBlock() != block) return false;

        SlabType type = target.get(Properties.SLAB_TYPE);
        boolean merge = (type == SlabType.BOTTOM && this.hit.faceY() > 0)
                || (type == SlabType.TOP && this.hit.faceY() < 0);
        if (!merge) return false;

        this.world.setBlock(this.hit.x(), this.hit.y(), this.hit.z(),
                target.with(Properties.SLAB_TYPE, SlabType.DOUBLE).getId());
        this.soundManager.playPlace(block.getSoundGroup(),
                this.hit.x() + 0.5, this.hit.y() + 0.5, this.hit.z() + 0.5);
        if (this.player.getGamemode() == Gamemode.SURVIVAL) this.consumeHeld(null);
        return true;
    }

    /**
     * Rechtsklick auf eine Truhe öffnet ihr GUI (Truhe + Spielerinventar) und öffnet den Deckel.
     * Bei einer Doppeltruhe kommt die Partnerhälfte dazu: 6 Reihen, beide Deckel.
     */
    private boolean tryOpenChest() {
        int x = this.hit.x(), y = this.hit.y(), z = this.hit.z();
        BlockEntity be = this.world.getBlockEntity(x, y, z);
        if (!(be instanceof ChestBlockEntity chest)) return false;

        BlockState state = Blocks.getState(this.world.getBlock(x, y, z));
        ChestType type = state.getValues().containsKey(Properties.CHEST_TYPE)
                ? state.get(Properties.CHEST_TYPE) : ChestType.SINGLE;
        ChestBlockEntity partner = null;
        int partnerX = x, partnerZ = z;
        if (type != ChestType.SINGLE) {
            Direction toPartner = ChestType.connectedDirection(state.get(Properties.FACING), type);
            partnerX = x + toPartner.offsetX();
            partnerZ = z + toPartner.offsetZ();
            if (this.world.getBlockEntity(partnerX, y, partnerZ) instanceof ChestBlockEntity other) {
                partner = other;
            }
        }
        /* Reihenfolge wie MC (ChestBlock.getBlockType): die RECHTE Hälfte liefert die oberen
           drei Reihen. */
        ChestBlockEntity top = type == ChestType.LEFT && partner != null ? partner : chest;
        ChestBlockEntity bottom = top == chest ? partner : chest;
        this.guiManager.open(new GuiChest(top, bottom, this.playerInventory));

        /* Persistenz: das GUI mutiert das Truhen-Inventar direkt (kein World-Hook) —
           Über-Approximation "geöffnet = potenziell geändert" kostet einen Chunk-Write. */
        this.world.markChunkModified(x, z);
        if (partner != null) this.world.markChunkModified(partnerX, partnerZ);
        return true;
    }

    /**
     * Eimer-Interaktion: gefüllt platziert eine Fluid-Quelle, leer nimmt eine Quelle auf.
     * Im Survival wird der Eimer getauscht (gefüllt↔leer), im Creative nicht.
     */
    private boolean handleBucket(BucketItem bucket) {
        boolean consume = this.player.getGamemode() == Gamemode.SURVIVAL;

        if (bucket.isEmpty()) {
            /* Aufnehmen: fluid-bewusster Strahl, damit Wasser/Lava als Ziel zählt. Nur eine
               Quelle (LEVEL 0, nicht fallend). */
            BlockRaycast.Hit fhit = BlockRaycast.raycast(this.world, this.eyePosition,
                    this.eyeDirection, REACH, true);
            if (fhit == null) return false;
            BlockState state = Blocks.getState(fhit.block());
            if (!state.isFluid() || state.get(Properties.FALLING) || state.get(Properties.LEVEL) != 0) return false;
            this.world.setBlock(fhit.x(), fhit.y(), fhit.z(), Blocks.AIR);
            if (consume) {
                String id = state.getBlock().getFluidInfo().lava ? "skyengine:lava_bucket" : "skyengine:water_bucket";
                this.consumeHeld(Items.get(Identifier.of(id)));
            }
            return true;
        }

        /* Platzieren wie ein Block: der normale (fluid-ignorierende) Strahl this.hit zielt durch
           Wasser hindurch auf die feste Blockseite. Quelle kommt an die Trefferseite (Luft/Fluid). */
        int[] t = this.placementTarget();
        if (t == null) return false;

        Block fluid = bucket.getFluid();
        int source = fluid.getDefaultState()
                .with(Properties.LEVEL, 0).with(Properties.FALLING, false).getId();
        this.world.setBlock(t[0], t[1], t[2], source);
        this.world.scheduleTick(t[0], t[1], t[2], 1);
        if (consume) this.consumeHeld(Items.get(Identifier.of("skyengine:bucket")));
        return true;
    }

    /** Verbraucht einen Eimer aus dem gehaltenen Slot und legt das Ergebnis-Item ab. */
    private void consumeHeld(Item result) {
        ItemStack held = this.playerInventory.get(this.hotbarIndex);
        if (held.getCount() > 1) {
            held.setCount(held.getCount() - 1);
            if (result != null) this.playerInventory.insert(new ItemStack(result, 1));
        } else {
            this.playerInventory.set(this.hotbarIndex, result != null ? new ItemStack(result, 1) : ItemStack.EMPTY);
        }
    }

    /** Leert das Spielerinventar (vor jedem Welt-Eintritt — keine Items aus der Vorwelt). */
    private void clearInventory() {
        for (int i = 0; i < this.playerInventory.size(); i++) {
            this.playerInventory.set(i, ItemStack.EMPTY);
        }
    }

    /** Eine Zelle ist überbaubar, wenn sie leer ist, ein Fluid enthält (Wasser/Lava)
     *  oder einen als replaceable markierten Block (Gras/Farn). */
    private boolean isReplaceable(int block) {
        return block == Blocks.AIR || Blocks.getState(block).isFluid()
                || Blocks.getState(block).getBlock().isReplaceable();
    }

    /** true, wenn die Kollisionsform des Blocks an px/py/pz die Spieler-Box schneidet. */
    private boolean collidesWithPlayer(BlockState state, int px, int py, int pz) {
        for (AABB local : state.getCollisionShape().boxes()) {
            if (local.copy().move(px, py, pz).intersects(this.player.getBoundingBox())) {
                return true;
            }
        }
        return false;
    }

    /**
     * true, wenn die Kollisionsform des Blocks an px/py/pz eine kollidierbare Entity schneidet
     * (z.B. fallender Sand) - dann kann dort nicht gebaut werden, wie in Minecraft.
     */
    private boolean collidesWithEntities(BlockState state, int px, int py, int pz) {
        for (AABB local : state.getCollisionShape().boxes()) {
            if (this.world.intersectsCollidableEntity(local.copy().move(px, py, pz))) {
                return true;
            }
        }
        return false;
    }

    private void handleHotbarInput(Input input) {
        int before = this.hotbarIndex;
        for (int i = 0; i < 9; i++) {
            if (input.isBindPressed(this.settings.key(KeyBindings.hotbar(i + 1)))) {
                this.hotbarIndex = i;
            }
        }
        /* Mausrad: hoch = vorheriger Slot, runter = nächster (mit Wrap), wie in Minecraft. */
        double scroll = input.getScrollY();
        if (scroll > 0) {
            this.hotbarIndex = (this.hotbarIndex + 8) % 9;
        } else if (scroll < 0) {
            this.hotbarIndex = (this.hotbarIndex + 1) % 9;
        }
        if (this.hotbarIndex != before) {
            this.itemNameShownAt = System.currentTimeMillis();
            this.eatingTicks = 0; // Slot-Wechsel bricht angefangenes Essen ab
        }
    }

    /** Gameplay-Hotkeys ohne offenes GUI: Doppel-Sprung=Fliegen und Gamemode-Wechsel (Keybind). */
    private void handleGameplayHotkeys(Input input) {
        /* Doppel-Sprungtaste = Fliegen umschalten (toggleFlying prüft den Modus selbst). */
        if (input.isBindPressed(this.settings.key(KeyBindings.JUMP))) {
            long now = System.currentTimeMillis();
            if (now - this.lastSpacePressTime <= DOUBLE_TAP_MS) {
                this.player.toggleFlying();
                this.logger.debug("Flying: " + this.player.isFlying());
                this.lastSpacePressTime = 0; // verbraucht, damit ein dritter Tipp nicht sofort wieder toggelt
            } else {
                this.lastSpacePressTime = now;
            }
        }
        /* Gamemode durchschalten (Cheat-Feature-Keybind, Default G). */
        if (input.isBindPressed(this.settings.key(KeyBindings.GAMEMODE))) {
            this.player.setGamemode(this.player.getGamemode().next());
            this.logger.debug("Gamemode: " + this.player.getGamemode());
        }
    }

    /**
     * Hotkeys, die immer wirken (auch bei offenem GUI): Screenshot (Keybind, Default F2),
     * Debug-Overlay (F3, inkl. F3+X-Kombi-Gerüst) und Vollbild (F11). Alle sonstigen Debug-
     * Schalter liegen jetzt im GuiDebugScreen (Optionsmenü).
     */
    private void handleGlobalHotkeys(Input input) {
        /* Laufende Keybind-Aufnahme schluckt ALLE Tasten — sonst löst das Binden von F2
           gleichzeitig einen Screenshot aus. */
        if (this.guiManager.capturesKeys()) return;

        if (input.isBindPressed(this.settings.key(KeyBindings.SCREENSHOT))) {
            /* Nur markieren: der Pixel-Read passiert erst nach dem fertigen Frame (SkyEngine.onRender). */
            this.screenshotRequested = true;
        }

        /* F3-Overlay + F3+X-Kombi-Gerüst (Minecraft-Stil): wurde während des Haltens eine Kombi
           benutzt, unterdrückt das den Overlay-Toggle beim Loslassen. Weitere F3+X hier ergänzen. */
        if (input.isKeyDown(GLFW.GLFW_KEY_F3) && !this.guiManager.isOpen()) {
            if (input.isKeyPressed(GLFW.GLFW_KEY_H)) {
                DebugFlags.entityHitboxes = !DebugFlags.entityHitboxes; // Rendering folgt später
                this.logger.debug("Entity-Hitboxen: " + (DebugFlags.entityHitboxes ? "an" : "aus"));
                this.f3ComboUsed = true;
            }
            if (input.isKeyPressed(GLFW.GLFW_KEY_G)) {
                DebugFlags.chunkBorders = (DebugFlags.chunkBorders + 1) % 3;
                this.logger.debug("Chunk-Grenzen: " + switch (DebugFlags.chunkBorders) {
                    case 1 -> "Chunk";
                    case 2 -> "Chunk + Sections";
                    default -> "aus";
                });
                this.f3ComboUsed = true;
            }
        }
        if (input.isKeyReleased(GLFW.GLFW_KEY_F3)) {
            if (!this.f3ComboUsed) {
                this.debugOverlay.toggle();
                this.logger.debug("Debug-Overlay: " + (this.debugOverlay.isVisible() ? "an" : "aus"));
            }
            this.f3ComboUsed = false;
        }

        if (input.isKeyPressed(GLFW.GLFW_KEY_F11)) {
            boolean fullscreen = SkyEngine.get().getConfig().isWindowed();
            /* addTaskToMainThread statt getMainThreadTasks().add: nur ersteres weckt den in
               glfwWaitEvents hängenden Window-Thread. Direkt eingehängt lief der Toggle erst,
               wenn zufällig das nächste OS-Event eintraf. */
            SkyEngine.get().addTaskToMainThread(() ->
                    SkyEngine.get().getWindow().setWindowMode(fullscreen
                            ? EngineConfig.WindowMode.BORDERLESS_FULLSCREEN : EngineConfig.WindowMode.WINDOWED));
            this.logger.debug("Toggle Fullscreen");
        }
    }

    /** Sichtweite der Projektion: mit LOD hinter den äußersten Ring gelegt, sonst wie bisher 1500. */
    private float computeFarPlane() {
        if (!this.settings.lodEnabled) return 1500.0F;
        return (Math.max(this.settings.lodMaxDistance, this.settings.renderDistance) + 8) * 32.0F;
    }

    /** Screenshot programmatisch anfordern (Messstand: Bildvergleich der Cull-Pfade). */
    public void requestScreenshot() {
        this.screenshotRequested = true;
    }

    /** Holt eine angeforderte Screenshot-Aufnahme ab und setzt das Flag zurück. */
    public boolean consumeScreenshotRequest() {
        boolean requested = this.screenshotRequested;
        this.screenshotRequested = false;
        return requested;
    }

    public Camera getCamera() {
        return camera;
    }

    public EntityPlayer getPlayer() {
        return player;
    }

    public World getWorld() {
        return world;
    }

    public GuiManager getGuiManager() {
        return guiManager;
    }
}