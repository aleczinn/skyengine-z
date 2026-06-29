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
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;
import de.skyengine.core.settings.GameSettings;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.gui.ChestScreen;
import de.skyengine.graphics.gui.GuiManager;
import de.skyengine.graphics.world.SelectionBoxRenderer;
import de.skyengine.utils.Utils;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.joml.Vector3d;
import org.lwjgl.glfw.GLFW;

import java.io.File;

public class GameContainer implements IInitializable, IResizeable, IDisposable {

    private final Logger logger = LogManager.getLogger(GameContainer.class.getName());

    private Camera camera;
    private EntityPlayer player;
    private World world;
    private SelectionBoxRenderer selectionBoxRenderer;
    private GuiManager guiManager;

    private final GameSettings settings = GameSettings.get();

    private static final double REACH = 6.0;

    /** Reichweite (Blöcke), in der der Spieler gedroppte Items aufsammelt. */
    private static final double PICKUP_RANGE = 1.4;

    /* Block-Interaktion: sofort beim Klick, beim Halten alle 200ms (= 4 Ticks, wie Minecraft) */
    private static final long INTERACT_DELAY_MS = 200;
    private long lastBreakTime = 0;
    private long lastPlaceTime = 0;

    /* Doppel-Leertaste schaltet das Fliegen um (wie Minecraft): zweiter Tipp binnen 300ms. */
    private static final long DOUBLE_TAP_MS = 300;
    private long lastSpacePressTime = 0;

    /* Wiederverwendet, um Allokationen pro Frame zu vermeiden */
    private final Vector3d rayDirection = new Vector3d();

    private boolean debugChunkBoundingBox = false;
    private boolean debugChunkWireframe = false;

    /* Wird per F2 gesetzt und von SkyEngine nach dem fertigen Frame abgeholt. */
    private boolean screenshotRequested = false;

    private BlockRaycast.Hit hit = null;

    /* Spieler-Inventar (36 Slots: 0..8 Hotbar, 9..35 Hauptinventar). Auswahl per Zahlentasten 1..9. */
    private final SimpleItemStorage playerInventory = new SimpleItemStorage(36);
    private int hotbarIndex = 0;

    public GameContainer() {
        this.camera = new Camera();
        this.player = new EntityPlayer();
        this.player.setPosition(0, 90, 0);

        this.world = new World("world");
        this.selectionBoxRenderer = new SelectionBoxRenderer();
    }

    @Override
    public void init() {
        Blocks.bootstrap(new File(Files.RESOURCES_PATH, "game/blocks"));

        this.fillStartInventory();

        this.world.init(); // creates ChunkManager, renderer, texture array
        this.camera.setInverseDepth(SkyEngine.get().getWindow().getProperties().isUseInverseDepth());
        this.selectionBoxRenderer.init();

        this.guiManager = new GuiManager(SkyEngine.get().getInput());
        this.guiManager.init(this.world.getChunkRenderer().getTextureArray(),
                this.world.getBlockEntityRenderDispatcher());

        this.applySettings();

        SkyEngine.get().getInput().centerMouse();
        SkyEngine.get().getInput().disableCursor();
    }

    /** Übernimmt die persistenten Einstellungen in die laufenden Systeme. */
    private void applySettings() {
        this.world.getChunkManager().setRenderDistance(this.settings.renderDistance);
        this.camera.setFov(this.settings.fov);
        this.guiManager.setScale(this.settings.guiScaleFactor());
        /* Über das Window setzen, damit dessen Zustand (config.isVSync) authoritativ bleibt -
           der FPS-Limiter im gameLoop liest window.isVSync(). Läuft auf dem Render-Thread,
           wo der GL-Kontext aktiv ist (glfwSwapInterval gehört dorthin, nicht auf den Main-Thread). */
        SkyEngine.get().getWindow().setVsync(this.settings.vsync);
    }

