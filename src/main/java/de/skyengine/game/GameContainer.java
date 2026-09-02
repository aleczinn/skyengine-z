package de.skyengine.game;

import de.skyengine.client.network.ClientMultiplayerConnection;
import de.skyengine.client.network.ClientPredictionController;
import de.skyengine.client.network.ServerAddress;
import de.skyengine.client.world.RemoteWorldView;
import de.skyengine.core.EngineConfig;
import de.skyengine.core.SkyEngine;
import de.skyengine.core.file.Files;
import de.skyengine.core.resource.ResourcePack;
import de.skyengine.core.resource.Resources;
import de.skyengine.core.input.Input;
import de.skyengine.core.io.*;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.entity.PlayerControls;
import de.skyengine.game.entity.ItemEntity;
import de.skyengine.game.entity.ItemFrameEntity;
import de.skyengine.game.entity.MinecartEntity;
import de.skyengine.game.entity.Entity;
import de.skyengine.game.entity.FallingBlockEntity;
import de.skyengine.game.entity.PrimedTntEntity;
import de.skyengine.game.command.ChatManager;
import de.skyengine.game.command.CommandContext;
import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.BlockRaycast;
import de.skyengine.game.world.block.BlockTextures;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.DimensionManager;
import de.skyengine.game.world.World;
import de.skyengine.game.world.PlayerLocation;
import de.skyengine.game.world.dimension.PortalController;
import de.skyengine.game.world.dimension.PortalCoordinates;
import de.skyengine.game.world.dimension.PortalDefinition;
import de.skyengine.game.world.dimension.PortalIndex;
import de.skyengine.game.world.dimension.PortalLinks;
import de.skyengine.game.world.dimension.DimensionDefinition;
import de.skyengine.game.world.dimension.WorldgenRegistries;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.ChestBlockEntity;
import de.skyengine.game.world.block.entity.DispenserBlockEntity;
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
import de.skyengine.game.world.chunk.ChunkMesher;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.game.world.chunk.ChunkStatus;
import de.skyengine.game.world.chunk.FluidGeometry;
import de.skyengine.graphics.DebugFlags;
import de.skyengine.graphics.FrameProfiler;
import de.skyengine.graphics.PerformanceProfiler;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.camera.ZoomController;
import de.skyengine.audio.SoundCategory;
import de.skyengine.audio.SoundManager;
import de.skyengine.audio.BlockSoundGroup;
import de.skyengine.audio.BlockOpenSound;
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
import de.skyengine.graphics.gui.screens.GuiCraftingStation;
import de.skyengine.graphics.gui.screens.GuiFurnace;
import de.skyengine.graphics.gui.DebugOverlay;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.BlockEntityMenus;
import de.skyengine.graphics.gui.SaveToast;
import de.skyengine.graphics.gui.SpriteRenderer;
import de.skyengine.graphics.gui.font.FontStyle;
import de.skyengine.graphics.gui.screens.GuiIngameMenu;
import de.skyengine.graphics.gui.screens.GuiDeathScreen;
import de.skyengine.graphics.gui.screens.GuiMainMenu;
import de.skyengine.graphics.gui.screens.GuiResourcePackLoading;
import de.skyengine.graphics.gui.screens.GuiWorldLoading;
import de.skyengine.graphics.gui.screens.GuiConnecting;
import de.skyengine.graphics.gui.screens.GuiDisconnected;
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
import de.skyengine.game.world.save.LevelData;
import de.skyengine.game.world.save.WorldSaves;
import de.skyengine.game.world.structure.StructurePlacement;
import de.skyengine.game.world.structure.StructureTemplate;
import de.skyengine.game.world.structure.StructureTransform;
import de.skyengine.game.world.structure.WorldEditSelection;
import de.skyengine.game.world.structure.WorldEditSession;
import de.skyengine.game.world.generator.biome.Biome;
import de.skyengine.game.world.generator.biome.BiomeLocator;
import de.skyengine.game.world.generator.biome.Biomes;
import org.lwjgl.opengl.GL11;
import de.skyengine.graphics.post.PostProcessor;
import de.skyengine.graphics.world.ChunkBorderRenderer;
import de.skyengine.graphics.world.SelectionBoxRenderer;
import de.skyengine.graphics.world.DimensionView;
import de.skyengine.graphics.world.StructurePreviewRenderer;
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
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;

import de.skyengine.game.GameplaySession.PendingArrival;
import de.skyengine.game.GameplaySession.PendingDimensionSwitch;
import de.skyengine.shared.player.PlayerInputFrame;
import de.skyengine.shared.player.PlayerStateSnapshot;
import de.skyengine.shared.player.PlayerMovementState;
import de.skyengine.shared.player.PlayerGameMode;
import de.skyengine.shared.entity.NetworkEntityTypes;
import de.skyengine.shared.entity.NetworkEntitySnapshot;
import de.skyengine.shared.gameplay.NetworkItemStack;
import de.skyengine.shared.network.packets.CorePackets;
import de.skyengine.game.world.save.DataTagIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/* Kein IInitializable mehr: der Boot läuft zweistufig über initBoot()/initStaged() (Ladebildschirm). */
public class GameContainer implements IResizeable, IDisposable {

    private final Logger logger = LogManager.getLogger(GameContainer.class.getName());

    private Camera camera;
    private GameplaySession session;
    private final ClientMultiplayerConnection multiplayer = new ClientMultiplayerConnection();
    private RemoteWorldView remoteWorldView;
    private EntityPlayer remotePlayer;
    private int remoteInventoryRevision = Integer.MIN_VALUE;
    private CorePackets.ContainerOpen remoteOpenContainer;
    private de.skyengine.game.world.block.entity.SimpleItemStorage remoteContainerInventory;
    private long remoteActionSequence;
    private long remoteCommandSequence;
    private float remoteYaw, remotePitch;
    private boolean remoteRotationInitialized;
    private long remoteInputSequence;
    private long remoteClientTick;
    private PlayerStateSnapshot remoteLastAuthoritativeState;
    private long remoteLastSpacePressTime;
    private int remoteSpectatorSpeedDelta;
    private boolean remoteRespawnPending;
    /** Client-only portal contact progress; authoritative travel remains on the server. */
    private final PortalController remotePortalPresentation = new PortalController();
    private ClientPredictionController remotePrediction;
    private PlayerStateSnapshot remotePreviousPredicted;
    private PlayerStateSnapshot remoteCurrentPredicted;
    private double remoteCorrectionX, remoteCorrectionY, remoteCorrectionZ;
    private long remoteCorrectionFrameNanos;
    private static final class RemotePlayerVisual {
        final EntityPlayer player;
        final PlayerAnimationState animation = new PlayerAnimationState();
        ItemStack held = ItemStack.EMPTY;
        long revision = -1;

        RemotePlayerVisual(java.util.UUID identity) { this.player = new EntityPlayer(identity); }
    }
    private final java.util.Map<Integer, RemotePlayerVisual> remotePlayerVisuals = new java.util.HashMap<>();
    private static final class RemoteEntityVisual {
        Entity entity;
        long revision = -1;

        RemoteEntityVisual(Entity entity) { this.entity = entity; }
    }
    private final java.util.Map<Integer, RemoteEntityVisual> remoteEntityVisuals = new java.util.HashMap<>();
    private final java.util.List<Entity> remoteRenderedEntities = new java.util.ArrayList<>();
    private final java.util.List<Entity> remoteDebugEntities = new java.util.ArrayList<>();
    private int remoteEntityHitId;
    private Entity remoteEntityHit;
    private SelectionBoxRenderer selectionBoxRenderer;
    private ChunkBorderRenderer chunkBorderRenderer;
    private EntityHitboxRenderer entityHitboxRenderer;
    private CrackRenderer crackRenderer;
    private StructurePreviewRenderer structurePreviewRenderer;
    private GuiManager guiManager;

    /* Welt-unabhängige Engine-Ressourcen (Boot-Init, leben bis zum Exit): Welt-Ein-/Austritte
       erzeugen sie nicht neu — Layer-Indizes des Atlas stecken in den gebackenen Modellen. */
    private final BlockTextureAtlas atlas = new BlockTextureAtlas();
    private final BlockEntityRenderDispatcher blockEntityRenderers = new BlockEntityRenderDispatcher();

    private final GameSettings settings = GameSettings.get();

    /* TAA-Jitter des Frames (wiederverwendet, s. renderWorld) */
    private final Vector2f taaJitter = new Vector2f();
    /* Gehaltener First-Person-Zoom: pro Frame animiert, unabhängig vom 20-TPS-Takt. */
    private final ZoomController zoomController = new ZoomController();
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
    /** Effektiver (auch im Pausemenue eingefrorener) Partial-Tick des zuletzt gerenderten Weltframes. */
    private float renderedPartialTick = 0F;

    private BlockRaycast.Hit hit = null;
    /** Entity-Treffer nur, wenn er vor dem naechsten Block auf demselben Augenstrahl liegt. */
    private ItemFrameEntity itemFrameHit = null;
    private MinecartEntity minecartHit = null;

    private final ChatManager chat = new ChatManager();
    private final ChatHud chatHud = new ChatHud();
    private CompletableFuture<BiomeLocator.Result> biomeSearch;
    private int biomeSearchGeneration;
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
        this.structurePreviewRenderer = new StructurePreviewRenderer();
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
        Blocks.bootstrap(Resources.defaultGameRoot().resolve("blocks").toFile());
        BlockEntityMenus.clear();
        BlockEntityMenus.register(BlockEntities.FURNACE, GuiFurnace::new);
        BlockEntityMenus.register(BlockEntities.BASIC_ENERGY_CUBE,
                de.skyengine.graphics.gui.screens.GuiEnergyCube::new);
        BlockEntityMenus.register(BlockEntities.COAL_GENERATOR,
                de.skyengine.graphics.gui.screens.GuiCoalGenerator::new);
        ParticleSprites.bootstrap();

        progress.frame(I18n.tr("boot.textures"), 0.45f);
        this.atlas.init();

        progress.frame(I18n.tr("boot.renderer"), 0.65f);
        this.camera.setInverseDepth(SkyEngine.get().getWindow().getProperties().isUseInverseDepth());
        this.selectionBoxRenderer.init();
        this.chunkBorderRenderer.init();
        this.entityHitboxRenderer.init();
        this.crackRenderer.init(this.atlas.textures());
        this.structurePreviewRenderer.init(this.atlas.textures());
        this.blockEntityRenderers.register(BlockEntities.CHEST, new ChestRenderer());
        this.blockEntityRenderers.register(BlockEntities.ENCHANTING_TABLE, new EnchantingTableRenderer());
        this.blockEntityRenderers.register(BlockEntities.PISTON_MOVING,
                new de.skyengine.graphics.blockentity.PistonMovingRenderer(this.atlas.textures()));
        this.blockEntityRenderers.register(BlockEntities.BASIC_ENERGY_CUBE,
                new de.skyengine.graphics.blockentity.EnergyCubeRenderer(this.atlas.textures()));
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
     * Dimension + Spieler auf (Position/Inventar aus level.json, sonst Spawn + Start-Inventar),
     * wendet die Welt-Einstellungen an und zeigt den Welt-Ladebildschirm.
     */
    public void enterWorld(WorldSaves.WorldSave save) {
        this.multiplayer.disconnect();
        this.closeRemoteWorldView();
        this.cancelBiomeSearch();
        this.waterVision.reset();
        this.playerWasInWater = false;
        this.underwaterAudio.reset();
        this.session = new GameplaySession(save, this.atlas, this.blockEntityRenderers,
                this.soundManager);
        this.session.portalController.reset();
        this.session.pendingDimensionSwitch = null;
        this.session.pendingArrival = null;

        this.hudStatusText = "";
        this.hudStatusShownAt = 0;
        this.animState.reset();
        this.perspective = CameraPerspective.FIRST_PERSON;

        this.applySettings(); // Welt-Anteile (Render-/Sim-Distanz, farPlane) greifen jetzt

        this.guiManager.open(new GuiWorldLoading());
    }

    /** Starts a remote connection without blocking the render or tick thread. */
    public void connectToServer(ServerAddress address) {
        if (this.session != null) throw new IllegalStateException("Leave the current world before connecting");
        this.closeRemoteWorldView();
        this.multiplayer.connect(address, System.getProperty("user.name", "Player"));
    }

    public void disconnectFromServer() {
        this.multiplayer.disconnect();
        this.closeRemoteWorldView();
    }

    public ClientMultiplayerConnection getMultiplayerConnection() {
        return this.multiplayer;
    }

    private void updateRemoteWorldLifecycle(Input input) {
        if (this.session != null) return;
        ClientMultiplayerConnection.Phase phase = this.multiplayer.phase();
        if (phase == ClientMultiplayerConnection.Phase.PLAY && this.remoteWorldView == null
                && this.multiplayer.joinGame() != null && this.multiplayer.playerState() != null
                && this.multiplayer.chunks() != null) {
            this.remoteWorldView = new RemoteWorldView(this.multiplayer.joinGame().dimension(),
                    this.multiplayer.chunks(), this.atlas, this.blockEntityRenderers);
            PlayerStateSnapshot state = this.multiplayer.playerState();
            this.remotePlayer = new EntityPlayer(this.multiplayer.joinGame().identity());
            this.applyRemotePlayerState(state);
            this.remoteWorldView.setPhysicsPlayer(this.remotePlayer);
            this.syncRemoteInventory();
            this.remoteYaw = state.yaw();
            this.remotePitch = state.pitch();
            this.remoteRotationInitialized = true;
            this.remoteInputSequence = state.lastProcessedInputSequence();
            this.remoteClientTick = 0;
            this.remoteLastAuthoritativeState = state;
            this.remotePrediction = new ClientPredictionController(state, this::simulateRemoteInput);
            this.remotePreviousPredicted = state;
            this.remoteCurrentPredicted = state;
            this.remoteCorrectionX = this.remoteCorrectionY = this.remoteCorrectionZ = 0;
            this.remoteCorrectionFrameNanos = System.nanoTime();
            this.remotePortalPresentation.reset();
        }
        this.rebuildRemoteWorldForDimension();
        this.reconcileRemotePlayerState();
        if (this.remotePlayer != null && this.remotePlayer.isDead()
                && !this.remoteRespawnPending
                && !(this.guiManager.current() instanceof GuiDeathScreen)) {
            this.guiManager.open(new GuiDeathScreen());
        }
        if (this.remotePlayer != null && !this.remotePlayer.isDead()) this.remoteRespawnPending = false;
        this.multiplayer.drainOpenedContainers(this::openRemoteContainer);
        this.multiplayer.drainClosedContainers(this::serverClosedRemoteContainer);
        this.multiplayer.drainWorldSounds(this::playRemoteWorldSound);
        this.multiplayer.drainEntityEvents(this::playRemoteEntityEvent);
        this.syncRemoteInventory();
        this.syncRemotePlayerVisuals();
        this.syncRemoteEntityVisuals();
        if (this.remotePlayer != null) {
            if (this.rightClickDelay > 0) this.rightClickDelay--;
            this.animState.tickHeldItem(this.remotePlayer.getInventory().get(this.remotePlayer.getSelectedSlot()));
            this.animState.tick(this.remotePlayer);
            this.updateRemotePresentation(input);
        }
        if (this.remoteWorldView != null) {
            this.remoteWorldView.tickVisualEffects();
        }
        if (this.remoteWorldView != null && this.remoteWorldView.hasRenderableChunks()
                && this.guiManager.current() instanceof GuiConnecting) {
            this.guiManager.close();
        }
        if (this.remoteWorldView != null && (phase == ClientMultiplayerConnection.Phase.DISCONNECTED
                || phase == ClientMultiplayerConnection.Phase.FAILED)) {
            boolean connectingScreen = this.guiManager.current() instanceof GuiConnecting;
            this.closeRemoteWorldView();
            if (!connectingScreen) {
                String message = this.multiplayer.detail();
                this.guiManager.open(new GuiDisconnected(new GuiMainMenu(),
                        I18n.tr("multiplayer.connection_failed"), message));
            }
        }
        if (phase == ClientMultiplayerConnection.Phase.PLAY && this.remoteWorldView != null
                && this.multiplayer.session() != null) {
            this.sendRemoteInput(input);
        }
    }

    private void updateRemotePresentation(Input input) {
        Dimension visual = this.visualDimension();
        if (visual == null || this.remotePlayer == null) return;
        this.updateMovementParticles();
        boolean eyesUnderwater = this.playerEyesUnderwater();
        this.waterVision.tick(eyesUnderwater);
        this.underwaterAudio.tick(eyesUnderwater, this.remotePlayer.isTouchingWater(visual),
                !this.remotePlayer.isFlying(),
                this.remotePlayer.x - this.remotePlayer.lastX,
                this.remotePlayer.y - this.remotePlayer.lastY,
                this.remotePlayer.z - this.remotePlayer.lastZ);
        this.updateStepSounds();
        this.updateRemoteEatingAnimation(input);
        // Mirror only the visual distortion curve. A returned Travel is deliberately ignored:
        // the server owns portal activation, destination selection and dimension changes.
        this.remotePortalPresentation.tick(visual, this.remotePlayer);
    }

    private void updateRemoteEatingAnimation(Input input) {
        ItemStack held = this.remotePlayer.getInventory().get(this.remotePlayer.getSelectedSlot());
        if (this.guiManager.isOpen() || this.remotePlayer.getGamemode() != Gamemode.SURVIVAL
                || this.remotePlayer.isDead() || !(held.getItem() instanceof FoodItem)
                || this.remotePlayer.getFoodLevel() >= EntityPlayer.MAX_FOOD
                || !input.isBindDown(this.settings.key(KeyBindings.USE))) {
            this.eatingTicks = 0;
            this.animState.clearEating();
            return;
        }
        if (++this.eatingTicks >= EAT_TICKS) {
            this.eatingTicks = 0;
            this.animState.clearEating();
            this.soundManager.playBurp();
        } else {
            this.animState.setEating(EAT_TICKS - this.eatingTicks);
            if (this.eatingTicks > 7 && this.eatingTicks % 4 == 0) {
                this.soundManager.playEat();
                this.emitHeldItemParticles(held);
            }
        }
    }

    /** Replaces only the replicated render/physics view when the authoritative server changes dimension. */
    private void rebuildRemoteWorldForDimension() {
        PlayerStateSnapshot state = this.multiplayer.playerState();
        if (state == null || this.remoteWorldView == null
                || state.dimension().equals(this.remoteWorldView.dimension())) return;
        GL11.glFinish();
        this.soundManager.stopMinecartSounds();
        this.remoteWorldView.close();
        this.remoteWorldView = new RemoteWorldView(state.dimension(), this.multiplayer.chunks(), this.atlas,
                this.blockEntityRenderers);
        if (this.remotePlayer != null) {
            this.applyRemotePlayerState(state);
            this.remoteWorldView.setPhysicsPlayer(this.remotePlayer);
        }
        this.remotePlayerVisuals.clear();
        this.remoteEntityVisuals.clear();
        this.remoteRenderedEntities.clear();
        this.remoteCorrectionX = this.remoteCorrectionY = this.remoteCorrectionZ = 0;
        this.remotePortalPresentation.lockUntilExit();
    }

    /** Sends intent only; position, velocity and collision remain authoritative on the server. */
    private void sendRemoteInput(Input input) {
        boolean acceptsInput = !this.guiManager.isOpen();
        float forward = 0F;
        float strafe = 0F;
        int buttons = 0;
        if (acceptsInput) {
            if (input.isBindDown(this.settings.key(KeyBindings.FORWARD))) forward += 1F;
            if (input.isBindDown(this.settings.key(KeyBindings.BACK))) forward -= 1F;
            if (input.isBindDown(this.settings.key(KeyBindings.RIGHT))) strafe += 1F;
            if (input.isBindDown(this.settings.key(KeyBindings.LEFT))) strafe -= 1F;
            if (input.isBindDown(this.settings.key(KeyBindings.JUMP))) buttons |= PlayerInputFrame.JUMP;
            if (input.isBindDown(this.settings.key(KeyBindings.SNEAK))) buttons |= PlayerInputFrame.SNEAK;
            if (input.isBindDown(this.settings.key(KeyBindings.SPRINT))) buttons |= PlayerInputFrame.SPRINT;
            if (input.isBindDown(this.settings.key(KeyBindings.USE))) buttons |= PlayerInputFrame.USE;
            if (input.isBindDown(this.settings.key(KeyBindings.ATTACK))) buttons |= PlayerInputFrame.ATTACK;
            if (input.consumeBindPress(this.settings.key(KeyBindings.GAMEMODE))) {
                buttons |= PlayerInputFrame.CYCLE_GAME_MODE;
            }
            if (input.consumeBindPress(this.settings.key(KeyBindings.JUMP))) {
                long now = System.currentTimeMillis();
                if (now - this.remoteLastSpacePressTime <= DOUBLE_TAP_MS) {
                    buttons |= PlayerInputFrame.TOGGLE_FLY;
                    this.remoteLastSpacePressTime = 0;
                } else {
                    this.remoteLastSpacePressTime = now;
                }
            }
            if (this.settings.sneakToggle) buttons |= PlayerInputFrame.SNEAK_TOGGLE_MODE;
            if (this.settings.sprintToggle) buttons |= PlayerInputFrame.SPRINT_TOGGLE_MODE;
            if (this.remoteSpectatorSpeedDelta > 0) {
                buttons |= PlayerInputFrame.SPECTATOR_SPEED_UP;
                this.remoteSpectatorSpeedDelta--;
            } else if (this.remoteSpectatorSpeedDelta < 0) {
                buttons |= PlayerInputFrame.SPECTATOR_SPEED_DOWN;
                this.remoteSpectatorSpeedDelta++;
            }
        }
        PlayerInputFrame frame = new PlayerInputFrame(++this.remoteInputSequence,
                this.remoteClientTick++, forward, strafe, this.remoteYaw, this.remotePitch, buttons,
                this.remotePlayer == null ? 0 : this.remotePlayer.getSelectedSlot());
        if (this.remotePrediction != null) {
            this.remotePreviousPredicted = this.remoteCurrentPredicted;
            this.remoteCurrentPredicted = this.remotePrediction.submit(frame);
            this.applyRemotePlayerState(this.remoteCurrentPredicted);
        }
        this.multiplayer.session().sendInput(frame);
        this.sendRemoteWorldActions(input);
    }

