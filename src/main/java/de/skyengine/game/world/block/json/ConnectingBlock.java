package de.skyengine.game.world.block.json;

import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.game.world.block.model.BlockModels;
import de.skyengine.game.world.block.model.BoxElement;
import de.skyengine.game.world.block.shape.BlockShape;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.state.Property;

import java.util.ArrayList;
import java.util.List;

/**
 * Basis für Blöcke mit Verbindungssystem (Zäune, Panes/Iron-Bars): zentraler
 * Pfosten plus Arme zu den vier horizontalen Nachbarn. Die Verbindungen liegen
 * als vier Boolean-Properties vor und werden bei Platzierung und Nachbar-Updates
 * neu berechnet.
 */
public abstract class ConnectingBlock extends JsonBlock {

    public ConnectingBlock(Identifier identifier, Settings settings, BlockDefinition definition) {
        super(identifier, settings, definition);
    }

    @Override
    protected void appendProperties(List<Property<?>> properties) {
        properties.add(Properties.NORTH);
        properties.add(Properties.EAST);
        properties.add(Properties.SOUTH);
        properties.add(Properties.WEST);
    }

    @Override
    public boolean isOpaqueCube(BlockState state) {
        return false;
    }

    /* ---- Geometrie-Parameter (von Subklassen) ---- */

    protected abstract double postMin();
    protected abstract double postMax();
    /** Kollisionshöhe (Zaun 1.5 gegen Drüberspringen, Pane 1.0). */
    protected abstract double collisionHeight();
    /** true: verbindet sich mit diesem (gleichfamiliären) Nachbarblock. */
    protected abstract boolean connectsToFamily(Block other);

    /**
     * Vertikale Segmente (Riegel) der Arme im SICHTBAREN Modell, je {y0, y1}.
     * Zaun: zwei Riegel mit Hohlraum dazwischen. Pane: ein durchgehender Riegel.
     */
    protected abstract double[][] armSegments();

    /** Breite der Arme (perpendikular). Default = Pfostenbreite; Zaun macht sie dünner. */
    protected double armMin() { return this.postMin(); }
    protected double armMax() { return this.postMax(); }

    protected int postTexture() { return this.resolveLayer("all", "side"); }
    protected int armTexture() { return this.resolveLayer("all", "side"); }

    /* ---- Verbindungslogik ---- */

    private boolean connected(World world, int x, int y, int z, Direction d) {
        BlockState nb = Blocks.getState(world.getBlock(x + d.offsetX(), y, z + d.offsetZ()));
        return this.connectsToFamily(nb.getBlock()) || nb.isOpaqueCube();
    }

    private BlockState computeState(World world, int x, int y, int z, BlockState state) {
        for (Direction d : Direction.horizontal()) {
            state = state.with(Properties.connection(d), this.connected(world, x, y, z, d));
        }
        return state;
    }

    @Override
    public BlockState getPlacementState(World world, int x, int y, int z,
                                        int faceX, int faceY, int faceZ,
                                        double hitY, float playerYaw) {
        return this.computeState(world, x, y, z, this.getDefaultState());
    }

    @Override
    public BlockState getStateForNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        return this.computeState(world, x, y, z, state);
    }

    /* ---- Boxen / Modell / Shapes ---- */

    /** Sichtbares Modell: Pfosten + Arme aus mehreren Riegel-Segmenten (Hohlraum). */
    private List<BoxElement> modelBoxes(BlockState state, int postTex, int armTex) {
        double a = this.postMin(), b = this.postMax();
        List<BoxElement> els = new ArrayList<>();
        els.add(BoxElement.of(a, 0, a, b, 1, b, postTex)); // Pfosten

        for (Direction d : Direction.horizontal()) {
            if (!state.get(Properties.connection(d))) continue;
            for (double[] seg : this.armSegments()) {
                els.add(this.arm(d, seg[0], seg[1], armTex));
            }
        }
        return els;
    }

    /** Kollision/Umriss: Pfosten + ein einfacher Kasten je Arm (Pfosten -> Kante). */
    private List<BoxElement> shapeBoxes(BlockState state, double height) {
        double a = this.postMin(), b = this.postMax();
        List<BoxElement> els = new ArrayList<>();
        els.add(BoxElement.of(a, 0, a, b, height, b, 0));

        for (Direction d : Direction.horizontal()) {
            if (state.get(Properties.connection(d))) els.add(this.arm(d, 0, height, 0));
        }
        return els;
    }

    /** Ein Arm-Kasten Richtung d, perpendikular auf Armbreite, y von y0..y1. */
    private BoxElement arm(Direction d, double y0, double y1, int tex) {
        double a = this.postMin(), b = this.postMax();
        double am = this.armMin(), aM = this.armMax();
        return switch (d) {
            case NORTH -> BoxElement.of(am, y0, 0, aM, y1, b, tex);
            case SOUTH -> BoxElement.of(am, y0, a, aM, y1, 1, tex);
            case WEST -> BoxElement.of(0, y0, am, b, y1, aM, tex);
            default -> BoxElement.of(a, y0, am, 1, y1, aM, tex); // EAST
        };
    }

    @Override
    public BakedQuad[] bakeModel(BlockState state) {
        return BlockModels.bake(this.modelBoxes(state, this.postTexture(), this.armTexture()));
    }

    /**
     * true: Kollision ist EINE umschließende AABB des ganzen Moduls statt einzelner
     * Arm-Boxen. Leichter und verhindert Hängenbleiben zwischen den Pfählen (Zäune).
     */
    protected boolean mergedCollision() { return false; }

    @Override
    public BlockShape getCollisionShape(BlockState state) {
        List<BoxElement> els = this.shapeBoxes(state, this.collisionHeight());
        return this.mergedCollision() ? new BlockShape(new AABB[]{union(els)}) : toShape(els);
    }

    @Override
    public BlockShape getOutlineShape(BlockState state) {
        return toShape(this.shapeBoxes(state, 1.0));
    }

    private static BlockShape toShape(List<BoxElement> els) {
        AABB[] boxes = new AABB[els.size()];
        for (int i = 0; i < els.size(); i++) boxes[i] = els.get(i).toAABB();
        return new BlockShape(boxes);
    }

    /** Umschließende AABB aller Boxen (das komplette Modul als eine Box). */
    private static AABB union(List<BoxElement> els) {
        BoxElement f = els.get(0);
        double minX = f.x0, minY = f.y0, minZ = f.z0, maxX = f.x1, maxY = f.y1, maxZ = f.z1;
        for (BoxElement e : els) {
            minX = Math.min(minX, e.x0); minY = Math.min(minY, e.y0); minZ = Math.min(minZ, e.z0);
            maxX = Math.max(maxX, e.x1); maxY = Math.max(maxY, e.y1); maxZ = Math.max(maxZ, e.z1);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
