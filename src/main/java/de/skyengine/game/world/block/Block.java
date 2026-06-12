package de.skyengine.game.world.block;

import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Property;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Block {

    private final Identifier identifier;
    private final Settings settings;

    private final List<Property<?>> properties = new ArrayList<>();
    private final List<BlockState> states = new ArrayList<>();
    private final Map<Map<Property<?>, Object>, BlockState> stateLookup = new HashMap<>();
    private BlockState defaultState;

    public Block(Identifier identifier, Settings settings) {
        this.identifier = identifier;
        this.settings = settings;

        this.appendProperties(this.properties);
        this.createStates();
    }

    /** Für Blöcke mit Properties (Treppen, Slabs, Farben, Zäune) überschreiben. */
    protected void appendProperties(List<Property<?>> properties) {
    }

    /** Kartesisches Produkt aller Property-Werte -> alle BlockStates dieses Blocks. */
    private void createStates() {
        List<Map<Property<?>, Object>> combos = new ArrayList<>();
        combos.add(new HashMap<>());

        for (Property<?> property : this.properties) {
            List<Map<Property<?>, Object>> next = new ArrayList<>();
            for (Map<Property<?>, Object> base : combos) {
                for (Object value : property.getValues()) {
                    Map<Property<?>, Object> copy = new HashMap<>(base);
                    copy.put(property, value);
                    next.add(copy);
                }
            }
            combos = next;
        }

        for (Map<Property<?>, Object> combo : combos) {
            BlockState state = new BlockState(this, Map.copyOf(combo));
            this.states.add(state);
            this.stateLookup.put(state.getValues(), state);
        }
        this.defaultState = this.states.get(0);
    }

    public BlockState getState(Map<Property<?>, Object> values) {
        return this.stateLookup.get(values);
    }

    /* --- Eigenschaften: nehmen den State entgegen, damit Subklassen
           später pro State variieren können (z.B. Double-Slab = opaque) --- */

    public boolean isAir() {
        return this.settings.air;
    }

    public boolean isOpaqueCube(BlockState state) {
        return this.settings.opaque;
    }

    public boolean isSolid(BlockState state) {
        return this.settings.solid;
    }

    public RenderLayer getRenderLayer(BlockState state) {
        return this.settings.renderLayer;
    }

    /** true: innere Faces zwischen zwei identischen Blöcken werden geculled (Glas-an-Glas). */
    public boolean cullsSameBlock() {
        return this.settings.cullSame;
    }

    /**
     * Backt das Modell eines States. Wird beim Registry-Bake aufgerufen,
     * nachdem alle Texturen registriert sind.
     */
    public BakedQuad[] bakeModel(BlockState state) {
        return new BakedQuad[0];
    }

    public Identifier getIdentifier() {
        return identifier;
    }

    public List<BlockState> getStates() {
        return states;
    }

    public BlockState getDefaultState() {
        return defaultState;
    }

    @Override
    public String toString() {
        return "Block[" + this.identifier + "]";
    }

    /* ------------------------------------------------------------------ */

    public static class Settings {

        boolean air = false;
        boolean opaque = true;
        boolean solid = true;
        boolean cullSame = false;
        RenderLayer renderLayer = RenderLayer.OPAQUE;

        public static Settings create() {
            return new Settings();
        }

        public Settings air() {
            this.air = true;
            this.opaque = false;
            this.solid = false;
            return this;
        }

        public Settings opaque(boolean opaque) {
            this.opaque = opaque;
            return this;
        }

        public Settings solid(boolean solid) {
            this.solid = solid;
            return this;
        }

        public Settings layer(RenderLayer layer) {
            this.renderLayer = layer;
            return this;
        }

        public Settings cullSame(boolean cullSame) {
            this.cullSame = cullSame;
            return this;
        }
    }
}