    private void sendRemoteWorldActions(Input input) {
        if (this.remotePlayer == null || this.guiManager.isOpen()
                || !this.remotePlayer.getGamemode().interactsWithWorld()) {
            this.cancelRemoteBreaking();
            return;
        }
        if (input.isBindPressed(this.settings.key(KeyBindings.DROP))) {
            ItemStack selected = this.remotePlayer.getInventory().get(this.remotePlayer.getSelectedSlot());
            if (!selected.isEmpty()) {
                this.sendRemoteInventoryAction(this.remotePlayer.getSelectedSlot(), -1,
                        de.skyengine.shared.gameplay.InventoryActionRequest.Action.DROP,
                        input.isCtrlDown() ? 1 : 0, ItemStack.EMPTY);
                this.animState.swing();
            }
        }
        if (input.isBindPressed(this.settings.key(KeyBindings.PICK_BLOCK))
                && this.remotePlayer.getGamemode() == Gamemode.CREATIVE) {
            if (this.remoteEntityHitId != 0) {
                this.sendRemoteEntityAction(de.skyengine.shared.gameplay.EntityActionRequest.Action.PICK);
            } else if (this.hit != null) {
                Item picked = Items.forBlock(Blocks.getState(this.hit.block()).getBlock());
                if (picked != null) {
                    ItemStack stack = new ItemStack(picked, picked.getMaxStackSize());
                    this.sendRemoteInventoryAction(-3, this.remotePlayer.getSelectedSlot(),
                            de.skyengine.shared.gameplay.InventoryActionRequest.Action.CLONE, 0, stack);
                    this.itemNameShownAt = System.currentTimeMillis();
                }
            }
        }
        boolean attack = input.isBindDown(this.settings.key(KeyBindings.ATTACK));
        if (this.remoteEntityHitId != 0) {
            this.cancelRemoteBreaking();
            if (input.isBindPressed(this.settings.key(KeyBindings.ATTACK))) {
                this.sendRemoteEntityAction(de.skyengine.shared.gameplay.EntityActionRequest.Action.ATTACK);
                this.animState.swing();
            }
        } else if (!attack || this.hit == null) {
            this.cancelRemoteBreaking();
        } else if (!this.isDestroying || !this.sameDestroyTarget()) {
            this.cancelRemoteBreaking();
            this.isDestroying = true;
            this.miningX = this.hit.x(); this.miningY = this.hit.y(); this.miningZ = this.hit.z();
            this.miningProgress = 0F;
            this.sendRemoteBlockAction(de.skyengine.shared.gameplay.BlockActionRequest.Action.START_BREAK);
            this.animState.swing();
            if (this.remotePlayer.getGamemode().isInstantBreak()) this.resetMining();
        } else {
            BlockState state = Blocks.getState(this.hit.block());
            this.miningProgress += de.skyengine.game.world.PlayerBlockActions.destroyProgress(
                    this.remotePlayer, state);
            this.destroyTicks++;
            if (this.miningProgress >= 1F) {
                this.sendRemoteBlockAction(de.skyengine.shared.gameplay.BlockActionRequest.Action.FINISH_BREAK);
                this.animState.swing();
                this.resetMining();
            }
        }
        if (input.isBindDown(this.settings.key(KeyBindings.USE))
                && this.rightClickDelay == 0
                && !(this.remotePlayer.getInventory().get(this.remotePlayer.getSelectedSlot()).getItem()
                instanceof FoodItem && this.remotePlayer.getFoodLevel() < EntityPlayer.MAX_FOOD)) {
            this.rightClickDelay = RIGHT_CLICK_DELAY;
            boolean sent = this.remoteEntityHitId != 0
                    ? this.sendRemoteEntityAction(de.skyengine.shared.gameplay.EntityActionRequest.Action.INTERACT)
                    : this.sendRemoteBlockAction(de.skyengine.shared.gameplay.BlockActionRequest.Action.PLACE);
            if (sent) {
                this.animState.swing();
            }
        }
    }

    private void cancelRemoteBreaking() {
        if (!this.isDestroying || this.multiplayer.session() == null || this.hit == null) {
            this.resetMining();
            return;
        }
        this.sendRemoteBlockAction(de.skyengine.shared.gameplay.BlockActionRequest.Action.CANCEL_BREAK);
        this.resetMining();
    }

    private boolean sendRemoteBlockAction(de.skyengine.shared.gameplay.BlockActionRequest.Action action) {
        if (this.multiplayer.playerState() == null) return false;
        BlockRaycast.Hit actionHit = this.hit;
        ItemStack held = this.remotePlayer.getInventory().get(this.remotePlayer.getSelectedSlot());
        if (action == de.skyengine.shared.gameplay.BlockActionRequest.Action.PLACE
                && held.getItem() instanceof BucketItem bucket && bucket.isEmpty()) {
            actionHit = BlockRaycast.raycastInteractive(this.remoteWorldView.blockAccess(),
                    this.eyePosition, this.eyeDirection, REACH, true);
        }
        if (actionHit == null) return false;
        Direction face = directionOf(actionHit.faceX(), actionHit.faceY(), actionHit.faceZ());
        if (face == null) return false;
        int requestedState = -1;
        if (!held.isEmpty() && held.getItem().getPlacedBlock() != null) {
            requestedState = this.multiplayer.blockStateToNetwork(
                    held.getItem().getPlacedBlock().getDefaultState().getId());
        }
        int targetX = actionHit.x(), targetY = actionHit.y(), targetZ = actionHit.z();
        if (!Blocks.getState(actionHit.block()).getBlock().isReplaceable()) {
            targetX += face.offsetX(); targetY += face.offsetY(); targetZ += face.offsetZ();
        }
        int hitX = quantizeHit(actionHit.hitX() - targetX);
        int hitY = quantizeHit(actionHit.hitY() - targetY);
        int hitZ = quantizeHit(actionHit.hitZ() - targetZ);
        this.multiplayer.session().sendBlockAction(new de.skyengine.shared.gameplay.BlockActionRequest(
                ++this.remoteActionSequence, action, this.multiplayer.playerState().dimension(),
                actionHit.x(), actionHit.y(), actionHit.z(), face.faceIndex(), 0,
                this.multiplayer.blockStateToNetwork(actionHit.block()), requestedState,
                hitX, hitY, hitZ, this.remotePlayer.isSecondaryUseActive()));
        var particles = this.remoteWorldView.physicsDimension().particles();
        if (action == de.skyengine.shared.gameplay.BlockActionRequest.Action.START_BREAK) {
            particles.blockHit(Blocks.getState(actionHit.block()), actionHit.hitX(), actionHit.hitY(),
                    actionHit.hitZ(), actionHit.faceX(), actionHit.faceY(), actionHit.faceZ());
        } else if (action == de.skyengine.shared.gameplay.BlockActionRequest.Action.FINISH_BREAK) {
            particles.blockBreak(actionHit.x(), actionHit.y(), actionHit.z(),
                    Blocks.getState(actionHit.block()));
        }
        return true;
    }

    private boolean sendRemoteEntityAction(de.skyengine.shared.gameplay.EntityActionRequest.Action action) {
        if (this.multiplayer.session() == null || this.remoteEntityHitId == 0) return false;
        this.multiplayer.session().sendEntityAction(new de.skyengine.shared.gameplay.EntityActionRequest(
                ++this.remoteActionSequence, action, this.remoteEntityHitId));
        return true;
    }

    private static int quantizeHit(double value) {
        return (int) Math.round(Math.clamp(value, 0.0, 1.0) * 255.0);
    }

    private void reconcileRemotePlayerState() {
        PlayerStateSnapshot authoritative = this.multiplayer.playerState();
        if (authoritative == null || authoritative == this.remoteLastAuthoritativeState) return;
        this.remoteLastAuthoritativeState = authoritative;
        if (this.remotePrediction == null) {
            this.remotePrediction = new ClientPredictionController(authoritative, this::simulateRemoteInput);
            this.remotePreviousPredicted = this.remoteCurrentPredicted = authoritative;
            this.applyRemotePlayerState(authoritative);
            return;
        }
        PlayerStateSnapshot before = this.remotePrediction.predicted();
        ClientPredictionController.Reconciliation correction = this.remotePrediction.reconcile(authoritative);
        PlayerStateSnapshot predicted = this.remotePrediction.predicted();
        this.remotePreviousPredicted = predicted;
        this.remoteCurrentPredicted = predicted;
        if (correction.hardCorrection()) {
            this.remoteCorrectionX = this.remoteCorrectionY = this.remoteCorrectionZ = 0;
        } else {
            this.remoteCorrectionX += before.x() - predicted.x();
            this.remoteCorrectionY += before.y() - predicted.y();
            this.remoteCorrectionZ += before.z() - predicted.z();
        }
        this.applyRemotePlayerState(predicted);
    }

    /** Runs the same EntityPlayer movement and block-shape collision code as the server. */
    private PlayerStateSnapshot simulateRemoteInput(PlayerStateSnapshot state, PlayerInputFrame input) {
        if (state.vehicleEntityId() != 0) {
            return new PlayerStateSnapshot(state.serverTick() + 1, input.sequence(), state.dimension(),
                    state.x(), state.y(), state.z(), state.velocityX(), state.velocityY(), state.velocityZ(),
                    input.yaw(), input.pitch(), state.grounded(), state.gameMode(), state.movementState(),
                    state.health(), state.foodLevel(), state.saturation(), input.selectedHotbarSlot(),
                    state.vehicleEntityId(), state.spectatorFlySpeed());
        }
        if (this.remoteWorldView == null || !this.remoteWorldView.isPhysicsAreaReady(state.x(), state.z())) {
            return new PlayerStateSnapshot(state.serverTick() + 1, input.sequence(), state.dimension(),
                    state.x(), state.y(), state.z(), 0, 0, 0, input.yaw(), input.pitch(),
                    state.grounded(), state.gameMode(), state.movementState(), state.health(),
                    state.foodLevel(), state.saturation(), input.selectedHotbarSlot(), state.vehicleEntityId(),
                    state.spectatorFlySpeed());
        }
        EntityPlayer player = new EntityPlayer(this.multiplayer.joinGame().identity());
        applyPlayerSnapshot(player, state, true);
        player.yaw = input.yaw();
        player.pitch = input.pitch();
        player.setSelectedSlot(input.selectedHotbarSlot());
        if (input.pressed(PlayerInputFrame.CYCLE_GAME_MODE)) player.setGamemode(player.getGamemode().next());
        if (input.pressed(PlayerInputFrame.TOGGLE_FLY)) player.toggleFlying();
        if (input.pressed(PlayerInputFrame.SPECTATOR_SPEED_UP)) player.adjustSpectatorFlySpeed(1);
        if (input.pressed(PlayerInputFrame.SPECTATOR_SPEED_DOWN)) player.adjustSpectatorFlySpeed(-1);
        this.remoteWorldView.setPhysicsPlayer(player);
        player.update(new PlayerControls(input.forward(), input.strafe(),
                input.pressed(PlayerInputFrame.JUMP), input.pressed(PlayerInputFrame.SNEAK),
                input.pressed(PlayerInputFrame.SPRINT),
                input.pressed(PlayerInputFrame.SNEAK_TOGGLE_MODE),
                input.pressed(PlayerInputFrame.SPRINT_TOGGLE_MODE)),
                this.remoteWorldView.physicsDimension());
        this.remoteWorldView.setPhysicsPlayer(this.remotePlayer);
        return snapshotPredictedPlayer(player, state.serverTick() + 1, input.sequence(), state.vehicleEntityId());
    }

    private static PlayerStateSnapshot snapshotPredictedPlayer(EntityPlayer player, long tick, long sequence,
                                                                int vehicleEntityId) {
        int flags = 0;
        if (player.isFlying()) flags |= PlayerMovementState.FLYING;
        if (player.isNoClip()) flags |= PlayerMovementState.NO_CLIP;
        if (player.isSprinting()) flags |= PlayerMovementState.SPRINTING;
        if (player.isSneaking()) flags |= PlayerMovementState.SNEAKING;
        return new PlayerStateSnapshot(tick, sequence, player.getDimensionId().toString(),
                player.x, player.y, player.z, player.motionX, player.motionY, player.motionZ,
                player.yaw, player.pitch, player.onGround,
                PlayerGameMode.valueOf(player.getGamemode().name()), flags, player.getHealth(),
                player.getFoodLevel(), player.getSaturation(), player.getSelectedSlot(), vehicleEntityId,
                player.getSpectatorFlySpeed());
    }

    private static void applyPlayerSnapshot(EntityPlayer player, PlayerStateSnapshot state,
                                            boolean snapInterpolation) {
        player.setPosition(state.x(), state.y(), state.z());
        player.motionX = state.velocityX();
        player.motionY = state.velocityY();
        player.motionZ = state.velocityZ();
        player.yaw = state.yaw();
        player.pitch = state.pitch();
        player.onGround = state.grounded();
        player.setDimensionId(Identifier.of(state.dimension()));
        player.setGamemode(Gamemode.valueOf(state.gameMode().name()));
        player.restoreNetworkMovementState(
                (state.movementState() & PlayerMovementState.FLYING) != 0,
                (state.movementState() & PlayerMovementState.NO_CLIP) != 0,
                (state.movementState() & PlayerMovementState.SPRINTING) != 0,
                (state.movementState() & PlayerMovementState.SNEAKING) != 0);
        player.restoreNetworkSpectatorFlySpeed(state.spectatorFlySpeed());
        player.setHealth(state.health());
        player.setFoodLevel(state.foodLevel());
        player.setSaturation(state.saturation());
        player.setSelectedSlot(state.selectedHotbarSlot());
        if (snapInterpolation) player.snapPrevToCurrent();
    }

    private void applyRemotePlayerState(PlayerStateSnapshot state) {
        if (this.remotePlayer == null || state == null) return;
        double previousX = this.remotePlayer.x, previousY = this.remotePlayer.y, previousZ = this.remotePlayer.z;
        applyPlayerSnapshot(this.remotePlayer, state, false);
        if (this.remoteLastAuthoritativeState != null) {
            this.remotePlayer.lastX = previousX;
            this.remotePlayer.lastY = previousY;
            this.remotePlayer.lastZ = previousZ;
        }
        this.remoteWorldView.setPhysicsPlayer(this.remotePlayer);
    }

    private void syncRemoteInventory() {
        if (this.remotePlayer == null || this.multiplayer.session() == null) return;
        int containerId = this.remoteOpenContainer == null ? 0 : this.remoteOpenContainer.containerId();
        var container = this.multiplayer.session().inventory().get(containerId);
        if (container == null) return;
        if (container.revision() != this.remoteInventoryRevision) {
            this.remoteInventoryRevision = container.revision();
            int offset = this.remoteOpenContainer == null ? 0 : this.remoteOpenContainer.containerSlots();
            if (this.remoteContainerInventory != null) {
                int blockSlots = Math.min(this.remoteContainerInventory.size(), container.slots().size());
                for (int slot = 0; slot < blockSlots; slot++) {
                    this.remoteContainerInventory.set(slot, this.decodeRemoteItem(container.slots().get(slot)));
                }
            }
            int available = Math.max(0, container.slots().size() - offset);
            int count = Math.min(this.remotePlayer.getInventory().size(), available);
            for (int slot = 0; slot < count; slot++) {
                this.remotePlayer.getInventory().set(slot, this.decodeRemoteItem(container.slots().get(offset + slot)));
            }
            for (int slot = count; slot < this.remotePlayer.getInventory().size(); slot++) {
                this.remotePlayer.getInventory().set(slot, ItemStack.EMPTY);
            }
        }
        if (this.guiManager.current() instanceof de.skyengine.graphics.gui.screens.GuiContainer screen) {
            screen.acceptAuthoritativeCarried(this.decodeRemoteItem(container.carried()));
        }
        if (this.guiManager.current() instanceof GuiFurnace furnace && containerId != 0) {
            furnace.acceptRemoteData(this.multiplayer.session().inventory().data(containerId));
        }
    }

    private void openRemoteContainer(CorePackets.ContainerOpen opened) {
        if (this.remotePlayer == null || this.multiplayer.session() == null) return;
        this.setRemoteChestOpen(this.remoteOpenContainer, false);
        this.remoteOpenContainer = opened;
        this.setRemoteChestOpen(opened, true);
        this.remoteInventoryRevision = Integer.MIN_VALUE;
        this.remoteContainerInventory = new de.skyengine.game.world.block.entity.SimpleItemStorage(
                opened.containerSlots());
        this.syncRemoteInventory();
        de.skyengine.graphics.gui.screens.GuiContainer.InventoryActionSink sink =
                (source, target, action, button, offered) -> this.sendRemoteContainerInventoryAction(
                        opened.containerId(), source, target, action, button, offered);
        Runnable close = () -> this.closeRemoteContainer(opened.containerId());
        int craftingInputSlots = Math.max(0, opened.containerSlots() - 1);
        de.skyengine.game.world.block.entity.ItemStorage craftingInput = new de.skyengine.game.world.block.entity.ItemStorageView(
                this.remoteContainerInventory, 0, craftingInputSlots);
        de.skyengine.game.world.block.entity.ItemStorage craftingOutput = new de.skyengine.game.world.block.entity.ItemStorageView(
                this.remoteContainerInventory, craftingInputSlots, opened.containerSlots() - craftingInputSlots);
        de.skyengine.graphics.gui.GuiScreen screen = switch (opened.kind()) {
            case CHEST -> new GuiChest(this.remoteContainerInventory,
                    opened.rows() == 6 ? 6 : 3, this.remotePlayer.getInventory(), sink, close);
            case HOPPER -> new de.skyengine.graphics.gui.screens.GuiHopper(
                    this.remoteContainerInventory, this.remotePlayer.getInventory(), sink, close);
            case DISPENSER -> new GuiDispenser(this.remoteContainerInventory,
                    this.remotePlayer.getInventory(), sink, close);
            case FURNACE -> new GuiFurnace(this.remoteContainerInventory,
                    this.remotePlayer.getInventory(), sink, close);
            case CRAFTING -> {
                int grid = (int) Math.round(Math.sqrt(craftingInputSlots));
                yield new GuiCraftingStation(grid, opened.rows(), craftingInput, craftingOutput,
                        this.remotePlayer.getInventory(), sink, close);
            }
            case PLAYER_INVENTORY -> {
                Supplier<ItemStack> held = () -> this.remotePlayer.getInventory().get(
                        this.remotePlayer.getSelectedSlot());
                yield new GuiInventory(craftingInput, craftingOutput, this.remotePlayer.getInventory(),
                        this.playerRenderer, this.heldItemMeshes, held, sink, close);
            }
        };
        this.guiManager.open(screen);
    }

    private void closeRemoteContainer(int containerId) {
        if (this.multiplayer.session() != null && this.remoteOpenContainer != null
                && this.remoteOpenContainer.containerId() == containerId) {
            this.multiplayer.session().closeContainer(containerId);
        }
        this.setRemoteChestOpen(this.remoteOpenContainer, false);
        this.remoteOpenContainer = null;
        this.remoteContainerInventory = null;
        this.remoteInventoryRevision = Integer.MIN_VALUE;
    }

    private void serverClosedRemoteContainer(int containerId) {
        if (this.remoteOpenContainer == null || this.remoteOpenContainer.containerId() != containerId) return;
        this.setRemoteChestOpen(this.remoteOpenContainer, false);
        this.remoteOpenContainer = null;
        this.remoteContainerInventory = null;
        this.remoteInventoryRevision = Integer.MIN_VALUE;
        if (this.guiManager.current() instanceof de.skyengine.graphics.gui.screens.GuiContainer) {
            this.guiManager.close();
        }
    }

