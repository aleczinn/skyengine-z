package de.skyengine.game.world.block;

import de.skyengine.game.world.block.archetype.BlockConfig;
import de.skyengine.game.world.block.archetype.FluidInfo;
import de.skyengine.game.world.block.behavior.BlockBehavior;
import de.skyengine.game.world.block.behavior.ObserverBehavior;
import de.skyengine.game.world.block.behavior.PlacementContext;
import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.game.world.block.model.BlockModels;
import de.skyengine.game.world.block.model.BlockStateModels;
import de.skyengine.game.world.block.shape.BlockShape;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.loot.LootContext;
import de.skyengine.game.world.loot.LootSink;
import de.skyengine.game.world.loot.LootTables;
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
    private final boolean reconcileRedstoneOnChunkBoundary;
    private final boolean redstoneSignalSource;

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
        boolean reconcile = false;
        boolean signalSource = false;
        for (BlockBehavior behavior : config.behaviors()) {
            if (behavior.reconcileRedstoneOnChunkBoundary()) {
                reconcile = true;
            }
            if (behavior.isRedstoneSignalSource()) signalSource = true;
        }
        this.reconcileRedstoneOnChunkBoundary = reconcile;
        this.redstoneSignalSource = signalSource;

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

        /* Default ist State 0 (= erster Wert jeder Property), sofern der Archetyp nichts anderes
           deklariert. Erst hier auswerten: with() braucht das fertig gefüllte stateLookup. */
        BlockState state = this.states.get(0);
        for (Map.Entry<Property<?>, Object> e : this.config.defaultValues().entrySet()) {
            if (state.getValues().containsKey(e.getKey())) {
                state = state.with(castProperty(e.getKey()), e.getValue());
            }
        }
        this.defaultState = state;
    }

    @SuppressWarnings("unchecked")
    private static Property<Object> castProperty(Property<?> property) {
        return (Property<Object>) property;
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

    /**
     * Wirft AO/verschattet Ecklicht im Mesher — getrennt vom Culling ({@link #isOpaqueCube}),
     * damit die ausgefahrene Kolben-Basis weiter verschattet, ohne Nachbarflächen zu cullen.
     * Ohne Prädikat gilt die Automatik „wie opaque".
     */
    public boolean occludesAo(BlockState state) {
        return this.config.aoOccluderPredicate() != null
                ? this.config.aoOccluderPredicate().test(state)
                : this.isOpaqueCube(state);
    }

    /**
     * Leitet stark empfangenes Redstone-Signal an seine Nachbarn weiter. Diese Gameplay-
     * Eigenschaft ist in Java Edition ausdrücklich nicht mit visueller Opazität identisch:
     * ein Beobachter ist ein opaker Vollwürfel, aber kein Redstone-Leiter.
     */
    public boolean isRedstoneConductor(BlockState state) {
        return this.config.redstoneConductorPredicate() != null
                ? this.config.redstoneConductorPredicate().test(state)
                : this.isOpaqueCube(state);
    }

    /** true, wenn der Block selbst Signal erzeugt statt nur starkes Signal weiterzuleiten. */
    public boolean isRedstoneSignalSource() {
        return this.redstoneSignalSource;
    }

    public boolean isSolid(BlockState state) {
        return this.settings.solid;
    }

    /**
     * Licht-Opazität 0..15 (wie viel Himmelslicht eine Zelle dieses Blocks schluckt). Ohne
     * {@code light_opacity} in der Block-JSON gilt die Automatik „opaker Vollblock = 15, sonst 0" —
     * per State, damit eine Doppel-Halbstufe blockt und eine einfache nicht. Explizit gesetzt wird
     * nur, wo Licht <b>dämpfen</b> statt durchfallen oder hart enden soll (Wasser, Laub: 1).
     */
    public int getLightOpacity(BlockState state) {
        int v = this.config.lightOpacity();
        return v >= 0 ? v : (this.isOpaqueCube(state) ? 15 : 0);
    }

    /**
     * Eigenleuchten 0..15 (Fackel 14, Lava 15, wie MC); 0 = der Block leuchtet nicht. Quelle ist
     * {@code light_level} in der Block-JSON. Zustandsabhängig über LIT: trägt der State die
     * Property und ist sie false, leuchtet der Block nicht (Redstone-Lampe, Redstone-Fackel).
     * Gebacken pro State in die Flags (Bits 14-17), der Licht-Edit-Pfad reagiert damit
     * automatisch auf lit-Wechsel.
     *
     * <p>Monochrom: {@link BlockConfig#lightColor()} gibt es zwar, wirkt aber noch nicht.
     */
    public int getLuminance(BlockState state) {
        Object lit = state.getValues().get(Properties.LIT);
        if (lit != null && !(Boolean) lit) return 0;
        return this.config.lightLevel();
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

    /**
     * Mitgliedschaft in Vanillas Blocktag {@code does_not_block_hoppers}. Solche Blöcke
     * lassen die Item-Entity-Saugzone eines Hoppers trotz voller Kollisionsform durch.
     */
    public boolean doesNotBlockHoppers() {
        return this.settings.doesNotBlockHoppers;
    }

    /** true: kein Auto-BlockItem — ein Material-Item mit {@code places_block} übernimmt (Staub). */
    public boolean hasNoItem() {
        return this.settings.noItem;
    }

    /**
     * Kolben-Reaktion dieses Blocks. Unzerstörbare Blöcke blockieren immer; ein explizites
     * DESTROY gilt auch für BlockEntity-Blöcke wie den Vanilla-Comparator. Übrige
     * BlockEntity-Blöcke (Truhe, Zaubertisch — ihr Inhalt kann nicht mitreisen) blockieren.
     */
    public PistonReaction getPistonReaction() {
        if (this.config.hardness() < 0) return PistonReaction.BLOCK;
        /* Explizites DESTROY muss vor dem generischen BlockEntity-Schutz gewinnen:
           Vanilla-Comparatoren besitzen eine BE, werden vom Kolben aber trotzdem zerstört. */
        if (this.config.pistonReaction() == PistonReaction.DESTROY) return PistonReaction.DESTROY;
        if (this.config.blockEntityType() != null) return PistonReaction.BLOCK;
        return this.config.pistonReaction();
    }

    /** Klebe-Gruppe fürs Kolben-Schieben ({@code sticky_group}: Slime/Honig) oder null. */
    public String getStickyGroup() {
        return this.config.stickyGroup();
    }

    /** Trichter: Ticks Pause je Transfer ({@code hopper_cooldown}, MC 8 = 2,5 Items/s). */
    public int getHopperCooldown() {
        return this.config.hopperCooldown();
    }

    /** Trichter: Items je Transfer ({@code hopper_amount}). */
    public int getHopperAmount() {
        return this.config.hopperAmount();
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
                                        double hitX, double hitY, double hitZ, float playerYaw,
                                        float playerPitch, boolean sneaking) {
        BlockState state = this.defaultState;
        if (this.config.behaviors().isEmpty()) return state;

        PlacementContext ctx = new PlacementContext(world, x, y, z, faceX, faceY, faceZ,
                hitX, hitY, hitZ, playerYaw, playerPitch, sneaking);
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

    /** Von einem Kolben an dieser Zelle abgesetzt — s. {@link BlockBehavior#onMovedByPiston}. */
    public void onMovedByPiston(de.skyengine.game.world.World world, int x, int y, int z,
                                BlockState state, Direction moveDirection) {
        for (BlockBehavior behavior : this.config.behaviors()) {
            behavior.onMovedByPiston(world, x, y, z, state, moveDirection);
        }
    }

    /**
     * Recompute des eigenen States nach einer Nachbaränderung (Verbindungen,
     * Treppen-Ecken). Delegiert an die Behaviors; Default: unverändert.
     */
    public BlockState getStateForNeighborUpdate(de.skyengine.game.world.World world,
                                                int x, int y, int z, BlockState state) {
        return this.getStateForGeneralNeighborUpdate(world, x, y, z, state);
    }

    /**
     * Gerichtete Variante für einen Shape-Update-Auslöser. Die Richtung zeigt vom Empfänger
     * zum geänderten Nachbarn; der alte ungerichtete Hook bleibt für die übrigen, historisch
     * zusammengefassten Neighbor-Changed-Verhalten erhalten.
     */
    public BlockState getStateForNeighborUpdate(de.skyengine.game.world.World world,
                                                int x, int y, int z, BlockState state,
                                                Direction direction, BlockState neighborState) {
        if (direction != null) {
            state = this.getStateForShapeUpdate(world, x, y, z, state, direction, neighborState);
        }
        return this.getStateForGeneralNeighborUpdate(world, x, y, z, state);
    }

    /** Nur der allgemeine {@code neighborChanged}-Hook, ohne gerichtetes Shape-Update. */
    public BlockState getStateForGeneralNeighborUpdate(de.skyengine.game.world.World world,
                                                       int x, int y, int z, BlockState state) {
        for (BlockBehavior behavior : this.config.behaviors()) {
            state = behavior.onNeighborUpdate(world, x, y, z, state);
        }
        return state;
    }

    /** Nur der gerichtete Shape-Hook, ohne den allgemeinen Neighbor-Changed-Recompute. */
    public BlockState getStateForShapeUpdate(de.skyengine.game.world.World world,
                                             int x, int y, int z, BlockState state,
                                             Direction direction, BlockState neighborState) {
        for (BlockBehavior behavior : this.config.behaviors()) {
            state = behavior.onNeighborShapeUpdate(world, x, y, z, state, direction, neighborState);
        }
        return state;
    }

    /**
     * Nach einem durch Nachbar-Update geschriebenen State-Wechsel. Neben den blockeigenen
     * Seiteneffekten erhalten gerichtete Observer den Shape-/State-Wechsel der beobachteten
     * Zelle. Das muss zentral passieren: {@code World.updateStateAt} schreibt reine
     * State-Änderungen absichtlich ohne einen weiteren allgemeinen Nachbarring.
     */
    public void onStateChangedByNeighborUpdate(de.skyengine.game.world.World world,
                                               int x, int y, int z,
                                               BlockState oldState, BlockState newState) {
        for (BlockBehavior behavior : this.config.behaviors()) {
            behavior.onStateChangedByNeighborUpdate(world, x, y, z, oldState, newState);
        }
        ObserverBehavior.notifyWatching(world, x, y, z);
    }

    /** Rechtsklick-Interaktion. Delegiert an die Behaviors; true = verbraucht. */
    public boolean onUse(de.skyengine.game.world.World world, int x, int y, int z, BlockState state) {
        for (BlockBehavior behavior : this.config.behaviors()) {
            if (behavior.onUse(world, x, y, z, state)) return true;
        }
        return false;
    }

    /** Rechtsklick-Variante mit Blickrichtung für richtungsabhängige Interaktionen wie Zauntore. */
    public boolean onUse(de.skyengine.game.world.World world, int x, int y, int z,
                         BlockState state, float playerYaw) {
        for (BlockBehavior behavior : this.config.behaviors()) {
            if (behavior.onUse(world, x, y, z, state, playerYaw)) return true;
        }
        return false;
    }

    /** Abbau-Hook (vor dem Entfernen). Delegiert an die Behaviors; Default: nichts. */
    public void onBreak(de.skyengine.game.world.World world, int x, int y, int z, BlockState state) {
        for (BlockBehavior behavior : this.config.behaviors()) {
            behavior.onBreak(world, x, y, z, state);
        }
    }

    /** Post-Removal-Dispatch, nachdem die Welt bereits den Nachfolgezustand enthaelt. */
    public void onRemoved(de.skyengine.game.world.World world, int x, int y, int z,
                          BlockState oldState, BlockState newState) {
        for (BlockBehavior behavior : this.config.behaviors()) {
            behavior.onRemoved(world, x, y, z, oldState, newState);
        }
    }

    /** Block-Event-Dispatch (s. {@code World.enqueueBlockEvent}). Delegiert; Default: nichts. */
    public void onBlockEvent(de.skyengine.game.world.World world, int x, int y, int z, BlockState state,
                             int eventId, int eventParam) {
        for (BlockBehavior behavior : this.config.behaviors()) {
            behavior.onBlockEvent(world, x, y, z, state, eventId, eventParam);
        }
    }

    /** Wertet die kompilierte Tabelle aus und hängt danach Behavior-spezifische Drops an. */
    public void appendDrops(LootContext context,
                            LootSink sink) {
        LootTables.generate(context, sink);
        for (BlockBehavior behavior : this.config.behaviors()) behavior.appendDrops(context, sink);
    }

    public long canonicalLootPosition(LootContext context) {
        long own = de.skyengine.game.world.block.BlockPos.asLong(context.x(), context.y(), context.z());
        for (BlockBehavior behavior : this.config.behaviors()) {
            long candidate = behavior.canonicalLootPosition(context);
            if (candidate != own) return candidate;
        }
        return own;
    }

    /** Ob ein durch Support-/Nachbarupdates selbst entfernter Block Loot erzeugen darf. */
    public boolean dropsWhenUnsupported() {
        for (BlockBehavior behavior : this.config.behaviors()) {
            if (!behavior.dropsWhenUnsupported()) return false;
        }
        return true;
    }

    /** Entity-BoundingBox überlappt die Zelle (aus {@code Entity.move}). Delegiert; Default: nichts. */
    public void onEntityInside(de.skyengine.game.world.World world, int x, int y, int z, BlockState state,
                               de.skyengine.game.entity.Entity entity) {
        for (BlockBehavior behavior : this.config.behaviors()) {
            behavior.onEntityInside(world, x, y, z, state, entity);
        }
    }

    /** Schwaches Redstone-Signal Richtung {@code side} (Konvention s. {@code BlockBehavior.weakPower}). Max über die Behaviors. */
    public int getWeakPower(de.skyengine.game.world.World world, int x, int y, int z, BlockState state, Direction side) {
        int power = 0;
        for (BlockBehavior behavior : this.config.behaviors()) {
            power = Math.max(power, behavior.weakPower(world, x, y, z, state, side));
        }
        return power;
    }

    /** Starkes Redstone-Signal Richtung {@code side} (leitet durch Redstone-Leiter). Max über die Behaviors. */
    public int getStrongPower(de.skyengine.game.world.World world, int x, int y, int z, BlockState state, Direction side) {
        int power = 0;
        for (BlockBehavior behavior : this.config.behaviors()) {
            power = Math.max(power, behavior.strongPower(world, x, y, z, state, side));
        }
        return power;
    }

    /** Verbindet sich Redstone-Staub aus Richtung {@code side} mit diesem Block? OR über die Behaviors. */
    public boolean connectsRedstoneWire(BlockState state, Direction side) {
        for (BlockBehavior behavior : this.config.behaviors()) {
            if (behavior.connectsRedstoneWire(state, side)) return true;
        }
        return false;
    }

    /** Muss dieser Block nach einer geladenen oder entladenen Chunk-Kante sein Signal neu prüfen? */
    public boolean reconcilesRedstoneOnChunkBoundary() {
        return this.reconcileRedstoneOnChunkBoundary;
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

    /** Explosions-Widerstand (MC-Blast-Resistance); ohne JSON-Feld gilt die Härte. */
    public float getResistance() {
        return this.config.resistance();
    }

    /** Bodenreibung (MC-Default 0.6; höher = rutschiger, Eis 0.98). */
    public float getFriction() {
        return this.config.friction();
    }

    /** Faktor auf die Horizontalgeschwindigkeit (MC-Default 1.0; Seelensand 0.4). */
    public float getSpeedFactor() {
        return this.config.speedFactor();
    }

    /** Faktor auf die Sprungkraft (MC-Default 1.0; Honigblock 0.5). */
    public float getJumpFactor() {
        return this.config.jumpFactor();
    }

    /** Anteil der Aufprallgeschwindigkeit, der beim Landen umgekehrt wird (0 = kein Abprallen). */
    public float getBounciness() {
        return this.config.bounciness();
    }

    /** Multiplikator auf den Fallschaden (1.0 = normal, 0 = immun wie Slimeblock). */
    public float getFallDamageFactor() {
        return this.config.fallDamageFactor();
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

    /** Auf-/Zu-Sound (Tür, Truhe) oder {@code null}, wenn der Block sich nicht öffnet. */
    public de.skyengine.audio.BlockOpenSound getOpenSound() {
        return this.config.openSound();
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
        boolean doesNotBlockHoppers = false;
        boolean noItem = false;
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

        public Settings noItem(boolean noItem) {
            this.noItem = noItem;
            return this;
        }

        public Settings leaves(boolean leaves) {
            this.leaves = leaves;
            return this;
        }

        public Settings doesNotBlockHoppers(boolean doesNotBlockHoppers) {
            this.doesNotBlockHoppers = doesNotBlockHoppers;
            return this;
        }
    }
}
