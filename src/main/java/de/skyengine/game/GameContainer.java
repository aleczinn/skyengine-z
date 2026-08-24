package de.skyengine.game;

import de.skyengine.core.EngineConfig;
import de.skyengine.core.SkyEngine;
import de.skyengine.core.file.Files;
import de.skyengine.core.resource.ResourcePack;
import de.skyengine.core.resource.Resources;
import de.skyengine.core.input.Input;
import de.skyengine.core.io.*;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.entity.ItemEntity;
import de.skyengine.game.entity.ItemFrameEntity;
import de.skyengine.game.entity.MinecartEntity;
import de.skyengine.game.command.ChatManager;
import de.skyengine.game.command.CommandContext;
import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.BlockRaycast;
import de.skyengine.game.world.block.BlockTextures;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.World;
import de.skyengine.game.world.dimension.PortalController;
import de.skyengine.game.world.dimension.PortalDefinition;
import de.skyengine.game.world.dimension.DimensionDefinition;
import de.skyengine.game.world.dimension.WorldgenRegistries;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.ChestBlockEntity;
import de.skyengine.game.world.block.entity.DispenserBlockEntity;
import de.skyengine.game.world.block.entity.SimpleItemStorage;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.ChestType;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.state.SlabType;
import de.skyengine.game.world.item.BlockItem;
import de.skyengine.game.world.item.BucketItem;
import de.skyengine.game.world.item.FlintAndSteelItem;
import de.skyengine.game.world.item.FoodItem;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.ItemFrameItem;
import de.skyengine.game.world.item.MinecartItem;
import de.skyengine.game.world.item.Items;
import de.skyengine.game.world.item.ToolItem;
import de.skyengine.game.world.item.BlockItem;
import de.skyengine.game.world.particle.ParticleSprites;
import de.skyengine.game.world.loot.LootContext;
import de.skyengine.graphics.world.ChunkRenderer;
import de.skyengine.graphics.world.CrackRenderer;
import de.skyengine.core.i18n.I18n;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.core.settings.KeyBindings;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.game.world.chunk.FluidGeometry;
import de.skyengine.graphics.DebugFlags;
import de.skyengine.graphics.FrameProfiler;
import de.skyengine.graphics.PerformanceProfiler;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.audio.SoundCategory;
import de.skyengine.audio.SoundManager;
import de.skyengine.audio.UnderwaterAudioController;
import de.skyengine.graphics.blockentity.BlockEntityRenderDispatcher;
import de.skyengine.graphics.blockentity.ChestRenderer;
import de.skyengine.graphics.blockentity.EnchantingTableRenderer;
import de.skyengine.graphics.gui.BootProgress;
import de.skyengine.graphics.gui.ChatHud;
import de.skyengine.graphics.gui.screens.GuiChest;
import de.skyengine.graphics.gui.screens.GuiChat;
import de.skyengine.graphics.gui.screens.GuiDispenser;
import de.skyengine.graphics.gui.screens.GuiCreativeInventory;
import de.skyengine.graphics.gui.screens.GuiInventory;
import de.skyengine.graphics.gui.DebugOverlay;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.SaveToast;
import de.skyengine.graphics.gui.SpriteRenderer;
import de.skyengine.graphics.gui.font.FontStyle;
import de.skyengine.graphics.gui.screens.GuiIngameMenu;
import de.skyengine.graphics.gui.screens.GuiDeathScreen;
import de.skyengine.graphics.gui.screens.GuiMainMenu;
import de.skyengine.graphics.gui.screens.GuiResourcePackLoading;
import de.skyengine.graphics.gui.screens.GuiWorldLoading;
import de.skyengine.graphics.gui.text.RichText;
import de.skyengine.graphics.gui.text.Span;
import de.skyengine.graphics.gui.text.TextColors;
import de.skyengine.game.entity.PlayerAnimationState;
import de.skyengine.graphics.camera.CameraPerspective;
import de.skyengine.graphics.player.FirstPersonHandRenderer;
import de.skyengine.graphics.player.HeldItemMeshes;
import de.skyengine.graphics.player.PlayerRenderer;
import de.skyengine.graphics.texture.BlockTextureAtlas;
import de.skyengine.game.world.block.entity.BlockEntities;
import de.skyengine.game.world.block.entity.DataTag;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.game.world.save.PlayerIO;
import de.skyengine.game.world.save.WorldSaves;
import org.lwjgl.opengl.GL11;
import de.skyengine.graphics.post.PostProcessor;
import de.skyengine.graphics.world.ChunkBorderRenderer;
import de.skyengine.graphics.world.SelectionBoxRenderer;
import de.skyengine.graphics.entity.EntityHitboxRenderer;
import de.skyengine.utils.Utils;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3d;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.util.List;
import java.util.ArrayList;
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
    private EntityHitboxRenderer entityHitboxRenderer;
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

    /* F1: HUD samt First-Person-Hand ausblenden. Menues bleiben sichtbar. */
    private boolean hudHidden = false;

    /* F3-Debug-Overlay (FPS/Position/Biome/...), Toggle in handleGlobalHotkeys. */
    private final DebugOverlay debugOverlay = new DebugOverlay();
    private final Runnable profilerOverlayPass = this::renderProfilerBelowScreen;

    /* Audio: Effekt-Sounds + Musik (OpenAL, komplett auf dem Render-Thread). */
    private final SoundManager soundManager = new SoundManager();
    private final UnderwaterAudioController underwaterAudio = new UnderwaterAudioController(this.soundManager);

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
    private final WaterVision waterVision = new WaterVision();
    private boolean playerWasInWater;

    /* Wird per F2 gesetzt und von SkyEngine nach dem fertigen Frame abgeholt. */
    private boolean screenshotRequested = false;

    /* Pausenzustand (Pausenmenü). partialTick friert beim Pausieren auf seinem letzten Wert ein
       — siehe updatePaused. */
    private boolean paused = false;
    private float pausedPartialTick = 0F;

    private BlockRaycast.Hit hit = null;
    /** Entity-Treffer nur, wenn er vor dem naechsten Block auf demselben Augenstrahl liegt. */
    private ItemFrameEntity itemFrameHit = null;
    private MinecartEntity minecartHit = null;

    /* Spieler-Inventar (36 Slots: 0..8 Hotbar, 9..35 Hauptinventar). Auswahl per Zahlentasten 1..9. */
    private final SimpleItemStorage playerInventory = new SimpleItemStorage(36);
    private final ChatManager chat = new ChatManager();
    private final ChatHud chatHud = new ChatHud();
    private int hotbarIndex = 0;
    /* Spieler-UUID (player.dat, Multiplayer-Vorbereitung): beim Betreten geladen oder neu erzeugt. */
    private UUID playerUuid;
    /* Slot-Wechsel-Zeitpunkt für die Itemnamen-Einblendung über der Hotbar (reine Anzeige). */
    private static final long ITEM_NAME_HOLD_MS = 2000, ITEM_NAME_FADE_MS = 500;
    private long itemNameShownAt = 0;
    /* Kurzmeldung an derselben HUD-Position (z.B. Spectator-Fluggeschwindigkeit). */
    private String hudStatusText = "";
    private long hudStatusShownAt = 0;
    /* Ess-Fortschritt in Ticks (Rechtsklick halten auf ein FoodItem, MC: 32 Ticks = 1,6 s).
       Public: auch Zeitbasis der Ess-Animation (FirstPersonHandRenderer). */
    public static final int EAT_TICKS = 32;
    private int eatingTicks = 0;

    /* Aktives Savegame (null im Hauptmenü) — Ziel für saveCurrentWorld beim Austritt/Beenden. */
    private WorldSaves.WorldSave currentSave;
    private final PortalController portalController = new PortalController();
    private PendingDimensionSwitch pendingDimensionSwitch;
    private PendingArrival pendingArrival;

    private record PendingDimensionSwitch(Identifier target, int x, int y, int z,
                                          Identifier portalType, boolean createReturnPortal,
                                          Direction.Axis portalAxis) {}
    private record PendingArrival(int x, int y, int z, Identifier portalType,
                                  boolean createReturnPortal, Direction.Axis portalAxis,
                                  de.skyengine.game.world.dimension.PortalIndex.Entry indexedPortal) {}

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
        this.entityHitboxRenderer = new EntityHitboxRenderer();
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
        ParticleSprites.bootstrap();

        progress.frame(I18n.tr("boot.textures"), 0.45f);
        this.atlas.init();

        progress.frame(I18n.tr("boot.renderer"), 0.65f);
        this.camera.setInverseDepth(SkyEngine.get().getWindow().getProperties().isUseInverseDepth());
        this.selectionBoxRenderer.init();
        this.chunkBorderRenderer.init();
        this.entityHitboxRenderer.init();
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
        this.soundManager.startMusicPlaylist();

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
        this.waterVision.reset();
        this.playerWasInWater = false;
        this.underwaterAudio.reset();
        this.currentSave = save;
        /* Die aktive Dimension steht im player.dat. Alt-Saves und entfernte optionale
           Dimensionen fallen sicher auf die Overworld zurueck. */
        DataTag playerTag = PlayerIO.read(new File(WorldSaves.dir(save.dirName()), "player/player.dat"));
        Identifier dimension = this.playerDimension(playerTag);
        this.world = new World(save.dirName(), save.level(), dimension, this.atlas, this.blockEntityRenderers);
        this.world.setSoundManager(this.soundManager); // Sounds aus der Welt-Logik (z.B. TNT-Explosion)
        this.world.init();
        this.portalController.reset();
        this.pendingDimensionSwitch = null;
        this.pendingArrival = null;

        this.player = new EntityPlayer();
        this.hudStatusText = "";
        this.hudStatusShownAt = 0;
        this.playerUuid = null;
        /* Spielerzustand: player.dat ist die Quelle; Alt-Saves ohne player.dat werden einmalig
           aus den level.json-Feldern migriert (beim nächsten Speichern genullt). */
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

    private Identifier playerDimension(DataTag playerTag) {
        Identifier fallback = WorldgenRegistries.OVERWORLD;
        if (playerTag == null) return fallback;
        Identifier saved = Identifier.of(playerTag.getString("dimension", fallback.toString()));
        if (WorldgenRegistries.DIMENSIONS.get(saved) != null) return saved;
        this.logger.warning("Spielerdimension ist nicht registriert, verwende Overworld: " + saved);
        return fallback;
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
        this.waterVision.reset();
        this.playerWasInWater = false;
        this.underwaterAudio.reset();
        this.animState.reset();
        this.player.motionX = 0;
        this.player.motionY = 0;
        this.player.motionZ = 0;
        this.player.resetVitals();
        if (!this.world.getDimensionId().equals(WorldgenRegistries.OVERWORLD)) {
            this.pendingDimensionSwitch = new PendingDimensionSwitch(
                    WorldgenRegistries.OVERWORLD, 0, 64, 0, null, false, null);
        } else {
            this.placeAtWorldSpawn(this.player);
        }
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
        this.soundManager.stopMinecartSounds();
        this.underwaterAudio.reset();
        this.waterVision.reset();
        this.world.dispose();
        this.world = null;
        this.player = null;
        this.currentSave = null;
        this.pendingDimensionSwitch = null;
        this.pendingArrival = null;
        this.portalController.reset();
        this.hit = null;
        this.itemFrameHit = null;
        this.minecartHit = null;
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
        level.formatVersion = 2;
        if (level.worldType == null) level.worldType = "default";
        level.player = null;
        level.inventory.clear();
        this.world.saveLootRandomStates(level);
        WorldSaves.save(this.currentSave);

        DataTag tag = new DataTag();
        tag.putLong("uuidMost", this.playerUuid.getMostSignificantBits());
        tag.putLong("uuidLeast", this.playerUuid.getLeastSignificantBits());
        tag.putDouble("x", this.player.x);
        tag.putDouble("y", this.player.y);
        tag.putDouble("z", this.player.z);
        tag.putString("dimension", this.world.getDimensionId().toString());
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
        PerformanceProfiler profiler = PerformanceProfiler.get();
        long playerLogicStarted = profiler.begin();

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
            this.updateMovementParticles();

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

            boolean eyesUnderwater = this.playerEyesUnderwater();
            this.waterVision.tick(eyesUnderwater);
            this.underwaterAudio.tick(eyesUnderwater, this.player.isTouchingWater(this.world),
                    !this.player.isFlying(),
                    this.player.x - this.player.lastX,
                    this.player.y - this.player.lastY,
                    this.player.z - this.player.lastZ);
            this.updateStepSounds();
            this.updateHurtSounds();
            this.updateEating(input);
            if (!this.player.isDead() && this.pendingDimensionSwitch == null) {
                PortalController.Travel travel = this.portalController.tick(this.world, this.player);
                if (travel != null) {
                    this.queuePortalTravel(travel);
                }
            }
            /* Tod (z.B. Fallschaden — auch mit offenem Container-GUI möglich): Todesscreen
               öffnen; open() schließt ein offenes Inventar/eine Truhe sauber über onClose. */
            if (this.player.isDead() && !(this.guiManager.current() instanceof GuiDeathScreen)) {
                this.guiManager.open(new GuiDeathScreen());
            }
        }
        /* Pause-Menü hält die Welt komplett an (wie MC-Singleplayer); Container-GUIs
           (Truhe) lassen sie weiterticken. */
        if (!this.guiManager.pausesGame()) {
            profiler.recordElapsed(PerformanceProfiler.TickSection.PLAYER_GAME_LOGIC, playerLogicStarted);
            this.world.update(input, this.player);
            this.pickupItems();
            this.finalizePendingArrival();
            /* Autosave. Die Modulo-Prüfung gehört IN diesen Zweig: gameTime steht bei Pause
               still, außerhalb würde sie dann jeden Tick erneut feuern. */
            if (this.world.getGameTime() % AUTOSAVE_INTERVAL == 0 && this.saveCurrentWorld(false) > 0) {
                this.notifyOnSaveDone = true; // beim Autosave nur melden, wenn es wirklich etwas gab
            }
            if (this.pendingDimensionSwitch != null) this.executeDimensionSwitch();
        }
    }

    private void queuePortalTravel(PortalController.Travel travel) {
        this.soundManager.playPortalTravel();
        PortalDefinition definition = WorldgenRegistries.PORTALS.get(travel.portalType());
        int sourceX = travel.x(), sourceY = travel.y(), sourceZ = travel.z();
        Direction.Axis axis = null;
        if (definition != null && definition.linkPolicy() == PortalDefinition.LinkPolicy.NETHER) {
            var shape = de.skyengine.game.world.dimension.NetherPortalShape.find(
                    this.world, sourceX, sourceY, sourceZ, true);
            if (shape != null) {
                sourceX = shape.centerX();
                sourceY = shape.bottomY();
                sourceZ = shape.centerZ();
                axis = shape.axis();
            }
            var target = WorldgenRegistries.DIMENSIONS.get(travel.targetDimension());
            double ratio = this.world.getEnvironment().coordinateScale()
                    / target.environment().coordinateScale();
            sourceX = (int) Math.floor(sourceX * ratio);
            sourceZ = (int) Math.floor(sourceZ * ratio);
        }
        this.pendingDimensionSwitch = new PendingDimensionSwitch(travel.targetDimension(),
                sourceX, sourceY, sourceZ, travel.portalType(), true, axis);
    }

    /** Reiht einen sicheren 1:1-Wechsel fuer Befehle und spaetere Gameplay-Systeme ein. */
    public boolean requestDimensionChange(Identifier target) {
        if (this.world == null || this.player == null || this.pendingDimensionSwitch != null
                || target == null || target.equals(this.world.getDimensionId())
                || WorldgenRegistries.DIMENSIONS.get(target) == null) return false;
        this.pendingDimensionSwitch = new PendingDimensionSwitch(target,
                (int) Math.floor(this.player.x), (int) Math.floor(this.player.y),
                (int) Math.floor(this.player.z), null, false, null);
        return true;
    }

    private void executeDimensionSwitch() {
        PendingDimensionSwitch request = this.pendingDimensionSwitch;
        this.pendingDimensionSwitch = null;
        if (request == null || this.currentSave == null || request.target.equals(this.world.getDimensionId())) return;

        /* Zielmetadaten vor dem Abbau validieren und bei Erstbetreten persistent anlegen. */
        de.skyengine.game.world.dimension.DimensionSaves.resolve(
                WorldSaves.dir(this.currentSave.dirName()), this.currentSave.level(), request.target);
        this.saveCurrentWorld(true);
        GL11.glFinish();
        this.soundManager.stopMinecartSounds();
        this.underwaterAudio.reset();
        this.waterVision.reset();
        this.world.dispose();

        this.world = new World(this.currentSave.dirName(), this.currentSave.level(), request.target,
                this.atlas, this.blockEntityRenderers);
        this.world.setSoundManager(this.soundManager);
        this.world.init();
        PortalDefinition definition = request.portalType == null ? null
                : WorldgenRegistries.PORTALS.get(request.portalType);
        int radius = request.target.equals(WorldgenRegistries.NETHER) ? 16 : 128;
        var indexed = definition != null && definition.linkPolicy() == PortalDefinition.LinkPolicy.NETHER
                ? this.world.getPortalIndex().nearest(request.portalType, request.x, request.y, request.z, radius)
                : null;
        int loadX = indexed == null ? request.x : indexed.x();
        int loadZ = indexed == null ? request.z : indexed.z();
        int provisionalY = indexed == null
                ? Math.clamp(request.y > 0 ? request.y : this.world.getGenerator().sampleHeight(loadX, loadZ) + 2,
                2, de.skyengine.game.world.chunk.Chunk.HEIGHT - 2)
                : indexed.y();
        this.player.setPosition(loadX + 0.5, provisionalY, loadZ + 0.5);
        this.resetPlayerAfterDimensionMove();
        this.pendingArrival = new PendingArrival(request.x, request.y, request.z,
                request.portalType, request.createReturnPortal, request.portalAxis, indexed);
        this.portalController.lockUntilExit();
        this.notifyOnSaveDone = false;
        this.hit = null;
        this.itemFrameHit = null;
        this.minecartHit = null;
        this.resetMining();
        this.applySettings();
        this.guiManager.open(new GuiWorldLoading());
    }

    private void finalizePendingArrival() {
        PendingArrival arrival = this.pendingArrival;
        int checkX = arrival != null && arrival.indexedPortal != null ? arrival.indexedPortal.x() : arrival == null ? 0 : arrival.x;
        int checkZ = arrival != null && arrival.indexedPortal != null ? arrival.indexedPortal.z() : arrival == null ? 0 : arrival.z;
        if (arrival == null || !this.world.getChunkManager().isInitialLoadComplete()
                || !this.arrivalAreaReady(checkX, checkZ)) return;

        PortalDefinition definition = arrival.portalType == null ? null
                : WorldgenRegistries.PORTALS.get(arrival.portalType);
        if (definition != null && definition.linkPolicy() == PortalDefinition.LinkPolicy.NETHER) {
            this.finalizeNetherArrival(arrival);
            return;
        }

        int portalY = arrival.createReturnPortal
                ? this.findSafePortalY(arrival.x, arrival.z, arrival.portalType) : -1;
        int feetY;
        if (portalY >= 1) {
            feetY = portalY;
        } else {
            int floorY = this.findArrivalFloor(arrival.x, arrival.z);
            for (int x = arrival.x - 1; x <= arrival.x + 1; x++) {
                for (int z = arrival.z - 1; z <= arrival.z + 1; z++) {
                    this.world.setBlock(x, floorY, z, Blocks.OBSIDIAN);
                    this.world.setBlock(x, floorY + 1, z, Blocks.AIR);
                    this.world.setBlock(x, floorY + 2, z, Blocks.AIR);
                }
            }
            feetY = floorY + 1;
            if (arrival.createReturnPortal) {
                this.world.setBlock(arrival.x, feetY, arrival.z, Blocks.MINING_PORTAL);
            }
        }
        this.player.setPosition(arrival.x + 0.5, feetY, arrival.z + 0.5);
        this.resetPlayerAfterDimensionMove();
        this.portalController.lockUntilExit();
        this.pendingArrival = null;
        this.saveCurrentWorld(false);
    }

    private void finalizeNetherArrival(PendingArrival arrival) {
        if (arrival.indexedPortal != null) {
            var entry = arrival.indexedPortal;
            var shape = de.skyengine.game.world.dimension.NetherPortalShape.find(
                    this.world, entry.x(), entry.y(), entry.z(), true);
            if (shape != null) {
                this.finishArrival(shape.centerX() + 0.5, shape.bottomY(), shape.centerZ() + 0.5);
                return;
            }
            this.world.getPortalIndex().remove(entry);
            this.player.setPosition(arrival.x + 0.5, Math.clamp(arrival.y, 2, Chunk.HEIGHT - 2),
                    arrival.z + 0.5);
            this.pendingArrival = new PendingArrival(arrival.x, arrival.y, arrival.z,
                    arrival.portalType, arrival.createReturnPortal, arrival.portalAxis, null);
            return;
        }

        Direction.Axis axis = arrival.portalAxis == null ? Direction.Axis.X : arrival.portalAxis;
        int[] site = this.findNetherPortalSite(arrival.x, arrival.y, arrival.z, axis);
        int minX = axis == Direction.Axis.X ? site[0] - 1 : site[0];
        int minZ = axis == Direction.Axis.Z ? site[2] - 1 : site[2];
        int bottomY = site[1] + 1;
        this.buildNetherPortal(minX, bottomY, minZ, axis);
        var shape = de.skyengine.game.world.dimension.NetherPortalShape.find(
                this.world, minX, bottomY, minZ, true);
        if (shape == null) throw new IllegalStateException("Erzeugtes Netherportal ist ungueltig");
        this.finishArrival(shape.centerX() + 0.5, shape.bottomY(), shape.centerZ() + 0.5);
    }

    private int[] findNetherPortalSite(int targetX, int targetY, int targetZ, Direction.Axis axis) {
        int maxFloor = this.world.getDimensionId().equals(WorldgenRegistries.NETHER) ? 120 : Chunk.HEIGHT - 5;
        for (int radius = 0; radius <= 8; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    int x = targetX + dx, z = targetZ + dz;
                    for (int y = Math.min(maxFloor, Math.max(2, targetY + 16)); y >= 1; y--) {
                        if (this.portalSiteClear(x, y, z, axis)) return new int[]{x, y, z};
                    }
                }
            }
        }

        int floor = Math.clamp(targetY, 32, maxFloor);
        for (int x = targetX - 2; x <= targetX + 2; x++) {
            for (int z = targetZ - 2; z <= targetZ + 2; z++) {
                this.world.setBlock(x, floor, z, Blocks.OBSIDIAN, false);
                for (int y = floor + 1; y <= floor + 5; y++) this.world.setBlock(x, y, z, Blocks.AIR, false);
            }
        }
        return new int[]{targetX, floor, targetZ};
    }

    private boolean portalSiteClear(int centerX, int floorY, int centerZ, Direction.Axis axis) {
        int sx = axis == Direction.Axis.X ? 1 : 0;
        int sz = axis == Direction.Axis.Z ? 1 : 0;
        int px = axis == Direction.Axis.X ? 0 : 1;
        int pz = axis == Direction.Axis.Z ? 0 : 1;
        for (int w = -2; w <= 1; w++) {
            int x = centerX + sx * w, z = centerZ + sz * w;
            if (!Blocks.getState(this.world.getBlock(x, floorY, z)).isSolid()) return false;
            for (int side = -1; side <= 1; side++) {
                for (int y = 1; y <= 4; y++) {
                    BlockState state = Blocks.getState(this.world.getBlock(
                            x + px * side, floorY + y, z + pz * side));
                    if (state.isSolid() || state.isFluid()) return false;
                }
            }
        }
        return true;
    }

    private void buildNetherPortal(int minX, int bottomY, int minZ, Direction.Axis axis) {
        int sx = axis == Direction.Axis.X ? 1 : 0;
        int sz = axis == Direction.Axis.Z ? 1 : 0;
        for (int w = -1; w <= 2; w++) {
            this.world.setBlock(minX + sx * w, bottomY - 1, minZ + sz * w, Blocks.OBSIDIAN, false);
            this.world.setBlock(minX + sx * w, bottomY + 3, minZ + sz * w, Blocks.OBSIDIAN, false);
        }
        for (int h = 0; h < 3; h++) {
            this.world.setBlock(minX - sx, bottomY + h, minZ - sz, Blocks.OBSIDIAN, false);
            this.world.setBlock(minX + sx * 2, bottomY + h, minZ + sz * 2, Blocks.OBSIDIAN, false);
            for (int w = 0; w < 2; w++) {
                this.world.setBlock(minX + sx * w, bottomY + h, minZ + sz * w, Blocks.AIR, false);
            }
        }
        if (!de.skyengine.game.world.dimension.NetherPortalShape.activate(
                this.world, minX, bottomY, minZ)) {
            throw new IllegalStateException("Netherportalrahmen konnte nicht aktiviert werden");
        }
    }

    private void finishArrival(double x, int feetY, double z) {
        this.player.setPosition(x, feetY, z);
        this.resetPlayerAfterDimensionMove();
        this.portalController.lockUntilExit();
        this.pendingArrival = null;
        this.saveCurrentWorld(false);
    }

    private boolean arrivalAreaReady(int centerX, int centerZ) {
        for (int x = centerX - 1; x <= centerX + 1; x++) {
            for (int z = centerZ - 1; z <= centerZ + 1; z++) {
                var chunk = this.world.getChunkManager().getChunk(
                        x >> ChunkSection.SHIFT, z >> ChunkSection.SHIFT);
                if (chunk == null || chunk.status != ChunkStatus.READY) return false;
            }
        }
        return true;
    }

    private int findSafePortalY(int x, int z, Identifier portalType) {
        PortalDefinition definition = portalType == null ? null : WorldgenRegistries.PORTALS.get(portalType);
        if (definition == null) return -1;
        for (int y = de.skyengine.game.world.chunk.Chunk.HEIGHT - 2; y >= 1; y--) {
            int state = this.world.getBlock(x, y, z);
            if (!Blocks.getState(state).getBlock().getIdentifier().equals(definition.block())) continue;
            int floor = this.world.getBlock(x, y - 1, z);
            int head = this.world.getBlock(x, y + 1, z);
            if (Blocks.getState(floor).isSolid() && !Blocks.getState(head).isSolid()) return y;
        }
        return -1;
    }

    private int findArrivalFloor(int x, int z) {
        for (int y = de.skyengine.game.world.chunk.Chunk.HEIGHT - 3; y >= 1; y--) {
            BlockState state = Blocks.getState(this.world.getBlock(x, y, z));
            if (state.isSolid() && !state.isFluid()) return y;
        }
        return 64;
    }

    private void resetPlayerAfterDimensionMove() {
        this.player.motionX = 0;
        this.player.motionY = 0;
        this.player.motionZ = 0;
        this.player.snapPrevToCurrent();
        this.animState.reset();
        this.animState.snapPrev();
        this.waterVision.reset();
        this.playerWasInWater = false;
        this.underwaterAudio.reset();
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

    /** Bewegungspartikel aus bereits berechneten Spieler-Flanken; verändert keinerlei Physik. */
    private void updateMovementParticles() {
        boolean inWater = this.player.isTouchingWater(this.world);
        double dx = this.player.x - this.player.lastX;
        double dy = this.player.y - this.player.lastY;
        double dz = this.player.z - this.player.lastZ;
        double speed = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (inWater && !this.playerWasInWater) {
            this.world.particles().splash(this.player.x, this.player.y, this.player.z, dx, dy, dz);
        } else if (inWater && speed > 0.02) {
            this.world.particles().swim(this.player.x, this.player.y + 0.8, this.player.z, dx, dy, dz);
        }
        this.playerWasInWater = inWater;

        BlockState ground = this.groundStateAtPlayer();
        if (!inWater && ground != null && this.player.onGround && this.player.isSprinting()
                && dx * dx + dz * dz > 0.001) {
            this.world.particles().sprint(this.player.x, this.player.y, this.player.z, ground, dx, dz);
        }
        float landing = this.player.consumeLandingDistance();
        if (landing > 3F && ground != null && !inWater) {
            this.world.particles().landing(this.player.x, this.player.y, this.player.z, ground, landing);
        }
    }

    private BlockState groundStateAtPlayer() {
        int bx = (int) Math.floor(this.player.x);
        int by = (int) Math.floor(this.player.y - 0.2);
        int bz = (int) Math.floor(this.player.z);
        BlockState state = Blocks.getState(this.world.getBlock(bx, by, bz));
        if (state.isAir() || state.isFluid()) state = Blocks.getState(this.world.getBlock(bx, by - 1, bz));
        return state.isAir() || state.isFluid() ? null : state;
    }

    /** Itemkrümel am Gesicht; verwendet denselben TextureArray-Layer wie GUI und World-Items. */
    private void emitHeldItemParticles(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        int texture = -1;
        if (stack.getItem() instanceof BlockItem blockItem) {
            var quads = blockItem.getBlock().getDefaultState().getModel();
            if (quads.length > 0) texture = quads[0].textureLayer();
        } else if (stack.getItem().getIconTexture() != null) {
            texture = BlockTextures.layerOf(stack.getItem().getIconTexture());
        }
        if (texture < 0) return;
        double yaw = Math.toRadians(this.player.yaw);
        double pitch = Math.toRadians(this.player.pitch);
        double cp = Math.cos(pitch);
        double dirX = cp * Math.sin(yaw);
        double dirY = -Math.sin(pitch);
        double dirZ = -cp * Math.cos(yaw);
        double px = this.player.x + dirX * 0.35;
        double py = this.player.y + this.player.getEyeHeight(1F) + dirY * 0.35 - 0.1;
        double pz = this.player.z + dirZ * 0.35;
        this.world.particles().itemCrumb(texture, px, py, pz, dirX, dirY, dirZ);
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
        PostProcessor post = SkyEngine.get().getPostProcessor();
        post.setMenuBlur(
                this.world != null && this.guiManager.blursBackground());

        /* Hauptmenü (keine Welt): nur GUI-Eingaben routen, gezeichnet wird in renderGui. */
        if (this.world == null) {
            post.setUnderwater(false, 0F);
            post.setPortalEffect(0F);
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
            /* T oeffnet eine leere Chatzeile; Slash springt wie in Minecraft direkt in einen
               Befehl. Der Screen pausiert die Welt nicht und blockiert unten die Gameplay-Eingabe. */
            if (input.isBindPressed(this.settings.key(KeyBindings.OPEN_CHAT))) {
                this.openChat("");
            } else if (input.isKeyPressed(GLFW.GLFW_KEY_SLASH)) {
                this.openChat("/");
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
        BlockState cameraFluid = this.cameraFluidState();
        post.setUnderwater(shouldRenderUnderwaterEffect(DebugFlags.underwaterEffect, cameraFluid),
                this.waterVision.factor());
        post.setPortalEffect(this.portalController.contactProgress());

        /* Audio pro Frame: Listener auf die interpolierte Kamera (Streaming läuft oben). */
        this.soundManager.updateListener(this.camera);
        this.world.updateEntitySounds(partialTick);

        this.hit = BlockRaycast.raycastInteractive(this.world, this.eyePosition, this.eyeDirection, REACH);
        double entityReach = this.hit == null ? REACH : Math.sqrt(
                sq(this.hit.hitX() - this.eyePosition.x)
                        + sq(this.hit.hitY() - this.eyePosition.y)
                        + sq(this.hit.hitZ() - this.eyePosition.z));
        this.itemFrameHit = this.world.raycastItemFrame(this.eyePosition.x, this.eyePosition.y,
                this.eyePosition.z, this.eyeDirection.x, this.eyeDirection.y, this.eyeDirection.z,
                entityReach);
        if (this.itemFrameHit != null && !this.world.isPlayerInteractionReady(
                this.itemFrameHit.getAnchorX(), this.itemFrameHit.getAnchorY(),
                this.itemFrameHit.getAnchorZ())) {
            this.itemFrameHit = null;
        }
        this.minecartHit = this.world.raycastMinecart(this.eyePosition.x, this.eyePosition.y,
                this.eyePosition.z, this.eyeDirection.x, this.eyeDirection.y, this.eyeDirection.z,
                entityReach);
        if (this.minecartHit != null && !this.world.isPlayerInteractionReady(
                (int) Math.floor(this.minecartHit.x), (int) Math.floor(this.minecartHit.y),
                (int) Math.floor(this.minecartHit.z))) {
            this.minecartHit = null;
        }

        if (guiOpen) {
            this.guiManager.handleInput();       // Schließen + Slot-Klicks (kann den GuiScreen schließen)
            /* Ein GuiScreen-Callback (GuiIngameMenu „Hauptmenü") kann exitToTitle() ausgelöst haben —
               dann ist die Welt weg und der Rest des Frames darf sie nicht mehr anfassen. */
            if (this.world == null) {
                post.setUnderwater(false, 0F);
                return;
            }
        } else {
            /* Nur Klick-Flanken einsammeln — die Interaktion selbst läuft wie in MC im Tick. */
            this.pollInteractionClicks(input);
        }

        /* Wireframe (F3+V) gilt NUR für die Welt-Geometrie: der Line-Mode ist globaler GL-State und
           würde sonst auch das Fullscreen-Dreieck der Post-Kette (und die GUI-Quads) zu Linien
           machen — dann bliebe der Default-Framebuffer unbeschrieben ("eingefrorenes" Bild). */
        if (DebugFlags.wireframe) Utils.enableWireframe();
        if (this.perspective.isFirstPerson()) {
            this.world.render(this.camera, partialTick);
        } else {
            /* Am Auge samplen: Der Fußpunkt liegt beim Sitzen im Fahrzeug oder Stützblock. */
            float playerLight = this.playerLightAtEyes(partialTick);
            this.world.render(this.camera, partialTick, () ->
                    this.playerRenderer.renderThirdPerson(this.player, this.animState, this.camera, partialTick,
                            this.heldItemMeshes, this.playerInventory.get(this.hotbarIndex), playerLight));
        }
        if (DebugFlags.wireframe) Utils.disableWireframe();

        FrameProfiler.cpuStart(FrameProfiler.Cpu.OVL);
        FrameProfiler.gpuBegin(FrameProfiler.Gpu.OVERLAYS);

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

        if (DebugFlags.entityHitboxes) {
            this.entityHitboxRenderer.render(this.camera, this.player, this.world, partialTick);
        }

        /* First-Person-Hand ins Szene-Target (läuft durch die Post-Kette), eigener Nah-Depthbereich. */
        if (!this.hudHidden && this.perspective.isFirstPerson()
                && this.player.getGamemode() != Gamemode.SPECTATOR) {
            /* Licht der AUGEN-Zelle (nicht der Füße): die Hand hängt vor dem Gesicht, und in
               einem 1 Block hohen Kriechgang unterscheiden sich beide sichtbar. */
            float handLight = this.playerLightAtEyes(partialTick);
            this.handRenderer.render(this.playerRenderer, this.heldItemMeshes, this.player,
                    this.animState, (float) width / height, partialTick, this.viewEffect, handLight);
        }

        /* Wasser folgt als Depth-Post-Pass; Lava bleibt ein dichter Overlay und liegt damit
           ebenfalls ueber First-Person-Hand und gehaltenem Item. */
        this.renderLavaOverlay();

        FrameProfiler.gpuEnd(FrameProfiler.Gpu.OVERLAYS);
        FrameProfiler.cpuStop(FrameProfiler.Cpu.OVL);
    }

    static boolean shouldRenderUnderwaterEffect(boolean enabled, BlockState cameraFluid) {
        return enabled && cameraFluid != null && cameraFluid.isFluid()
                && !cameraFluid.getBlock().getFluidInfo().lava;
    }

    /** Licht an der interpolierten Augenposition; verhindert 20-TPS-Sprünge beim Rendern. */
    private float playerLightAtEyes(float partialTick) {
        double x = this.player.lastX + (this.player.x - this.player.lastX) * partialTick;
        double y = this.player.lastY + (this.player.y - this.player.lastY) * partialTick
                + this.player.getEyeHeight(partialTick);
        double z = this.player.lastZ + (this.player.z - this.player.lastZ) * partialTick;
        return this.lightAt(x, y, z);
    }

    /**
     * Wahrgenommener Licht-Faktor an einer Weltposition. Statt eine einzelne ganzzahlige
     * Blockzelle zu wählen, werden die acht umliegenden Zellzentren trilinear gemischt. So
     * bleibt die Minecraft-Lichtkurve erhalten, aber Hand und gehaltenes Item springen beim
     * Wechsel zwischen den diskreten Lichtleveln nicht mehr in sichtbaren Etappen.
     */
    private float lightAt(double x, double y, double z) {
        double gx = x - 0.5, gy = y - 0.5, gz = z - 0.5;
        int x0 = (int) Math.floor(gx), y0 = (int) Math.floor(gy), z0 = (int) Math.floor(gz);
        float fx = (float) (gx - x0), fy = (float) (gy - y0), fz = (float) (gz - z0);
        float result = 0F;
        for (int dy = 0; dy <= 1; dy++) {
            float wy = dy == 0 ? 1F - fy : fy;
            for (int dz = 0; dz <= 1; dz++) {
                float wz = dz == 0 ? 1F - fz : fz;
                for (int dx = 0; dx <= 1; dx++) {
                    float wx = dx == 0 ? 1F - fx : fx;
                    float light = ChunkRenderer.lightFactor(
                            this.world.getRenderedSkyLight(x0 + dx, y0 + dy, z0 + dz),
                            this.world.getBlockLight(x0 + dx, y0 + dy, z0 + dz),
                            this.world.getEnvironment().ambientLight());
                    result += light * wx * wy * wz;
                }
            }
        }
        return result;
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
        boolean showDebug = !this.hudHidden && this.debugOverlay.isVisible() && this.world != null;
        FrameProfiler.cpuStart(FrameProfiler.Cpu.GUI);
        FrameProfiler.gpuBegin(FrameProfiler.Gpu.GUI);
        /* Im Hauptmenü kein HUD (Inventar null -> GuiManager überspringt Hotbar/Crosshair);
           Crosshair nur in First Person (in den F5-Ansichten zielt man nicht über die Bildmitte). */
        this.guiManager.render(width, height,
                this.world != null && !this.hudHidden ? this.playerInventory : null,
                this.hotbarIndex, showHotbar && !this.hudHidden,
                !this.hudHidden && this.perspective.isFirstPerson(),
                this.hudHidden ? 0F : this.itemNameAlpha(),
                this.hudHidden ? "" : this.hudStatusText,
                this.hudHidden ? 0F : this.hudStatusAlpha(), this.player,
                showDebug && FrameProfiler.isEnabled() ? this.profilerOverlayPass : null);
        if (!this.hudHidden && this.world != null && !this.guiManager.isOpen()) {
            this.chatHud.render(this.guiManager, this.chat, this.guiManager.vHeight() - 40F, false);
        }
        if (showDebug) {
            if (FrameProfiler.isEnabled()) {
                FrameProfiler.cpuStop(FrameProfiler.Cpu.GUI);
                FrameProfiler.cpuStart(FrameProfiler.Cpu.PROFILER_UI);
            }
            this.debugOverlay.render(this.guiManager, this.world, this.player);
            if (FrameProfiler.isEnabled()) {
                FrameProfiler.cpuStop(FrameProfiler.Cpu.PROFILER_UI);
                FrameProfiler.cpuStart(FrameProfiler.Cpu.GUI);
            }
        }
        /* Gespeichert-Meldung NACH dem GuiScreen: sie soll auch über dem abgedunkelten
           Pausenmenü lesbar sein (im Hud läge sie unter dessen Dim). */
        if (!this.hudHidden && this.world != null) this.saveToast.render(this.guiManager);
        FrameProfiler.gpuEnd(FrameProfiler.Gpu.GUI);
        FrameProfiler.cpuStop(FrameProfiler.Cpu.GUI);
    }

    /** Profiler unter einem offenen GuiScreen, aber ueber HUD und Welt zeichnen. */
    private void renderProfilerBelowScreen() {
        FrameProfiler.cpuStop(FrameProfiler.Cpu.GUI);
        FrameProfiler.cpuStart(FrameProfiler.Cpu.PROFILER_UI);
        this.debugOverlay.renderProfiler(this.guiManager);
        FrameProfiler.cpuStop(FrameProfiler.Cpu.PROFILER_UI);
        FrameProfiler.cpuStart(FrameProfiler.Cpu.GUI);
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
                this.emitHeldItemParticles(held);
            }
        }
    }

    /** Einblend-Alpha des Hotbar-Itemnamens: 2 s voll, dann 0,5 s linear ausblenden. */
    private float itemNameAlpha() {
        return timedHudAlpha(this.itemNameShownAt);
    }

    private float hudStatusAlpha() {
        return timedHudAlpha(this.hudStatusShownAt);
    }

    private static float timedHudAlpha(long shownAt) {
        long since = System.currentTimeMillis() - shownAt;
        if (shownAt == 0 || since >= ITEM_NAME_HOLD_MS + ITEM_NAME_FADE_MS) return 0f;
        if (since <= ITEM_NAME_HOLD_MS) return 1f;
        return (ITEM_NAME_HOLD_MS + ITEM_NAME_FADE_MS - since) / (float) ITEM_NAME_FADE_MS;
    }

    /** Liefert das Fluid, dessen echte Oberflaeche die Kamera gerade ueberdeckt. */
    private BlockState cameraFluidState() {
        Vector3d eye = this.camera.getPosition();
        int bx = (int) Math.floor(eye.x);
        int by = (int) Math.floor(eye.y);
        int bz = (int) Math.floor(eye.z);
        BlockState state = Blocks.getState(this.world.getRenderedBlock(bx, by, bz));
        BlockState above = Blocks.getState(this.world.getRenderedBlock(bx, by + 1, bz));
        return isCameraSubmerged(eye.y, by, state, above) ? state : null;
    }

    /** Tick-Sample am echten Spielerauge; steuert Water Vision und Unterwasser-Audio. */
    private boolean playerEyesUnderwater() {
        double eyeY = this.player.y + this.player.getEyeHeight(1F);
        int bx = (int) Math.floor(this.player.x);
        int by = (int) Math.floor(eyeY);
        int bz = (int) Math.floor(this.player.z);
        BlockState state = Blocks.getState(this.world.getRenderedBlock(bx, by, bz));
        BlockState above = Blocks.getState(this.world.getRenderedBlock(bx, by + 1, bz));
        return isCameraSubmerged(eyeY, by, state, above)
                && !state.getBlock().getFluidInfo().lava;
    }

    /** Pure Oberflaechenpruefung, getrennt vom World-Zugriff fuer Regressionstests. */
    static boolean isCameraSubmerged(double eyeY, int blockY, BlockState state, BlockState above) {
        if (!state.isFluid()) return false;
        float height = above.isFluid() && above.getBlock() == state.getBlock()
                ? 1.0F : FluidGeometry.fluidHeight(state);
        return eyeY - blockY < height;
    }

    /** Dichtes Lava-Overlay; Wasser wird vom depth-basierten Post-Pass behandelt. */
    private void renderLavaOverlay() {
        BlockState state = this.cameraFluidState();
        if (state == null || !state.getBlock().getFluidInfo().lava) return;

        SpriteRenderer sr = this.guiManager.sprites();
        sr.begin(1, 1); // Ortho 0..1 -> Fullscreen-Rect unabhängig vom GUI-Scale
        sr.drawRect(0, 0, 1, 1, 0.6f, 0.1f, 0.0f, 0.8f);
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
        if (this.entityHitboxRenderer != null) this.entityHitboxRenderer.dispose();
        if (this.crackRenderer != null) this.crackRenderer.dispose();
        this.playerRenderer.dispose();
        this.heldItemMeshes.dispose();
        if (this.guiManager != null) this.guiManager.dispose();
        this.blockEntityRenderers.dispose();
        this.atlas.dispose();
        this.underwaterAudio.reset();
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
            if (this.breakTargetBlock(state, false)) this.destroyDelay = DESTROY_DELAY;
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
            boolean broken = this.breakTargetBlock(Blocks.getState(this.hit.block()), false);
            if (broken) this.destroyDelay = DESTROY_DELAY;
            return broken;
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
            this.world.particles().blockHit(state, this.hit.hitX(), this.hit.hitY(), this.hit.hitZ(),
                    this.hit.faceX(), this.hit.faceY(), this.hit.faceZ());
        }
        this.destroyTicks++;

        if (this.miningProgress >= 1F) {
            this.isDestroying = false;
            boolean broken = this.breakTargetBlock(state, true);
            this.miningProgress = 0F;
            this.destroyTicks = 0F;
            if (broken) this.destroyDelay = DESTROY_DELAY;
            return broken;
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
    private boolean breakTargetBlock(BlockState broken, boolean applyDurability) {
        if (this.hit == null || !this.world.isPlayerInteractionReady(
                this.hit.x(), this.hit.y(), this.hit.z())
                || this.world.getBlock(this.hit.x(), this.hit.y(), this.hit.z()) != broken.getId()) {
            this.resetMining();
            return false;
        }
        /* Loot VOR onBreak/setBlock auswerten — solange State und BlockEntity lesbar sind. */
        ItemStack held = this.playerInventory.get(this.hotbarIndex);
        java.util.ArrayList<ItemStack> drops = new java.util.ArrayList<>(2);
        if (this.player.getGamemode().dropsItems() && isHarvestable(broken, held)) {
            var context = new LootContext(this.world,
                    this.hit.x(), this.hit.y(), this.hit.z(), broken, held,
                    LootContext.Cause.PLAYER, 0.0F, this.world.random());
            broken.getBlock().appendDrops(context, (stack, x, y, z) -> drops.add(stack));
        }
        int breakX = this.hit.x(), breakY = this.hit.y(), breakZ = this.hit.z();
        boolean removed = this.world.runPlayerBlockChange(() -> {
            broken.getBlock().onBreak(this.world, breakX, breakY, breakZ, broken);
            return this.world.setBlock(breakX, breakY, breakZ, Blocks.AIR);
        });
        if (!removed) {
            this.resetMining();
            return false;
        }

        /* PortalBehavior kollabiert die ganze Flaeche und erzeugt genau einen Glas-Effekt. */
        if (!de.skyengine.game.world.dimension.NetherPortalShape.isPortalState(broken.getId())) {
            this.soundManager.playBreak(broken.getBlock().getSoundGroup(),
                    this.hit.x() + 0.5, this.hit.y() + 0.5, this.hit.z() + 0.5);
            this.world.particles().blockBreak(breakX, breakY, breakZ, broken);
        }

        for (ItemStack drop : drops) this.world.spawnItem(this.hit.x() + 0.5,
                this.hit.y() + 0.5, this.hit.z() + 0.5, drop);

        /* Tool-Abnutzung (nur Survival bei Härte > 0): zerbricht bei erreichter Haltbarkeit. */
        if (applyDurability && held.getItem() instanceof ToolItem tool) {
            held.setDamage(held.getDamage() + 1);
            if (held.getDamage() >= tool.getTier().durability()) {
                this.emitHeldItemParticles(held);
                this.playerInventory.set(this.hotbarIndex, ItemStack.EMPTY);
            }
        } else if (applyDurability && held.getItem() instanceof de.skyengine.game.world.item.ShearsItem
                && (broken.isLeaves() || broken.getBlock().getIdentifier().path().equals("short_grass")
                || broken.getBlock().getIdentifier().path().equals("fern")
                || broken.getBlock().getIdentifier().path().equals("tall_grass")
                || broken.getBlock().getIdentifier().path().equals("dead_bush"))) {
            held.setDamage(held.getDamage() + 1);
            if (held.getDamage() >= de.skyengine.game.world.item.ShearsItem.DURABILITY) {
                this.emitHeldItemParticles(held);
                this.playerInventory.set(this.hotbarIndex, ItemStack.EMPTY);
            }
        }

        this.resetMining();
        return true;
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
        if (this.minecartHit != null) {
            MinecartEntity minecart = this.minecartHit;
            ItemStack held = this.playerInventory.get(this.hotbarIndex);
            boolean pickaxe = held.getItem() instanceof ToolItem tool
                    && tool.getType() == de.skyengine.game.world.item.ToolType.PICKAXE;
            minecart.attack(this.world, this.player.getGamemode() == Gamemode.CREATIVE, pickaxe);
            this.soundManager.playStrongAttack();
            this.stopDestroyBlock();
            endAttack = true;
        } else if (this.itemFrameHit != null) {
            this.itemFrameHit.attack(this.world, this.player.getGamemode() == Gamemode.CREATIVE);
            this.stopDestroyBlock();
            endAttack = true;
        } else if (this.hit != null) {
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

        if (down && (this.itemFrameHit != null || this.minecartHit != null)) {
            this.stopDestroyBlock();
        } else if (down && this.hit != null) {
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
        if (this.minecartHit != null && this.minecartHit.interact(this.player)) return true;
        if (this.itemFrameHit != null
                && this.itemFrameHit.interact(this.world, held,
                this.player.getGamemode() == Gamemode.CREATIVE)) return true;
        /* Ein leerer Eimer kann eine Fluid-Quelle treffen, obwohl der normale Blockstrahl keinen
           Treffer hat. Bei einem Blocktreffer kommt er dagegen erst NACH dessen Interaktion:
           Container öffnen sich in Vanilla mit einem Eimer in der Hand, außer Secondary Use ist
           aktiv. */
        if (this.hit == null) {
            return held.getItem() instanceof BucketItem bucket && this.handleBucket(bucket);
        }

        if (held.getItem() instanceof MinecartItem && this.tryPlaceMinecart()) return true;

        /* Sneaken mit einem Block in der Hand überspringt JEDE Block-Interaktion und platziert
           stattdessen — MCs Regel (`!isSecondaryUseActive() || leere Hand`). Das ist die einzige
           Möglichkeit, eine Truhe an die Seite einer anderen zu setzen oder auf einem Redstone-
           Staub zu bauen, statt ihn umzuschalten. */
        boolean placingWhileSneaking = this.player.isSecondaryUseActive()
                && held.getItem() != null
                && (held.getItem().getPlacedBlock() != null || held.getItem() instanceof ItemFrameItem);

        if (!placingWhileSneaking && this.pendingDimensionSwitch == null) {
            PortalController.Travel travel = this.portalController.use(this.world,
                    this.hit.x(), this.hit.y(), this.hit.z());
            if (travel != null) {
                this.queuePortalTravel(travel);
                return true;
            }
        }

        /* Feuerzeug: der einzige Zündweg für TNT, der über die Hand läuft. Steht VOR der
           Block-Interaktion und bewusst NICHT hinter dem Sneak-Gate — in MC überspringt Sneaken
           nur `useWithoutItem`, `useItemOn` läuft trotzdem. */
        if (held.getItem() instanceof FlintAndSteelItem && this.tryIgnite(held)) return true;

        /* Rechtsklick-Interaktion des getroffenen Blocks (z.B. Tür auf/zu) hat Vorrang. */
        BlockState hitState = Blocks.getState(this.hit.block());
        if (!placingWhileSneaking
                && hitState.getBlock().onUse(this.world, this.hit.x(), this.hit.y(), this.hit.z(),
                        hitState, this.player.yaw)) {
            return true;
        }

        /* Truhe: Rechtsklick öffnet das Truhen-GUI (Deckel geht auf). */
        if (!placingWhileSneaking && this.tryOpenChest()) return true;
        if (!placingWhileSneaking && this.tryOpenHopper()) return true;
        if (!placingWhileSneaking && this.tryOpenDispenser()) return true;

        if (held.getItem() instanceof BucketItem bucket && this.handleBucket(bucket)) return true;

        if (held.getItem() instanceof ItemFrameItem && this.tryPlaceItemFrame()) return true;

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
        if (!this.world.runPlayerBlockChange(() -> this.world.placeBlock(px, py, pz, place))) return false;
        this.soundManager.playPlace(place.getBlock().getSoundGroup(), px + 0.5, py + 0.5, pz + 0.5);
        /* Survival verbraucht den Block (Creative baut unbegrenzt, wie MC). */
        if (this.player.getGamemode() == Gamemode.SURVIVAL) this.consumeHeld(null);
        return true;
    }

    /**
     * Pick Block (nur Creative): legt den anvisierten Block in den ausgewählten Hotbar-Slot.
     */
    private void pickBlock() {
        if (this.player.getGamemode() != Gamemode.CREATIVE) return;
        if (this.minecartHit != null) {
            Item item = Items.get(Identifier.of("skyengine:minecart"));
            if (item != null) this.playerInventory.set(this.hotbarIndex, new ItemStack(item, 1));
            this.itemNameShownAt = System.currentTimeMillis();
            return;
        }
        if (this.itemFrameHit != null) {
            this.playerInventory.set(this.hotbarIndex, this.itemFrameHit.getPickResult());
            this.itemNameShownAt = System.currentTimeMillis();
            return;
        }
        if (this.hit == null) return;
        Block picked = Blocks.getState(this.hit.block()).getBlock();
        Item item = Items.forBlock(picked); // BlockItem bzw. places_block-Item (Staub); null bei Air/Fluid
        if (item != null) {
            this.playerInventory.set(this.hotbarIndex, new ItemStack(item, 1));
            this.itemNameShownAt = System.currentTimeMillis(); // Namens-Einblendung wie bei Slot-Wechsel
        }
    }

    /** ItemFrameItem.useOn: Zielzelle = getroffener Block + getroffene Flaeche, alle sechs Seiten. */
    private boolean tryPlaceItemFrame() {
        Direction direction = directionOf(this.hit.faceX(), this.hit.faceY(), this.hit.faceZ());
        if (direction == null) return false;
        int x = this.hit.x() + direction.offsetX();
        int y = this.hit.y() + direction.offsetY();
        int z = this.hit.z() + direction.offsetZ();
        if (!this.world.isPlayerInteractionReady(x, y, z)) return false;
        if (!this.world.placeItemFrame(x, y, z, direction)) return false;
        if (this.player.getGamemode() == Gamemode.SURVIVAL) this.consumeHeld(null);
        return true;
    }

    private static Direction directionOf(int x, int y, int z) {
        for (Direction direction : Direction.sharedValues()) {
            if (direction.offsetX() == x && direction.offsetY() == y && direction.offsetZ() == z) {
                return direction;
            }
        }
        return null;
    }

    private static double sq(double value) {
        return value * value;
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
        if (!this.world.isPlayerInteractionReady(px, py, pz)
                || !this.isReplaceable(this.world.getBlock(px, py, pz))) return null;
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

        if (!this.world.runPlayerBlockChange(() -> this.world.setBlock(
                this.hit.x(), this.hit.y(), this.hit.z(),
                target.with(Properties.SLAB_TYPE, SlabType.DOUBLE).getId()))) return false;
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

        return true;
    }

    /** Rechtsklick auf einen Trichter öffnet sein GUI (5 Slots + Spielerinventar). */
    private boolean tryOpenHopper() {
        int x = this.hit.x(), y = this.hit.y(), z = this.hit.z();
        BlockEntity be = this.world.getBlockEntity(x, y, z);
        if (!(be instanceof de.skyengine.game.world.block.entity.HopperBlockEntity hopper)) return false;
        this.guiManager.open(new de.skyengine.graphics.gui.screens.GuiHopper(hopper, this.playerInventory));
        return true;
    }

    /** Minecart-Item: nur direkt auf einer Schiene platzieren, Steigungen sitzen einen halben Block höher. */
    private boolean tryPlaceMinecart() {
        BlockState rail = Blocks.getState(this.hit.block());
        if (!rail.getValues().containsKey(Properties.RAIL_SHAPE)
                && !rail.getValues().containsKey(Properties.STRAIGHT_RAIL_SHAPE)) return false;
        double yOffset = de.skyengine.game.world.block.behavior.RailBehavior.shape(rail).isAscending()
                ? 0.5625 : 0.0625;
        this.world.spawnMinecart(this.hit.x() + 0.5, this.hit.y() + yOffset, this.hit.z() + 0.5);
        if (this.player.getGamemode() == Gamemode.SURVIVAL) this.consumeHeld(null);
        return true;
    }

    /** Rechtsklick auf Dispenser oder Dropper öffnet das gemeinsame 9-Slot-GUI. */
    private boolean tryOpenDispenser() {
        BlockEntity blockEntity = this.world.getBlockEntity(this.hit.x(), this.hit.y(), this.hit.z());
        if (!(blockEntity instanceof DispenserBlockEntity dispenser)) return false;
        this.guiManager.open(new GuiDispenser(dispenser, this.playerInventory));
        return true;
    }

    /**
     * Eimer-Interaktion: gefüllt platziert eine Fluid-Quelle, leer nimmt eine Quelle auf.
     * Im Survival wird der Eimer getauscht (gefüllt↔leer), im Creative nicht.
     */
    /**
     * Feuerzeug auf einen Block: zündet ihn, wenn er sprengbar ist (TNT), und verschleißt dabei.
     *
     * <p>Entspricht MCs {@code TntBlock.useItemOn} — nur dass dort der Block das Item prüft und
     * hier der Aufrufer das Behavior sucht, weil {@code Item} keinen Interaktions-Hook hat. Alles
     * andere als TNT lässt das Feuerzeug durchfallen: es gibt hier weder Feuer noch Kerzen noch
     * Lagerfeuer, also bleibt kein zweiter Zündweg übrig.
     *
     * <p>Verschleiß nur im Survival (MCs {@code hurtAndBreak} überspringt Spieler mit
     * unbegrenzten Materialien) und nach demselben Muster wie die Werkzeug-Abnutzung im
     * Abbau-Pfad.
     */
    private boolean tryIgnite(ItemStack held) {
        int[] target = this.placementTarget();
        if (target != null && this.world.runPlayerBlockChange(() ->
                de.skyengine.game.world.dimension.NetherPortalShape.activateNear(
                        this.world, target[0], target[1], target[2]))) {
            if (this.world.getSoundManager() != null) {
                this.world.getSoundManager().playIgnite(target[0] + 0.5, target[1] + 0.5, target[2] + 0.5);
                this.world.getSoundManager().playPortalTrigger(
                        target[0] + 0.5, target[1] + 1.5, target[2] + 0.5);
            }
            this.damageFlintAndSteel(held);
            return true;
        }

        BlockState state = Blocks.getState(this.hit.block());
        de.skyengine.game.world.block.behavior.ExplosionBehavior explosive =
                state.getBlock().getBehavior(de.skyengine.game.world.block.behavior.ExplosionBehavior.class);
        if (explosive == null) return false;

        /* TNT spielt beim Prime bereits seinen Fuse-Sound in World.spawnPrimedTnt. Minecrafts
           TntBlock.useItemOn legt hier keinen zusaetzlichen Feuerzeug-/Ignite-Sound darueber. */
        explosive.prime(this.world, this.hit.x(), this.hit.y(), this.hit.z());
        this.damageFlintAndSteel(held);
        return true;
    }

    private void damageFlintAndSteel(ItemStack held) {
        if (this.player.getGamemode() != Gamemode.SURVIVAL) return;
        held.setDamage(held.getDamage() + 1);
        if (held.getDamage() >= FlintAndSteelItem.DURABILITY) {
            this.emitHeldItemParticles(held);
            this.playerInventory.set(this.hotbarIndex, ItemStack.EMPTY);
        }
    }

    private boolean handleBucket(BucketItem bucket) {
        boolean consume = this.player.getGamemode() == Gamemode.SURVIVAL;

        if (bucket.isEmpty()) {
            /* Aufnehmen: fluid-bewusster Strahl, damit Wasser/Lava als Ziel zählt. Nur eine
               Quelle (LEVEL 0, nicht fallend). */
            BlockRaycast.Hit fhit = BlockRaycast.raycastInteractive(this.world, this.eyePosition,
                    this.eyeDirection, REACH, true);
            if (fhit == null) return false;
            BlockState state = Blocks.getState(fhit.block());
            if (!state.isFluid() || state.get(Properties.FALLING) || state.get(Properties.LEVEL) != 0) return false;
            if (!this.world.runPlayerBlockChange(() ->
                    this.world.setBlock(fhit.x(), fhit.y(), fhit.z(), Blocks.AIR))) return false;
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
        if (this.world.getEnvironment().ultrawarm() && fluid.getFluidInfo() != null
                && !fluid.getFluidInfo().lava) {
            this.world.playFluidExtinguish(t[0], t[1], t[2]);
            if (consume) this.consumeHeld(Items.get(Identifier.of("skyengine:bucket")));
            return true;
        }
        int source = fluid.getDefaultState()
                .with(Properties.LEVEL, 0).with(Properties.FALLING, false).getId();
        if (!this.world.runPlayerBlockChange(() ->
                this.world.setBlock(t[0], t[1], t[2], source))) return false;
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
        if (this.player.getGamemode() == Gamemode.SPECTATOR && scroll != 0) {
            float beforeSpeed = this.player.getSpectatorFlySpeed();
            this.player.adjustSpectatorFlySpeed(scroll);
            float speed = this.player.getSpectatorFlySpeed();
            if (speed != beforeSpeed) {
                this.hudStatusText = I18n.tr("gui.hud.spectator_speed", Math.round(speed * 100F));
                this.hudStatusShownAt = System.currentTimeMillis();
            }
        } else if (scroll > 0) {
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

        if (this.world != null && input.isBindPressed(this.settings.key(KeyBindings.TOGGLE_HUD))) {
            this.hudHidden = !this.hudHidden;
            this.logger.debug("HUD: " + (this.hudHidden ? "aus" : "an"));
        }

        /* F3-Overlay + F3+X-Kombi-Gerüst (Minecraft-Stil): wurde während des Haltens eine Kombi
           benutzt, unterdrückt das den Overlay-Toggle beim Loslassen. Weitere F3+X hier ergänzen. */
        if (input.isKeyDown(GLFW.GLFW_KEY_F3) && !this.guiManager.isOpen()) {
            if (input.consumeKeyPress(GLFW.GLFW_KEY_P)) {
                this.debugOverlay.toggleProfiler();
                this.logger.debug("Profiler: " + (FrameProfiler.isEnabled() ? "an" : "aus"));
                this.f3ComboUsed = true;
            }
            if (input.consumeKeyPress(GLFW.GLFW_KEY_B)) {
                DebugFlags.entityHitboxes = !DebugFlags.entityHitboxes;
                this.logger.debug("Entity-Hitboxen: " + (DebugFlags.entityHitboxes ? "an" : "aus"));
                this.addDebugMessage("chat.debug.entity_hitboxes",
                        DebugFlags.entityHitboxes ? "gui.on" : "gui.off");
                this.f3ComboUsed = true;
            }
            if (input.consumeKeyPress(GLFW.GLFW_KEY_G)) {
                DebugFlags.chunkBorders = (DebugFlags.chunkBorders + 1) % 3;
                this.logger.debug("Chunk-Grenzen: " + switch (DebugFlags.chunkBorders) {
                    case 1 -> "Chunk";
                    case 2 -> "Chunk + Sections";
                    default -> "aus";
                });
                this.addDebugMessage("chat.debug.chunk_borders", switch (DebugFlags.chunkBorders) {
                    case 1 -> "chat.debug.chunk";
                    case 2 -> "chat.debug.chunk_sections";
                    default -> "gui.off";
                });
                this.f3ComboUsed = true;
            }
            if (input.consumeKeyPress(GLFW.GLFW_KEY_V)) {
                DebugFlags.wireframe = !DebugFlags.wireframe;
                this.logger.debug("Wireframe: " + (DebugFlags.wireframe ? "an" : "aus"));
                this.addDebugMessage("chat.debug.wireframe",
                        DebugFlags.wireframe ? "gui.on" : "gui.off");
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

    /** Minecraft-artige lokale Statusmeldung für F3-Debug-Kombinationen. */
    private void addDebugMessage(String messageKey, String valueKey) {
        this.chat.addMessage("§e" + I18n.tr(messageKey, I18n.tr(valueKey)));
    }

    private void openChat(String initial) {
        CommandContext.DimensionAccess dimensions = new CommandContext.DimensionAccess() {
            @Override public Identifier current() {
                return GameContainer.this.world.getDimensionId();
            }

            @Override public List<Identifier> available() {
                WorldgenRegistries.bootstrap();
                return WorldgenRegistries.DIMENSIONS.values().stream()
                        .map(DimensionDefinition::id).toList();
            }

            @Override public boolean request(Identifier target) {
                return GameContainer.this.requestDimensionChange(target);
            }
        };
        this.guiManager.open(new GuiChat(this.chat, new CommandContext(this.playerInventory, dimensions),
                this.chatHud, initial));
    }

    /** Sichtweite der Projektion: mit LOD hinter den äußersten Ring gelegt, sonst wie bisher 1500. */
    private float computeFarPlane() {
        if (!this.settings.lodEnabled || (this.world != null && !this.world.isLodAllowed())) {
            return 1500.0F;
        }
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

    /** Zeigt das Ergebnis des nach dem GUI aufgenommenen F2-Screenshots im Chat an. */
    public void notifyScreenshotResult(File screenshot) {
        if (screenshot == null) {
            this.chat.addMessage("§c" + I18n.tr("chat.screenshot_failed"));
            return;
        }
        RichText message = RichText.of(List.of(
                new Span(I18n.tr("chat.screenshot_saved"), FontStyle.REGULAR, null),
                new Span(screenshot.getName(), FontStyle.REGULAR, TextColors.parse("aqua"))));
        this.chat.addMessage(message, 1, screenshot.toPath());
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

    /**
     * Startet GUI-Aktionen, die erst nach einem praesentierten Zwischenframe laufen duerfen.
     * Wird pro Loop (auch im Hauptmenue, wo es keine Welt-Ticks gibt) auf dem Render-Thread
     * aufgerufen.
     */
    public void processDeferredGuiActions() {
        if (this.guiManager.current() instanceof GuiResourcePackLoading loading
                && loading.isReadyToReload()) {
            loading.runReload(this, this.guiManager);
        }
    }

    /**
     * Aktiviert eine neue Pack-Reihenfolge auf dem Render-Thread. {@code null} bedeutet Erfolg,
     * andernfalls wird der alte Stack vollstaendig wiederhergestellt und die Meldung geliefert.
     */
    public String reloadResourcePacks(List<String> requested) {
        return this.reloadResourcePacks(requested, (stage, progress) -> {});
    }

    public String reloadResourcePacks(List<String> requested, ResourceReloadProgress progress) {
        List<String> next = requested == null ? List.of() : List.copyOf(requested);
        var repository = Resources.repository();
        progress.frame(I18n.tr("resourcepacks.loading.prepare"), 0.05F);
        repository.refresh();
        for (String name : next) {
            ResourcePack pack = repository.get(name);
            if (pack == null) return "Paket nicht gefunden: " + name;
            if (!pack.valid()) return pack.displayName() + ": " + pack.error();
        }

        List<String> previous = Resources.get().activePackNames();
        try {
            this.applyResourceStack(next, progress);
            this.settings.resourcePacks = new ArrayList<>(next);
            this.settings.save();
            progress.frame(I18n.tr("resourcepacks.loading.done"), 1F);
            return null;
        } catch (Throwable error) {
            this.logger.error("Ressourcenpakete konnten nicht geladen werden; Rollback", error);
            try {
                progress.frame(I18n.tr("resourcepacks.loading.rollback"), 0.05F);
                this.applyResourceStack(previous, progress);
            } catch (Throwable rollback) {
                this.logger.error("Ressourcenpaket-Rollback fehlgeschlagen", rollback);
            }
            String message = error.getMessage();
            return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
        }
    }

    private void applyResourceStack(List<String> packs, ResourceReloadProgress progress) {
        progress.frame(I18n.tr("resourcepacks.loading.models"), 0.15F);
        Resources.activate(packs);
        Blocks.reloadVisuals();

        progress.frame(I18n.tr("resourcepacks.loading.textures"), 0.35F);
        this.atlas.reload();

        /* Renderer mit eigenem Textur-/Modellcache in sicherer Reihenfolge erneuern. */
        progress.frame(I18n.tr("resourcepacks.loading.renderers"), 0.58F);
        this.heldItemMeshes.dispose();
        this.playerRenderer.dispose();
        this.blockEntityRenderers.dispose();
        this.blockEntityRenderers.init();
        this.playerRenderer.init();
        this.heldItemMeshes.init(this.atlas.textures(), this.blockEntityRenderers);
        if (this.world != null) {
            progress.frame(I18n.tr("resourcepacks.loading.world"), 0.70F);
            this.world.reloadEntityRenderer();
            this.world.getChunkManager().remeshAll();
        }

        progress.frame(I18n.tr("resourcepacks.loading.audio"), 0.82F);
        this.soundManager.reloadResources();
        this.applyAudioSettings();
        this.soundManager.startMusicPlaylist();

        progress.frame(I18n.tr("resourcepacks.loading.interface"), 0.92F);
        I18n.load(this.settings.language);
        if (this.guiManager != null) {
            this.guiManager.reloadAssets(this.atlas.textures(), this.blockEntityRenderers);
        }
    }

    @FunctionalInterface
    public interface ResourceReloadProgress {
        void frame(String stage, float progress);
    }
}
