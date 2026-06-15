package de.skyengine.game.world.block.json;

import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.shape.BlockShape;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.state.Property;

import java.util.ArrayList;
import java.util.List;

/**
 * Basis für Blöcke mit Verbindungssystem (Zäune, Panes/Iron-Bars). Die vier
 * Boolean-Properties NORTH/EAST/SOUTH/WEST werden bei Platzierung und Nachbar-Updates
 * berechnet; die <b>Optik</b> kommt aus dem {@code multipart}-Blockstate (post + side
 * je Verbindung). Die <b>Kollision/Outline</b> bleibt Java: eine zusammenhängende
 * Balken-Shape (verbindungsabhängig), Höhe aus {@link #collisionHeight()}.
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

    /** Kollisionshöhe (Zaun 1.5 gegen Drüberspringen, Pane 1.0). Gameplay-Wert, kein Modellmaß. */
    protected abstract double collisionHeight();
    /** true: verbindet sich mit diesem (gleichfamiliären) Nachbarblock. */
    protected abstract boolean connectsToFamily(Block other);

    /* Pfostenbreite kommt aus der Block-JSON (post in 0..16 Pixeln; bei Connecting-Blöcken Pflicht). */
    private double rPostMin() { return this.definition.post.x0(); }
    private double rPostMax() { return this.definition.post.x1(); }

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

    /* ---- Kollision/Outline: eine zusammenhängende Balken-Shape ---- */

    @Override
    public BlockShape getCollisionShape(BlockState state) {
        return new BlockShape(this.connectedShape(state, this.collisionHeight()));
    }

    @Override
    public BlockShape getOutlineShape(BlockState state) {
        return new BlockShape(this.connectedShape(state, 1.0));
    }

    /**
     * Höchstens zwei sich am Pfosten überschneidende Balken (Z = NORTH..SOUTH,
     * X = WEST..EAST) in Pfostenbreite. Gerade Verbindung → ein durchgehender Balken,
     * Ecke/T/Kreuz → zwei überlappende Balken; nie eine umschließende Box.
     */
    private AABB[] connectedShape(BlockState state, double height) {
        double a = this.rPostMin(), b = this.rPostMax();
        boolean n = state.get(Properties.NORTH), s = state.get(Properties.SOUTH);
        boolean w = state.get(Properties.WEST), e = state.get(Properties.EAST);

        List<AABB> boxes = new ArrayList<>(2);
        if (n || s) boxes.add(new AABB(a, 0, n ? 0 : a, b, height, s ? 1 : b));
        if (w || e) boxes.add(new AABB(w ? 0 : a, 0, a, e ? 1 : b, height, b));
        if (boxes.isEmpty()) boxes.add(new AABB(a, 0, a, b, height, b));
        return boxes.toArray(new AABB[0]);
    }
}