    private void setRemoteChestOpen(CorePackets.ContainerOpen opened, boolean open) {
        if (opened == null || opened.kind() != de.skyengine.shared.gameplay.ContainerKind.CHEST
                || this.remoteWorldView == null
                || !opened.dimension().equals(this.remoteWorldView.dimension())) return;
        BlockEntity entity = this.remoteWorldView.physicsDimension().getBlockEntity(
                opened.x(), opened.y(), opened.z());
        if (entity instanceof ChestBlockEntity chest) chest.setOpen(open, false);
    }

    private void playRemoteWorldSound(CorePackets.WorldSound sound) {
        if (this.multiplayer.playerState() == null
                || !sound.dimension().equals(this.multiplayer.playerState().dimension())) return;
        BlockSoundGroup[] groups = BlockSoundGroup.values();
        BlockOpenSound[] openSounds = BlockOpenSound.values();
        switch (sound.type()) {
            case HIT -> { if (sound.data() >= 0 && sound.data() < groups.length) this.soundManager.playHit(groups[sound.data()], sound.x(), sound.y(), sound.z()); }
            case BREAK -> { if (sound.data() >= 0 && sound.data() < groups.length) this.soundManager.playBreak(groups[sound.data()], sound.x(), sound.y(), sound.z()); }
            case PLACE -> { if (sound.data() >= 0 && sound.data() < groups.length) this.soundManager.playPlace(groups[sound.data()], sound.x(), sound.y(), sound.z()); }
            case COMPARATOR_CLICK -> this.soundManager.playComparatorClick(sound.data() != 0, sound.x(), sound.y(), sound.z());
            case LEVER_CLICK -> this.soundManager.playLeverClick(sound.data() != 0, sound.x(), sound.y(), sound.z());
            case EXPLOSION -> {
                this.soundManager.playExplosion(sound.x(), sound.y(), sound.z());
                if (this.remoteWorldView != null) this.remoteWorldView.physicsDimension().particles()
                        .explosion(sound.x(), sound.y(), sound.z(), 4F, 64);
            }
            case FUSE -> {
                this.soundManager.playFuse(sound.x(), sound.y(), sound.z());
                if (this.remoteWorldView != null) this.remoteWorldView.physicsDimension().particles()
                        .tntFuseSmoke(sound.x(), sound.y() + 0.5, sound.z());
            }
            case PISTON_EXTEND -> this.soundManager.playPistonExtend(sound.x(), sound.y(), sound.z());
            case PISTON_CONTRACT -> this.soundManager.playPistonContract(sound.x(), sound.y(), sound.z());
            case FIZZ -> this.soundManager.playFizz(sound.x(), sound.y(), sound.z());
            case FLUID_EXTINGUISH -> {
                this.soundManager.playFluidExtinguish(sound.x(), sound.y(), sound.z());
                if (this.remoteWorldView != null) this.remoteWorldView.physicsDimension().particles()
                        .fluidReaction(sound.x(), sound.y(), sound.z());
            }
            case WATER_AMBIENT -> this.soundManager.playWaterAmbient(sound.x(), sound.y(), sound.z());
            case LAVA_AMBIENT -> this.soundManager.playLavaAmbient(sound.x(), sound.y(), sound.z());
            case LAVA_POP -> {
                this.soundManager.playLavaPop(sound.x(), sound.y(), sound.z());
                if (this.remoteWorldView != null) this.remoteWorldView.physicsDimension().particles()
                        .lavaPop(sound.x(), sound.y(), sound.z());
            }
            case IGNITE -> this.soundManager.playIgnite(sound.x(), sound.y(), sound.z());
            case PORTAL_AMBIENT -> this.soundManager.playPortalAmbient(sound.x(), sound.y(), sound.z());
            case PORTAL_TRIGGER -> this.soundManager.playPortalTrigger(sound.x(), sound.y(), sound.z());
            case PORTAL_TRAVEL -> this.soundManager.playPortalTravel();
            case DISPENSER_SUCCESS -> this.soundManager.playDispenserSuccess(sound.x(), sound.y(), sound.z());
            case DISPENSER_FAILURE -> this.soundManager.playDispenserFailure(sound.x(), sound.y(), sound.z());
            case BUCKET_EMPTY -> this.soundManager.playBucketEmpty(sound.data() != 0, sound.x(), sound.y(), sound.z());
            case BUCKET_FILL -> this.soundManager.playBucketFill(sound.data() != 0, sound.x(), sound.y(), sound.z());
            case ITEM_FRAME_REMOVE_ITEM -> this.soundManager.playItemFrameRemoveItem(sound.x(), sound.y(), sound.z());
            case ITEM_FRAME_BREAK -> this.soundManager.playItemFrameBreak(sound.x(), sound.y(), sound.z());
            case BLOCK_OPEN -> { if (sound.data() >= 0 && sound.data() < openSounds.length) this.soundManager.playBlockOpen(openSounds[sound.data()], sound.x(), sound.y(), sound.z()); }
            case BLOCK_CLOSE -> { if (sound.data() >= 0 && sound.data() < openSounds.length) this.soundManager.playBlockClose(openSounds[sound.data()], sound.x(), sound.y(), sound.z()); }
        }
    }

    private void playRemoteEntityEvent(CorePackets.EntityEvent event) {
        int ownId = this.multiplayer.joinGame() == null ? 0
                : this.multiplayer.joinGame().playerEntityId();
        if (event.networkId() == ownId) {
            if (event.eventId() == de.skyengine.shared.entity.EntityEventTypes.HURT) {
                float fallDamage = event.data() / 1000F;
                if (fallDamage > 0) this.soundManager.playFall(fallDamage >= 4F);
                this.soundManager.playHurt();
                this.animState.hurt();
            } else if (event.eventId() == de.skyengine.shared.entity.EntityEventTypes.PICKUP) {
                this.soundManager.playPickup();
            }
            // Der eigene Armschwung startet bereits ohne Netzwerklatenz bei der Eingabe.
            return;
        }
        RemotePlayerVisual visual = this.remotePlayerVisuals.get(event.networkId());
        if (visual == null) return;
        if (event.eventId() == de.skyengine.shared.entity.EntityEventTypes.SWING) {
            visual.animation.swing();
        } else if (event.eventId() == de.skyengine.shared.entity.EntityEventTypes.HURT) {
            visual.animation.hurt();
        }
    }

