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
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.state.SlabType;
import de.skyengine.game.world.item.BlockItem;
import de.skyengine.game.world.item.BucketItem;
import de.skyengine.game.world.item.FoodItem;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;
import de.skyengine.game.world.item.ToolItem;
import de.skyengine.graphics.world.CrackRenderer;
import de.skyengine.core.i18n.I18n;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.core.settings.KeyBindings;
import de.skyengine.game.world.chunk.ChunkManager;
import de.skyengine.game.world.chunk.FluidGeometry;
import de.skyengine.game.world.lod.LodMesher;
import de.skyengine.graphics.FrameProfiler;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.audio.SoundCategory;
import de.skyengine.audio.SoundManager;
import de.skyengine.graphics.blockentity.BlockEntityRenderDispatcher;
import de.skyengine.graphics.blockentity.ChestRenderer;
import de.skyengine.graphics.blockentity.EnchantingTableRenderer;
import de.skyengine.graphics.gui.BootProgress;
import de.skyengine.graphics.gui.screens.GuiChest;
import de.skyengine.graphics.gui.screens.GuiInventory;
import de.skyengine.graphics.gui.DebugOverlay;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.gui.SpriteRenderer;
import de.skyengine.graphics.gui.screens.GuiIngameMenu;
import de.skyengine.graphics.gui.screens.GuiDeathScreen;
import de.skyengine.graphics.gui.screens.GuiMainMenu;
import de.skyengine.graphics.gui.screens.GuiWorldLoading;
import de.skyengine.graphics.texture.BlockTextureAtlas;
import de.skyengine.game.world.block.entity.BlockEntities;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.game.world.save.WorldSaves;
import org.lwjgl.opengl.GL11;
import de.skyengine.graphics.post.PostProcessingSettings;
import de.skyengine.graphics.post.PostProcessingSettings.AntiAliasingMode;
import de.skyengine.graphics.post.PostProcessor;
import de.skyengine.graphics.world.SelectionBoxRenderer;
import de.skyengine.utils.Utils;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.joml.Vector2f;
import org.joml.Vector3d;
import org.lwjgl.glfw.GLFW;

import java.io.File;

/* Kein IInitializable mehr: der Boot läuft zweistufig über initBoot()/initStaged() (Ladebildschirm). */
public class GameContainer implements IResizeable, IDisposable {

    private final Logger logger = LogManager.getLogger(GameContainer.class.getName());

    private Camera camera;
    private EntityPlayer player;
    private World world;
    private SelectionBoxRenderer selectionBoxRenderer;
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

    /* Block-Interaktion: sofort beim Klick, beim Halten alle 200ms (= 4 Ticks, wie Minecraft) */
    private static final long INTERACT_DELAY_MS = 200;
    private long lastBreakTime = 0;
    private long lastPlaceTime = 0;

    /* Survival-Mining: zeitbasierter Abbau-Fortschritt am aktuellen Ziel-Block (0..1).
       Zielwechsel oder Loslassen setzt zurück; Creative bricht weiterhin instant. */
    private int miningX = Integer.MIN_VALUE, miningY, miningZ;
    private float miningProgress = 0F;
    private long lastMiningMs = 0;

    /* Doppel-Leertaste schaltet das Fliegen um (wie Minecraft): zweiter Tipp binnen 300ms. */
    private static final long DOUBLE_TAP_MS = 300;
    private long lastSpacePressTime = 0;

    /* Wiederverwendet, um Allokationen pro Frame zu vermeiden */
    private final Vector3d rayDirection = new Vector3d();

    private boolean debugChunkBoundingBox = false;
    private boolean debugChunkWireframe = false;

    /* F3-Debug-Overlay (FPS/Position/Biome/...), Toggle in handleGlobalHotkeys. */
    private final DebugOverlay debugOverlay = new DebugOverlay();

    /* Audio: Effekt-Sounds + Musik (OpenAL, komplett auf dem Render-Thread). */
    private final SoundManager soundManager = new SoundManager();
    /* Laufgeräusche: zurückgelegte Distanz seit dem letzten Schritt (MC-Kadenz ~1.6 Blöcke). */
    private static final double STEP_INTERVAL = 1.6;
    private double stepDistance = 0;
    /* Periodischer Hit-Sound während des Survival-Abbaus (zeitbasiert, updateMining ist frame-getaktet). */
    private long lastHitSoundMs = 0;

    /* Wird per F2 gesetzt und von SkyEngine nach dem fertigen Frame abgeholt. */
    private boolean screenshotRequested = false;

    private BlockRaycast.Hit hit = null;

    /* Spieler-Inventar (36 Slots: 0..8 Hotbar, 9..35 Hauptinventar). Auswahl per Zahlentasten 1..9. */
    private final SimpleItemStorage playerInventory = new SimpleItemStorage(36);
    private int hotbarIndex = 0;
    /* Slot-Wechsel-Zeitpunkt für die Itemnamen-Einblendung über der Hotbar (reine Anzeige). */
    private static final long ITEM_NAME_HOLD_MS = 2000, ITEM_NAME_FADE_MS = 500;
    private long itemNameShownAt = 0;
    /* Ess-Fortschritt in Ticks (Rechtsklick halten auf ein FoodItem, MC: 32 Ticks = 1,6 s). */
    private static final int EAT_TICKS = 32;
    private int eatingTicks = 0;

