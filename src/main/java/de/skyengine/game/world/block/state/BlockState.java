package de.skyengine.game.world.block.state;

import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.RenderLayer;
import de.skyengine.game.world.block.model.BakedQuad;

import java.util.HashMap;
import java.util.Map;

public final class BlockState {

    private final Block block;
    private final Map<Property<?>, Object> values;

    /** Runtime-ID, wird beim Registry-Bake vergeben. NICHT persistieren! */
    private short id;
    private BakedQuad[] model = new BakedQuad[0];

    public BlockState(Block block, Map<Property<?>, Object> values) {
        this.block = block;
        this.values = values;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(Property<T> property) {
        return (T) this.values.get(property);
    }

    /** Liefert den State mit einem geänderten Property-Wert (z.B. state.with(FACING, NORTH)). */
    public <T> BlockState with(Property<T> property, T value) {
        Map<Property<?>, Object> copy = new HashMap<>(this.values);
        copy.put(property, value);

        BlockState state = this.block.getState(copy);
        if (state == null) {
            throw new IllegalArgumentException("Ungültiger Wert " + value + " für " + property + " bei " + this.block.getIdentifier());
        }
        return state;
    }

    /* --- Hot-Path-Abfragen (Mesher, Kollision) --- */

    public boolean isAir() {
        return this.block.isAir();
    }

    public boolean isOpaqueCube() {
        return this.block.isOpaqueCube(this);
    }

    public boolean isSolid() {
        return this.block.isSolid(this);
    }

    public RenderLayer getRenderLayer() {
        return this.block.getRenderLayer(this);
    }

    public boolean hasRandomOffset() {
        return this.block.hasRandomOffset(this);
    }

    /* --- Infrastruktur --- */

    public Block getBlock() {
        return block;
    }

    public Map<Property<?>, Object> getValues() {
        return values;
    }

    public short getId() {
        return id;
    }

    public void setId(short id) {
        this.id = id;
    }

    public BakedQuad[] getModel() {
        return model;
    }

    public void setModel(BakedQuad[] model) {
        this.model = model;
    }

    @Override
    public String toString() {
        return this.block.getIdentifier() + (this.values.isEmpty() ? "" : this.values.toString());
    }
}