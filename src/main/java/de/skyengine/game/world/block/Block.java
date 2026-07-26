package de.skyengine.game.world.block;

import de.skyengine.game.world.block.archetype.BlockConfig;
import de.skyengine.game.world.block.archetype.FluidInfo;
import de.skyengine.game.world.block.behavior.BlockBehavior;
import de.skyengine.game.world.block.behavior.PlacementContext;
import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.game.world.block.model.BlockModels;
import de.skyengine.game.world.block.model.BlockStateModels;
import de.skyengine.game.world.block.shape.BlockShape;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
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

    /** true: nie als LOD-Terrain-Oberfläche sampeln (Logs — LOD zeigt nur Terrain). */
    public boolean isExcludedFromLodSurface() {
        return this.settings.noLodSurface;
    }

    /** true: Laub — bei LeavesQuality LOW cullen Laub-Faces gegen JEDES Nachbar-Laub. */
    public boolean isLeaves() {
        return this.settings.leaves;
    }

    /** true: Wasser/Lava — Geometrie kommt dynamisch aus dem Mesher (kein gebackenes Modell). */
    public boolean isFluid() {
        return this.config.fluidInfo() != null;
    }

    /** Fluid-Metadaten (Texturlayer, Ausbreitung, Tick) oder {@code null}, wenn kein Fluid. */
    public FluidInfo getFluidInfo() {
        return this.config.fluidInfo();
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
     * @return der zu platzierende State, oder {@code null} wenn ein Behavior die
     *         Platzierung ablehnt (z.B. Tür ohne Platz für den oberen Teil)
     */
    public BlockState getPlacementState(de.skyengine.game.world.World world,
                                        int x, int y, int z,
                                        int faceX, int faceY, int faceZ,
                                        double hitX, double hitY, double hitZ, float playerYaw) {
        BlockState state = this.defaultState;
        if (this.config.behaviors().isEmpty()) return state;

        PlacementContext ctx = new PlacementContext(world, x, y, z, faceX, faceY, faceZ, hitX, hitY, hitZ, playerYaw);
        for (BlockBehavior behavior : this.config.behaviors()) {
            state = behavior.onPlace(ctx, state);
        }
        /* Veto nach dem Berechnen des States: lehnt ein Behavior ab, wird nicht platziert. */
        for (BlockBehavior behavior : this.config.behaviors()) {
            if (!behavior.canPlace(ctx, state)) return null;
        }
        return state;
    }

    /**
     * Seiteneffekte nach erfolgreicher Platzierung (z.B. den oberen Türteil setzen).
     * Wird erst aufgerufen, nachdem der State validiert und gesetzt wurde.
     */
    public void onPlaced(de.skyengine.game.world.World world, int x, int y, int z, BlockState state) {
        for (BlockBehavior behavior : this.config.behaviors()) {
            behavior.onPlaced(world, x, y, z, state);
        }
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

    /** Rechtsklick-Interaktion. Delegiert an die Behaviors; true = verbraucht. */
    public boolean onUse(de.skyengine.game.world.World world, int x, int y, int z, BlockState state) {
        for (BlockBehavior behavior : this.config.behaviors()) {
            if (behavior.onUse(world, x, y, z, state)) return true;
        }
        return false;
    }

    /** Abbau-Hook (vor dem Entfernen). Delegiert an die Behaviors; Default: nichts. */
    public void onBreak(de.skyengine.game.world.World world, int x, int y, int z, BlockState state) {
        for (BlockBehavior behavior : this.config.behaviors()) {
            behavior.onBreak(world, x, y, z, state);
        }
    }

    /** Geplanter Tick (Fluss, Fall, ...), von {@code World.scheduleTick} ausgelöst. Delegiert; Default: nichts. */
    public void scheduledTick(de.skyengine.game.world.World world, int x, int y, int z, BlockState state) {
        for (BlockBehavior behavior : this.config.behaviors()) {
            behavior.scheduledTick(world, x, y, z, state);
        }
    }

    /** Zufalls-Tick (Wachstum, Verfall, ...). Nur wenn {@link #ticksRandomly()}. Delegiert; Default: nichts. */
    public void randomTick(de.skyengine.game.world.World world, int x, int y, int z, BlockState state) {
        for (BlockBehavior behavior : this.config.behaviors()) {
            behavior.randomTick(world, x, y, z, state);
        }
    }

    /** true, wenn dieser Block beim Random-Tick berücksichtigt wird (Pflanzen, Gras). */
    public boolean ticksRandomly() {
        return this.config.tickRandomly();
    }

    /** Die erste Verhaltens-Instanz des gegebenen Typs oder {@code null} (z.B. für die TNT-Kettenreaktion). */
    public <T extends BlockBehavior> T getBehavior(Class<T> type) {
        for (BlockBehavior behavior : this.config.behaviors()) {
            if (type.isInstance(behavior)) return type.cast(behavior);
        }
        return null;
    }

    /**
     * Backt das Modell eines States aus dem datengetriebenen Blockstate-/Modell-System
     * (Phase 3). Wird beim Registry-Bake aufgerufen, nachdem die Modelle geladen sind.
     * Sonderfälle (z.B. Cross) überschreiben dies.
     */
    public BakedQuad[] bakeModel(BlockState state) {
        if (this.isAir()) return new BakedQuad[0];
        /* Fluids: keine gebackene Geometrie (der Mesher erzeugt sie nachbarabhängig). Hier nur die
           Still-/Flow-Texturlayer im Atlas reservieren (läuft beim Bake, single-threaded). */
        FluidInfo fluid = this.config.fluidInfo();
        if (fluid != null) {
            if (fluid.stillLayer < 0) {
                fluid.stillLayer = BlockTextures.layerOf(fluid.stillTexture);
                fluid.flowLayer = BlockTextures.layerOf(fluid.flowTexture);
            }
            return new BakedQuad[0];
        }
        BakedQuad[] quads = this.config.modelGenerator() != null
                ? this.config.modelGenerator().bake(state)
                : BlockStateModels.bake(this, state).quads();
        return this.applyTint(quads);
    }

    /**
     * Wendet den Block-Tint (Vegetation, siehe {@link Tints}) auf die gebackenen Quads an —
     * no-op bei neutralem Tint. Die Face-Maske schränkt optional auf einzelne Faces ein
     * (Grasblock: nur oben); Maske -1 tintet alle Quads inkl. NO_CULL (Cross). Public,
     * weil der Icon-Pfad frisch aus den Modell-JSONs backt und den Tint selbst anwenden muss.
     */
    public BakedQuad[] applyTint(BakedQuad[] quads) {
        int tint = this.config.tint();
        if (tint == BakedQuad.WHITE) return quads;
        int tintType = this.config.tintType();
        int mask = this.config.tintFaceMask();
        BakedQuad[] out = new BakedQuad[quads.length];
        for (int i = 0; i < quads.length; i++) {
            BakedQuad q = quads[i];
            boolean hit = mask == -1 || (q.cullFace() >= 0 && (mask & 1 << q.cullFace()) != 0);
            /* Vertex-Array wird geteilt (nie mutiert) — nur Tint-Wert und -Typ ändern sich. */
            out[i] = hit ? new BakedQuad(q.vertices(), q.textureLayer(), q.cullFace(), q.face(),
                    q.brightness(), tint, tintType) : q;
        }
        return out;
    }

    /**
     * Getintete Seiten-Overlay-Quads (Grasblock: Grasrand über der Dirt-Seite) oder leer.
     * Landen beim Meshing KOPLANAR (identische Vertices wie die Basis-Seite) im CUTOUT-Layer;
     * der CUTOUT-Pass zeichnet mit "or-equal"-Depth-Func, damit das Overlay exakt gewinnt.
     */
    public BakedQuad[] bakeOverlay(BlockState state) {
        String texture = this.config.overlayTexture();
        if (texture == null) return new BakedQuad[0];
        /* Layer-Registrierung zur Bake-Zeit, single-threaded — wie die Fluid-Layer oben. */
        return BlockModels.overlaySides(BlockTextures.layerOf(texture), this.config.tint(), this.config.tintType());
    }

    /** Multiplikations-Tint 0xRRGGBB des Blocks (WHITE = neutral) — u.a. für flache Item-Icons. */
    public int getTint() {
        return this.config.tint();
    }

    /** Abbau-Härte (Survival): 0 = instant, negativ = unzerstörbar (Bedrock). */
    public float getHardness() {
        return this.config.hardness();
    }

    /** Effektive Tool-Klasse oder null (= Hand reicht, droppt immer). */
    public de.skyengine.game.world.item.ToolType getToolType() {
        return this.config.toolType();
    }

    /** Mindest-Harvest-Level für Drops (0 = jedes Tier der passenden Klasse). */
    public int getHarvestLevel() {
        return this.config.harvestLevel();
    }

    /** Sound-Gruppe für Schritt-/Abbau-/Platzier-Sounds. */
    public de.skyengine.audio.BlockSoundGroup getSoundGroup() {
        return this.config.soundGroup();
    }

    /** true = Platzieren in diese Zelle ersetzt den Block (Gras/Farn, wie MC — kein Drop). */
    public boolean isReplaceable() {
        return this.config.replaceable();
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

    /**
     * State für Icon/Inventar/Hand/Drop-Darstellung: wie der Default-State, aber Pillar/Log-Blöcke
     * (mit {@link Properties#AXIS}) stehen aufrecht (AXIS=Y). Der Default-State ist sonst AXIS=X
     * (Enum-Reihenfolge X,Y,Z -> erster State), sodass Stämme liegend gerendert würden. Gleiche
     * Korrektur wie im Weltgenerator (TreeShapes.verticalLog); Blöcke ohne AXIS bleiben unverändert.
     */
    public BlockState getIconState() {
        if (this.defaultState.getValues().containsKey(Properties.AXIS)) {
            return this.defaultState.with(Properties.AXIS, Direction.Axis.Y);
        }
        return this.defaultState;
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
        boolean noLodSurface = false;
        boolean leaves = false;
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

        public Settings noLodSurface(boolean noLodSurface) {
            this.noLodSurface = noLodSurface;
            return this;
        }

        public Settings leaves(boolean leaves) {
            this.leaves = leaves;
            return this;
        }
    }
}