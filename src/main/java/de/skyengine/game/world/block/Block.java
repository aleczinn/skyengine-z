package de.skyengine.game.world.block;

import de.skyengine.game.world.block.archetype.BlockConfig;
import de.skyengine.game.world.block.behavior.BlockBehavior;
import de.skyengine.game.world.block.behavior.PlacementContext;
import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.game.world.block.model.BlockStateModels;
import de.skyengine.game.world.block.shape.BlockShape;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Property;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Block {

    private final Identifier identifier;
    private final Settings settings;
    private final BlockConfig config;

    private final List<Property<?>> properties = new ArrayList<>();
    private final List<BlockState> states = new ArrayList<>();
    private final Map<Map<Property<?>, Object>, BlockState> stateLookup = new HashMap<>();
    private BlockState defaultState;

    public Block(Identifier identifier, Settings settings) {
        this(identifier, settings, BlockConfig.EMPTY);
    }

    /**
     * Komposition: zusätzliche Properties, Verhalten, Shapes und Modell kommen aus dem
     * {@link BlockConfig} (vom Archetyp gefüllt). Transitionale Subklassen nutzen
     * {@link #appendProperties} und Methoden-Overrides; ihre Config ist {@code EMPTY}.
     */
    public Block(Identifier identifier, Settings settings, BlockConfig config) {
        this.identifier = identifier;
        this.settings = settings;
        this.config = config;

        this.appendProperties(this.properties);
        for (Property<?> property : config.properties()) {
            if (!this.properties.contains(property)) this.properties.add(property);
        }
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
        return this.config.opaquePredicate() != null
                ? this.config.opaquePredicate().test(state)
                : this.settings.opaque;
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
     * Der Block bekommt beim Meshen einen seed-basierten XZ-Offset (wie
     * Minecraft), damit Cross-Blöcke (Gras, Blumen) nicht in Reih und Glied stehen.
     */
    public boolean hasRandomOffset(BlockState state) {
        return this.config.randomOffset();
    }

    /* --- Formen (Phase 2): Kollision, Raycast, Selection-Box --- */

    /** Kollisionsform (Entity/Spieler). Aus dem ShapeProvider (Archetyp) oder Default. */
    public BlockShape getCollisionShape(BlockState state) {
        if (this.config.shapeProvider() != null) return this.config.shapeProvider().collision(state);
        return this.isSolid(state) ? BlockShape.FULL_CUBE : BlockShape.EMPTY;
    }

    /** Umrissform für Raycast + Selection-Box. Aus dem ShapeProvider (Archetyp) oder Default. */
    public BlockShape getOutlineShape(BlockState state) {
        if (this.config.shapeProvider() != null) return this.config.shapeProvider().outline(state);
        return BlockShape.FULL_CUBE;
    }

    /**
     * State, der beim Platzieren gesetzt wird (Facing aus Blickrichtung, Slab-Hälfte
     * aus Trefferpunkt, ...). Default: Default-State.
     *
     * @param hitY relativer Trefferpunkt-Y innerhalb des Zielfeldes (0..1)
     */
    public BlockState getPlacementState(de.skyengine.game.world.World world,
                                        int x, int y, int z,
                                        int faceX, int faceY, int faceZ,
                                        double hitY, float playerYaw) {
        BlockState state = this.defaultState;
        if (!this.config.behaviors().isEmpty()) {
            PlacementContext ctx = new PlacementContext(world, x, y, z, faceX, faceY, faceZ, hitY, playerYaw);
            for (BlockBehavior behavior : this.config.behaviors()) {
                state = behavior.onPlace(ctx, state);
            }
        }
        return state;
    }

    /**
     * Recompute des eigenen States nach einer Nachbaränderung (Verbindungen,
     * Treppen-Ecken). Delegiert an die Behaviors; Default: unverändert.
     */
    public BlockState getStateForNeighborUpdate(de.skyengine.game.world.World world,
                                                int x, int y, int z, BlockState state) {
        for (BlockBehavior behavior : this.config.behaviors()) {
            state = behavior.onNeighborUpdate(world, x, y, z, state);
        }
        return state;
    }

    /**
     * Backt das Modell eines States aus dem datengetriebenen Blockstate-/Modell-System
     * (Phase 3). Wird beim Registry-Bake aufgerufen, nachdem die Modelle geladen sind.
     * Sonderfälle (z.B. Cross) überschreiben dies.
     */
    public BakedQuad[] bakeModel(BlockState state) {
        if (this.isAir()) return new BakedQuad[0];
        if (this.config.modelGenerator() != null) return this.config.modelGenerator().bake(state);
        return BlockStateModels.bake(this, state).quads();
    }

    /** Connection-Gruppe (z.B. "fence", "pane") oder null. Steuert Verbindungen (siehe ConnectionRules). */
    public String getConnectionGroup() {
        return this.config.connectionGroup();
    }

    /** BlockEntity-Typ dieses Blocks oder null (kein „lebender" Block). */
    public de.skyengine.game.world.block.entity.BlockEntityType<?> getBlockEntityType() {
        return this.config.blockEntityType();
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