    /* Aktives Savegame (null im Hauptmenü) — Ziel für saveCurrentWorld beim Austritt/Beenden. */
    private WorldSaves.WorldSave currentSave;

    public GameContainer() {
        /* Sprache VOR dem ersten Screen/Boot-Frame laden — alle GUI-Texte laufen über I18n. */
        I18n.load(this.settings.language);
        this.camera = new Camera();
        /* world/player sind lazy: sie entstehen erst beim Welt-Eintritt (enterWorld) und
           sterben bei der Rückkehr ins Hauptmenü (exitToTitle). */
        this.selectionBoxRenderer = new SelectionBoxRenderer();
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
        this.crackRenderer.init(this.atlas.textures());
        this.blockEntityRenderers.register(BlockEntities.CHEST, new ChestRenderer());
        this.blockEntityRenderers.register(BlockEntities.ENCHANTING_TABLE, new EnchantingTableRenderer());
        this.blockEntityRenderers.init();
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
        this.world = new World(save.dirName(), save.level().seed, this.atlas, this.blockEntityRenderers);
        this.world.init();

        this.player = new EntityPlayer();
        LevelData.PlayerData saved = save.level().player;
        if (saved != null) {
            this.player.setPosition(saved.x, saved.y, saved.z);
            this.player.yaw = saved.yaw;
            this.player.pitch = saved.pitch;
            try {
                this.player.setGamemode(Gamemode.valueOf(saved.gamemode));
            } catch (Exception ignored) { /* unbekannter Modus -> Default */ }
            this.player.setFlying(saved.flying);
        } else {
            this.placeAtWorldSpawn(this.player);
        }
        if (saved != null) {
            /* Vitals nur übernehmen, wenn vorhanden — alte level.json ohne die Felder liefert
               null (Boxed-Typen), sonst wäre GSON-Default 0 = sofort tot. */
            if (saved.health != null) this.player.setHealth(saved.health);
            if (saved.foodLevel != null) this.player.setFoodLevel(saved.foodLevel);
            if (saved.saturation != null) this.player.setSaturation(saved.saturation);
        }

        this.clearInventory();
        if (!save.level().inventory.isEmpty()) {
            this.loadInventory(save.level().inventory);
        } else {
            this.fillStartInventory();
        }
        this.hotbarIndex = 0;

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
        this.saveCurrentWorld();
        GL11.glFinish();
        this.world.dispose();
        this.world = null;
        this.player = null;
        this.currentSave = null;
        this.hit = null;
        this.resetMining();
        this.guiManager.open(new GuiMainMenu());
    }

