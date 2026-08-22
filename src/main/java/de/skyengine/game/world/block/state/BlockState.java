package de.skyengine.game.world.block.state;

import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.RenderLayer;
import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.game.world.block.model.BlockParticleSprite;
import de.skyengine.game.world.block.shape.BlockShape;

import java.util.HashMap;
import java.util.Map;

public final class BlockState {

    private final Block block;
    private final Map<Property<?>, Object> values;

    /** Runtime-ID, wird beim Registry-Bake vergeben. NICHT persistieren! */
    private int id;
    /** Gepackte Hot-Path-Flags, beim Registry-Bake gesetzt (siehe {@link StateFlags}). */
    private int flags;
    private BakedQuad[] model = new BakedQuad[0];
    private BlockParticleSprite particleSprite = BlockParticleSprite.MISSING;
    /** Getintete Seiten-Overlay-Quads (Grasblock) — der Mesher emittiert sie in den CUTOUT-Layer. */
    private BakedQuad[] overlay = new BakedQuad[0];

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
        return (this.flags & StateFlags.OPAQUE_CUBE) != 0;
    }

    /** Wirft AO/verschattet Ecklicht im Mesher (Default = opaker Vollwürfel). */
    public boolean occludesAo() {
        return (this.flags & StateFlags.AO_OCCLUDER) != 0;
    }

    public boolean isSolid() {
        return (this.flags & StateFlags.SOLID) != 0;
    }

    /** Leitet stark empfangenes Redstone-Signal weiter (unabhängig von Render-Opazität). */
    public boolean isRedstoneConductor() {
        return (this.flags & StateFlags.REDSTONE_CONDUCTOR) != 0;
    }

    public RenderLayer getRenderLayer() {
        return StateFlags.layer(this.flags);
    }

    public boolean hasRandomOffset() {
        return (this.flags & StateFlags.RANDOM_OFFSET) != 0;
    }

    /** Laub-Block (bei LeavesQuality LOW cullen Laub-Faces gegen jedes Nachbar-Laub). */
    public boolean isLeaves() {
        return (this.flags & StateFlags.LEAVES) != 0;
    }

    /** true: nimmt am Random-Tick teil (Pflanzen, Verfall). Flag wird beim Bake gesetzt. */
    public boolean ticksRandomly() {
        return (this.flags & StateFlags.TICKS_RANDOMLY) != 0;
    }

    /** true: innere Faces zwischen zwei identischen Blöcken werden geculled (Glas-an-Glas). */
    public boolean cullsSameBlock() {
        return (this.flags & StateFlags.CULL_SAME) != 0;
    }

    /** true: Wasser/Lava — Geometrie wird dynamisch vom Mesher erzeugt (kein gebackenes Modell). */
    public boolean isFluid() {
        return (this.flags & StateFlags.FLUID) != 0;
    }

    /** true: nie als LOD-Terrain-Oberfläche sampeln (Logs — LOD zeigt nur Terrain). */
    public boolean isExcludedFromLodSurface() {
        return (this.flags & StateFlags.NO_LOD_SURFACE) != 0;
    }

    /**
     * Licht-Opazität 0..15: wie viel Himmelslicht dieser Block je Zelle schluckt.
     * 0 = durchlässig (Glas, Luft), 1 = dämpfend (Wasser, Laub), 15 = opak.
     */
    public int getLightOpacity() {
        return StateFlags.opacity(this.flags);
    }

    /**
     * Eigenleuchten 0..15: wie hell dieser Block selbst strahlt.
     * 0 = leuchtet nicht (der Normalfall), Fackel 14, Lava 15.
     */
    public int getLuminance() {
        return StateFlags.luminance(this.flags);
    }

    public BlockShape getCollisionShape() {
        return this.block.getCollisionShape(this);
    }

    public BlockShape getOutlineShape() {
        return this.block.getOutlineShape(this);
    }

    /* --- Infrastruktur --- */

    public Block getBlock() {
        return block;
    }

    public Map<Property<?>, Object> getValues() {
        return values;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getFlags() {
        return flags;
    }

    public void setFlags(int flags) {
        this.flags = flags;
    }

    public BakedQuad[] getModel() {
        return model;
    }

    public void setModel(BakedQuad[] model) {
        this.model = model;
    }

    public BlockParticleSprite getParticleSprite() {
        return this.particleSprite;
    }

    public void setParticleSprite(BlockParticleSprite particleSprite) {
        this.particleSprite = particleSprite == null ? BlockParticleSprite.MISSING : particleSprite;
    }

    public BakedQuad[] getOverlay() {
        return overlay;
    }

    public void setOverlay(BakedQuad[] overlay) {
        this.overlay = overlay;
    }

    @Override
    public String toString() {
        return this.block.getIdentifier() + (this.values.isEmpty() ? "" : this.values.toString());
    }
}