    public void update(Input input) {
        /* Bei offenem GUI friert die Spielerbewegung ein; prev=current verhindert Kamera-Jitter
           (sonst interpoliert Camera.follow weiter zwischen zwei Tick-Positionen). Welt tickt weiter. */
        if (this.guiManager.isOpen()) {
            this.player.snapPrevToCurrent();
        } else {
            this.player.update(input, this.world);
        }
        this.world.update(input, this.player);
        this.pickupItems();
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

    public void render(Input input, int width, int height, float partialTick) {
        this.handleGlobalHotkeys(input);   // immer: Fullscreen, GUI-Scale, Render-Distanz

        boolean guiOpen = this.guiManager.isOpen();

        /* Maus-Blick + Hotbar + Gameplay-Debug nur ohne offenes GUI. */
        if (!guiOpen) {
            if (input.isKeyPressed(GLFW.GLFW_KEY_ESCAPE)) SkyEngine.get().shutdown();
            this.handleDebugInput(input);
            this.handleHotbarInput(input);
            double sens = this.settings.mouseSensitivity;
            this.player.turn(input.getDeltaMouseX() * sens, input.getDeltaMouseY() * sens);
        }

        this.camera.follow(this.player, partialTick);
        this.camera.update((double) width / height);

        this.hit = BlockRaycast.raycast(
                this.world,
                this.camera.getPosition(),
                this.camera.getDirection(this.rayDirection),
                REACH
        );

        if (guiOpen) {
            this.guiManager.handleInput();       // Schließen + Slot-Klicks (kann den Screen schließen)
        } else {
            this.handleBlockInteraction(input);  // kann ein GUI öffnen
        }

        this.world.render(this.camera, partialTick);

        if (this.hit != null && !this.guiManager.isOpen() && this.player.getGamemode().interactsWithWorld()) {
            this.selectionBoxRenderer.render(this.camera, this.hit.x(), this.hit.y(), this.hit.z(),
                    Blocks.getState(this.hit.block()).getOutlineShape());
        }

        /* Zentrale GUI-Verwaltung: HUD (kein Screen) bzw. Screen-Overlay + Cursor-Sync.
           Im Spectator ist die Hotbar ausgeblendet. */
        boolean showHotbar = this.player.getGamemode() != Gamemode.SPECTATOR;
        this.guiManager.render(width, height, this.playerInventory, this.hotbarIndex, showHotbar);
    }

    @Override
    public void resize(int width, int height) {

    }

    @Override
    public void dispose() {
        this.settings.save();
        if (this.world != null) {
            this.world.dispose();
        }
        this.selectionBoxRenderer.dispose();
        if (this.guiManager != null) this.guiManager.dispose();
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

        if (!breakBlock && !placeBlock) return;

        /* Eimer: eigener fluid-bewusster Strahl (Fluids sind im Normal-Raycast unsichtbar),
           daher unabhängig von hit und vor dessen null-Prüfung. */
        if (placeBlock) {
            ItemStack held = this.playerInventory.get(this.hotbarIndex);
            if (held.getItem() instanceof BucketItem bucket && this.handleBucket(bucket, now)) return;
        }

        if (hit == null) return;

        if (breakBlock) {
            BlockState broken = Blocks.getState(this.hit.block());
            broken.getBlock().onBreak(this.world, hit.x(), hit.y(), hit.z(), broken);
            this.world.setBlock(hit.x(), hit.y(), hit.z(), Blocks.AIR);
            /* Drops nur im Survival; Creative baut ohne Item ab. */
            if (this.player.getGamemode().dropsItems()) {
                Item drop = Items.get(broken.getBlock().getIdentifier());
                if (drop != null) {
                    this.world.spawnItem(hit.x() + 0.5, hit.y() + 0.5, hit.z() + 0.5, new ItemStack(drop, 1));
                }
            }
            this.lastBreakTime = now;
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

            /* Platzieren: an der getroffenen Seite, nicht im Block selbst */
            int px = hit.x() + hit.faceX();
            int py = hit.y() + hit.faceY();
            int pz = hit.z() + hit.faceZ();

            /* Kamera-im-Block-Fall: face ist (0,0,0) -> würde den Zielblock ersetzen, abbrechen */
            if (hit.faceX() == 0 && hit.faceY() == 0 && hit.faceZ() == 0) return;

            if (this.isReplaceable(this.world.getBlock(px, py, pz))) {
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
                    this.lastPlaceTime = now;
                }
            }
        }
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
        this.lastPlaceTime = now;
        return true;
    }