    /** Schreibt Spielerzustand + Inventar in die level.json des aktiven Savegames. */
    private void saveCurrentWorld() {
        if (this.currentSave == null || this.world == null || this.player == null) return;

        LevelData level = this.currentSave.level();
        level.lastPlayed = System.currentTimeMillis();

        LevelData.PlayerData data = new LevelData.PlayerData();
        data.x = this.player.x;
        data.y = this.player.y;
        data.z = this.player.z;
        data.yaw = this.player.yaw;
        data.pitch = this.player.pitch;
        data.gamemode = this.player.getGamemode().name();
        data.flying = this.player.isFlying();
        data.health = this.player.getHealth();
        data.foodLevel = this.player.getFoodLevel();
        data.saturation = this.player.getSaturation();
        level.player = data;

        level.inventory.clear();
        for (int slot = 0; slot < this.playerInventory.size(); slot++) {
            ItemStack stack = this.playerInventory.get(slot);
            if (stack.isEmpty()) continue;
            LevelData.ItemEntry entry = new LevelData.ItemEntry();
            entry.slot = slot;
            entry.id = stack.getItem().getId().toString();
            entry.count = stack.getCount();
            entry.damage = stack.getDamage();
            level.inventory.add(entry);
        }

        WorldSaves.save(this.currentSave);
        this.logger.info("Welt gespeichert: " + this.currentSave.dirName());
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
        this.guiManager.setScale(this.settings.guiScaleFactor());
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

        /* Spieler friert nur ein, wenn das Spiel pausiert (prev=current verhindert Kamera-Jitter,
           sonst interpoliert Camera.follow weiter zwischen zwei Tick-Positionen). Ausnahme
           Ladebildschirm: pausiert nicht (Chunks laden über world.update), aber der Spieler darf
           nicht in die ungeladene Welt fallen (ungeladene Chunks kollidieren als Luft). */
        if (this.guiManager.pausesGame() || this.guiManager.current() instanceof GuiWorldLoading) {
            this.player.snapPrevToCurrent();
        } else {
            /* Offenes Container-GUI (Inventar/Truhe): Physik läuft weiter (fallen/Strömung),
               aber ohne Tasten — wie in MC gehen die Eingaben ans GUI. */
            this.player.update(this.guiManager.isOpen() ? Input.EMPTY : input, this.world);
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

            ItemStack remaining = this.playerInventory.insert(item.getStack());
            if (remaining.isEmpty()) {
                item.remove();
            } else {
                item.getStack().setCount(remaining.getCount());
            }
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
        if (this.player.consumeHurt()) this.soundManager.playHurt();
    }

    /**
     * Welt-Anteil des Frames (Input/Kamera/Raycast + Welt, 3D-Overlays, Fluid-Tint) — zeichnet
     * in das gebundene Szene-Target (HDR). Die GUI folgt getrennt in {@link #renderGui} NACH
     * der Post-Kette (SkyEngine.onRender), damit HUD/Text nie durch Grading/AA laufen.
     */
    public void renderWorld(Input input, int width, int height, float partialTick) {
        this.handleGlobalHotkeys(input);   // immer: Fullscreen, GUI-Scale, Render-Distanz

        /* Musik-Streaming läuft auch im Hauptmenü weiter. */
        this.soundManager.update();

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
            /* ESC öffnet das Pause-Menü (Beenden geht über dessen Button). */
            if (input.isKeyPressed(GLFW.GLFW_KEY_ESCAPE)) this.guiManager.open(new GuiIngameMenu());
            /* Inventar-Taste (Default E) öffnet das Spielerinventar; dieselbe Taste schließt es
               wieder (closesOnInventoryKey im GuiManager-Routing). */
            if (input.isKeyPressed(this.settings.key(KeyBindings.OPEN_INVENTORY))) {
                this.guiManager.open(new GuiInventory(this.playerInventory));
            }
            this.handleDebugInput(input);
            this.handleHotbarInput(input);
            double sens = this.settings.mouseSensitivity;
            this.player.turn(input.getDeltaMouseX() * sens, input.getDeltaMouseY() * sens);
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
        this.camera.update((double) width / height);
        post.updateTaaCamera(this.camera);

        /* Audio pro Frame: Listener auf die interpolierte Kamera (Streaming läuft oben). */
        this.soundManager.updateListener(this.camera);

        this.hit = BlockRaycast.raycast(
                this.world,
                this.camera.getPosition(),
                this.camera.getDirection(this.rayDirection),
                REACH
        );

        if (guiOpen) {
            this.guiManager.handleInput();       // Schließen + Slot-Klicks (kann den GuiScreen schließen)
            /* Ein GuiScreen-Callback (GuiIngameMenu „Hauptmenü") kann exitToTitle() ausgelöst haben —
               dann ist die Welt weg und der Rest des Frames darf sie nicht mehr anfassen. */
            if (this.world == null) return;
        } else {
            this.handleBlockInteraction(input);  // kann ein GUI öffnen
        }

        /* Wireframe (F6) gilt NUR für die Welt-Geometrie: der Line-Mode ist globaler GL-State und
           würde sonst auch das Fullscreen-Dreieck der Post-Kette (und die GUI-Quads) zu Linien
           machen — dann bliebe der Default-Framebuffer unbeschrieben ("eingefrorenes" Bild). */
        if (this.debugChunkWireframe) Utils.enableWireframe();
        this.world.render(this.camera, partialTick);
        if (this.debugChunkWireframe) Utils.disableWireframe();

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

        this.renderFluidOverlay();

        FrameProfiler.cpuStop(FrameProfiler.Cpu.OVL);
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
        /* Im Hauptmenü kein HUD (Inventar null -> GuiManager überspringt Hotbar/Crosshair). */
        this.guiManager.render(width, height, this.world != null ? this.playerInventory : null,
                this.hotbarIndex, showHotbar, this.itemNameAlpha(), this.player);
        if (this.debugOverlay.isVisible() && this.world != null) {
            this.debugOverlay.render(this.guiManager, this.world, this.player);
        }
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
                || !input.isMouseDown(GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
            this.eatingTicks = 0;
            return;
        }
        if (++this.eatingTicks >= EAT_TICKS) {
            this.eatingTicks = 0;
            this.player.eat(food.getNutrition(), food.getSaturation());
            this.soundManager.playBurp();
            held.setCount(held.getCount() - 1);
            if (held.getCount() <= 0) {
                this.playerInventory.set(this.hotbarIndex, ItemStack.EMPTY);
            }
        } else if (this.eatingTicks % 4 == 0) {
            this.soundManager.playEat(); // Kau-Sound alle 4 Ticks (MC-Gefühl: ~8 Kauer bis zum Burp)
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
        this.saveCurrentWorld(); // Welt-Zustand auch beim direkten Beenden aus dem Spiel sichern
        if (this.world != null) {
            this.world.dispose();
        }
        this.selectionBoxRenderer.dispose();
        if (this.crackRenderer != null) this.crackRenderer.dispose();
        if (this.guiManager != null) this.guiManager.dispose();
        this.blockEntityRenderers.dispose();
        this.atlas.dispose();
        this.soundManager.dispose();
    }

    /**
     * Survival-Mining: akkumuliert Abbau-Fortschritt am Ziel-Block (MC-Formel), solange die
     * linke Maustaste gehalten wird. Härte 0 = instant (über den Klick-/Halte-Trigger),
     * Härte &lt; 0 = unzerstörbar (Bedrock). Drops/Abnutzung übernimmt {@link #breakTargetBlock}.
     */
    private void updateMining(boolean held, boolean clickTrigger, long now) {
        if (!held || this.hit == null) {
            this.resetMining();
            return;
        }

        /* Zielwechsel -> Fortschritt neu ansetzen */
        if (this.hit.x() != this.miningX || this.hit.y() != this.miningY || this.hit.z() != this.miningZ) {
            this.miningX = this.hit.x();
            this.miningY = this.hit.y();
            this.miningZ = this.hit.z();
            this.miningProgress = 0F;
            this.lastMiningMs = now;
        }

        BlockState state = Blocks.getState(this.hit.block());
        float hardness = state.getBlock().getHardness();
        if (hardness < 0F) { // Bedrock: unzerstörbar
            this.miningProgress = 0F;
            this.lastMiningMs = now;
            return;
        }

        if (hardness == 0F) { // instant (Pflanzen, TNT) — mit demselben Klick-/Halte-Takt wie Creative
            if (clickTrigger) this.breakTargetBlock(state, false, now);
            return;
        }

        ItemStack held0 = this.playerInventory.get(this.hotbarIndex);
        float speed = 1F;
        if (held0.getItem() instanceof ToolItem tool && tool.getType() == state.getBlock().getToolType()) {
            speed = tool.getTier().speed();
        }
        /* MC-Formel: Schaden pro Tick = speed/hardness/30 (harvestbar) bzw. /100 -> pro Sekunde /1.5 bzw. /5 */
        float perSecond = speed / hardness / (isHarvestable(state, held0) ? 1.5F : 5F);

        float dt = (now - this.lastMiningMs) / 1000F;
        this.lastMiningMs = now;
        this.miningProgress += dt * perSecond;

        /* Periodischer Hit-Sound während des Abbaus (wie MCs Schlag-Takt). */
        if (now - this.lastHitSoundMs >= 250) {
            this.lastHitSoundMs = now;
            this.soundManager.playHit(state.getBlock().getSoundGroup(),
                    this.miningX + 0.5, this.miningY + 0.5, this.miningZ + 0.5);
        }

        if (this.miningProgress >= 1F) {
            this.breakTargetBlock(state, true, now);
        }
    }

    /**
     * Baut den Ziel-Block ({@code this.hit}) ab: onBreak + AIR setzen, Drop nur bei
     * dropsItems UND passendem Tool (MC-Harvest-Regel), optional Tool-Abnutzung.
     */
    private void breakTargetBlock(BlockState broken, boolean applyDurability, long now) {
        this.soundManager.playBreak(broken.getBlock().getSoundGroup(),
                this.hit.x() + 0.5, this.hit.y() + 0.5, this.hit.z() + 0.5);
        broken.getBlock().onBreak(this.world, this.hit.x(), this.hit.y(), this.hit.z(), broken);
        this.world.setBlock(this.hit.x(), this.hit.y(), this.hit.z(), Blocks.AIR);

        ItemStack held = this.playerInventory.get(this.hotbarIndex);
        /* Drops nur im Survival UND nur, wenn Tool-Klasse + Tier passen. */
        if (this.player.getGamemode().dropsItems() && isHarvestable(broken, held)) {
            Item drop = Items.get(broken.getBlock().getIdentifier());
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

        this.lastBreakTime = now;
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
    }

    private void handleBlockInteraction(Input input) {
        /* Spectator kann nicht abbauen/platzieren/nutzen (auch keine Truhe öffnen). */
        if (!this.player.getGamemode().interactsWithWorld()) return;

        long now = System.currentTimeMillis();

        /* Sofort beim Klick (isMousePressed) ODER beim Halten nach Ablauf des Delays */
        boolean breakBlock = input.isMousePressed(GLFW.GLFW_MOUSE_BUTTON_LEFT)
                || (input.isMouseDown(GLFW.GLFW_MOUSE_BUTTON_LEFT) && now - this.lastBreakTime >= INTERACT_DELAY_MS);

        boolean placeBlock = input.isMousePressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT)
                || (input.isMouseDown(GLFW.GLFW_MOUSE_BUTTON_RIGHT) && now - this.lastPlaceTime >= INTERACT_DELAY_MS);

        /* Survival: zeitbasierter Abbau nach Härte/Tool — läuft jeden Frame, solange die linke
           Maustaste gehalten wird (der breakBlock-Trigger unten gilt nur für den Instant-Pfad). */
        if (!this.player.getGamemode().isInstantBreak()) {
            this.updateMining(input.isMouseDown(GLFW.GLFW_MOUSE_BUTTON_LEFT), breakBlock, now);
        } else {
            this.resetMining();
        }

        if (!breakBlock && !placeBlock) return;

        /* Eimer vor der hit==null-Prüfung: Der LEERE Eimer nutzt einen eigenen fluid-bewussten
           Strahl (Fluids sind im Normal-Raycast unsichtbar) und funktioniert auch ohne this.hit.
           Der gefüllte Eimer platziert wie ein Block über this.hit (siehe handleBucket). */
        if (placeBlock) {
            ItemStack held = this.playerInventory.get(this.hotbarIndex);
            if (held.getItem() instanceof BucketItem bucket && this.handleBucket(bucket, now)) return;
        }

        if (hit == null) return;

        if (breakBlock) {
            /* Instant-Abbau nur im Creative — Survival läuft über updateMining (oben). */
            if (this.player.getGamemode().isInstantBreak()) {
                this.breakTargetBlock(Blocks.getState(this.hit.block()), false, now);
            }
        } else {
            /* Rechtsklick-Interaktion des getroffenen Blocks (z.B. Tür auf/zu) hat Vorrang. */
            BlockState hitState = Blocks.getState(this.hit.block());
            if (hitState.getBlock().onUse(this.world, this.hit.x(), this.hit.y(), this.hit.z(), hitState)) {
                this.lastPlaceTime = now;
                return;
            }

            /* Truhe: Rechtsklick öffnet das Truhen-GUI (Deckel geht auf). */
            if (this.tryOpenChest(now)) return;

            /* Ausgewählter Hotbar-Slot muss einen platzierbaren Block enthalten. */
            ItemStack selected = this.playerInventory.get(this.hotbarIndex);
            if (selected.isEmpty() || !(selected.getItem() instanceof BlockItem blockItem)) return;
            Block block = blockItem.getBlock();

            /* Slab auf vorhandene gleiche Slab -> Doppel-Slab */
            if (this.tryMergeSlab(block, now)) return;

            /* Platzieren: an der getroffenen Seite (Fluids zählen als Luft, this.hit ignoriert sie). */
            int[] t = this.placementTarget();
            if (t == null) return;
            int px = t[0], py = t[1], pz = t[2];

            double relHitX = this.hit.hitX() - px;
            double relHitY = this.hit.hitY() - py;
            double relHitZ = this.hit.hitZ() - pz;
            BlockState place = block.getPlacementState(this.world, px, py, pz,
                    this.hit.faceX(), this.hit.faceY(), this.hit.faceZ(),
                    relHitX, relHitY, relHitZ, this.player.yaw);

            /* place == null: ein Behavior lehnt ab (z.B. Tür ohne Platz). Sonst nicht in den
               eigenen Körper bauen - gegen die ECHTE Kollisionsform testen, damit dünne Blöcke
               (Panes, Zäune) neben einem platzierbar bleiben. */
            if (place != null && !this.collidesWithPlayer(place, px, py, pz)
                    && !this.collidesWithEntities(place, px, py, pz)) {
                this.world.placeBlock(px, py, pz, place);
                this.soundManager.playPlace(place.getBlock().getSoundGroup(), px + 0.5, py + 0.5, pz + 0.5);
                this.lastPlaceTime = now;
            }
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
    private boolean tryMergeSlab(Block block, long now) {
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
        this.lastPlaceTime = now;
        return true;
    }

    /** Rechtsklick auf eine Truhe öffnet ihr GUI (Truhe + Spielerinventar) und öffnet den Deckel. */
    private boolean tryOpenChest(long now) {
        BlockEntity be = this.world.getBlockEntity(this.hit.x(), this.hit.y(), this.hit.z());
        if (!(be instanceof ChestBlockEntity chest)) return false;
        this.guiManager.open(new GuiChest(chest, this.playerInventory));
        this.lastPlaceTime = now;
        return true;
    }

    /**
     * Eimer-Interaktion: gefüllt platziert eine Fluid-Quelle, leer nimmt eine Quelle auf.
     * Im Survival wird der Eimer getauscht (gefüllt↔leer), im Creative nicht.
     */
    private boolean handleBucket(BucketItem bucket, long now) {
        boolean consume = this.player.getGamemode() == Gamemode.SURVIVAL;

        if (bucket.isEmpty()) {
            /* Aufnehmen: fluid-bewusster Strahl, damit Wasser/Lava als Ziel zählt. Nur eine
               Quelle (LEVEL 0, nicht fallend). */
            BlockRaycast.Hit fhit = BlockRaycast.raycast(this.world, this.camera.getPosition(),
                    this.camera.getDirection(this.rayDirection), REACH, true);
            if (fhit == null) return false;
            BlockState state = Blocks.getState(fhit.block());
            if (!state.isFluid() || state.get(Properties.FALLING) || state.get(Properties.LEVEL) != 0) return false;
            this.world.setBlock(fhit.x(), fhit.y(), fhit.z(), Blocks.AIR);
            if (consume) {
                String id = state.getBlock().getFluidInfo().lava ? "skyengine:lava_bucket" : "skyengine:water_bucket";
                this.consumeHeld(Items.get(Identifier.of(id)));
            }
            this.lastPlaceTime = now;
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
        this.lastPlaceTime = now;
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

    private void fillStartInventory() {
        /* Hotbar (Slots 0-8): Test-Blöcke + die drei Eimer hinten, damit Wasser/Lava direkt
           testbar sind. Wasser hat kein Block-Item mehr (gehört in den Eimer). */
        int[] start = {
                Blocks.GLASS,
                Blocks.STONE_SLAB,
                Blocks.SAND,
                Blocks.COBBLESTONE_STAIRS,
                Blocks.CACTUS,
        };
        for (int i = 0; i < start.length; i++) {
            Item item = Items.get(Blocks.getState(start[i]).getBlock().getIdentifier());
            if (item != null) this.playerInventory.set(i, new ItemStack(item, 64));
        }

        this.setItem(0, "skyengine:tuff");
        this.setItem(1, "skyengine:coarse_dirt");
        this.setItem(2, "skyengine:red_mushroom");

        /* TEMP: Essen zum Hunger-Testen (Survival, Rechtsklick halten). */
        this.setItem(3, "skyengine:apple", 16);
        this.setItem(4, "skyengine:bread", 16);

        this.setItem(5, "skyengine:chest"); // TEMP: Truhe zum GUI-Testen direkt in der Hotbar
        this.setItem(6, "skyengine:water_bucket");
        this.setItem(7, "skyengine:lava_bucket");
        this.setItem(8, "skyengine:bucket");

        /* Glasscheibe/Tür + Sand ins Hauptinventar (zum Testen, Truhe befüllen/leeren). */
        this.setBlock(9, Blocks.GLASS_PANE);
        this.setBlock(10, Blocks.OAK_DOOR);
        this.setBlock(11, Blocks.SAND);

        // TEMP: neue Blöcke zum visuellen Testen, wird nach der Verifikation wieder entfernt.
        String[] testBlocks = {
                "skyengine:tuff", "skyengine:tuff_bricks", "skyengine:polished_tuff",
                "skyengine:chiseled_tuff", "skyengine:chiseled_tuff_bricks",
                "skyengine:coarse_dirt", "skyengine:rooted_dirt", "skyengine:dirt_path", "skyengine:podzol",
                "skyengine:mud", "skyengine:mud_bricks", "skyengine:packed_mud", "skyengine:muddy_mangrove_roots",
                "skyengine:melon", "skyengine:pumpkin", "skyengine:carved_pumpkin",
                "skyengine:brown_mushroom", "skyengine:red_mushroom",
                "skyengine:brown_mushroom_block", "skyengine:red_mushroom_block", "skyengine:mushroom_stem"
        };
        for (int i = 0; i < testBlocks.length; i++) {
            this.setItem(12 + i, testBlocks[i]);
        }
    }

    /** Legt 64 eines Blocks in einen Inventar-Slot (Block-Item über die Identifier-Registry). */
    private void setBlock(int slot, int block) {
        Item item = Items.get(Blocks.getState(block).getBlock().getIdentifier());
        if (item != null) this.playerInventory.set(slot, new ItemStack(item, 64));
    }

    private void setItem(int slot, String itemId) {
        this.setItem(slot, itemId, 1);
    }

    private void setItem(int slot, String itemId, int count) {
        Item item = Items.get(Identifier.of(itemId));
        if (item != null) this.playerInventory.set(slot, new ItemStack(item, count));
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
            if (input.isKeyPressed(this.settings.key(KeyBindings.hotbar(i + 1)))) {
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

    private void handleDebugInput(Input input) {
        /* Doppel-Sprungtaste = Fliegen umschalten (toggleFlying prüft den Modus selbst). */
        if (input.isKeyPressed(this.settings.key(KeyBindings.JUMP))) {
            long now = System.currentTimeMillis();
            if (now - this.lastSpacePressTime <= DOUBLE_TAP_MS) {
                this.player.toggleFlying();
                this.logger.debug("Flying: " + this.player.isFlying());
                this.lastSpacePressTime = 0; // verbraucht, damit ein dritter Tipp nicht sofort wieder toggelt
            } else {
                this.lastSpacePressTime = now;
            }
        }
        if (input.isKeyPressed(GLFW.GLFW_KEY_F)) {
            GameSettings.get().fog = !GameSettings.get().fog;
            this.logger.debug("Fog: " + GameSettings.get().fog);
        }
        if (input.isKeyPressed(GLFW.GLFW_KEY_G)) {
            this.player.setGamemode(this.player.getGamemode().next());
            this.logger.debug("Gamemode: " + this.player.getGamemode());
        }
        if (input.isKeyPressed(GLFW.GLFW_KEY_F4)) {
            /* Debug (A/B-Messung): LOD-Höhenquantisierung an/aus. Nicht persistiert;
               der LodManager remesht bei Wechsel via Epoche alle LOD-Regionen. */
            LodMesher.QUANTIZE_HEIGHT = !LodMesher.QUANTIZE_HEIGHT;
            this.logger.debug("LOD Höhenquantisierung: " + (LodMesher.QUANTIZE_HEIGHT ? "an" : "aus"));
        }
        if (input.isKeyPressed(GLFW.GLFW_KEY_F5)) {
            /* TEMP/Debug (Perf-Messung): koplanare LOD-Seiten-Overlays an/aus. Nicht persistiert;
               der LodManager remesht bei Wechsel via Epoche alle LOD-Regionen. */
            LodMesher.EMIT_GRASS_OVERLAY = !LodMesher.EMIT_GRASS_OVERLAY;
            this.logger.debug("LOD Seiten-Overlay: " + (LodMesher.EMIT_GRASS_OVERLAY ? "an" : "aus"));
        }
        if (input.isKeyPressed(GLFW.GLFW_KEY_F6)) {
            /* Nur das Flag: den GL-Line-Mode setzt renderWorld eng um den Welt-Draw (s. dort). */
            this.debugChunkWireframe = !this.debugChunkWireframe;
            this.logger.debug("Wireframe: " + this.debugChunkWireframe);
        }
        if (input.isKeyPressed(GLFW.GLFW_KEY_F7)) {
            this.debugChunkBoundingBox = !this.debugChunkBoundingBox;
            this.logger.debug("Chunk Bounding Box: " + this.debugChunkBoundingBox);
        }
        if (input.isKeyPressed(GLFW.GLFW_KEY_F8)) {
            /* Über clearAllChunks statt getChunks().clear(): bumpt die Removal-Version,
               sonst räumt der Renderer die alten Meshes nicht ab (Geistergeometrie). */
            this.world.getChunkManager().clearAllChunks();
            this.logger.debug("reload chunks");
        }
        /* Post-Processing: F9 schaltet den AA-Modus durch (NONE/FXAA/...), F10 lädt
           config/postprocessing.json neu (Grading-Tuning ohne Neustart). Nur Laufzeit-
           Zustand — nichts davon wird in options.json persistiert. */
        if (input.isKeyPressed(GLFW.GLFW_KEY_F9)) {
            PostProcessingSettings post = SkyEngine.get().getPostProcessor().getSettings();
            AntiAliasingMode[] modes = AntiAliasingMode.values();
            AntiAliasingMode next = modes[(post.getAaMode().ordinal() + 1) % modes.length];
            post.setAaMode(next);
            this.logger.debug("Anti-Aliasing: " + next);
        }
        if (input.isKeyPressed(GLFW.GLFW_KEY_F10)) {
            SkyEngine.get().getPostProcessor().getSettings().reloadFromFile();
            this.logger.debug("Post-Processing-Einstellungen neu geladen");
        }
        /* Stufenhöhe live justieren (Bild auf/ab) - zum Ausprobieren hoher Sprünge */
        if (input.isKeyPressed(GLFW.GLFW_KEY_PAGE_UP)) {
            this.player.stepHeight += 0.5;
            this.logger.debug("Step height: " + this.player.stepHeight);
        }
        if (input.isKeyPressed(GLFW.GLFW_KEY_PAGE_DOWN)) {
            this.player.stepHeight = Math.max(0, this.player.stepHeight - 0.5);
            this.logger.debug("Step height: " + this.player.stepHeight);
        }
    }

    /**
     * Hotkeys, die immer wirken (auch bei offenem GUI): Vollbild (F11), GUI-Scale ([ / ]) und
     * Render-Distanz (- / =). Geänderte Einstellungen werden sofort angewandt und persistiert —
     * Übergangslösung bis zum editierbaren Optionsmenü.
     */
    private void handleGlobalHotkeys(Input input) {
        /* Laufende Keybind-Aufnahme schluckt ALLE Tasten — sonst macht das Binden von F2
           gleichzeitig einen Screenshot. */
        if (this.guiManager.capturesKeys()) return;
        if (input.isKeyPressed(GLFW.GLFW_KEY_F2)) {
            /* Nur markieren: der Pixel-Read passiert erst nach dem fertigen Frame (SkyEngine.onRender). */
            this.screenshotRequested = true;
        }
        if (input.isKeyPressed(GLFW.GLFW_KEY_F3)) {
            this.debugOverlay.toggle();
            this.logger.debug("Debug-Overlay: " + (this.debugOverlay.isVisible() ? "an" : "aus"));
        }
        if (input.isKeyPressed(GLFW.GLFW_KEY_F11)) {
            boolean fullscreen = SkyEngine.get().getConfig().isWindowed();
            SkyEngine.get().getMainThreadTasks().add(() ->
                    SkyEngine.get().getWindow().setWindowMode(fullscreen
                            ? EngineConfig.WindowMode.BORDERLESS_FULLSCREEN : EngineConfig.WindowMode.WINDOWED));
            this.logger.debug("Toggle Fullscreen");
        }

        /* Bei offenem GuiScreen keine Buchstaben-/Symbol-Hotkeys — sonst tippen Textfelder
           versehentlich GUI-Scale/Render-Distanz um. F2/F3/F11 (oben) bleiben immer aktiv. */
        if (this.guiManager.isOpen()) return;

        boolean changed = false;
        if (input.isKeyPressed(GLFW.GLFW_KEY_LEFT_BRACKET)) {
            this.settings.guiScalePercent = Math.max(30, this.settings.guiScalePercent - 5);
            this.guiManager.setScale(this.settings.guiScaleFactor());
            this.logger.debug("GUI-Größe: " + this.settings.guiScalePercent + " %");
            changed = true;
        }
        if (input.isKeyPressed(GLFW.GLFW_KEY_RIGHT_BRACKET)) {
            this.settings.guiScalePercent = Math.min(170, this.settings.guiScalePercent + 5);
            this.guiManager.setScale(this.settings.guiScaleFactor());
            this.logger.debug("GUI-Größe: " + this.settings.guiScalePercent + " %");
            changed = true;
        }
        if (input.isKeyPressed(GLFW.GLFW_KEY_MINUS)) {
            this.settings.renderDistance = Math.max(2, this.settings.renderDistance - 1);
            this.world.getChunkManager().setRenderDistance(this.settings.renderDistance);
            this.logger.debug("Render-Distanz: " + this.settings.renderDistance);
            changed = true;
        }
        if (input.isKeyPressed(GLFW.GLFW_KEY_EQUAL)) {
            this.settings.renderDistance = Math.min(32, this.settings.renderDistance + 1);
            this.world.getChunkManager().setRenderDistance(this.settings.renderDistance);
            this.logger.debug("Render-Distanz: " + this.settings.renderDistance);
            changed = true;
        }
        if (input.isKeyPressed(GLFW.GLFW_KEY_O)) {
            this.settings.ambientOcclusion = !this.settings.ambientOcclusion;
            /* AO steckt im gebackenen Mesh -> alle Chunks progressiv neu meshen
               (LOD zieht via Epoche im nächsten LodManager-Tick selbst nach) */
            this.world.getChunkManager().remeshAll();
            this.logger.debug("Ambient Occlusion: " + (this.settings.ambientOcclusion ? "an" : "aus"));
            changed = true;
        }
        if (input.isKeyPressed(GLFW.GLFW_KEY_K)) {
            /* Debug: GPU-Cull-Pfad live an/aus (A/B ohne Neustart, nicht persistiert) */
            de.skyengine.graphics.world.GpuCull.ENABLED = !de.skyengine.graphics.world.GpuCull.ENABLED;
            this.logger.debug("GPU-Cull: " + (de.skyengine.graphics.world.GpuCull.ENABLED ? "an" : "aus"));
        }
        if (input.isKeyPressed(GLFW.GLFW_KEY_J)) {
            /* Debug: GPU-Occlusion-Verdikte rot zeichnen statt cullen (nicht persistiert) */
            de.skyengine.graphics.world.GpuCull.DEBUG_TINT = !de.skyengine.graphics.world.GpuCull.DEBUG_TINT;
            this.logger.debug("GPU-Cull-Debug (rot statt cullen): "
                    + (de.skyengine.graphics.world.GpuCull.DEBUG_TINT ? "an" : "aus"));
        }
        if (input.isKeyPressed(GLFW.GLFW_KEY_H)) {
            /* Laub-Qualität zyklisch LOW -> MID -> HIGH; steckt im gebackenen Mesh -> Voll-Remesh */
            GameSettings.LeavesQuality[] values = GameSettings.LeavesQuality.values();
            this.settings.leavesQuality = values[(this.settings.leavesQuality.ordinal() + 1) % values.length];
            this.world.getChunkManager().remeshAll();
            this.logger.debug("Laub-Qualität: " + this.settings.leavesQuality);
            changed = true;
        }
        if (input.isKeyPressed(GLFW.GLFW_KEY_L)) {
            this.settings.lodEnabled = !this.settings.lodEnabled;
            /* LodManager liest das Setting im nächsten Tick; farPlane sofort nachziehen */
            this.camera.setFarPlane(this.computeFarPlane());
            this.logger.debug("LOD: " + (this.settings.lodEnabled ? "an" : "aus"));
            changed = true;
        }
        if (input.isKeyPressed(GLFW.GLFW_KEY_P)) {
            /* Debug: Chunk-Loading einfrieren, um in LOD-Gebiete zu fliegen (nicht persistiert) */
            ChunkManager chunkManager = this.world.getChunkManager();
            chunkManager.setLoadingPaused(!chunkManager.isLoadingPaused());
            this.logger.debug("Chunk-Loading " + (chunkManager.isLoadingPaused() ? "pausiert" : "fortgesetzt"));
        }
        if (changed) this.settings.save();
    }

    /** Sichtweite der Projektion: mit LOD hinter den äußersten Ring gelegt, sonst wie bisher 1500. */
    private float computeFarPlane() {
        if (!this.settings.lodEnabled) return 1500.0F;
        return (Math.max(this.settings.lodMaxDistance, this.settings.renderDistance) + 8) * 32.0F;
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