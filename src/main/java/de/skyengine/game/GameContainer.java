package de.skyengine.game;

import de.skyengine.core.EngineConfig;
import de.skyengine.core.SkyEngine;
import de.skyengine.core.file.Files;
import de.skyengine.core.input.Input;
import de.skyengine.core.io.*;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.BlockRaycast;
import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.state.SlabType;
import de.skyengine.graphics.camera.Camera;
import de.skyengine.graphics.ui.UIRenderer;
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
    private UIRenderer uiRenderer;

    private static final double REACH = 6.0;

    /* Block-Interaktion: sofort beim Klick, beim Halten alle 200ms (= 4 Ticks, wie Minecraft) */
    private static final long INTERACT_DELAY_MS = 200;
    private long lastBreakTime = 0;
    private long lastPlaceTime = 0;

    /* Wiederverwendet, um Allokationen pro Frame zu vermeiden */
    private final Vector3d rayDirection = new Vector3d();

    private boolean debugChunkBoundingBox = false;
    private boolean debugChunkWireframe = false;

    private BlockRaycast.Hit hit = null;

    /* Test-Hotbar: Auswahl per Zahlentasten 1..n */
    private short[] hotbar = new short[0];
    private int hotbarIndex = 0;

    public GameContainer() {
        this.camera = new Camera();
        this.player = new EntityPlayer();
        this.player.setPosition(0, 90, 0);

        this.world = new World("world");
        this.selectionBoxRenderer = new SelectionBoxRenderer();
        this.uiRenderer = new UIRenderer();
    }

    @Override
    public void init() {
        Blocks.bootstrap(new File(Files.RESOURCES_PATH, "game/blocks"));

        this.hotbar = new short[]{
                Blocks.OAK_PLANKS,
                Blocks.STONE_SLAB,
                Blocks.STONE_STAIRS,
                Blocks.COBBLESTONE_STAIRS,
                Blocks.OAK_FENCE,
                Blocks.GLASS_PANE,
                Blocks.OAK_DOOR,
                Blocks.GLASS
        };

        this.world.init(); // creates ChunkManager, renderer, texture array
        this.camera.setInverseDepth(SkyEngine.get().getWindow().getProperties().isUseInverseDepth());
        this.selectionBoxRenderer.init();
        this.uiRenderer.init();

        SkyEngine.get().getInput().centerMouse();
        SkyEngine.get().getInput().disableCursor();
    }

    public void update(Input input) {
        this.player.update(input, this.world);
        this.world.update(input, this.player);
    }

    public void render(Input input, int width, int height, float partialTick) {
        this.handleDebugInput(input);
        this.handleHotbarInput(input);

        /* Mouse look per frame */
        this.player.turn(input.getDeltaMouseX(), input.getDeltaMouseY());

        this.camera.follow(this.player, partialTick);
        this.camera.update((double) width / height);

        this.hit = BlockRaycast.raycast(
                this.world,
                this.camera.getPosition(),
                this.camera.getDirection(this.rayDirection),
                REACH
        );

        this.handleBlockInteraction(input);

        this.world.render(this.camera, partialTick);

        if (hit != null) {
            this.selectionBoxRenderer.render(this.camera, this.hit.x(), this.hit.y(), this.hit.z(),
                    Blocks.getState(this.hit.block()).getOutlineShape());
        }

        this.uiRenderer.render(width, height);
    }

    @Override
    public void resize(int width, int height) {

    }

    @Override
    public void dispose() {
        if (this.world != null) {
            this.world.dispose();
        }
        this.selectionBoxRenderer.dispose();
        this.uiRenderer.dispose();
    }

    private void handleBlockInteraction(Input input) {
        long now = System.currentTimeMillis();

        /* Sofort beim Klick (isMousePressed) ODER beim Halten nach Ablauf des Delays */
        boolean breakBlock = input.isMousePressed(GLFW.GLFW_MOUSE_BUTTON_LEFT)
                || (input.isMouseDown(GLFW.GLFW_MOUSE_BUTTON_LEFT) && now - this.lastBreakTime >= INTERACT_DELAY_MS);

        boolean placeBlock = input.isMousePressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT)
                || (input.isMouseDown(GLFW.GLFW_MOUSE_BUTTON_RIGHT) && now - this.lastPlaceTime >= INTERACT_DELAY_MS);

        if (!breakBlock && !placeBlock) return;
        if (hit == null) return;

        if (breakBlock) {
            BlockState broken = Blocks.getState(this.hit.block());
            broken.getBlock().onBreak(this.world, hit.x(), hit.y(), hit.z(), broken);
            this.world.setBlock(hit.x(), hit.y(), hit.z(), Blocks.AIR);
            this.lastBreakTime = now;
        } else {
            /* Rechtsklick-Interaktion des getroffenen Blocks (z.B. Tür auf/zu) hat Vorrang. */
            BlockState hitState = Blocks.getState(this.hit.block());
            if (hitState.getBlock().onUse(this.world, this.hit.x(), this.hit.y(), this.hit.z(), hitState)) {
                this.lastPlaceTime = now;
                return;
            }

            short selected = this.hotbar[this.hotbarIndex];
            Block block = Blocks.getState(selected).getBlock();

            /* Slab auf vorhandene gleiche Slab -> Doppel-Slab */
            if (this.tryMergeSlab(block, now)) return;

            /* Platzieren: an der getroffenen Seite, nicht im Block selbst */
            int px = hit.x() + hit.faceX();
            int py = hit.y() + hit.faceY();
            int pz = hit.z() + hit.faceZ();

            /* Kamera-im-Block-Fall: face ist (0,0,0) -> würde den Zielblock ersetzen, abbrechen */
            if (hit.faceX() == 0 && hit.faceY() == 0 && hit.faceZ() == 0) return;

            if (this.world.getBlock(px, py, pz) == Blocks.AIR) {
                double relHitX = this.hit.hitX() - px;
                double relHitY = this.hit.hitY() - py;
                double relHitZ = this.hit.hitZ() - pz;
                BlockState place = block.getPlacementState(this.world, px, py, pz,
                        this.hit.faceX(), this.hit.faceY(), this.hit.faceZ(),
                        relHitX, relHitY, relHitZ, this.player.yaw);

                /* place == null: ein Behavior lehnt ab (z.B. Tür ohne Platz). Sonst nicht in den
                   eigenen Körper bauen - gegen die ECHTE Kollisionsform testen, damit dünne Blöcke
                   (Panes, Zäune) neben einem platzierbar bleiben. */
                if (place != null && !this.collidesWithPlayer(place, px, py, pz)) {
                    this.world.setBlock(px, py, pz, place.getId());
                    block.onPlaced(this.world, px, py, pz, place);
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

    /** true, wenn die Kollisionsform des Blocks an px/py/pz die Spieler-Box schneidet. */
    private boolean collidesWithPlayer(BlockState state, int px, int py, int pz) {
        for (AABB local : state.getCollisionShape().boxes()) {
            if (local.copy().move(px, py, pz).intersects(this.player.getBoundingBox())) {
                return true;
            }
        }
        return false;
    }

    private void handleHotbarInput(Input input) {
        for (int i = 0; i < this.hotbar.length && i < 9; i++) {
            if (input.isKeyPressed(GLFW.GLFW_KEY_1 + i)) {
                this.hotbarIndex = i;
            }
        }
    }

    private void handleDebugInput(Input input) {
        if (input.isKeyPressed(GLFW.GLFW_KEY_ESCAPE)) {
            SkyEngine.get().shutdown();
        }
        if (input.isKeyPressed(GLFW.GLFW_KEY_F)) {
            this.player.toggleFlying();
            this.logger.debug("Flying: " + this.player.isFlying());
        }
        if (input.isKeyPressed(GLFW.GLFW_KEY_N)) {
            this.player.toggleNoClip();
            this.logger.debug("NoClip: " + this.player.isNoClip());
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
        if (input.isKeyPressed(GLFW.GLFW_KEY_F11)) {
            boolean fullscreen = SkyEngine.get().getConfig().isWindowed();

            SkyEngine.get().getMainThreadTasks().add(() -> {
                SkyEngine.get().getWindow().setWindowMode(fullscreen ? EngineConfig.WindowMode.BORDERLESS_FULLSCREEN : EngineConfig.WindowMode.WINDOWED);
            });

            this.logger.debug("Toggle Fullscreen");
        }
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