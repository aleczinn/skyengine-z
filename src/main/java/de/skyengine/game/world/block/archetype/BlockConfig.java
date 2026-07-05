package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.behavior.BlockBehavior;
import de.skyengine.game.world.block.entity.BlockEntityType;
import de.skyengine.game.world.block.model.ModelGenerator;
import de.skyengine.game.world.block.shape.ShapeProvider;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Property;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Datengetriebene Zusammensetzung eines Blocks: zusätzliche Properties, Verhalten,
 * Shape-/Model-Generator und ein optionales Opaque-Prädikat. Wird vom {@link Archetype}
 * gefüllt und an {@link de.skyengine.game.world.block.Block} übergeben (Komposition statt
 * Subklassen). {@code null}-Generatoren bedeuten „Block-Default verwenden".
 */
public final class BlockConfig {

    public static final BlockConfig EMPTY = builder().build();

    private final List<Property<?>> properties;
    private final List<BlockBehavior> behaviors;
    private final ShapeProvider shapeProvider;
    private final ModelGenerator modelGenerator;
    private final Predicate<BlockState> opaquePredicate;
    private final boolean randomOffset;
    private final String connectionGroup;
    private final BlockEntityType<?> blockEntityType;
    private final boolean tickRandomly;
    private final FluidInfo fluidInfo;
    private final int tint;
    private final int tintFaceMask;
    private final String overlayTexture;
    private final List<String> placeOn;
    private final boolean placeOnFullTop;

    private BlockConfig(Builder b) {
        this.properties = List.copyOf(b.properties);
        this.behaviors = List.copyOf(b.behaviors);
        this.shapeProvider = b.shapeProvider;
        this.modelGenerator = b.modelGenerator;
        this.opaquePredicate = b.opaquePredicate;
        this.randomOffset = b.randomOffset;
        this.connectionGroup = b.connectionGroup;
        this.blockEntityType = b.blockEntityType;
        this.tickRandomly = b.tickRandomly;
        this.fluidInfo = b.fluidInfo;
        this.tint = b.tint;
        this.tintFaceMask = b.tintFaceMask;
        this.overlayTexture = b.overlayTexture;
        this.placeOn = b.placeOn == null ? null : List.copyOf(b.placeOn);
        this.placeOnFullTop = b.placeOnFullTop;
    }

    public List<Property<?>> properties() { return properties; }
    public List<BlockBehavior> behaviors() { return behaviors; }
    public ShapeProvider shapeProvider() { return shapeProvider; }
    public ModelGenerator modelGenerator() { return modelGenerator; }
    public Predicate<BlockState> opaquePredicate() { return opaquePredicate; }
    public boolean randomOffset() { return randomOffset; }
    public String connectionGroup() { return connectionGroup; }
    public BlockEntityType<?> blockEntityType() { return blockEntityType; }
    public boolean tickRandomly() { return tickRandomly; }
    public FluidInfo fluidInfo() { return fluidInfo; }
    /** Multiplikations-Tint 0xRRGGBB ({@code BakedQuad.WHITE} = neutral). */
    public int tint() { return tint; }
    /** Bitmaske {@code 1 << face} der zu tintenden Faces; -1 = alle Quads (inkl. NO_CULL). */
    public int tintFaceMask() { return tintFaceMask; }
    /** Texturpfad für getintete Seiten-Overlay-Quads (Grasblock) oder null. */
    public String overlayTexture() { return overlayTexture; }
    /** Erlaubte Träger-Block-IDs ("skyengine:sand", …) oder null = keine Einschränkung. */
    public List<String> placeOn() { return placeOn; }
    /** true = Träger braucht eine volle tragende Oberseite (Vollblock, Top-Slab, Kopfüber-Treppe). */
    public boolean placeOnFullTop() { return placeOnFullTop; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final List<Property<?>> properties = new ArrayList<>();
        private final List<BlockBehavior> behaviors = new ArrayList<>();
        private ShapeProvider shapeProvider;
        private ModelGenerator modelGenerator;
        private Predicate<BlockState> opaquePredicate;
        private boolean randomOffset;
        private String connectionGroup;
        private BlockEntityType<?> blockEntityType;
        private boolean tickRandomly;
        private FluidInfo fluidInfo;
        private int tint = de.skyengine.game.world.block.model.BakedQuad.WHITE;
        private int tintFaceMask = -1;
        private String overlayTexture;
        private List<String> placeOn;
        private boolean placeOnFullTop;

        public Builder property(Property<?> p) { this.properties.add(p); return this; }
        public Builder behavior(BlockBehavior b) { this.behaviors.add(b); return this; }
        public Builder shapes(ShapeProvider s) { this.shapeProvider = s; return this; }
        public Builder model(ModelGenerator m) { this.modelGenerator = m; return this; }
        public Builder opaque(Predicate<BlockState> p) { this.opaquePredicate = p; return this; }
        public Builder randomOffset(boolean v) { this.randomOffset = v; return this; }
        public Builder connectionGroup(String g) { this.connectionGroup = g; return this; }
        public Builder blockEntity(BlockEntityType<?> t) { this.blockEntityType = t; return this; }
        public Builder tickRandomly(boolean v) { this.tickRandomly = v; return this; }
        public Builder fluid(FluidInfo f) { this.fluidInfo = f; return this; }
        public Builder tint(int t) { this.tint = t; return this; }
        public Builder tintFaces(int mask) { this.tintFaceMask = mask; return this; }
        public Builder overlayTexture(String path) { this.overlayTexture = path; return this; }
        public Builder placeOn(List<String> ids) { this.placeOn = ids; return this; }
        public Builder placeOnFullTop(boolean v) { this.placeOnFullTop = v; return this; }

        public BlockConfig build() { return new BlockConfig(this); }
    }
}