    /** Rechtsklick auf eine Truhe öffnet ihr GUI (Truhe + Spielerinventar) und öffnet den Deckel. */
    private boolean tryOpenChest(long now) {
        BlockEntity be = this.world.getBlockEntity(this.hit.x(), this.hit.y(), this.hit.z());
        if (!(be instanceof ChestBlockEntity chest)) return false;
        this.guiManager.open(new ChestScreen(chest, this.playerInventory));
        this.lastPlaceTime = now;
        return true;
    }

    /**
     * Eimer-Interaktion: gefüllt platziert eine Fluid-Quelle, leer nimmt eine Quelle auf.
     * Im Survival wird der Eimer getauscht (gefüllt↔leer), im Creative nicht.
     * Nutzt einen fluid-bewussten Raycast, damit Fluids als Ziel zählen.
     */
    private boolean handleBucket(BucketItem bucket, long now) {
        BlockRaycast.Hit fhit = BlockRaycast.raycast(this.world, this.camera.getPosition(),
                this.camera.getDirection(this.rayDirection), REACH, true);
        if (fhit == null) return false;

        boolean consume = this.player.getGamemode() == Gamemode.SURVIVAL;

        if (bucket.isEmpty()) {
            /* Aufnehmen: nur eine Fluid-Quelle (LEVEL 0, nicht fallend). */
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

        /* Platzieren: Trifft der Strahl direkt ein Fluid, wird DIESE Zelle zur Quelle (neue Quelle
           im Wasser), sonst an der getroffenen Seite. Ziel muss überbaubar sein (Luft/Fluid). */
        int tx, ty, tz;
        if (Blocks.getState(this.world.getBlock(fhit.x(), fhit.y(), fhit.z())).isFluid()) {
            tx = fhit.x(); ty = fhit.y(); tz = fhit.z();
        } else {
            tx = fhit.x() + fhit.faceX();
            ty = fhit.y() + fhit.faceY();
            tz = fhit.z() + fhit.faceZ();
        }
        if (!this.isReplaceable(this.world.getBlock(tx, ty, tz))) return false;

        Block fluid = bucket.getFluid();
        short source = fluid.getDefaultState()
                .with(Properties.LEVEL, 0).with(Properties.FALLING, false).getId();
        this.world.setBlock(tx, ty, tz, source);
        this.world.scheduleTick(tx, ty, tz, 1);
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

    private void fillStartInventory() {
        /* Hotbar (Slots 0-8): Test-Blöcke + die drei Eimer hinten, damit Wasser/Lava direkt
           testbar sind. Wasser hat kein Block-Item mehr (gehört in den Eimer). */
        short[] start = {
                Blocks.CHEST,
                Blocks.OAK_PLANKS,
                Blocks.STONE_SLAB,
                Blocks.SAND,
                Blocks.COBBLESTONE_STAIRS,
                Blocks.OAK_FENCE,
        };
        for (int i = 0; i < start.length; i++) {
            Item item = Items.get(Blocks.getState(start[i]).getBlock().getIdentifier());
            if (item != null) this.playerInventory.set(i, new ItemStack(item, 64));
        }
        this.setItem(6, "skyengine:water_bucket");
        this.setItem(7, "skyengine:lava_bucket");
        this.setItem(8, "skyengine:bucket");

        /* Glasscheibe/Tür + Sand ins Hauptinventar (zum Testen, Truhe befüllen/leeren). */
        this.setBlock(9, Blocks.GLASS_PANE);
        this.setBlock(10, Blocks.OAK_DOOR);
        this.setBlock(11, Blocks.SAND);
    }

    /** Legt 64 eines Blocks in einen Inventar-Slot (Block-Item über die Identifier-Registry). */
    private void setBlock(int slot, short block) {
        Item item = Items.get(Blocks.getState(block).getBlock().getIdentifier());
        if (item != null) this.playerInventory.set(slot, new ItemStack(item, 64));
    }

    private void setItem(int slot, String itemId) {
        Item item = Items.get(Identifier.of(itemId));
        if (item != null) this.playerInventory.set(slot, new ItemStack(item, 1));
    }

    /** Eine Zelle ist überbaubar, wenn sie leer ist oder ein Fluid enthält (Wasser/Lava). */
    private boolean isReplaceable(short block) {
        return block == Blocks.AIR || Blocks.getState(block).isFluid();
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
        for (int i = 0; i < 9; i++) {
            if (input.isKeyPressed(GLFW.GLFW_KEY_1 + i)) {
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
    }

    private void handleDebugInput(Input input) {
        /* Doppel-Leertaste = Fliegen umschalten (toggleFlying prüft den Modus selbst). */
        if (input.isKeyPressed(GLFW.GLFW_KEY_SPACE)) {
            long now = System.currentTimeMillis();
            if (now - this.lastSpacePressTime <= DOUBLE_TAP_MS) {
                this.player.toggleFlying();
                this.logger.debug("Flying: " + this.player.isFlying());
                this.lastSpacePressTime = 0; // verbraucht, damit ein dritter Tipp nicht sofort wieder toggelt
            } else {
                this.lastSpacePressTime = now;
            }
        }
        if (input.isKeyPressed(GLFW.GLFW_KEY_N)) {
            this.player.toggleNoClip();
            this.logger.debug("NoClip: " + this.player.isNoClip());
        }
        if (input.isKeyPressed(GLFW.GLFW_KEY_G)) {
            this.player.setGamemode(this.player.getGamemode().next());
            this.logger.debug("Gamemode: " + this.player.getGamemode());
        }
        if (input.isKeyPressed(GLFW.GLFW_KEY_F6)) {
            this.debugChunkWireframe = !this.debugChunkWireframe;
            this.logger.debug("Wireframe: " + this.debugChunkWireframe);
            Utils.setWireframe(this.debugChunkWireframe);
        }
        if (input.isKeyPressed(GLFW.GLFW_KEY_F7)) {
            this.debugChunkBoundingBox = !this.debugChunkBoundingBox;
            this.logger.debug("Chunk Bounding Box: " + this.debugChunkBoundingBox);
        }
        if (input.isKeyPressed(GLFW.GLFW_KEY_F8)) {
            this.world.getChunkManager().getChunks().clear();
            this.logger.debug("reload chunks");
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
        if (input.isKeyPressed(GLFW.GLFW_KEY_F2)) {
            /* Nur markieren: der Pixel-Read passiert erst nach dem fertigen Frame (SkyEngine.onRender). */
            this.screenshotRequested = true;
        }
        if (input.isKeyPressed(GLFW.GLFW_KEY_F11)) {
            boolean fullscreen = SkyEngine.get().getConfig().isWindowed();
            SkyEngine.get().getMainThreadTasks().add(() ->
                    SkyEngine.get().getWindow().setWindowMode(fullscreen
                            ? EngineConfig.WindowMode.BORDERLESS_FULLSCREEN : EngineConfig.WindowMode.WINDOWED));
            this.logger.debug("Toggle Fullscreen");
        }

        boolean changed = false;
        if (input.isKeyPressed(GLFW.GLFW_KEY_LEFT_BRACKET)) {
            this.settings.guiScale = Math.max(1, this.settings.guiScale - 5);
            this.guiManager.setScale(this.settings.guiScaleFactor());
            this.logger.debug("GUI-Scale: " + this.settings.guiScale);
            changed = true;
        }
        if (input.isKeyPressed(GLFW.GLFW_KEY_RIGHT_BRACKET)) {
            this.settings.guiScale = Math.min(100, this.settings.guiScale + 5);
            this.guiManager.setScale(this.settings.guiScaleFactor());
            this.logger.debug("GUI-Scale: " + this.settings.guiScale);
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
        if (changed) this.settings.save();
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
}