    private void syncRemotePlayerVisuals() {
        if (this.multiplayer.session() == null || this.multiplayer.joinGame() == null
                || this.multiplayer.playerState() == null) {
            this.remotePlayerVisuals.clear();
            return;
        }
        int ownId = this.multiplayer.joinGame().playerEntityId();
        java.util.Set<Integer> present = new java.util.HashSet<>();
        for (NetworkEntitySnapshot snapshot : this.multiplayer.session().entities().snapshots()) {
            if (snapshot.typeId() != NetworkEntityTypes.PLAYER || snapshot.networkId() == ownId
                    || !snapshot.dimension().equals(this.multiplayer.playerState().dimension())) continue;
            present.add(snapshot.networkId());
            var joined = this.multiplayer.remotePlayers().get(snapshot.networkId());
            java.util.UUID identity = joined == null
                    ? new java.util.UUID(0, snapshot.networkId()) : joined.identity();
            RemotePlayerVisual visual = this.remotePlayerVisuals.computeIfAbsent(
                    snapshot.networkId(), ignored -> new RemotePlayerVisual(identity));
            if (snapshot.revision() <= visual.revision) continue;
            EntityPlayer player = visual.player;
            double oldX = player.x, oldY = player.y, oldZ = player.z;
            player.setPosition(snapshot.x(), snapshot.y(), snapshot.z());
            if (visual.revision >= 0) {
                player.lastX = oldX;
                player.lastY = oldY;
                player.lastZ = oldZ;
            }
            player.motionX = snapshot.velocityX();
            player.motionY = snapshot.velocityY();
            player.motionZ = snapshot.velocityZ();
            player.yaw = snapshot.yaw();
            player.pitch = snapshot.pitch();
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(snapshot.metadata()))) {
                int gameMode = input.readUnsignedByte();
                int selectedSlot = input.readUnsignedByte();
                if (gameMode < Gamemode.values().length) player.setGamemode(Gamemode.values()[gameMode]);
                if (selectedSlot < 9) player.setSelectedSlot(selectedSlot);
                visual.held = this.decodeRemoteItem(readNetworkStack(input));
            } catch (IOException | IllegalArgumentException invalid) {
                visual.held = ItemStack.EMPTY;
            }
            visual.animation.tickHeldItem(visual.held);
            visual.animation.tick(player);
            visual.revision = snapshot.revision();
        }
        this.remotePlayerVisuals.keySet().removeIf(id -> !present.contains(id));
    }

    private void syncRemoteEntityVisuals() {
        this.remoteRenderedEntities.clear();
        if (this.multiplayer.session() == null || this.multiplayer.playerState() == null) {
            this.remoteEntityVisuals.clear();
            return;
        }
        java.util.Set<Integer> present = new java.util.HashSet<>();
        String dimension = this.multiplayer.playerState().dimension();
        for (NetworkEntitySnapshot snapshot : this.multiplayer.session().entities().snapshots()) {
            if (snapshot.typeId() == NetworkEntityTypes.PLAYER || !dimension.equals(snapshot.dimension())) continue;
            present.add(snapshot.networkId());
            RemoteEntityVisual visual = this.remoteEntityVisuals.get(snapshot.networkId());
            if (visual == null) {
                Entity created = this.createRemoteEntity(snapshot);
                if (created == null) continue;
                visual = new RemoteEntityVisual(created);
                this.remoteEntityVisuals.put(snapshot.networkId(), visual);
            }
            if (snapshot.revision() > visual.revision) {
                Entity entity = visual.entity;
                double oldX = entity.x, oldY = entity.y, oldZ = entity.z;
                this.applyRemoteEntityMetadata(entity, snapshot);
                entity.setPosition(snapshot.x(), snapshot.y(), snapshot.z());
                if (visual.revision >= 0) {
                    entity.lastX = oldX;
                    entity.lastY = oldY;
                    entity.lastZ = oldZ;
                }
                entity.motionX = snapshot.velocityX();
                entity.motionY = snapshot.velocityY();
                entity.motionZ = snapshot.velocityZ();
                entity.yaw = snapshot.yaw();
                entity.pitch = snapshot.pitch();
                visual.revision = snapshot.revision();
            }
            this.remoteRenderedEntities.add(visual.entity);
        }
        this.remoteEntityVisuals.keySet().removeIf(id -> !present.contains(id));
    }

    private Entity createRemoteEntity(NetworkEntitySnapshot snapshot) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(snapshot.metadata()))) {
            return switch (snapshot.typeId()) {
                case NetworkEntityTypes.ITEM -> {
                    ItemEntity item = new ItemEntity(this.decodeRemoteItem(readNetworkStack(input)));
                    item.restoreNetworkState(input.readInt(), input.readInt());
                    yield item;
                }
                case NetworkEntityTypes.FALLING_BLOCK -> new FallingBlockEntity(
                        this.multiplayer.blockStateFromNetwork(input.readInt()));
                case NetworkEntityTypes.PRIMED_TNT -> new PrimedTntEntity(input.readFloat(), input.readInt());
                case NetworkEntityTypes.ITEM_FRAME -> {
                    int x = input.readInt(), y = input.readInt(), z = input.readInt();
                    Direction direction = de.skyengine.game.world.PlayerBlockActions.directionFromFace(
                            input.readUnsignedByte());
                    int rotation = input.readUnsignedByte();
                    ItemFrameEntity frame = new ItemFrameEntity(x, y, z, direction);
                    frame.loadContent(this.decodeRemoteItem(readNetworkStack(input)), rotation);
                    yield frame;
                }
                case NetworkEntityTypes.MINECART -> {
                    MinecartEntity minecart = new MinecartEntity();
                    minecart.setDamage(input.readFloat());
                    minecart.setHurtTime(input.readInt());
                    minecart.setHurtDirection(input.readInt());
                    yield minecart;
                }
                default -> null;
            };
        } catch (IOException | IllegalArgumentException invalid) {
            this.logger.warning("Ungueltige Entitydaten vom Server fuer " + snapshot.networkId()
                    + ": " + invalid.getMessage());
            return null;
        }
    }

    private void applyRemoteEntityMetadata(Entity entity, NetworkEntitySnapshot snapshot) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(snapshot.metadata()))) {
            if (entity instanceof ItemEntity item) {
                ItemStack stack = this.decodeRemoteItem(readNetworkStack(input));
                if (item.getStack().getItem() == stack.getItem()) {
                    item.getStack().setCount(stack.getCount());
                    item.getStack().setDamage(stack.getDamage());
                }
                item.restoreNetworkState(input.readInt(), input.readInt());
            } else if (entity instanceof PrimedTntEntity tnt) {
                input.readFloat();
                tnt.setFuse(input.readInt());
            } else if (entity instanceof ItemFrameEntity frame) {
                input.readInt(); input.readInt(); input.readInt();
                input.readUnsignedByte();
                int rotation = input.readUnsignedByte();
                frame.loadContent(this.decodeRemoteItem(readNetworkStack(input)), rotation);
            } else if (entity instanceof MinecartEntity minecart) {
                minecart.setDamage(input.readFloat());
                minecart.setHurtTime(input.readInt());
                minecart.setHurtDirection(input.readInt());
            }
        } catch (IOException | IllegalArgumentException invalid) {
            this.logger.warning("Ungueltiges Entity-Update fuer " + snapshot.networkId()
                    + ": " + invalid.getMessage());
        }
    }

    private static NetworkItemStack readNetworkStack(DataInputStream input) throws IOException {
        int itemId = input.readInt();
        int count = input.readInt();
        int length = input.readInt();
        if (length < 0 || length > NetworkItemStack.MAX_COMPONENT_BYTES) {
            throw new IOException("Item components exceed protocol limit");
        }
        return new NetworkItemStack(itemId, count, input.readNBytes(length));
    }

    private ItemStack decodeRemoteItem(NetworkItemStack network) {
        if (network == null || network.count() == 0) return ItemStack.EMPTY;
        byte[] components = network.components();
        if (components != null && components.length > 0) {
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(components))) {
                ItemStack stack = ItemStack.load(DataTagIO.read(input));
                if (!stack.isEmpty()) return stack;
            } catch (java.io.IOException invalid) {
                this.logger.warning("Ungueltige Itemdaten vom Server: " + invalid.getMessage());
            }
        }
        var mapping = this.multiplayer.session().registries().get("item");
        if (mapping == null || network.itemId() <= 0 || network.itemId() >= mapping.identifiers().size()) {
            return ItemStack.EMPTY;
        }
        Item item = Items.get(Identifier.of(mapping.identifiers().get(network.itemId())));
        return item == null ? ItemStack.EMPTY : new ItemStack(item, network.count());
    }

    private void closeRemoteWorldView() {
        this.setRemoteChestOpen(this.remoteOpenContainer, false);
        this.soundManager.stopMinecartSounds();
        if (this.remoteWorldView != null) {
            GL11.glFinish();
            this.remoteWorldView.close();
        }
        this.remoteWorldView = null;
        this.remotePlayer = null;
        this.remoteInventoryRevision = Integer.MIN_VALUE;
        this.remoteOpenContainer = null;
        this.remoteContainerInventory = null;
        this.remoteActionSequence = 0;
        this.remoteRotationInitialized = false;
        this.remoteSpectatorSpeedDelta = 0;
        this.remoteInputSequence = 0;
        this.remoteClientTick = 0;
        this.remoteLastAuthoritativeState = null;
        this.remoteLastSpacePressTime = 0;
        this.remoteRespawnPending = false;
        this.remotePrediction = null;
        this.remotePreviousPredicted = null;
        this.remoteCurrentPredicted = null;
        this.remoteCorrectionX = this.remoteCorrectionY = this.remoteCorrectionZ = 0;
        this.remotePortalPresentation.reset();
        this.remotePlayerVisuals.clear();
        this.remoteEntityVisuals.clear();
        this.remoteRenderedEntities.clear();
    }

    public boolean hasRemoteWorldView() {
        return this.remoteWorldView != null;
    }

    /** Setzt den Spieler an den Weltspawn (deterministisch: Terrainhöhe bei 0,0 + 2). */
    private void placeAtWorldSpawn(EntityPlayer player) {
        int spawnY = this.dimension().getGenerator().sampleHeight(0, 0) + 2;
        player.setPosition(0.5, spawnY, 0.5);
    }

    /**
     * Respawn nach dem Tod (Todesscreen-Button): zurück an den Weltspawn mit vollen Vitals;
     * das Inventar bleibt unangetastet (kein Item-Drop, User-Entscheid).
     */
    public void respawnPlayer() {
        if (this.session == null && this.remotePlayer != null && this.multiplayer.session() != null) {
            if (!this.remotePlayer.isDead() || this.remoteRespawnPending) return;
            this.remoteRespawnPending = true;
            this.multiplayer.session().requestRespawn();
            this.guiManager.close();
            return;
        }
        if (this.dimension() == null || this.player() == null) return;
        this.guiManager.close();
        this.waterVision.reset();
        this.playerWasInWater = false;
        this.underwaterAudio.reset();
        this.animState.reset();
        this.player().motionX = 0;
        this.player().motionY = 0;
        this.player().motionZ = 0;
        this.player().resetVitals();
        World.SpawnPoint configured = this.world().spawnPoint();
        Identifier spawnDimension = configured == null ? WorldgenRegistries.OVERWORLD : configured.dimension();
        if (!this.dimension().getDimensionId().equals(spawnDimension)) {
            PlayerLocation exact = configured == null ? null : new PlayerLocation(spawnDimension,
                    configured.x() + 0.5, configured.y(), configured.z() + 0.5,
                    configured.yaw(), configured.pitch());
            this.session.pendingDimensionSwitch = new PendingDimensionSwitch(
                    spawnDimension, configured == null ? 0 : configured.x(),
                    configured == null ? 64 : configured.y(), configured == null ? 0 : configured.z(),
                    null, false, null, this.dimension().getDimensionId(), null, null, exact);
        } else {
            if (configured == null) this.placeAtWorldSpawn(this.player());
            else {
                this.player().yaw = configured.yaw();
                this.player().pitch = configured.pitch();
                this.teleportPlayer(configured.x() + 0.5, configured.y(), configured.z() + 0.5);
            }
        }
        this.player().snapPrevToCurrent();
    }

    /**
     * Verlässt die Welt zurück ins Hauptmenü (Render-Thread): erst den GuiScreen schließen
     * (getragene Stapel landen im Inventar), dann speichern, dann die Welt abbauen.
     * glFinish stellt sicher, dass kein In-Flight-Draw mehr auf den GL-Ressourcen der Welt
     * liegt, bevor sie sterben (ein einmaliger Stall beim Menü-Wechsel ist unkritisch).
     */
    public void exitToTitle() {
        if (this.session == null) {
            this.multiplayer.disconnect();
            this.closeRemoteWorldView();
            this.guiManager.close();
            this.guiManager.open(new GuiMainMenu());
            return;
        }
        this.cancelBiomeSearch();
        this.guiManager.close();
        this.saveCurrentWorld(true);
        GL11.glFinish();
        this.soundManager.stopMinecartSounds();
        this.underwaterAudio.reset();
        this.waterVision.reset();
        this.session.dispose();
        this.session = null;
        this.hit = null;
        this.itemFrameHit = null;
        this.minecartHit = null;
        this.notifyOnSaveDone = false; // sonst quittiert die nächste Welt einen fremden Save
        this.resetMining();
        this.guiManager.open(new GuiMainMenu());
    }

    /**
     * Speichert die Welt: level.json (nur Welt-Metadaten), players/&lt;uuid&gt;.dat
     * (Zustand + Inventar, binäres DataTag) und reiht alle modifizierten Chunks ein
     * (den Flush garantiert storage.close() in world.dispose()).
     *
     * <p>{@code materializeFalling} nur beim Welt-Austritt: der periodische Autosave darf
     * fallende Blöcke nicht materialisieren, sie würden sichtbar in der Luft einrasten.
     *
     * @return Anzahl der eingereihten Chunks (0 = nichts zu schreiben oder keine Welt)
     */
    private int saveCurrentWorld(boolean materializeFalling) {
        if (this.currentSave() == null || this.dimension() == null || this.player() == null) return 0;

        LevelData level = this.currentSave().level();
        level.lastPlayed = System.currentTimeMillis();
        level.formatVersion = WorldSaves.CURRENT_FORMAT_VERSION;
        this.player().setDimensionId(this.dimension().getDimensionId());
        int chunks = this.world().saveModifiedChunks(materializeFalling);
        this.logger.info("Welt gespeichert: " + this.currentSave().dirName() + " (" + chunks + " Chunks)");
        return chunks;
    }

    /** Übernimmt die persistenten Einstellungen in die laufenden Systeme (auch vom Optionsmenü genutzt). */
    public void applySettings() {
        ChunkMesher.configure(this.settings.ambientOcclusion,
                this.settings.leavesQuality == GameSettings.LeavesQuality.LOW);
        /* Welt-Anteile nur mit Welt (Optionen sind auch aus dem Hauptmenü erreichbar);
           beim nächsten enterWorld laufen sie ohnehin erneut. */
        if (this.dimension() != null) {
            this.dimension().getChunkManager().setRenderDistance(this.settings.renderDistance);
            this.dimension().setSimulationDistance(this.settings.simulationDistance);
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
        this.multiplayer.update();
        this.multiplayer.drainMessages(this.chat::addMessage);
        this.updateRemoteWorldLifecycle(input);
        if (this.dimension() == null || this.player() == null) return; // Hauptmenü: nichts zu ticken
        this.world().tickLifecycle();
        PerformanceProfiler profiler = PerformanceProfiler.get();
        long playerLogicStarted = profiler.begin();

        /* Gespeichert-Meldung erst, wenn der IO-Thread durch ist. Bewusst VOR dem Pause-Zweig:
           das Speichern beim Öffnen des Pausenmenüs läuft ja gerade dann fertig. */
        if (this.notifyOnSaveDone && !this.world().hasPendingSaves()) {
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
                this.player().snapPrevToCurrent();
                this.animState.snapPrev();
            }
            /* Ess-Animation nicht in der Pause weiterwackeln lassen (ihr Kau-Nicken hängt am
               partialTick); das Essen bricht beim Fortsetzen ohnehin ab (Maustaste ist los). */
            this.animState.clearEating();
        } else {
            /* Offenes Container-GUI (Inventar/Truhe): Physik läuft weiter (fallen/Strömung),
               aber ohne Tasten — wie in MC gehen die Eingaben ans GUI. */
            this.player().update(this.guiManager.isOpen() ? PlayerControls.NONE : playerControls(input), this.dimension());
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
            this.animState.tickHeldItem(this.player().getInventory().get(this.player().getSelectedSlot()));
            this.animState.tick(this.player());

            boolean eyesUnderwater = this.playerEyesUnderwater();
            this.waterVision.tick(eyesUnderwater);
            this.underwaterAudio.tick(eyesUnderwater, this.player().isTouchingWater(this.dimension()),
                    !this.player().isFlying(),
                    this.player().x - this.player().lastX,
                    this.player().y - this.player().lastY,
                    this.player().z - this.player().lastZ);
            this.updateStepSounds();
            this.updateHurtSounds();
            this.updateEating(input);
            if (!this.player().isDead() && this.session.pendingDimensionSwitch == null) {
                PortalController.Travel travel = this.session.portalController.tick(this.dimension(), this.player());
                if (travel != null) {
                    this.queuePortalTravel(travel);
                }
            }
            /* Tod (z.B. Fallschaden — auch mit offenem Container-GUI möglich): Todesscreen
               öffnen; open() schließt ein offenes Inventar/eine Truhe sauber über onClose. */
            if (this.player().isDead() && !(this.guiManager.current() instanceof GuiDeathScreen)) {
                this.guiManager.open(new GuiDeathScreen());
            }
        }
        /* Pause-Menü hält die Welt komplett an (wie MC-Singleplayer); Container-GUIs
           (Truhe) lassen sie weiterticken. */
        if (!this.guiManager.pausesGame()) {
            profiler.recordElapsed(PerformanceProfiler.TickSection.PLAYER_GAME_LOGIC, playerLogicStarted);
            this.dimension().update(this.player());
            this.pickupItems();
            this.finalizePendingArrival();
            /* Autosave. Die Modulo-Prüfung gehört IN diesen Zweig: gameTime steht bei Pause
               still, außerhalb würde sie dann jeden Tick erneut feuern. */
            if (this.dimension().getGameTime() % AUTOSAVE_INTERVAL == 0 && this.saveCurrentWorld(false) > 0) {
                this.notifyOnSaveDone = true; // beim Autosave nur melden, wenn es wirklich etwas gab
            }
            if (this.session.pendingDimensionSwitch != null) this.executeDimensionSwitch();
        }
    }

    private void queuePortalTravel(PortalController.Travel travel) {
        this.soundManager.playPortalTravel();
        PortalDefinition definition = WorldgenRegistries.PORTALS.get(travel.portalType());
        int sourceX = travel.x(), sourceY = travel.y(), sourceZ = travel.z();
        Direction.Axis axis = null;
        Identifier sourceDimension = this.dimension().getDimensionId();
        String sourcePortalId = null;
        String targetPortalId = null;
        if (definition != null && definition.linkPolicy() == PortalDefinition.LinkPolicy.NETHER) {
            var shape = de.skyengine.game.world.dimension.NetherPortalShape.find(
                    this.dimension(), sourceX, sourceY, sourceZ, true);
            if (shape != null) {
                PortalIndex.Entry sourceEntry = this.dimension().getPortalIndex().add(travel.portalType(), shape);
                sourcePortalId = sourceEntry.id();
                sourceX = PortalCoordinates.scale(sourceEntry.centerX(),
                        this.dimension().getEnvironment(),
                        WorldgenRegistries.DIMENSIONS.get(travel.targetDimension()).environment());
                sourceY = shape.bottomY();
                sourceZ = PortalCoordinates.scale(sourceEntry.centerZ(),
                        this.dimension().getEnvironment(),
                        WorldgenRegistries.DIMENSIONS.get(travel.targetDimension()).environment());
                axis = shape.axis();
                PortalLinks.Endpoint linked = this.dimension().getPortalLinks().linked(
                        travel.portalType(), sourceDimension, sourcePortalId);
                if (linked != null && linked.dimension().equals(travel.targetDimension().toString())) {
                    targetPortalId = linked.portalId();
                }
            } else {
                var target = WorldgenRegistries.DIMENSIONS.get(travel.targetDimension());
                sourceX = PortalCoordinates.scale(sourceX, this.dimension().getEnvironment(), target.environment());
                sourceZ = PortalCoordinates.scale(sourceZ, this.dimension().getEnvironment(), target.environment());
            }
        }
        this.session.pendingDimensionSwitch = new PendingDimensionSwitch(travel.targetDimension(),
                sourceX, sourceY, sourceZ, travel.portalType(), true, axis,
                sourceDimension, sourcePortalId, targetPortalId);
    }

    /** Reiht einen sicheren 1:1-Wechsel fuer Befehle und spaetere Gameplay-Systeme ein. */
    public boolean requestDimensionChange(Identifier target) {
        if (this.dimension() == null || this.player() == null || this.session.pendingDimensionSwitch != null
                || target == null || target.equals(this.dimension().getDimensionId())
                || WorldgenRegistries.DIMENSIONS.get(target) == null) return false;
        this.session.pendingDimensionSwitch = new PendingDimensionSwitch(target,
                (int) Math.floor(this.player().x), (int) Math.floor(this.player().y),
                (int) Math.floor(this.player().z), null, false, null,
                this.dimension().getDimensionId(), null, null);
        return true;
    }

    private void executeDimensionSwitch() {
        PendingDimensionSwitch request = this.session.pendingDimensionSwitch;
        this.session.pendingDimensionSwitch = null;
        if (request == null || this.currentSave() == null || request.target.equals(this.dimension().getDimensionId())) return;

        this.saveCurrentWorld(false);
        GL11.glFinish();
        this.soundManager.stopMinecartSounds();
        this.underwaterAudio.reset();
        this.waterVision.reset();
        this.session.switchDimension(request.target, request);
        PortalDefinition definition = request.portalType == null ? null
                : WorldgenRegistries.PORTALS.get(request.portalType);
        boolean netherLink = definition != null
                && definition.linkPolicy() == PortalDefinition.LinkPolicy.NETHER;
        PortalIndex.Entry indexed = null;
        if (netherLink && request.targetPortalId != null) {
            indexed = this.dimension().getPortalIndex().byId(request.targetPortalId);
            if (indexed == null && request.sourcePortalId != null) {
                this.dimension().getPortalLinks().unlink(request.portalType,
                        request.sourceDimension, request.sourcePortalId);
            }
        }
        if (netherLink && indexed == null) {
            indexed = this.findAvailablePortal(request.portalType, request.x, request.y, request.z);
        }
        int loadX = indexed == null ? request.x : indexed.x();
        int loadZ = indexed == null ? request.z : indexed.z();
        int provisionalY = indexed == null
                ? Math.clamp(request.y > 0 ? request.y : this.dimension().getGenerator().sampleHeight(loadX, loadZ) + 2,
                2, de.skyengine.game.world.chunk.Chunk.HEIGHT - 2)
                : indexed.y();
        this.player().setPosition(loadX + 0.5, provisionalY, loadZ + 0.5);
        this.resetPlayerAfterDimensionMove();
        this.session.pendingArrival = new PendingArrival(request.x, request.y, request.z,
                request.portalType, request.createReturnPortal, request.portalAxis,
                request.sourceDimension, request.sourcePortalId, indexed, request.exactDestination);
        this.session.portalController.lockUntilExit();
        this.notifyOnSaveDone = false;
        this.hit = null;
        this.itemFrameHit = null;
        this.minecartHit = null;
        this.resetMining();
        this.applySettings();
        this.guiManager.open(new GuiWorldLoading());
    }

    private void finalizePendingArrival() {
        PendingArrival arrival = this.session.pendingArrival;
        int checkX = arrival != null && arrival.indexedPortal != null ? arrival.indexedPortal.x() : arrival == null ? 0 : arrival.x;
        int checkZ = arrival != null && arrival.indexedPortal != null ? arrival.indexedPortal.z() : arrival == null ? 0 : arrival.z;
        if (arrival == null || !this.dimension().getChunkManager().isInitialLoadComplete()
                || !this.arrivalAreaReady(checkX, checkZ)) return;

        PortalDefinition definition = arrival.portalType == null ? null
                : WorldgenRegistries.PORTALS.get(arrival.portalType);
        if (arrival.exactDestination != null) {
            PlayerLocation destination = arrival.exactDestination;
            this.player().yaw = destination.yaw();
            this.player().pitch = destination.pitch();
            this.finishArrival(destination.x(), destination.y(), destination.z());
            return;
        }
        if (definition != null && definition.linkPolicy() == PortalDefinition.LinkPolicy.NETHER) {
            this.finalizeNetherArrival(arrival);
            return;
        }

        boolean miningPortal = Identifier.of("mining_portal").equals(arrival.portalType);
        boolean createMiningArrival = shouldCreateMiningArrivalPlatform(arrival.portalType,
                this.dimension().getDimensionId(), arrival.createReturnPortal);
        int portalY = miningPortal
                ? this.findSafePortalY(arrival.x, arrival.z, arrival.portalType) : -1;
        if (portalY >= 1) {
            /* Mining-Portale sind feste Bloecke. Der Spieler erscheint deshalb oestlich daneben
               und nie innerhalb des Portalblocks. Dabei wird die Zielwelt nicht veraendert. */
            this.finishArrival(arrival.x + 1.5, portalY, arrival.z + 0.5);
            return;
        }
        if (createMiningArrival) {
            int floorY = this.findArrivalFloor(arrival.x, arrival.z);
            for (int x = arrival.x - 1; x <= arrival.x + 1; x++) {
                for (int z = arrival.z - 1; z <= arrival.z + 1; z++) {
                    this.dimension().setBlock(x, floorY, z, Blocks.OBSIDIAN);
                    this.dimension().setBlock(x, floorY + 1, z, Blocks.AIR);
                    this.dimension().setBlock(x, floorY + 2, z, Blocks.AIR);
                }
            }
            int feetY = floorY + 1;
            this.dimension().setBlock(arrival.x, feetY, arrival.z, Blocks.MINING_PORTAL);
            this.finishArrival(arrival.x + 1.5, feetY, arrival.z + 0.5);
            return;
        }

        /* Befehle und allgemeine SIMPLE-Portale duerfen am Ziel keine Plattform, Lufttasche oder
           sonstige Bloecke erzeugen. Es wird ausschliesslich ein vorhandener sicherer Standpunkt
           in der geladenen Umgebung gesucht. */
        int[] safe = this.findSafeArrival(arrival.x, arrival.y, arrival.z);
        this.finishArrival(safe[0] + 0.5, safe[1], safe[2] + 0.5);
    }

    static boolean shouldCreateMiningArrivalPlatform(Identifier portalType,
                                                      Identifier targetDimension,
                                                      boolean createReturnPortal) {
        return createReturnPortal
                && Identifier.of("mining_portal").equals(portalType)
                && WorldgenRegistries.MINING.equals(targetDimension);
    }

    private void finalizeNetherArrival(PendingArrival arrival) {
        if (arrival.indexedPortal != null) {
            var entry = arrival.indexedPortal;
            var shape = de.skyengine.game.world.dimension.NetherPortalShape.find(
                    this.dimension(), entry.x(), entry.y(), entry.z(), true);
            /* Ein fest verbundenes, nur erloschenes Gegenportal wird in seinem intakten Rahmen
               wieder entfacht. Ist der Rahmen zerstoert, faellt die Aktivierung sauber durch. */
            if (shape == null && de.skyengine.game.world.dimension.NetherPortalShape.activate(
                    this.dimension(), entry.x(), entry.y(), entry.z())) {
                shape = de.skyengine.game.world.dimension.NetherPortalShape.find(
                        this.dimension(), entry.x(), entry.y(), entry.z(), true);
            }
            if (shape != null) {
                PortalIndex.Entry targetEntry = this.dimension().getPortalIndex().add(arrival.portalType, shape);
                this.pairArrival(arrival, targetEntry);
                this.finishArrival(targetEntry.centerX(), shape.bottomY(), targetEntry.centerZ());
                return;
            }
            this.dimension().getPortalIndex().remove(entry);
            this.dimension().getPortalLinks().unlink(arrival.portalType,
                    this.dimension().getDimensionId(), entry.id());
            PortalIndex.Entry next = this.findAvailablePortal(
                    arrival.portalType, arrival.x, arrival.y, arrival.z);
            int retryX = next == null ? arrival.x : next.x();
            int retryY = next == null ? arrival.y : next.y();
            int retryZ = next == null ? arrival.z : next.z();
            this.player().setPosition(retryX + 0.5, Math.clamp(retryY, 2, Chunk.HEIGHT - 2),
                    retryZ + 0.5);
            this.session.pendingArrival = new PendingArrival(arrival.x, arrival.y, arrival.z,
                    arrival.portalType, arrival.createReturnPortal, arrival.portalAxis,
                    arrival.sourceDimension, arrival.sourcePortalId, next);
            return;
        }

        Direction.Axis axis = arrival.portalAxis == null ? Direction.Axis.X : arrival.portalAxis;
        int[] site = this.findNetherPortalSite(arrival.x, arrival.y, arrival.z, axis);
        int minX = axis == Direction.Axis.X ? site[0] - 1 : site[0];
        int minZ = axis == Direction.Axis.Z ? site[2] - 1 : site[2];
        int bottomY = site[1] + 1;
        this.buildNetherPortal(minX, bottomY, minZ, axis);
        var shape = de.skyengine.game.world.dimension.NetherPortalShape.find(
                this.dimension(), minX, bottomY, minZ, true);
        if (shape == null) throw new IllegalStateException("Erzeugtes Netherportal ist ungueltig");
        PortalIndex.Entry targetEntry = this.dimension().getPortalIndex().add(arrival.portalType, shape);
        this.pairArrival(arrival, targetEntry);
        this.finishArrival(targetEntry.centerX(), shape.bottomY(), targetEntry.centerZ());
    }

    private PortalIndex.Entry findAvailablePortal(Identifier portalType, int x, int y, int z) {
        int radius = PortalCoordinates.searchRadius(this.dimension().getEnvironment());
        List<PortalIndex.Entry> candidates = this.dimension().getPortalIndex().candidates(
                portalType, x, y, z, radius,
                entry -> !this.dimension().getPortalLinks().isLinked(
                        portalType, this.dimension().getDimensionId(), entry.id()));
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    private void pairArrival(PendingArrival arrival, PortalIndex.Entry targetEntry) {
        if (arrival.sourcePortalId == null || arrival.sourceDimension == null || targetEntry == null) return;
        this.dimension().getPortalLinks().pair(arrival.portalType,
                arrival.sourceDimension, arrival.sourcePortalId,
                this.dimension().getDimensionId(), targetEntry.id());
    }

    private int[] findNetherPortalSite(int targetX, int targetY, int targetZ, Direction.Axis axis) {
        int maxFloor = this.dimension().getDimensionId().equals(WorldgenRegistries.NETHER) ? 120 : Chunk.HEIGHT - 5;
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
        /* Der anschliessende buildNetherPortal-Aufruf erzeugt nur den notwendigen Rahmen und
           dessen Innenraum. Eine zusaetzliche Obsidianplattform gehoert nicht zur Ankunft. */
        return new int[]{targetX, floor, targetZ};
    }

    private boolean portalSiteClear(int centerX, int floorY, int centerZ, Direction.Axis axis) {
        int sx = axis == Direction.Axis.X ? 1 : 0;
        int sz = axis == Direction.Axis.Z ? 1 : 0;
        int px = axis == Direction.Axis.X ? 0 : 1;
        int pz = axis == Direction.Axis.Z ? 0 : 1;
        for (int w = -2; w <= 1; w++) {
            int x = centerX + sx * w, z = centerZ + sz * w;
            if (!Blocks.getState(this.dimension().getBlock(x, floorY, z)).isSolid()) return false;
            for (int side = -1; side <= 1; side++) {
                for (int y = 1; y <= 4; y++) {
                    BlockState state = Blocks.getState(this.dimension().getBlock(
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
            this.dimension().setBlock(minX + sx * w, bottomY - 1, minZ + sz * w, Blocks.OBSIDIAN, false);
            this.dimension().setBlock(minX + sx * w, bottomY + 3, minZ + sz * w, Blocks.OBSIDIAN, false);
        }
        for (int h = 0; h < 3; h++) {
            this.dimension().setBlock(minX - sx, bottomY + h, minZ - sz, Blocks.OBSIDIAN, false);
            this.dimension().setBlock(minX + sx * 2, bottomY + h, minZ + sz * 2, Blocks.OBSIDIAN, false);
            for (int w = 0; w < 2; w++) {
                this.dimension().setBlock(minX + sx * w, bottomY + h, minZ + sz * w, Blocks.AIR, false);
            }
        }
        if (!de.skyengine.game.world.dimension.NetherPortalShape.activate(
                this.dimension(), minX, bottomY, minZ)) {
            throw new IllegalStateException("Netherportalrahmen konnte nicht aktiviert werden");
        }
    }

    private void finishArrival(double x, double feetY, double z) {
        this.player().setPosition(x, feetY, z);
        this.resetPlayerAfterDimensionMove();
        this.session.portalController.lockUntilExit();
        this.session.pendingArrival = null;
        this.saveCurrentWorld(false);
    }

    private boolean arrivalAreaReady(int centerX, int centerZ) {
        for (int x = centerX - 1; x <= centerX + 1; x++) {
            for (int z = centerZ - 1; z <= centerZ + 1; z++) {
                var chunk = this.dimension().getChunkManager().getChunk(
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
            int state = this.dimension().getBlock(x, y, z);
            if (!Blocks.getState(state).getBlock().getIdentifier().equals(definition.block())) continue;
            int floor = this.dimension().getBlock(x, y - 1, z);
            int head = this.dimension().getBlock(x, y + 1, z);
            if (Blocks.getState(floor).isSolid() && !Blocks.getState(head).isSolid()) return y;
        }
        return -1;
    }

    private int findArrivalFloor(int x, int z) {
        for (int y = de.skyengine.game.world.chunk.Chunk.HEIGHT - 3; y >= 1; y--) {
            BlockState state = Blocks.getState(this.dimension().getBlock(x, y, z));
            if (state.isSolid() && !state.isFluid()) return y;
        }
        return 64;
    }

    private int[] findSafeArrival(int targetX, int preferredY, int targetZ) {
        int clampedY = Math.clamp(preferredY, 1, Chunk.HEIGHT - 2);
        for (int radius = 0; radius <= 1; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    int x = targetX + dx, z = targetZ + dz;
                    for (int distance = 0; distance < Chunk.HEIGHT; distance++) {
                        int above = clampedY + distance;
                        if (above < Chunk.HEIGHT - 1 && this.isSafeArrivalCell(x, above, z)) {
                            return new int[]{x, above, z};
                        }
                        int below = clampedY - distance;
                        if (distance > 0 && below >= 1 && this.isSafeArrivalCell(x, below, z)) {
                            return new int[]{x, below, z};
                        }
                    }
                }
            }
        }
        /* Auch im unguenstigen Fall bleibt die Operation strikt blockfrei. */
        return new int[]{targetX, clampedY, targetZ};
    }

    private boolean isSafeArrivalCell(int x, int feetY, int z) {
        BlockState floor = Blocks.getState(this.dimension().getBlock(x, feetY - 1, z));
        BlockState feet = Blocks.getState(this.dimension().getBlock(x, feetY, z));
        BlockState head = Blocks.getState(this.dimension().getBlock(x, feetY + 1, z));
        return floor.isSolid() && !floor.isFluid()
                && !feet.isSolid() && !feet.isFluid()
                && !head.isSolid() && !head.isFluid();
    }

    private void resetPlayerAfterDimensionMove() {
        this.player().resetFallDistance();
        this.player().motionX = 0;
        this.player().motionY = 0;
        this.player().motionZ = 0;
        this.player().snapPrevToCurrent();
        this.animState.reset();
        this.animState.snapPrev();
        this.waterVision.reset();
        this.playerWasInWater = false;
        this.underwaterAudio.reset();
    }

    /** Teleport innerhalb der aktiven Dimension, ohne irgendeinen Weltblock anzufassen. */
    private void teleportPlayer(double x, double y, double z) {
        this.player().setPosition(x, y, z);
        this.resetPlayerAfterDimensionMove();
        this.session.portalController.reset();
    }

    private CommandContext.HomeResult teleportHome() {
        PlayerLocation home = this.player().getHome();
        if (home == null) return CommandContext.HomeResult.NOT_SET;
        if (this.session.pendingDimensionSwitch != null) return CommandContext.HomeResult.BUSY;
        this.player().yaw = home.yaw();
        this.player().pitch = home.pitch();
        if (home.dimension().equals(this.dimension().getDimensionId())) {
            this.teleportPlayer(home.x(), home.y(), home.z());
            return CommandContext.HomeResult.TELEPORTED;
        }
        this.session.pendingDimensionSwitch = new PendingDimensionSwitch(home.dimension(),
                (int) Math.floor(home.x()), (int) Math.floor(home.y()), (int) Math.floor(home.z()),
                null, false, null, this.dimension().getDimensionId(), null, null, home);
        return CommandContext.HomeResult.QUEUED;
    }

    private void cancelBiomeSearch() {
        this.biomeSearchGeneration++;
        if (this.biomeSearch != null) this.biomeSearch.cancel(true);
        this.biomeSearch = null;
    }

    private boolean locateBiome(String name) {
        Biome target = BiomeLocator.byName(name);
        if (target == null || this.world() == null || this.dimension() == null) return false;
        if (this.biomeSearch != null) this.biomeSearch.cancel(true);
        int generation = ++this.biomeSearchGeneration;
        GameplaySession sourceSession = this.session;
        Identifier sourceDimension = this.dimension().getDimensionId();
        int originX = (int) Math.floor(this.player().x);
        int originZ = (int) Math.floor(this.player().z);
        var generator = this.dimension().getGenerator();
        this.biomeSearch = this.world().submitBackground(() -> BiomeLocator.locate(generator, target,
                originX, originZ, BiomeLocator.DEFAULT_RADIUS, BiomeLocator.DEFAULT_STEP));
        this.biomeSearch.whenComplete((result, error) -> SkyEngine.get().addTaskToRenderThread(() -> {
            if (this.session != sourceSession || generation != this.biomeSearchGeneration) return;
            this.biomeSearch = null;
            if (error != null && !(error instanceof java.util.concurrent.CancellationException)) {
                this.chat.addMessage("§c" + I18n.tr("command.biome.failed", error.getMessage()));
            } else if (result == null) {
                this.chat.addMessage("§c" + I18n.tr("command.biome.not_found", name,
                        DimensionDefinition.displayName(sourceDimension)));
            } else {
                this.chat.addMessage("§f" + I18n.tr("command.biome.found", name,
                        DimensionDefinition.displayName(sourceDimension), result.x(), result.z(), result.distance()));
            }
        }));
        return true;
    }

    /**
     * Sammelt gedroppte Items in Reichweite ins Spielerinventar. Läuft nach dem Welt-Tick; setzt nur
     * das removed-Flag (die Welt räumt die Liste selbst auf) - daher keine Mutation der Liste hier.
     */
    private void pickupItems() {
        double px = this.player().x;
        double py = this.player().y + 0.9; // grob Körpermitte
        double pz = this.player().z;
        this.dimension().forEachEntityNearby(px, pz, 1, entity -> {
            if (!(entity instanceof ItemEntity item) || item.isRemoved() || item.getPickupDelay() > 0) return;
            double dx = item.x - px;
            double dy = item.y - py;
            double dz = item.z - pz;
            if (dx * dx + dy * dy + dz * dz > PICKUP_RANGE * PICKUP_RANGE) return;

            int before = item.getStack().getCount();
            ItemStack remaining = this.player().getInventory().insert(item.getStack());
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
        Dimension dimension = this.visualDimension();
        if (dimension == null) return;
        boolean inWater = this.player().isTouchingWater(dimension);
        double dx = this.player().x - this.player().lastX;
        double dy = this.player().y - this.player().lastY;
        double dz = this.player().z - this.player().lastZ;
        double speed = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (inWater && !this.playerWasInWater) {
            dimension.particles().splash(this.player().x, this.player().y, this.player().z, dx, dy, dz);
        } else if (inWater && speed > 0.02) {
            dimension.particles().swim(this.player().x, this.player().y + 0.8, this.player().z, dx, dy, dz);
        }
        this.playerWasInWater = inWater;

        BlockState ground = this.groundStateAtPlayer();
        if (!inWater && ground != null && this.player().onGround && this.player().isSprinting()
                && dx * dx + dz * dz > 0.001) {
            dimension.particles().sprint(this.player().x, this.player().y, this.player().z, ground, dx, dz);
        }
        float landing = this.player().consumeLandingDistance();
        if (landing > 3F && ground != null && !inWater) {
            dimension.particles().landing(this.player().x, this.player().y, this.player().z, ground, landing);
        }
    }

    private BlockState groundStateAtPlayer() {
        Dimension dimension = this.visualDimension();
        if (dimension == null) return null;
        int bx = (int) Math.floor(this.player().x);
        int by = (int) Math.floor(this.player().y - 0.2);
        int bz = (int) Math.floor(this.player().z);
        BlockState state = Blocks.getState(dimension.getBlock(bx, by, bz));
        if (state.isAir() || state.isFluid()) state = Blocks.getState(dimension.getBlock(bx, by - 1, bz));
        return state.isAir() || state.isFluid() ? null : state;
    }

    /** Itemkrümel am Gesicht; verwendet denselben TextureArray-Layer wie GUI und Dimension-Items. */
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
        double yaw = Math.toRadians(this.player().yaw);
        double pitch = Math.toRadians(this.player().pitch);
        double cp = Math.cos(pitch);
        double dirX = cp * Math.sin(yaw);
        double dirY = -Math.sin(pitch);
        double dirZ = -cp * Math.cos(yaw);
        double px = this.player().x + dirX * 0.35;
        double py = this.player().y + this.player().getEyeHeight(1F) + dirY * 0.35 - 0.1;
        double pz = this.player().z + dirZ * 0.35;
        Dimension dimension = this.visualDimension();
        if (dimension != null) dimension.particles().itemCrumb(texture, px, py, pz, dirX, dirY, dirZ);
    }

    /**
     * Blockabhängige Laufgeräusche (pro Tick): Distanz-Akkumulator wie in MC — ein Schritt
     * pro {@link #STEP_INTERVAL} zurückgelegten Blöcken (Sprint = automatisch schnellere
     * Kadenz). Kein Sound in der Luft, beim Fliegen, Sneaken (lautlos wie MC) oder im Fluid.
     */
    private void updateStepSounds() {
        Dimension dimension = this.visualDimension();
        if (dimension == null) return;
        if (this.player().isFlying() || this.player().isSneaking()
                || this.player().isTouchingFluid(dimension)) {
            return;
        }
        /* Distanz auch in der Luft akkumulieren (MC: walkDist) — so überschreitet ein
           Sprint-Sprung die Schwelle sofort bei der Landung -> Schritt bei jedem Aufkommen. */
        double dx = this.player().x - this.player().lastX;
        double dz = this.player().z - this.player().lastZ;
        this.stepDistance += Math.sqrt(dx * dx + dz * dz);
        if (!this.player().onGround || this.stepDistance < STEP_INTERVAL) return;
        this.stepDistance = 0;

        int bx = (int) Math.floor(this.player().x);
        int by = (int) Math.floor(this.player().y - 0.2); // Fußpunkt leicht abgesenkt: trifft auch Slabs/Stufen
        int bz = (int) Math.floor(this.player().z);
        int ground = dimension.getBlock(bx, by, bz);
        if (ground == Blocks.AIR || Blocks.getState(ground).isFluid()) {
            ground = dimension.getBlock(bx, by - 1, bz); // Kantenlauf: eine Zelle tiefer probieren
            if (ground == Blocks.AIR || Blocks.getState(ground).isFluid()) return;
        }
        this.soundManager.playStep(Blocks.getState(ground).getBlock().getSoundGroup());
    }

    /** Hurt-/Aufprall-Sounds aus den EntityPlayer-Flanken (der Schaden entsteht tief in der Physik). */
    private void updateHurtSounds() {
        float fall = this.player().consumeFallDamage();
        if (fall > 0) this.soundManager.playFall(fall >= 4); // MC-Grenze: ab 4 Schaden „big"
        if (this.player().consumeHurt()) {
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
        // Nur der Integrated-Singleplayer darf durch ein GUI pausieren. Auf einem Dedicated
        // Server laeuft die autoritative Welt weiter; dort muessen auch Kamera-Interpolation,
        // Audio und visuelle Replikate weiterlaufen wie in Minecraft-Multiplayer.
        boolean now = this.session != null && this.guiManager.pausesGame();
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
        this.renderedPartialTick = partialTick;

        /* Menü-Blur: nur mit Welt UND blur-wolligem Screen (Pause + Unterseiten);
           der Pass animiert die Stärke selbst (Ein-/Ausblenden). */
        PostProcessor post = SkyEngine.get().getPostProcessor();
        post.setMenuBlur(
                this.dimension() != null && this.guiManager.blursBackground());

        if (this.dimension() == null && this.remoteWorldView != null) {
            this.renderRemoteWorld(input, width, height, partialTick, post);
            return;
        }

        /* Hauptmenü (keine Welt): nur GUI-Eingaben routen, gezeichnet wird in renderGui. */
        if (this.dimension() == null) {
            this.zoomController.reset();
            this.camera.setFov(this.settings.fov);
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
                Supplier<ItemStack> held = () -> this.player().getInventory().get(this.player().getSelectedSlot());
                this.guiManager.open(this.player().getGamemode() == Gamemode.CREATIVE
                        ? new GuiCreativeInventory(this.player().getInventory(), this.playerRenderer,
                                this.heldItemMeshes, held)
                        : new GuiInventory(this.player().getInventory(), this.playerRenderer,
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
            boolean zoomActive = !this.guiManager.isOpen()
                    && this.perspective.isFirstPerson()
                    && input.isBindDown(this.settings.key(KeyBindings.ZOOM));
            this.zoomController.update(zoomActive, System.nanoTime());
            this.camera.setFov(this.zoomController.fov(this.settings.fov, this.settings.zoomFactor));
            /* Blick nur drehen, wenn der Cursor auch PHYSISCH gefangen ist. Der Moduswechsel läuft
               deferiert auf dem Window-Thread — direkt nach dem Schließen eines GUIs ist er noch
               frei, und seine Bewegung soll die Kamera nicht mitziehen. */
            if (input.isCursorGrabbed()) {
                double sens = this.settings.mouseSensitivity
                        * this.zoomController.sensitivityScale(this.settings.zoomFactor);
                this.player().turn(input.getDeltaMouseX() * sens, input.getDeltaMouseY() * sens);
            }
        } else {
            this.zoomController.update(false, System.nanoTime());
            this.camera.setFov(this.zoomController.fov(this.settings.fov, this.settings.zoomFactor));
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
            this.view().chunks().getTextureArray().setLodBias(wantedBias);
            this.appliedMipBias = wantedBias;
            this.logger.debug("Textur-LOD-Bias: " + wantedBias);
        }

        this.camera.follow(this.player(), partialTick);
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
        post.setPortalEffect(this.session.portalController.contactProgress());

        /* Audio pro Frame: Listener auf die interpolierte Kamera (Streaming läuft oben). */
        this.soundManager.updateListener(this.camera);
        this.dimension().updateEntitySounds(partialTick);

        this.hit = BlockRaycast.raycastInteractive(this.dimension(), this.eyePosition, this.eyeDirection, REACH);
        double entityReach = this.hit == null ? REACH : Math.sqrt(
                sq(this.hit.hitX() - this.eyePosition.x)
                        + sq(this.hit.hitY() - this.eyePosition.y)
                        + sq(this.hit.hitZ() - this.eyePosition.z));
        this.itemFrameHit = this.dimension().raycastItemFrame(this.eyePosition.x, this.eyePosition.y,
                this.eyePosition.z, this.eyeDirection.x, this.eyeDirection.y, this.eyeDirection.z,
                entityReach);
        if (this.itemFrameHit != null && !this.dimension().isPlayerInteractionReady(
                this.itemFrameHit.getAnchorX(), this.itemFrameHit.getAnchorY(),
                this.itemFrameHit.getAnchorZ())) {
            this.itemFrameHit = null;
        }
        this.minecartHit = this.dimension().raycastMinecart(this.eyePosition.x, this.eyePosition.y,
                this.eyePosition.z, this.eyeDirection.x, this.eyeDirection.y, this.eyeDirection.z,
                entityReach);
        if (this.minecartHit != null && !this.dimension().isPlayerInteractionReady(
                (int) Math.floor(this.minecartHit.x), (int) Math.floor(this.minecartHit.y),
                (int) Math.floor(this.minecartHit.z))) {
            this.minecartHit = null;
        }

        if (guiOpen) {
            this.guiManager.handleInput();       // Schließen + Slot-Klicks (kann den GuiScreen schließen)
            /* Ein GuiScreen-Callback (GuiIngameMenu „Hauptmenü") kann exitToTitle() ausgelöst haben —
               dann ist die Welt weg und der Rest des Frames darf sie nicht mehr anfassen. */
            if (this.dimension() == null) {
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
            this.view().render(this.camera, partialTick);
        } else {
            /* Am Auge samplen: Der Fußpunkt liegt beim Sitzen im Fahrzeug oder Stützblock. */
            float playerLight = this.playerLightAtEyes(partialTick);
            this.view().render(this.camera, partialTick, () ->
                    this.playerRenderer.renderThirdPerson(this.player(), this.animState, this.camera, partialTick,
                            this.heldItemMeshes, this.player().getInventory().get(this.player().getSelectedSlot()), playerLight));
        }
        if (DebugFlags.wireframe) Utils.disableWireframe();

        FrameProfiler.cpuStart(FrameProfiler.Cpu.OVL);
        FrameProfiler.gpuBegin(FrameProfiler.Gpu.OVERLAYS);

        if (this.hit != null && !this.guiManager.isOpen() && this.player().getGamemode().interactsWithWorld()) {
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
        /* Die Ghost-Bloecke gehoeren zur Welt; ihre Linien folgen nach dem Postprocessing. */
        if (this.world() != null && this.dimension() != null) {
            WorldEditSession editor = this.world().worldEdit().session(this.player().getUuid());
            WorldEditSession.Preview preview = editor.preview();
            if (preview != null && this.dimension().getDimensionId().equals(preview.dimension())) {
                this.structurePreviewRenderer.render(this.camera, preview);
            }
        }

        /* First-Person-Hand ins Szene-Target (läuft durch die Post-Kette), eigener Nah-Depthbereich. */
        if (!this.hudHidden && this.perspective.isFirstPerson()
                && this.player().getGamemode() != Gamemode.SPECTATOR) {
            /* Licht der AUGEN-Zelle (nicht der Füße): die Hand hängt vor dem Gesicht, und in
               einem 1 Block hohen Kriechgang unterscheiden sich beide sichtbar. */
            float handLight = this.playerLightAtEyes(partialTick);
            this.handRenderer.render(this.playerRenderer, this.heldItemMeshes, this.player(),
                    this.animState, (float) width / height, partialTick, this.viewEffect, handLight);
        }

        /* Wasser folgt als Depth-Post-Pass; Lava bleibt ein dichter Overlay und liegt damit
           ebenfalls ueber First-Person-Hand und gehaltenem Item. */
        this.renderLavaOverlay();

        FrameProfiler.gpuEnd(FrameProfiler.Gpu.OVERLAYS);
        FrameProfiler.cpuStop(FrameProfiler.Cpu.OVL);
    }

    private PlayerControls playerControls(Input input) {
        float forward = 0;
        float strafe = 0;
        if (input.isBindDown(this.settings.key(KeyBindings.FORWARD))) forward += 1;
        if (input.isBindDown(this.settings.key(KeyBindings.BACK))) forward -= 1;
        if (input.isBindDown(this.settings.key(KeyBindings.RIGHT))) strafe += 1;
        if (input.isBindDown(this.settings.key(KeyBindings.LEFT))) strafe -= 1;
        return new PlayerControls(forward, strafe,
                input.isBindDown(this.settings.key(KeyBindings.JUMP)),
                input.isBindDown(this.settings.key(KeyBindings.SNEAK)),
                input.isBindDown(this.settings.key(KeyBindings.SPRINT)),
                this.settings.sneakToggle, this.settings.sprintToggle);
    }

    /** Transitional remote-world frame: authoritative camera plus the normal L0 renderer. */
    private void renderRemoteWorld(Input input, int width, int height, float partialTick, PostProcessor post) {
        PlayerStateSnapshot state = this.multiplayer.playerState();
        if (state == null) return;

        boolean guiOpen = this.guiManager.isOpen();
        if (!guiOpen) {
            if (input.isKeyPressed(GLFW.GLFW_KEY_ESCAPE)) this.guiManager.open(new GuiIngameMenu());
            if (this.remotePlayer != null
                    && input.isBindPressed(this.settings.key(KeyBindings.OPEN_INVENTORY))) {
                if (this.remotePlayer.getGamemode() == Gamemode.CREATIVE) {
                    Supplier<ItemStack> held = () -> this.remotePlayer.getInventory().get(
                            this.remotePlayer.getSelectedSlot());
                    this.guiManager.open(new GuiCreativeInventory(this.remotePlayer.getInventory(),
                            this.playerRenderer, this.heldItemMeshes, held, this::sendRemoteInventoryAction));
                } else if (this.multiplayer.session() != null) {
                    this.multiplayer.session().requestPlayerInventory();
                }
            }
            if (this.remotePlayer != null) this.handleRemoteHotbarInput(input);
            if (input.isBindPressed(this.settings.key(KeyBindings.TOGGLE_PERSPECTIVE))) {
                this.perspective = this.perspective.next();
            }
            if (input.isBindPressed(this.settings.key(KeyBindings.OPEN_CHAT))) {
                this.openChat("");
            } else if (input.isKeyPressed(GLFW.GLFW_KEY_SLASH)) {
                this.openChat("/");
            }
            if (input.isCursorGrabbed()) {
                double sensitivity = this.settings.mouseSensitivity
                        * this.zoomController.sensitivityScale(this.settings.zoomFactor);
                if (this.remotePlayer != null) {
                    this.remotePlayer.turn(input.getDeltaMouseX() * sensitivity,
                            input.getDeltaMouseY() * sensitivity);
                    this.remoteYaw = this.remotePlayer.yaw;
                    this.remotePitch = this.remotePlayer.pitch;
                }
            }
        } else {
            this.guiManager.handleInput();
            if (this.remoteWorldView == null) return;
        }

        if (!this.remoteRotationInitialized) {
            this.remoteYaw = state.yaw();
            this.remotePitch = state.pitch();
            this.remoteRotationInitialized = true;
        }
        boolean zooming = !guiOpen && input.isBindDown(this.settings.key(KeyBindings.ZOOM));
        this.zoomController.update(zooming, System.nanoTime());
        this.camera.setFov(this.zoomController.fov(this.settings.fov, this.settings.zoomFactor));
        PlayerStateSnapshot previous = this.remotePreviousPredicted == null ? state : this.remotePreviousPredicted;
        PlayerStateSnapshot current = this.remoteCurrentPredicted == null ? state : this.remoteCurrentPredicted;
        long now = System.nanoTime();
        if (this.remoteCorrectionFrameNanos != 0) {
            double seconds = Math.min(0.1, (now - this.remoteCorrectionFrameNanos) / 1_000_000_000.0);
            double decay = Math.exp(-12.0 * seconds);
            this.remoteCorrectionX *= decay;
            this.remoteCorrectionY *= decay;
            this.remoteCorrectionZ *= decay;
        }
        this.remoteCorrectionFrameNanos = now;
        double cameraX = previous.x() + (current.x() - previous.x()) * partialTick + this.remoteCorrectionX;
        double cameraY = previous.y() + (current.y() - previous.y()) * partialTick + this.remoteCorrectionY;
        double cameraZ = previous.z() + (current.z() - previous.z()) * partialTick + this.remoteCorrectionZ;
        if (current.vehicleEntityId() != 0) {
            RemoteEntityVisual vehicle = this.remoteEntityVisuals.get(current.vehicleEntityId());
            if (vehicle != null && vehicle.entity instanceof MinecartEntity) {
                cameraX = vehicle.entity.lastX + (vehicle.entity.x - vehicle.entity.lastX) * partialTick;
                cameraY = vehicle.entity.lastY + (vehicle.entity.y - vehicle.entity.lastY) * partialTick - 0.35;
                cameraZ = vehicle.entity.lastZ + (vehicle.entity.z - vehicle.entity.lastZ) * partialTick;
            }
        }
        float remoteEyeHeight = this.remotePlayer == null
                ? 1.62F : this.remotePlayer.getEyeHeight(partialTick);
        this.camera.setTransform(cameraX, cameraY + remoteEyeHeight, cameraZ,
                this.remoteYaw, this.remotePitch);

        this.eyePosition.set(cameraX, cameraY + remoteEyeHeight, cameraZ);
        this.camera.getDirection(this.eyeDirection);
        this.applyPerspective();
        this.updateViewEffect(partialTick);
        this.camera.setViewEffect(this.viewEffect);

        post.setMenuBlur(this.guiManager.blursBackground());
        BlockState cameraFluid = this.cameraFluidState();
        post.setUnderwater(shouldRenderUnderwaterEffect(DebugFlags.underwaterEffect, cameraFluid),
                this.waterVision.factor());
        post.setPortalEffect(this.remotePortalPresentation.contactProgress());
        post.nextJitter(this.taaJitter, width, height);
        this.camera.setJitter(this.taaJitter.x, this.taaJitter.y);
        this.camera.update((double) width / height);
        post.updateTaaCamera(this.camera);
        this.soundManager.updateListener(this.camera);
        this.updateRemoteEntitySounds(partialTick);
        this.hit = BlockRaycast.raycastInteractive(this.remoteWorldView.blockAccess(),
                this.eyePosition, this.eyeDirection, REACH);
        this.updateRemoteEntityHit();

        float wantedBias = post.getSettings().isTemporalAa()
                ? post.getSettings().getTaaMipBias() : 0F;
        if (wantedBias != this.appliedMipBias) {
            this.remoteWorldView.chunks().getTextureArray().setLodBias(wantedBias);
            this.appliedMipBias = wantedBias;
        }

        if (DebugFlags.wireframe) Utils.enableWireframe();
        this.remoteWorldView.render(this.camera, () -> this.renderRemotePlayers(partialTick),
                this.remoteRenderedEntities, partialTick);
        if (DebugFlags.wireframe) Utils.disableWireframe();
        if (this.hit != null && this.remoteEntityHitId == 0 && !this.guiManager.isOpen() && this.remotePlayer != null
                && this.remotePlayer.getGamemode().interactsWithWorld()) {
            this.selectionBoxRenderer.render(this.camera, this.hit.x(), this.hit.y(), this.hit.z(),
                    Blocks.getState(this.hit.block()).getOutlineShape());
            if (this.miningProgress > 0F && this.hit.x() == this.miningX
                    && this.hit.y() == this.miningY && this.hit.z() == this.miningZ) {
                int stage = Math.min(9, (int) (this.miningProgress * 10F));
                this.crackRenderer.render(this.camera, this.miningX, this.miningY, this.miningZ,
                        Blocks.getState(this.hit.block()).getOutlineShape(), stage);
            }
        }
        if (!this.hudHidden && this.remotePlayer != null && this.perspective.isFirstPerson()
                && this.remotePlayer.getGamemode() != Gamemode.SPECTATOR) {
            float handLight = this.playerLightAtEyes(partialTick);
            this.handRenderer.render(this.playerRenderer, this.heldItemMeshes, this.remotePlayer,
                    this.animState, (float) width / height, partialTick, this.viewEffect, handLight);
        }
        this.renderLavaOverlay();
    }

    /** Positional entity loops are presentation state and can be reconstructed from snapshots. */
    private void updateRemoteEntitySounds(float partialTick) {
        this.soundManager.beginMinecartSounds();
        int ridden = this.multiplayer.playerState() == null
                ? 0 : this.multiplayer.playerState().vehicleEntityId();
        for (var entry : this.remoteEntityVisuals.entrySet()) {
            Entity entity = entry.getValue().entity;
            if (!(entity instanceof MinecartEntity minecart) || minecart.isRemoved()) continue;
            double x = minecart.lastX + (minecart.x - minecart.lastX) * partialTick;
            double y = minecart.lastY + (minecart.y - minecart.lastY) * partialTick;
            double z = minecart.lastZ + (minecart.z - minecart.lastZ) * partialTick;
            double speed = Math.sqrt(minecart.motionX * minecart.motionX
                    + minecart.motionZ * minecart.motionZ);
            this.soundManager.updateMinecartSound(minecart, x, y, z, speed, entry.getKey() == ridden);
        }
        this.soundManager.endMinecartSounds();
    }

    private void updateRemoteEntityHit() {
        this.remoteEntityHitId = 0;
        this.remoteEntityHit = null;
        double nearest = this.hit == null ? REACH : Math.sqrt(
                sq(this.hit.hitX() - this.eyePosition.x)
                        + sq(this.hit.hitY() - this.eyePosition.y)
                        + sq(this.hit.hitZ() - this.eyePosition.z));
        for (var entry : this.remoteEntityVisuals.entrySet()) {
            Entity entity = entry.getValue().entity;
            double distance;
            if (entity instanceof ItemFrameEntity frame) {
                distance = frame.rayIntersection(this.eyePosition.x, this.eyePosition.y, this.eyePosition.z,
                        this.eyeDirection.x, this.eyeDirection.y, this.eyeDirection.z, nearest);
            } else if (entity instanceof MinecartEntity minecart) {
                distance = minecart.rayIntersection(this.eyePosition.x, this.eyePosition.y, this.eyePosition.z,
                        this.eyeDirection.x, this.eyeDirection.y, this.eyeDirection.z, nearest);
            } else {
                continue;
            }
            if (distance < nearest) {
                nearest = distance;
                this.remoteEntityHitId = entry.getKey();
                this.remoteEntityHit = entity;
            }
        }
    }

    private void renderRemotePlayers(float partialTick) {
        if (this.remoteWorldView == null) return;
        Dimension world = this.remoteWorldView.physicsDimension();
        if (!this.perspective.isFirstPerson() && this.remotePlayer != null) {
            int x = (int) Math.floor(this.remotePlayer.x);
            int y = (int) Math.floor(this.remotePlayer.y + this.remotePlayer.getEyeHeight(partialTick));
            int z = (int) Math.floor(this.remotePlayer.z);
            float light = ChunkRenderer.lightFactor(world.getRenderedSkyLight(x, y, z),
                    world.getBlockLight(x, y, z), world.getEnvironment().ambientLight());
            ItemStack held = this.remotePlayer.getInventory().get(this.remotePlayer.getSelectedSlot());
            this.playerRenderer.renderThirdPerson(this.remotePlayer, this.animState, this.camera, partialTick,
                    this.heldItemMeshes, held, light);
        }
        for (RemotePlayerVisual visual : this.remotePlayerVisuals.values()) {
            EntityPlayer player = visual.player;
            int x = (int) Math.floor(player.x);
            int y = (int) Math.floor(player.y + player.getEyeHeight(partialTick));
            int z = (int) Math.floor(player.z);
            float light = ChunkRenderer.lightFactor(world.getRenderedSkyLight(x, y, z),
                    world.getBlockLight(x, y, z), world.getEnvironment().ambientLight());
            this.playerRenderer.renderThirdPerson(player, visual.animation, this.camera, partialTick,
                    this.heldItemMeshes, visual.held, light);
        }
    }

    private void handleRemoteHotbarInput(Input input) {
        int before = this.remotePlayer.getSelectedSlot();
        for (int i = 0; i < 9; i++) {
            if (input.isBindPressed(this.settings.key(KeyBindings.hotbar(i + 1)))) {
                this.remotePlayer.setSelectedSlot(i);
            }
        }
        double scroll = input.getScrollY();
        if (this.remotePlayer.getGamemode() == Gamemode.SPECTATOR && scroll != 0) {
            int direction = scroll > 0 ? 1 : -1;
            float beforeSpeed = this.remotePlayer.getSpectatorFlySpeed();
            this.remotePlayer.adjustSpectatorFlySpeed(direction);
            float speed = this.remotePlayer.getSpectatorFlySpeed();
            if (speed != beforeSpeed) {
                this.remoteSpectatorSpeedDelta += direction;
                this.hudStatusText = I18n.tr("gui.hud.spectator_speed", Math.round(speed * 100F));
                this.hudStatusShownAt = System.currentTimeMillis();
            }
        } else if (scroll > 0) {
            this.remotePlayer.setSelectedSlot((this.remotePlayer.getSelectedSlot() + 8) % 9);
        } else if (scroll < 0) {
            this.remotePlayer.setSelectedSlot((this.remotePlayer.getSelectedSlot() + 1) % 9);
        }
        if (before != this.remotePlayer.getSelectedSlot()) this.itemNameShownAt = System.currentTimeMillis();
    }

    private void sendRemoteInventoryAction(int sourceSlot, int targetSlot,
                                           de.skyengine.shared.gameplay.InventoryActionRequest.Action action,
                                           int button, ItemStack offered) {
        if (this.multiplayer.session() == null) return;
        this.multiplayer.session().sendInventoryAction(new de.skyengine.shared.gameplay.InventoryActionRequest(
                ++this.remoteActionSequence, 0, sourceSlot, targetSlot, action, button,
                this.encodeRemoteItem(offered)));
    }

    private void sendRemoteContainerInventoryAction(int containerId, int sourceSlot, int targetSlot,
                                                     de.skyengine.shared.gameplay.InventoryActionRequest.Action action,
                                                     int button, ItemStack offered) {
        if (this.multiplayer.session() == null) return;
        this.multiplayer.session().sendInventoryAction(new de.skyengine.shared.gameplay.InventoryActionRequest(
                ++this.remoteActionSequence, containerId, sourceSlot, targetSlot, action, button,
                this.encodeRemoteItem(offered)));
    }

    private NetworkItemStack encodeRemoteItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return NetworkItemStack.empty();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                DataTagIO.write(stack.save(), output);
            }
            return new NetworkItemStack(this.multiplayer.itemToNetwork(stack.getItem().getId().toString()),
                    stack.getCount(), bytes.toByteArray());
        } catch (IOException error) {
            throw new IllegalStateException("Could not encode inventory action", error);
        }
    }

    /**
     * Welt-Debughilfen im fertigen Default-Framebuffer. Der Aufruf erfolgt nach TAA,
     * Wasser-/Portal-Effekten und Color-Grading, damit Linien weder reprojiziert noch getoent
     * werden; das GUI wird anschliessend weiterhin darueber gezeichnet. Nur F3+G prueft gegen
     * die aufgeloeste Szenentiefe, die uebrigen Debug-Bounds bleiben bewusst immer sichtbar.
     */
    public void renderDebugWorldOverlays() {
        Dimension visual = this.visualDimension();
        if (visual == null || this.player() == null) return;

        if (DebugFlags.chunkBorders != 0) {
            int ccx = ((int) Math.floor(this.player().x)) >> ChunkSection.SHIFT;
            int ccz = ((int) Math.floor(this.player().z)) >> ChunkSection.SHIFT;
            this.chunkBorderRenderer.render(this.camera, visual.getChunkManager(),
                    ccx, ccz, DebugFlags.chunkBorders,
                    SkyEngine.get().getWindow().getFrameBuffer().getPostDepthTexture());
        }

        if (this.world() != null) {
            WorldEditSession editor = this.world().worldEdit().session(this.player().getUuid());
            WorldEditSelection selection = editor.selection();
            if (this.isStructureWandHeld() && selection != null && selection.complete()
                    && selection.dimension().equals(this.dimension().getDimensionId())) {
                this.chunkBorderRenderer.renderBox(this.camera, selection.bounds(), 0.2F, 0.95F, 0.35F);
                BlockPos anchor = editor.structureAnchor();
                if (anchor != null) {
                    this.chunkBorderRenderer.renderBox(this.camera,
                            new de.skyengine.game.world.structure.StructureBounds(
                                    anchor.x(), anchor.y(), anchor.z(), anchor.x(), anchor.y(), anchor.z()),
                            1F, 0.75F, 0.1F);
                }
            }
            WorldEditSession.Preview preview = editor.preview();
            if (preview != null && this.dimension().getDimensionId().equals(preview.dimension())) {
                this.chunkBorderRenderer.renderBox(this.camera, preview.bounds(), 0.75F, 0.25F, 0.95F);
                this.chunkBorderRenderer.renderBox(this.camera,
                        new de.skyengine.game.world.structure.StructureBounds(preview.x(), preview.y(), preview.z(),
                                preview.x(), preview.y(), preview.z()), 0.15F, 0.9F, 1F);
            }
        }

        if (DebugFlags.entityHitboxes) {
            if (this.remoteWorldView == null) {
                this.entityHitboxRenderer.render(this.camera, this.player(), visual, this.renderedPartialTick);
            } else {
                this.remoteDebugEntities.clear();
                this.remoteDebugEntities.addAll(this.remoteRenderedEntities);
                for (RemotePlayerVisual player : this.remotePlayerVisuals.values()) {
                    this.remoteDebugEntities.add(player.player);
                }
                this.entityHitboxRenderer.render(this.camera, this.player(), this.remoteDebugEntities,
                        this.renderedPartialTick);
            }
        }
    }

    static boolean shouldRenderUnderwaterEffect(boolean enabled, BlockState cameraFluid) {
        return enabled && cameraFluid != null && cameraFluid.isFluid()
                && !cameraFluid.getBlock().getFluidInfo().lava;
    }

    /** Licht an der interpolierten Augenposition; verhindert 20-TPS-Sprünge beim Rendern. */
    private float playerLightAtEyes(float partialTick) {
        double x = this.player().lastX + (this.player().x - this.player().lastX) * partialTick;
        double y = this.player().lastY + (this.player().y - this.player().lastY) * partialTick
                + this.player().getEyeHeight(partialTick);
        double z = this.player().lastZ + (this.player().z - this.player().lastZ) * partialTick;
        return this.lightAt(x, y, z);
    }

    /**
     * Wahrgenommener Licht-Faktor an einer Weltposition. Statt eine einzelne ganzzahlige
     * Blockzelle zu wählen, werden die acht umliegenden Zellzentren trilinear gemischt. So
     * bleibt die Minecraft-Lichtkurve erhalten, aber Hand und gehaltenes Item springen beim
     * Wechsel zwischen den diskreten Lichtleveln nicht mehr in sichtbaren Etappen.
     */
    private float lightAt(double x, double y, double z) {
        Dimension dimension = this.visualDimension();
        if (dimension == null) return 1F;
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
                            dimension.getRenderedSkyLight(x0 + dx, y0 + dy, z0 + dz),
                            dimension.getBlockLight(x0 + dx, y0 + dy, z0 + dz),
                            dimension.getEnvironment().ambientLight());
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
        if (this.settings.viewBobbing && this.perspective.isFirstPerson() && !this.player().isFlying()) {
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
        Dimension dimension = this.visualDimension();
        if (dimension == null) return;
        boolean front = this.perspective == CameraPerspective.THIRD_PERSON_FRONT;
        this.camRayDirection.set(this.eyeDirection);
        if (!front) this.camRayDirection.negate();

        double dist = THIRD_PERSON_DISTANCE;
        BlockRaycast.Hit blocked = BlockRaycast.raycast(dimension, this.eyePosition, this.camRayDirection, dist);
        if (blocked != null) {
            double dx = blocked.hitX() - this.eyePosition.x;
            double dy = blocked.hitY() - this.eyePosition.y;
            double dz = blocked.hitZ() - this.eyePosition.z;
            dist = Math.max(0.3, Math.sqrt(dx * dx + dy * dy + dz * dz) - 0.1);
        }
        /* getPosition() ist die Live-Referenz der Kamera — bewusst in-place versetzen. */
        this.camera.getPosition().fma(dist, this.camRayDirection);
        if (front) {
            this.camera.setRotation(this.player().yaw + 180F, -this.player().pitch);
        }
    }

    /**
     * GUI-Anteil des Frames — zeichnet in den Default-Framebuffer, NACH der Post-Kette
     * (pixelgenau, kein Grading/AA). Zentrale GUI-Verwaltung: HUD (kein GuiScreen) bzw.
     * GuiScreen-Overlay + Cursor-Sync.
     */
    public void renderGui(int width, int height) {
        EntityPlayer player = this.player();
        boolean playableWorld = this.dimension() != null || this.remoteWorldView != null;
        /* Spectator zeigt wie MC keine Hotbar (Crosshair bleibt); ohne Spieler/Welt gibt es keine. */
        boolean showHotbar = player != null && player.getGamemode() != Gamemode.SPECTATOR;
        boolean showDebug = !this.hudHidden && this.debugOverlay.isVisible()
                && this.visualDimension() != null;
        FrameProfiler.cpuStart(FrameProfiler.Cpu.GUI);
        FrameProfiler.gpuBegin(FrameProfiler.Gpu.GUI);
        /* Im Hauptmenü kein HUD (Inventar null -> GuiManager überspringt Hotbar/Crosshair);
           Crosshair nur in First Person (in den F5-Ansichten zielt man nicht über die Bildmitte). */
        this.guiManager.render(width, height,
                player != null && playableWorld && !this.hudHidden
                        ? player.getInventory() : null,
                selectedSlotForGui(player), showHotbar && !this.hudHidden,
                !this.hudHidden && this.perspective.isFirstPerson(),
                this.hudHidden ? 0F : this.itemNameAlpha(),
                this.hudHidden ? "" : this.hudStatusText,
                this.hudHidden ? 0F : this.hudStatusAlpha(), player,
                showDebug && FrameProfiler.isEnabled() ? this.profilerOverlayPass : null);
        if (!this.hudHidden && playableWorld && !this.guiManager.isOpen()) {
            this.chatHud.render(this.guiManager, this.chat, this.guiManager.vHeight() - 40F, false);
        }
        if (showDebug) {
            if (FrameProfiler.isEnabled()) {
                FrameProfiler.cpuStop(FrameProfiler.Cpu.GUI);
                FrameProfiler.cpuStart(FrameProfiler.Cpu.PROFILER_UI);
            }
            ChunkRenderer debugChunks = this.view() != null ? this.view().chunks()
                    : this.remoteWorldView == null ? null : this.remoteWorldView.chunks();
            if (debugChunks != null) {
                this.debugOverlay.render(this.guiManager, this.visualDimension(), debugChunks, player);
            }
            if (FrameProfiler.isEnabled()) {
                FrameProfiler.cpuStop(FrameProfiler.Cpu.PROFILER_UI);
                FrameProfiler.cpuStart(FrameProfiler.Cpu.GUI);
            }
        }
        /* Gespeichert-Meldung NACH dem GuiScreen: sie soll auch über dem abgedunkelten
           Pausenmenü lesbar sein (im Hud läge sie unter dessen Dim). */
        if (!this.hudHidden && this.dimension() != null) this.saveToast.render(this.guiManager);
        FrameProfiler.gpuEnd(FrameProfiler.Gpu.GUI);
        FrameProfiler.cpuStop(FrameProfiler.Cpu.GUI);
    }

    static int selectedSlotForGui(EntityPlayer player) {
        return player == null ? 0 : player.getSelectedSlot();
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
        ItemStack held = this.player().getInventory().get(this.player().getSelectedSlot());
        if (this.guiManager.isOpen()
                || this.player().getGamemode() != Gamemode.SURVIVAL
                || this.player().isDead()
                || !(held.getItem() instanceof FoodItem food)
                || this.player().getFoodLevel() >= EntityPlayer.MAX_FOOD
                || !input.isBindDown(this.settings.key(KeyBindings.USE))) {
            this.eatingTicks = 0;
            this.animState.clearEating();
            return;
        }
        if (++this.eatingTicks >= EAT_TICKS) {
            this.eatingTicks = 0;
            this.animState.clearEating();
            this.player().eat(food.getNutrition(), food.getSaturation());
            this.soundManager.playBurp();
            held.setCount(held.getCount() - 1);
            if (held.getCount() <= 0) {
                this.player().getInventory().set(this.player().getSelectedSlot(), ItemStack.EMPTY);
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
        Dimension dimension = this.visualDimension();
        if (dimension == null) return null;
        Vector3d eye = this.camera.getPosition();
        int bx = (int) Math.floor(eye.x);
        int by = (int) Math.floor(eye.y);
        int bz = (int) Math.floor(eye.z);
        BlockState state = Blocks.getState(dimension.getRenderedBlock(bx, by, bz));
        BlockState above = Blocks.getState(dimension.getRenderedBlock(bx, by + 1, bz));
        return isCameraSubmerged(eye.y, by, state, above) ? state : null;
    }

    /** Tick-Sample am echten Spielerauge; steuert Water Vision und Unterwasser-Audio. */
    private boolean playerEyesUnderwater() {
        Dimension dimension = this.visualDimension();
        if (dimension == null) return false;
        double eyeY = this.player().y + this.player().getEyeHeight(1F);
        int bx = (int) Math.floor(this.player().x);
        int by = (int) Math.floor(eyeY);
        int bz = (int) Math.floor(this.player().z);
        BlockState state = Blocks.getState(dimension.getRenderedBlock(bx, by, bz));
        BlockState above = Blocks.getState(dimension.getRenderedBlock(bx, by + 1, bz));
        return isCameraSubmerged(eyeY, by, state, above)
                && !state.getBlock().getFluidInfo().lava;
    }

    /** Pure Oberflaechenpruefung, getrennt vom Dimension-Zugriff fuer Regressionstests. */
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
        this.multiplayer.close();
        this.closeRemoteWorldView();
        this.saveCurrentWorld(true); // Welt-Zustand auch beim direkten Beenden aus dem Spiel sichern
        if (this.session != null) {
            this.session.dispose();
            this.session = null;
        }
        this.selectionBoxRenderer.dispose();
        if (this.chunkBorderRenderer != null) this.chunkBorderRenderer.dispose();
        if (this.entityHitboxRenderer != null) this.entityHitboxRenderer.dispose();
        if (this.crackRenderer != null) this.crackRenderer.dispose();
        if (this.structurePreviewRenderer != null) this.structurePreviewRenderer.dispose();
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
        return de.skyengine.game.world.PlayerBlockActions.destroyProgress(this.player(), state);
    }

    /**
     * Beginnt den Abbau am anvisierten Block (Vorbild {@code MultiPlayerGameMode.startDestroyBlock}).
     * Creative zerstört sofort und sperrt {@link #DESTROY_DELAY} Ticks; Survival setzt den
     * Fortschritt neu an bzw. bricht sofort, wenn ein Tick schon reicht (Pflanzen, TNT).
     */
    private void startDestroyBlock() {
        BlockState state = Blocks.getState(this.hit.block());
        if (this.player().getGamemode().isInstantBreak()) {
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
        if (this.player().getGamemode().isInstantBreak()) {
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
            this.dimension().particles().blockHit(state, this.hit.hitX(), this.hit.hitY(), this.hit.hitZ(),
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
        if (this.hit == null || !this.dimension().isPlayerInteractionReady(
                this.hit.x(), this.hit.y(), this.hit.z())
                || this.dimension().getBlock(this.hit.x(), this.hit.y(), this.hit.z()) != broken.getId()) {
            this.resetMining();
            return false;
        }
        /* Loot VOR onBreak/setBlock auswerten — solange State und BlockEntity lesbar sind. */
        ItemStack held = this.player().getInventory().get(this.player().getSelectedSlot());
        java.util.ArrayList<ItemStack> drops = new java.util.ArrayList<>(2);
        if (this.player().getGamemode().dropsItems() && isHarvestable(broken, held)) {
            var context = new LootContext(this.dimension(),
                    this.hit.x(), this.hit.y(), this.hit.z(), broken, held,
                    LootContext.Cause.PLAYER, 0.0F, this.dimension().random());
            broken.getBlock().appendDrops(context, (stack, x, y, z) -> drops.add(stack));
        }
        int breakX = this.hit.x(), breakY = this.hit.y(), breakZ = this.hit.z();
        boolean removed = this.dimension().runPlayerBlockChange(() -> {
            broken.getBlock().onBreak(this.dimension(), breakX, breakY, breakZ, broken);
            return this.dimension().setBlock(breakX, breakY, breakZ, Blocks.AIR);
        });
        if (!removed) {
            this.resetMining();
            return false;
        }

        /* PortalBehavior kollabiert die ganze Flaeche und erzeugt genau einen Glas-Effekt. */
        if (!de.skyengine.game.world.dimension.NetherPortalShape.isPortalState(broken.getId())) {
            this.soundManager.playBreak(broken.getBlock().getSoundGroup(),
                    this.hit.x() + 0.5, this.hit.y() + 0.5, this.hit.z() + 0.5);
            this.dimension().particles().blockBreak(breakX, breakY, breakZ, broken);
        }

        for (ItemStack drop : drops) this.dimension().spawnItem(this.hit.x() + 0.5,
                this.hit.y() + 0.5, this.hit.z() + 0.5, drop);

        /* Tool-Abnutzung (nur Survival bei Härte > 0): zerbricht bei erreichter Haltbarkeit. */
        if (applyDurability && held.getItem() instanceof ToolItem tool) {
            held.setDamage(held.getDamage() + 1);
            if (held.getDamage() >= tool.getTier().durability()) {
                this.emitHeldItemParticles(held);
                this.player().getInventory().set(this.player().getSelectedSlot(), ItemStack.EMPTY);
            }
        } else if (applyDurability && held.getItem() instanceof de.skyengine.game.world.item.ShearsItem
                && (broken.isLeaves() || broken.getBlock().getIdentifier().path().equals("short_grass")
                || broken.getBlock().getIdentifier().path().equals("fern")
                || broken.getBlock().getIdentifier().path().equals("tall_grass")
                || broken.getBlock().getIdentifier().path().equals("dead_bush"))) {
            held.setDamage(held.getDamage() + 1);
            if (held.getDamage() >= de.skyengine.game.world.item.ShearsItem.DURABILITY) {
                this.emitHeldItemParticles(held);
                this.player().getInventory().set(this.player().getSelectedSlot(), ItemStack.EMPTY);
            }
        }

        this.resetMining();
        return true;
    }

    /** MC-Harvest-Regel: ohne Tool-Anforderung droppt alles; sonst passende Klasse + Mindest-Tier. */
    private static boolean isHarvestable(BlockState state, ItemStack held) {
        return de.skyengine.game.world.PlayerBlockActions.isHarvestable(state, held);
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
        if (!this.player().getGamemode().interactsWithWorld()) {
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

        /* Die Debug-Axt setzt Position 2 beziehungsweise den Anchor nur auf der Klickflanke. Der normale Haltepfad
           würde den Befehl sonst alle RIGHT_CLICK_DELAY Ticks erneut auslösen. */
        if (input.isBindDown(this.settings.key(KeyBindings.USE)) && this.rightClickDelay == 0
                && !usingItem && !this.isStructureWandHeld()) {
            this.startUseItem();
        }

        this.continueAttack(!instantAttack && input.isBindDown(this.settings.key(KeyBindings.ATTACK)));
    }

    /**
     * Wirft aus dem aktiven Hotbar-Slot (Vorbild MC {@code Player.drop}): ein Item, mit STRG den
     * ganzen Stapel. Geschwungen wird nur bei Erfolg — auf einem leeren Slot passiert nichts.
     */
    private void dropSelectedItem(boolean fullStack) {
        ItemStack held = this.player().getInventory().get(this.player().getSelectedSlot());
        if (held.isEmpty()) return;
        if (this.isStructureWandHeld()) {
            this.clearWorldEditSelection();
            this.stopDestroyBlock();
            return;
        }
        int amount = fullStack ? held.getCount() : 1;
        this.dimension().throwItem(this.player(), this.player().getInventory().extract(this.player().getSelectedSlot(), amount));
        this.animState.swing();
    }

    /**
     * Wirft einen Stapel aus einem offenen Container-GUI in die Welt ({@code GuiContainer}):
     * Drop-Taste im GUI, Klick neben das Fenster und das Auswerfen beim Schließen laufen alle
     * hier durch. Geschwungen wird wie beim normalen Drop — sonst fehlt dem Wurf das Feedback.
     */
    public void dropFromGui(ItemStack stack) {
        if (this.dimension() == null || this.player() == null) return;
        this.dimension().throwItem(this.player(), stack);
        this.animState.swing();
    }

    /** Q mit der Debug-Axt setzt die allgemeine Selektion zurueck; Clipboard/Preview bleiben. */
    public void clearWorldEditSelection() {
        if (this.world() == null || this.player() == null) return;
        this.world().worldEdit().session(this.player().getUuid()).clearSelection();
        this.chat.addMessage("§e" + I18n.tr("command.worldedit.selection_reset"));
    }

    /** MC {@code MultiPlayerGameMode.hasMissTime}: im Creative gibt es keine Schlagsperre. */
    private boolean hasMissTime() {
        return this.player().getGamemode() != Gamemode.CREATIVE;
    }

    /**
     * Ein Angriffs-Klick (Vorbild {@code Minecraft.startAttack}). Rückgabe {@code true}, wenn der
     * Block sofort zerbrach — dann unterdrückt der Aufrufer den Dauer-Abbau in diesem Tick.
     * Geschwungen wird IMMER, auch ins Leere.
     */
    private boolean startAttack() {
        if (this.missTime > 0) return false;

        if (this.handleWorldEditToolClick(true)) return true;

        boolean endAttack = false;
        if (this.minecartHit != null) {
            MinecartEntity minecart = this.minecartHit;
            ItemStack held = this.player().getInventory().get(this.player().getSelectedSlot());
            boolean pickaxe = held.getItem() instanceof ToolItem tool
                    && tool.getType() == de.skyengine.game.world.item.ToolType.PICKAXE;
            minecart.attack(this.dimension(), this.player().getGamemode() == Gamemode.CREATIVE, pickaxe);
            this.soundManager.playStrongAttack();
            this.stopDestroyBlock();
            endAttack = true;
        } else if (this.itemFrameHit != null) {
            this.itemFrameHit.attack(this.dimension(), this.player().getGamemode() == Gamemode.CREATIVE);
            this.stopDestroyBlock();
            endAttack = true;
        } else if (this.hit != null) {
            this.startDestroyBlock();
            if (Blocks.getState(this.dimension().getBlock(this.hit.x(), this.hit.y(), this.hit.z())).isAir()) {
                endAttack = true;
            }
        } else {
            this.soundManager.playSwingAttack();
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

        /* Position 1 beziehungsweise der Anchor wird nur über die Klickflanke gesetzt. Solange die Debug-Axt
           gehalten wird, darf der Dauer-Abbau niemals auf den markierten Block durchfallen. */
        if (this.isStructureWandHeld()) {
            this.stopDestroyBlock();
            return;
        }

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

        if (this.handleWorldEditToolClick(false)) return;

        ItemStack held = this.player().getInventory().get(this.player().getSelectedSlot());
        Item beforeItem = held.getItem();
        int beforeCount = held.getCount();

        if (!this.useItemOn()) return;

        this.animState.swing();
        ItemStack after = this.player().getInventory().get(this.player().getSelectedSlot());
        boolean stackChanged = after.getItem() != beforeItem || after.getCount() != beforeCount;
        if (beforeItem != null && (stackChanged || this.player().getGamemode() == Gamemode.CREATIVE)) {
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
        ItemStack held = this.player().getInventory().get(this.player().getSelectedSlot());
        if (this.minecartHit != null && this.minecartHit.interact(this.player())) return true;
        if (this.itemFrameHit != null
                && this.itemFrameHit.interact(this.dimension(), held,
                this.player().getGamemode() == Gamemode.CREATIVE)) return true;
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
        boolean placingWhileSneaking = this.player().isSecondaryUseActive()
                && held.getItem() != null
                && (held.getItem().getPlacedBlock() != null || held.getItem() instanceof ItemFrameItem);

        if (!placingWhileSneaking && this.session.pendingDimensionSwitch == null) {
            PortalController.Travel travel = this.session.portalController.use(this.dimension(),
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
                && hitState.getBlock().onUse(this.dimension(), this.hit.x(), this.hit.y(), this.hit.z(),
                        hitState, this.player().yaw)) {
            return true;
        }

        /* Truhe: Rechtsklick öffnet das Truhen-GUI (Deckel geht auf). */
        if (!placingWhileSneaking && this.tryOpenChest()) return true;
        if (!placingWhileSneaking && this.tryOpenHopper()) return true;
        if (!placingWhileSneaking && this.tryOpenDispenser()) return true;
        if (!placingWhileSneaking && this.tryOpenRegisteredBlockEntityMenu()) return true;
        if (!placingWhileSneaking && this.tryOpenCraftingStation()) return true;

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
        BlockState place = block.getPlacementState(this.dimension(), px, py, pz,
                this.hit.faceX(), this.hit.faceY(), this.hit.faceZ(),
                relHitX, relHitY, relHitZ, this.player().yaw, this.player().pitch,
                this.player().isSecondaryUseActive());

        /* place == null: ein Behavior lehnt ab (z.B. Tür ohne Platz). Sonst nicht in den
           eigenen Körper bauen - gegen die ECHTE Kollisionsform testen, damit dünne Blöcke
           (Panes, Zäune) neben einem platzierbar bleiben. */
        if (place == null || this.collidesWithPlayer(place, px, py, pz)
                || this.collidesWithEntities(place, px, py, pz)) {
            return false;
        }
        if (!this.dimension().runPlayerBlockChange(() -> this.dimension().placeBlock(px, py, pz, place, held))) return false;
        this.soundManager.playPlace(place.getBlock().getSoundGroup(), px + 0.5, py + 0.5, pz + 0.5);
        /* Survival verbraucht den Block (Creative baut unbegrenzt, wie MC). */
        if (this.player().getGamemode() == Gamemode.SURVIVAL) this.consumeHeld(null);
        return true;
    }

    /**
     * Pick Block (nur Creative): legt den anvisierten Block in den ausgewählten Hotbar-Slot.
     */
    private void pickBlock() {
        if (this.player().getGamemode() != Gamemode.CREATIVE) return;
        if (this.minecartHit != null) {
            Item item = Items.get(Identifier.of("minecart"));
            if (item != null) this.player().getInventory().set(this.player().getSelectedSlot(), new ItemStack(item, 1));
            this.itemNameShownAt = System.currentTimeMillis();
            return;
        }
        if (this.itemFrameHit != null) {
            this.player().getInventory().set(this.player().getSelectedSlot(), this.itemFrameHit.getPickResult());
            this.itemNameShownAt = System.currentTimeMillis();
            return;
        }
        if (this.hit == null) return;
        Block picked = Blocks.getState(this.hit.block()).getBlock();
        Item item = Items.forBlock(picked); // BlockItem bzw. places_block-Item (Staub); null bei Air/Fluid
        if (item != null) {
            this.player().getInventory().set(this.player().getSelectedSlot(), new ItemStack(item, 1));
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
        if (!this.dimension().isPlayerInteractionReady(x, y, z)) return false;
        if (!this.dimension().placeItemFrame(x, y, z, direction)) return false;
        if (this.player().getGamemode() == Gamemode.SURVIVAL) this.consumeHeld(null);
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
        if (!this.dimension().isPlayerInteractionReady(px, py, pz)
                || !this.isReplaceable(this.dimension().getBlock(px, py, pz))) return null;
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

        if (!this.dimension().runPlayerBlockChange(() -> this.dimension().setBlock(
                this.hit.x(), this.hit.y(), this.hit.z(),
                target.with(Properties.SLAB_TYPE, SlabType.DOUBLE).getId()))) return false;
        this.soundManager.playPlace(block.getSoundGroup(),
                this.hit.x() + 0.5, this.hit.y() + 0.5, this.hit.z() + 0.5);
        if (this.player().getGamemode() == Gamemode.SURVIVAL) this.consumeHeld(null);
        return true;
    }

    /**
     * Rechtsklick auf eine Truhe öffnet ihr GUI (Truhe + Spielerinventar) und öffnet den Deckel.
     * Bei einer Doppeltruhe kommt die Partnerhälfte dazu: 6 Reihen, beide Deckel.
     */
    private boolean tryOpenChest() {
        int x = this.hit.x(), y = this.hit.y(), z = this.hit.z();
        BlockEntity be = this.dimension().getBlockEntity(x, y, z);
        if (!(be instanceof ChestBlockEntity chest)) return false;

        BlockState state = Blocks.getState(this.dimension().getBlock(x, y, z));
        ChestType type = state.getValues().containsKey(Properties.CHEST_TYPE)
                ? state.get(Properties.CHEST_TYPE) : ChestType.SINGLE;
        ChestBlockEntity partner = null;
        int partnerX = x, partnerZ = z;
        if (type != ChestType.SINGLE) {
            Direction toPartner = ChestType.connectedDirection(state.get(Properties.FACING), type);
            partnerX = x + toPartner.offsetX();
            partnerZ = z + toPartner.offsetZ();
            if (this.dimension().getBlockEntity(partnerX, y, partnerZ) instanceof ChestBlockEntity other) {
                partner = other;
            }
        }
        /* Reihenfolge wie MC (ChestBlock.getBlockType): die RECHTE Hälfte liefert die oberen
           drei Reihen. */
        ChestBlockEntity top = type == ChestType.LEFT && partner != null ? partner : chest;
        ChestBlockEntity bottom = top == chest ? partner : chest;
        this.guiManager.open(new GuiChest(top, bottom, this.player().getInventory()));

        return true;
    }

    /** Rechtsklick auf einen Trichter öffnet sein GUI (5 Slots + Spielerinventar). */
    private boolean tryOpenHopper() {
        int x = this.hit.x(), y = this.hit.y(), z = this.hit.z();
        BlockEntity be = this.dimension().getBlockEntity(x, y, z);
        if (!(be instanceof de.skyengine.game.world.block.entity.HopperBlockEntity hopper)) return false;
        this.guiManager.open(new de.skyengine.graphics.gui.screens.GuiHopper(hopper, this.player().getInventory()));
        return true;
    }

    /** Minecart-Item: nur direkt auf einer Schiene platzieren, Steigungen sitzen einen halben Block höher. */
    private boolean tryPlaceMinecart() {
        BlockState rail = Blocks.getState(this.hit.block());
        if (!rail.getValues().containsKey(Properties.RAIL_SHAPE)
                && !rail.getValues().containsKey(Properties.STRAIGHT_RAIL_SHAPE)) return false;
        double yOffset = de.skyengine.game.world.block.behavior.RailBehavior.shape(rail).isAscending()
                ? 0.5625 : 0.0625;
        this.dimension().spawnMinecart(this.hit.x() + 0.5, this.hit.y() + yOffset, this.hit.z() + 0.5);
        if (this.player().getGamemode() == Gamemode.SURVIVAL) this.consumeHeld(null);
        return true;
    }

    /** Rechtsklick auf Dispenser oder Dropper öffnet das gemeinsame 9-Slot-GUI. */
    private boolean tryOpenDispenser() {
        BlockEntity blockEntity = this.dimension().getBlockEntity(this.hit.x(), this.hit.y(), this.hit.z());
        if (!(blockEntity instanceof DispenserBlockEntity dispenser)) return false;
        this.guiManager.open(new GuiDispenser(dispenser, this.player().getInventory()));
        return true;
    }

    /** Oeffnet jedes ueber crafting_grid deklarierte, temporaere Crafting-Raster. */
    private boolean tryOpenCraftingStation() {
        BlockState state = Blocks.getState(this.dimension().getBlock(this.hit.x(), this.hit.y(), this.hit.z()));
        Block block = state.getBlock();
        if (block.getCraftingWidth() <= 0 || block.getCraftingHeight() <= 0
                || block.getCraftingRecipeType() == null) return false;
        this.guiManager.open(new GuiCraftingStation(block.getCraftingWidth(), block.getCraftingHeight(),
                block.getCraftingRecipeType(), this.player().getInventory()));
        return true;
    }

    private boolean tryOpenRegisteredBlockEntityMenu() {
        BlockEntity entity = this.dimension().getBlockEntity(this.hit.x(), this.hit.y(), this.hit.z());
        de.skyengine.graphics.gui.GuiScreen screen = BlockEntityMenus.create(
                entity, this.player().getInventory());
        if (screen == null) return false;
        this.guiManager.open(screen);
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
        if (target != null && this.dimension().runPlayerBlockChange(() ->
                de.skyengine.game.world.dimension.NetherPortalShape.activateNear(
                        this.dimension(), target[0], target[1], target[2]))) {
            if (this.dimension().getSoundManager() != null) {
                this.dimension().getSoundManager().playIgnite(target[0] + 0.5, target[1] + 0.5, target[2] + 0.5);
                this.dimension().getSoundManager().playPortalTrigger(
                        target[0] + 0.5, target[1] + 1.5, target[2] + 0.5);
            }
            this.damageFlintAndSteel(held);
            return true;
        }

        BlockState state = Blocks.getState(this.hit.block());
        de.skyengine.game.world.block.behavior.ExplosionBehavior explosive =
                state.getBlock().getBehavior(de.skyengine.game.world.block.behavior.ExplosionBehavior.class);
        if (explosive == null) return false;

        /* TNT spielt beim Prime bereits seinen Fuse-Sound in Dimension.spawnPrimedTnt. Minecrafts
           TntBlock.useItemOn legt hier keinen zusaetzlichen Feuerzeug-/Ignite-Sound darueber. */
        explosive.prime(this.dimension(), this.hit.x(), this.hit.y(), this.hit.z());
        this.damageFlintAndSteel(held);
        return true;
    }

    private void damageFlintAndSteel(ItemStack held) {
        if (this.player().getGamemode() != Gamemode.SURVIVAL) return;
        held.setDamage(held.getDamage() + 1);
        if (held.getDamage() >= FlintAndSteelItem.DURABILITY) {
            this.emitHeldItemParticles(held);
            this.player().getInventory().set(this.player().getSelectedSlot(), ItemStack.EMPTY);
        }
    }

    private boolean handleBucket(BucketItem bucket) {
        boolean consume = this.player().getGamemode() == Gamemode.SURVIVAL;

        if (bucket.isEmpty()) {
            /* Aufnehmen: fluid-bewusster Strahl, damit Wasser/Lava als Ziel zählt. Nur eine
               Quelle (LEVEL 0, nicht fallend). */
            BlockRaycast.Hit fhit = BlockRaycast.raycastInteractive(this.dimension(), this.eyePosition,
                    this.eyeDirection, REACH, true);
            if (fhit == null) return false;
            BlockState state = Blocks.getState(fhit.block());
            if (!state.isFluid() || state.get(Properties.FALLING) || state.get(Properties.LEVEL) != 0) return false;
            if (!this.dimension().runPlayerBlockChange(() ->
                    this.dimension().setBlock(fhit.x(), fhit.y(), fhit.z(), Blocks.AIR))) return false;
            this.dimension().playBucketFill(fhit.x(), fhit.y(), fhit.z(),
                    state.getBlock().getFluidInfo().lava);
            if (consume) {
                String id = state.getBlock().getFluidInfo().lava ? "lava_bucket" : "water_bucket";
                this.consumeHeld(Items.get(Identifier.of(id)));
            }
            return true;
        }

        /* Platzieren wie ein Block: der normale (fluid-ignorierende) Strahl this.hit zielt durch
           Wasser hindurch auf die feste Blockseite. Quelle kommt an die Trefferseite (Luft/Fluid). */
        int[] t = this.placementTarget();
        if (t == null) return false;

        Block fluid = bucket.getFluid();
        if (this.dimension().getEnvironment().ultrawarm() && fluid.getFluidInfo() != null
                && !fluid.getFluidInfo().lava) {
            this.dimension().playFluidExtinguish(t[0], t[1], t[2]);
            if (consume) this.consumeHeld(Items.get(Identifier.of("bucket")));
            return true;
        }
        int source = fluid.getDefaultState()
                .with(Properties.LEVEL, 0).with(Properties.FALLING, false).getId();
        if (!this.dimension().runPlayerBlockChange(() ->
                this.dimension().setBlock(t[0], t[1], t[2], source))) return false;
        this.dimension().scheduleTick(t[0], t[1], t[2], 1);
        this.dimension().playBucketEmpty(t[0], t[1], t[2], fluid.getFluidInfo().lava);
        if (consume) this.consumeHeld(Items.get(Identifier.of("bucket")));
        return true;
    }

    /** Verbraucht einen Eimer aus dem gehaltenen Slot und legt das Ergebnis-Item ab. */
    private void consumeHeld(Item result) {
        ItemStack held = this.player().getInventory().get(this.player().getSelectedSlot());
        if (held.getCount() > 1) {
            held.setCount(held.getCount() - 1);
            if (result != null) this.player().getInventory().insert(new ItemStack(result, 1));
        } else {
            this.player().getInventory().set(this.player().getSelectedSlot(), result != null ? new ItemStack(result, 1) : ItemStack.EMPTY);
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
            if (local.copy().move(px, py, pz).intersects(this.player().getBoundingBox())) {
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
            if (this.dimension().intersectsCollidableEntity(local.copy().move(px, py, pz))) {
                return true;
            }
        }
        return false;
    }

    private void handleHotbarInput(Input input) {
        int before = this.player().getSelectedSlot();
        for (int i = 0; i < 9; i++) {
            if (input.isBindPressed(this.settings.key(KeyBindings.hotbar(i + 1)))) {
                this.player().setSelectedSlot(i);
            }
        }
        /* Mausrad: hoch = vorheriger Slot, runter = nächster (mit Wrap), wie in Minecraft. */
        double scroll = input.getScrollY();
        boolean changeWorldEditMode = scroll != 0 && this.isStructureWandHeld()
                && input.isBindDown(this.settings.key(KeyBindings.SNEAK));
        if (changeWorldEditMode) {
            WorldEditSession.ToolMode mode = this.world().worldEdit().session(this.player().getUuid())
                    .cycleToolMode(scroll > 0 ? -1 : 1);
            this.hudStatusText = I18n.tr("command.worldedit.tool_mode_status",
                    I18n.tr("command.worldedit.tool_mode_"
                            + mode.name().toLowerCase(java.util.Locale.ROOT)));
            this.hudStatusShownAt = System.currentTimeMillis();
        } else if (this.player().getGamemode() == Gamemode.SPECTATOR && scroll != 0) {
            float beforeSpeed = this.player().getSpectatorFlySpeed();
            this.player().adjustSpectatorFlySpeed(scroll);
            float speed = this.player().getSpectatorFlySpeed();
            if (speed != beforeSpeed) {
                this.hudStatusText = I18n.tr("gui.hud.spectator_speed", Math.round(speed * 100F));
                this.hudStatusShownAt = System.currentTimeMillis();
            }
        } else if (scroll > 0) {
            this.player().setSelectedSlot((this.player().getSelectedSlot() + 8) % 9);
        } else if (scroll < 0) {
            this.player().setSelectedSlot((this.player().getSelectedSlot() + 1) % 9);
        }
        if (this.player().getSelectedSlot() != before) {
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
                this.player().toggleFlying();
                this.logger.debug("Flying: " + this.player().isFlying());
                this.lastSpacePressTime = 0; // verbraucht, damit ein dritter Tipp nicht sofort wieder toggelt
            } else {
                this.lastSpacePressTime = now;
            }
        }
        /* Gamemode durchschalten (Cheat-Feature-Keybind, Default G). */
        if (input.isBindPressed(this.settings.key(KeyBindings.GAMEMODE))) {
            this.player().setGamemode(this.player().getGamemode().next());
            this.logger.debug("Gamemode: " + this.player().getGamemode());
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

        if (this.visualDimension() != null && input.isBindPressed(this.settings.key(KeyBindings.TOGGLE_HUD))) {
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
        if (this.session == null && this.multiplayer.session() != null) {
            this.guiManager.open(new GuiChat(this.chat, this.chatHud, initial, value -> {
                String message = value == null ? "" : value.trim();
                if (message.isEmpty()) return;
                if (message.startsWith("/")) {
                    this.multiplayer.session().sendCommand(++this.remoteCommandSequence,
                            message.substring(1));
                } else this.multiplayer.session().sendChat(message);
            }));
            return;
        }
        CommandContext.DimensionAccess dimensions = new CommandContext.DimensionAccess() {
            @Override public Identifier current() {
                return GameContainer.this.dimension().getDimensionId();
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
        CommandContext.StructureAccess structures = new CommandContext.StructureAccess() {
            private int x() { return (int) Math.floor(GameContainer.this.player().x); }
            private int y() { return (int) Math.floor(GameContainer.this.player().y); }
            private int z() { return (int) Math.floor(GameContainer.this.player().z); }
            private WorldEditSession editor() {
                return GameContainer.this.world().worldEdit().session(GameContainer.this.player().getUuid());
            }
            @Override public String pos1() {
                int py = (int) Math.floor(GameContainer.this.player().y) - 1;
                editor().pos1(GameContainer.this.dimension().getDimensionId(), x(), py, z());
                return I18n.tr("command.worldedit.pos1_success", x(), py, z());
            }
            @Override public String pos2() {
                int py = (int) Math.floor(GameContainer.this.player().y) - 1;
                editor().pos2(GameContainer.this.dimension().getDimensionId(), x(), py, z());
                return I18n.tr("command.worldedit.pos2_success", x(), py, z());
            }
            private BlockRaycast.Hit targetedBlock() {
                BlockRaycast.Hit targeted = GameContainer.this.hit;
                if (targeted == null) throw new IllegalStateException(
                        I18n.tr("command.worldedit.no_target"));
                return targeted;
            }
            @Override public String hpos1() {
                BlockRaycast.Hit targeted = targetedBlock();
                editor().pos1(GameContainer.this.dimension().getDimensionId(),
                        targeted.x(), targeted.y(), targeted.z());
                return I18n.tr("command.worldedit.pos1_success",
                        targeted.x(), targeted.y(), targeted.z());
            }
            @Override public String hpos2() {
                BlockRaycast.Hit targeted = targetedBlock();
                editor().pos2(GameContainer.this.dimension().getDimensionId(),
                        targeted.x(), targeted.y(), targeted.z());
                return I18n.tr("command.worldedit.pos2_success",
                        targeted.x(), targeted.y(), targeted.z());
            }
            @Override public StructureTemplate save(String reference, boolean includeAir,
                                                    boolean overwrite, boolean useAnchor) throws Exception {
                return editor().save(GameContainer.this.dimension(), reference, includeAir, overwrite,
                        x(), y(), z(), useAnchor ? WorldEditSession.OperationOrigin.ANCHOR
                                : WorldEditSession.OperationOrigin.PLAYER);
            }
            @Override public StructureTemplate load(String reference) throws Exception {
                return editor().load(reference);
            }
            @Override public List<String> templates() throws Exception {
                return GameContainer.this.world().structures().references();
            }
            @Override public String wand() { return GameContainer.this.giveStructureWand(); }
            private Direction lookDirection() {
                if (GameContainer.this.player().pitch > 45F) return Direction.DOWN;
                if (GameContainer.this.player().pitch < -45F) return Direction.UP;
                return Direction.fromYaw(GameContainer.this.player().yaw);
            }
            private String directionName(Direction direction) {
                return I18n.tr("command.worldedit.direction_" + direction.name().toLowerCase());
            }
            @Override public String copy(boolean useAnchor) {
                var copied = editor().copy(GameContainer.this.dimension(), x(), y(), z(),
                        useAnchor ? WorldEditSession.OperationOrigin.ANCHOR
                                : WorldEditSession.OperationOrigin.PLAYER);
                String key = useAnchor ? "command.worldedit.copy_success_anchor"
                        : "command.worldedit.copy_success";
                return I18n.tr(key, copied.template().sizeX(),
                        copied.template().sizeY(), copied.template().sizeZ(), copied.template().cells().size());
            }
            @Override public StructurePlacement.Result cut(boolean useAnchor) {
                return editor().cut(GameContainer.this.dimension(), x(), y(), z(),
                        useAnchor ? WorldEditSession.OperationOrigin.ANCHOR
                                : WorldEditSession.OperationOrigin.PLAYER);
            }
            @Override public String expand(int amount) {
                Direction direction = lookDirection();
                var changed = editor().expand(GameContainer.this.dimension().getDimensionId(), direction, amount);
                var bounds = changed.bounds();
                return I18n.tr("command.worldedit.expand_success", amount, directionName(direction),
                        bounds.sizeX(), bounds.sizeY(), bounds.sizeZ());
            }
            @Override public String contract(int amount) {
                Direction direction = lookDirection();
                var changed = editor().contract(GameContainer.this.dimension().getDimensionId(), direction, amount);
                var bounds = changed.bounds();
                return I18n.tr("command.worldedit.contract_success", amount, directionName(direction),
                        bounds.sizeX(), bounds.sizeY(), bounds.sizeZ());
            }
            @Override public StructurePlacement.Result set(int state) {
                return editor().setBlock(GameContainer.this.dimension(), state);
            }
            @Override public StructurePlacement.Result replace(java.util.function.IntPredicate matcher, int state) {
                return editor().replace(GameContainer.this.dimension(), matcher, state);
            }
            @Override public StructurePlacement.Result stack(int count) {
                return editor().stack(GameContainer.this.dimension(), lookDirection(), count);
            }
            @Override public StructurePlacement.Result move(int distance) {
                return editor().move(GameContainer.this.dimension(), lookDirection(), distance);
            }
            @Override public StructurePlacement.Result regen() {
                return editor().regenerate(GameContainer.this.dimension());
            }
            @Override public String rotate(int degrees) {
                return I18n.tr("command.worldedit.rotate_success", editor().rotate(degrees).rotation());
            }
            @Override public String flip() {
                double yaw = Math.toRadians(GameContainer.this.player().yaw);
                boolean northSouth = Math.abs(Math.cos(yaw)) >= Math.abs(Math.sin(yaw));
                return I18n.tr("command.worldedit.flip_success", editor().flip(northSouth).mirror());
            }
            @Override public String preview(Integer px, Integer py, Integer pz, StructurePlacement.Rule rule) {
                int tx = px == null ? x() : px, ty = py == null ? y() : py, tz = pz == null ? z() : pz;
                WorldEditSession.Preview preview = editor().preview(
                        GameContainer.this.dimension().getDimensionId(), tx, ty, tz, rule);
                return I18n.tr("command.worldedit.preview_success",
                        preview.x(), preview.y(), preview.z());
            }
            @Override public void clearPreview() { editor().clearPreview(); }
            @Override public StructurePlacement.Result paste(Integer px, Integer py, Integer pz,
                                                               StructurePlacement.Rule rule,
                                                               boolean selectBounds) {
                WorldEditSession.Preview preview = editor().preview();
                int tx, ty, tz;
                StructurePlacement.Rule effectiveRule = rule;
                if (px == null && preview != null
                        && preview.dimension().equals(GameContainer.this.dimension().getDimensionId())) {
                    tx = preview.x(); ty = preview.y(); tz = preview.z(); effectiveRule = preview.rule();
                } else {
                    tx = px == null ? x() : px; ty = py == null ? y() : py; tz = pz == null ? z() : pz;
                }
                return editor().paste(GameContainer.this.dimension(), tx, ty, tz,
                        effectiveRule, selectBounds);
            }
            @Override public String undo(int amount) {
                var result = editor().undo(GameContainer.this.dimension(), amount);
                return result.operations() == 0 ? I18n.tr("command.worldedit.undo_empty")
                        : I18n.tr("command.worldedit.undo_success", result.operations(), result.cells());
            }
            @Override public String redo(int amount) {
                var result = editor().redo(GameContainer.this.dimension(), amount);
                return result.operations() == 0 ? I18n.tr("command.worldedit.redo_empty")
                        : I18n.tr("command.worldedit.redo_success", result.operations(), result.cells());
            }
        };
        CommandContext.PlayerAccess playerAccess = new CommandContext.PlayerAccess() {
            @Override public CommandContext.Position position() {
                return new CommandContext.Position(GameContainer.this.dimension().getDimensionId(),
                        GameContainer.this.player().x, GameContainer.this.player().y, GameContainer.this.player().z);
            }
            @Override public void kill() { GameContainer.this.player().kill(); }
            @Override public Gamemode gamemode() { return GameContainer.this.player().getGamemode(); }
            @Override public void gamemode(Gamemode gamemode) {
                GameContainer.this.player().setGamemode(gamemode);
            }
            @Override public boolean teleport(double x, double y, double z) {
                if (GameContainer.this.session.pendingDimensionSwitch != null) return false;
                GameContainer.this.teleportPlayer(x, y, z);
                return true;
            }
            @Override public CommandContext.Position setHome() {
                PlayerLocation home = new PlayerLocation(GameContainer.this.dimension().getDimensionId(),
                        GameContainer.this.player().x, GameContainer.this.player().y,
                        GameContainer.this.player().z, GameContainer.this.player().yaw,
                        GameContainer.this.player().pitch);
                GameContainer.this.player().setHome(home);
                GameContainer.this.world().players().save(GameContainer.this.player());
                return new CommandContext.Position(home.dimension(), home.x(), home.y(), home.z());
            }
            @Override public CommandContext.HomeResult home() { return GameContainer.this.teleportHome(); }
        };
        CommandContext.WorldAccess worldAccess = new CommandContext.WorldAccess() {
            @Override public CommandContext.Position setSpawnPoint() {
                int x = (int) Math.floor(GameContainer.this.player().x);
                int y = (int) Math.floor(GameContainer.this.player().y);
                int z = (int) Math.floor(GameContainer.this.player().z);
                Identifier dimension = GameContainer.this.dimension().getDimensionId();
                GameContainer.this.world().setSpawnPoint(dimension, x, y, z,
                        GameContainer.this.player().yaw, GameContainer.this.player().pitch);
                return new CommandContext.Position(dimension, x, y, z);
            }
            @Override public List<String> biomeNames() {
                return java.util.Arrays.stream(Biomes.ALL).map(biome -> biome.name).sorted().toList();
            }
            @Override public boolean locateBiome(String name) {
                return GameContainer.this.locateBiome(name);
            }
        };
        this.guiManager.open(new GuiChat(this.chat,
                new CommandContext(this.player().getInventory(), dimensions, structures,
                        playerAccess, worldAccess),
                this.chatHud, initial));
    }

    private boolean isStructureWandHeld() {
        if (this.player() == null) return false;
        ItemStack held = this.player().getInventory().get(this.player().getSelectedSlot());
        return !held.isEmpty() && held.getItem() != null
                && held.getItem().getId().equals(Identifier.of("structure_wand"));
    }

    /** Konsumiert Debug-Axt-Klicks, damit weder Abbau noch normale Blockinteraktion durchfallen. */
    private boolean handleWorldEditToolClick(boolean primary) {
        if (!this.isStructureWandHeld() || this.hit == null) return false;
        WorldEditSession editor = this.world().worldEdit().session(this.player().getUuid());
        try {
            if (editor.toolMode() == WorldEditSession.ToolMode.ANCHOR) {
                editor.anchor(this.dimension().getDimensionId(), this.hit.x(), this.hit.y(), this.hit.z());
                this.chat.addMessage("§e" + I18n.tr("command.worldedit.anchor_success",
                        this.hit.x(), this.hit.y(), this.hit.z()));
            } else if (primary) {
                editor.pos1(this.dimension().getDimensionId(), this.hit.x(), this.hit.y(), this.hit.z());
                this.chat.addMessage("§a" + I18n.tr("command.worldedit.pos1_success",
                        this.hit.x(), this.hit.y(), this.hit.z()));
            } else {
                editor.pos2(this.dimension().getDimensionId(), this.hit.x(), this.hit.y(), this.hit.z());
                this.chat.addMessage("§a" + I18n.tr("command.worldedit.pos2_success",
                        this.hit.x(), this.hit.y(), this.hit.z()));
            }
        } catch (RuntimeException error) {
            String message = error.getMessage();
            if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
            this.chat.addMessage("§c" + I18n.tr("command.worldedit.error_prefix", message));
        }
        this.stopDestroyBlock();
        this.animState.swing();
        return true;
    }

    private String giveStructureWand() {
        Item wand = Items.get(Identifier.of("structure_wand"));
        if (wand == null) throw new IllegalStateException(I18n.tr("command.worldedit.tool_not_registered"));
        for (int i = 0; i < this.player().getInventory().size(); i++) {
            ItemStack stack = this.player().getInventory().get(i);
            if (!stack.isEmpty() && stack.getItem() == wand) {
                if (i < 9) this.player().setSelectedSlot(i);
                return I18n.tr("command.worldedit.tool_already_owned");
            }
        }
        for (int i = 0; i < 9; i++) {
            if (this.player().getInventory().get(i).isEmpty()) {
                this.player().getInventory().set(i, new ItemStack(wand, 1));
                this.player().setSelectedSlot(i);
                return I18n.tr("command.worldedit.tool_received");
            }
        }
        ItemStack remaining = this.player().getInventory().insert(new ItemStack(wand, 1));
        if (!remaining.isEmpty()) throw new IllegalStateException("Inventar ist voll");
        return I18n.tr("command.worldedit.tool_received_inventory");
    }

    private float computeFarPlane() {
        return 1500.0F;
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

    private EntityPlayer player() {
        return this.session == null ? this.remotePlayer : this.session.player();
    }

    private Dimension dimension() {
        return this.session == null ? null : this.session.dimension();
    }

    /** Active client-visible dimension, including the non-simulating multiplayer mirror. */
    private Dimension visualDimension() {
        return this.session != null ? this.session.dimension()
                : this.remoteWorldView == null ? null : this.remoteWorldView.physicsDimension();
    }

    private World world() {
        return this.session == null ? null : this.session.world();
    }

    private DimensionView view() {
        return this.session == null ? null : this.session.view();
    }

    private WorldSaves.WorldSave currentSave() {
        return this.session == null ? null : this.session.save();
    }

    public EntityPlayer getPlayer() {
        return this.player();
    }

    public Dimension getDimension() {
        return this.dimension();
    }

    public de.skyengine.game.world.dimension.DimensionEnvironment getRenderEnvironment() {
        Dimension local = this.dimension();
        if (local != null) return local.getEnvironment();
        return this.remoteWorldView == null ? null : this.remoteWorldView.environment();
    }

    public World getWorld() {
        return this.world();
    }

    public DimensionView getDimensionView() {
        return this.view();
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
        this.structurePreviewRenderer.invalidate();

        /* Renderer mit eigenem Textur-/Modellcache in sicherer Reihenfolge erneuern. */
        progress.frame(I18n.tr("resourcepacks.loading.renderers"), 0.58F);
        this.heldItemMeshes.dispose();
        this.playerRenderer.dispose();
        this.blockEntityRenderers.dispose();
        this.blockEntityRenderers.init();
        this.playerRenderer.init();
        this.heldItemMeshes.init(this.atlas.textures(), this.blockEntityRenderers);
        if (this.dimension() != null) {
            progress.frame(I18n.tr("resourcepacks.loading.world"), 0.70F);
            this.view().reloadEntityRenderers();
            this.dimension().getChunkManager().remeshAll();
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
