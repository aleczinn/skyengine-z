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

        public Builder property(Property<?> p) { this.properties.add(p); return this; }
        public Builder behavior(BlockBehavior b) { this.behaviors.add(b); return this; }
        public Builder shapes(ShapeProvider s) { this.shapeProvider = s; return this; }
        public Builder model(ModelGenerator m) { this.modelGenerator = m; return this; }
        public Builder opaque(Predicate<BlockState> p) { this.opaquePredicate = p; return this; }
        public Builder randomOffset(boolean v) { this.randomOffset = v; return this; }
        public Builder connectionGroup(String g) { this.connectionGroup = g; return this; }
        public Builder blockEntity(BlockEntityType<?> t) { this.blockEntityType = t; return this; }
        public Builder tickRandomly(boolean v) { this.tickRandomly = v; return this; }

        public BlockConfig build() { return new BlockConfig(this); }
    }
}
