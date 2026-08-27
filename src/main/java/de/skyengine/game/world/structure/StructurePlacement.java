package de.skyengine.game.world.structure;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.registry.Registries;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.generator.feature.FeatureContext;
import de.skyengine.game.world.block.BlockPos;


/** Einziger Placement-Pfad fuer Debug-Tools, Structure Blocks und Worldgen. */
public final class StructurePlacement {

    public enum Rule { REPLACE_ALL, KEEP_EXISTING }

    @FunctionalInterface
    public interface Writer {
        boolean set(int x, int y, int z, int state);
    }

    public record Result(int written, int skipped, int failed) {
        public boolean complete() { return failed == 0; }
    }

    /** Vorab vollstaendig validierter, atomar anwendbarer Editor-Write. */
    public record Plan(long[] positions, int[] before, int[] after,
                       StructureTemplate.BlockEntitySnapshot[] afterBlockEntities,
                       int count, int skipped) {
        public Plan(long[] positions, int[] before, int[] after, int count, int skipped) {
            this(positions, before, after, new StructureTemplate.BlockEntitySnapshot[count], count, skipped);
        }
    }

    public Plan prepareInWorld(StructureTemplate template, Dimension dimension,
                               int x, int y, int z, StructureTransform transform, Rule rule) {
        return prepareInWorld(template, template.anchorX(), template.anchorY(), template.anchorZ(),
                dimension, x, y, z, transform, rule);
    }

    /** Placement mit einem Clipboard-Ursprung, der unabhaengig vom nativen Template-Anker ist. */
    public Plan prepareInWorld(StructureTemplate template, int originX, int originY, int originZ,
                               Dimension dimension, int x, int y, int z,
                               StructureTransform transform, Rule rule) {
        long[] positions = new long[template.cells().size()];
        int[] before = new int[positions.length];
        int[] after = new int[positions.length];
        StructureTemplate.BlockEntitySnapshot[] blockEntities =
                new StructureTemplate.BlockEntitySnapshot[positions.length];
        int requested = 0, skipped = 0;
        for (StructureTemplate.Cell cell : template.cells()) {
            BlockState state = transform.state(Blocks.getState(cell.state()));
            if (rule == Rule.KEEP_EXISTING && state.getId() == Blocks.AIR) { skipped++; continue; }
            int relX = cell.x() - originX, relZ = cell.z() - originZ;
            int wx = x + transform.transformedX(relX, relZ);
            int wy = y + cell.y() - originY;
            int wz = z + transform.transformedZ(relX, relZ);
            if (!dimension.isPositionEditable(wx, wy, wz)) {
                throw new IllegalStateException("Zielposition liegt nicht in einem READY-Chunk: "
                        + wx + ' ' + wy + ' ' + wz);
            }
            int existing = dimension.getBlock(wx, wy, wz);
            if (rule == Rule.KEEP_EXISTING && existing != Blocks.AIR) { skipped++; continue; }
            if (existing == state.getId() && cell.blockEntity() == null) { skipped++; continue; }
            positions[requested] = BlockPos.asLong(wx, wy, wz);
            before[requested] = existing;
            after[requested] = state.getId();
            blockEntities[requested] = cell.blockEntity();
            requested++;
        }
        return new Plan(java.util.Arrays.copyOf(positions, requested),
                java.util.Arrays.copyOf(before, requested), java.util.Arrays.copyOf(after, requested),
                java.util.Arrays.copyOf(blockEntities, requested),
                requested, skipped);
    }

    public Result applyPlan(Dimension dimension, Plan plan, boolean forward) {
        for (int i = 0; i < plan.count(); i++) {
            int x = BlockPos.unpackX(plan.positions()[i]);
            int y = BlockPos.unpackY(plan.positions()[i]);
            int z = BlockPos.unpackZ(plan.positions()[i]);
            if (!dimension.isPositionEditable(x, y, z)) {
                throw new IllegalStateException("Zielposition liegt nicht mehr in einem READY-Chunk: "
                        + x + ' ' + y + ' ' + z);
            }
        }
        int[] states = forward ? plan.after() : plan.before();
        dimension.runPlayerBlockChange(() -> {
            dimension.setBlocksBatch(plan.positions(), states, plan.count());
            return true;
        });
        int written = 0;
        for (int i = 0; i < plan.count(); i++) {
            int wx = BlockPos.unpackX(plan.positions()[i]);
            int wy = BlockPos.unpackY(plan.positions()[i]);
            int wz = BlockPos.unpackZ(plan.positions()[i]);
            if (dimension.getBlock(wx, wy, wz) != states[i]) continue;
            if (forward && plan.afterBlockEntities()[i] != null) {
                applyBlockEntity(dimension, wx, wy, wz, plan.afterBlockEntities()[i]);
            }
            dimension.updateNeighbors(wx, wy, wz);
            written++;
        }
        return new Result(written, plan.skipped(), plan.count() - written);
    }

    private static void applyBlockEntity(Dimension dimension, int x, int y, int z,
                                         StructureTemplate.BlockEntitySnapshot snapshot) {
        BlockEntity entity = dimension.getBlockEntity(x, y, z);
        if (entity == null) throw new IllegalStateException("BlockEntity fehlt nach Structure-Placement bei "
                + x + ' ' + y + ' ' + z);
        if (entity.getType() != Registries.BLOCK_ENTITY.get(snapshot.type())) {
            throw new IllegalStateException("Falscher BlockEntity-Typ nach Structure-Placement bei "
                    + x + ' ' + y + ' ' + z);
        }
        entity.load(snapshot.data());
        entity.setChanged();
    }

    public Result place(StructureTemplate template, int anchorX, int anchorY, int anchorZ,
                        StructureTransform transform, Rule rule, Writer writer) {
        int written = 0, skipped = 0, failed = 0;
        for (StructureTemplate.Cell cell : template.cells()) {
            int relX = cell.x() - template.anchorX();
            int relZ = cell.z() - template.anchorZ();
            int x = anchorX + transform.transformedX(relX, relZ);
            int y = anchorY + cell.y() - template.anchorY();
            int z = anchorZ + transform.transformedZ(relX, relZ);
            BlockState state = transform.state(Blocks.getState(cell.state()));
            if (rule == Rule.KEEP_EXISTING && state.getId() == Blocks.AIR) {
                skipped++;
                continue;
            }
            if (writer.set(x, y, z, state.getId())) written++; else failed++;
        }
        return new Result(written, skipped, failed);
    }

    public Result placeInWorld(StructureTemplate template, Dimension dimension,
                               int x, int y, int z, StructureTransform transform, Rule rule) {
        return applyPlan(dimension, prepareInWorld(template, dimension, x, y, z, transform, rule), true);
    }

    public Result placeInFeature(StructureTemplate template, FeatureContext context,
                                 int x, int y, int z, StructureTransform transform, Rule rule) {
        int written = 0, skipped = 0;
        for (StructureTemplate.Cell cell : template.cells()) {
            BlockState state = transform.state(Blocks.getState(cell.state()));
            if (rule == Rule.KEEP_EXISTING && state.getId() == Blocks.AIR) { skipped++; continue; }
            int relX = cell.x() - template.anchorX(), relZ = cell.z() - template.anchorZ();
            int wx = x + transform.transformedX(relX, relZ);
            int wy = y + cell.y() - template.anchorY();
            int wz = z + transform.transformedZ(relX, relZ);
            context.setStructureCell(wx, wy, wz, state.getId(), rule == Rule.KEEP_EXISTING,
                    cell.blockEntity());
            written++;
        }
        return new Result(written, skipped, 0);
    }
}
