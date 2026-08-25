package de.skyengine.game.world.structure;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.Blocks;
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
        long[] positions = new long[template.cells().size()];
        int[] states = new int[template.cells().size()];
        int requested = 0, skipped = 0, unchanged = 0;
        for (StructureTemplate.Cell cell : template.cells()) {
            BlockState state = transform.state(Blocks.getState(cell.state()));
            if (rule == Rule.KEEP_EXISTING && state.getId() == Blocks.AIR) { skipped++; continue; }
            int relX = cell.x() - template.anchorX(), relZ = cell.z() - template.anchorZ();
            int wx = x + transform.transformedX(relX, relZ);
            int wy = y + cell.y() - template.anchorY();
            int wz = z + transform.transformedZ(relX, relZ);
            int existing = dimension.getBlock(wx, wy, wz);
            if (rule == Rule.KEEP_EXISTING && existing != Blocks.AIR) { skipped++; continue; }
            if (existing == state.getId()) { unchanged++; continue; }
            positions[requested] = BlockPos.asLong(wx, wy, wz);
            states[requested] = state.getId();
            requested++;
        }
        final int writeCount = requested;
        final int[] changed = {0};
        dimension.runPlayerBlockChange(() -> {
            changed[0] = dimension.setBlocksBatch(positions, states, writeCount);
            return true;
        });
        Result result = new Result(changed[0] + unchanged, skipped, requested - changed[0]);
        /* Erst wenn alle Zellen sichtbar sind, Verbindungen/Shapes nachziehen. So sehen Tore,
           Zaeune und Treppen beim Update bereits ihre komplette Struktur. */
        for (int i = 0; i < requested; i++) {
            int wx = BlockPos.unpackX(positions[i]), wy = BlockPos.unpackY(positions[i]),
                    wz = BlockPos.unpackZ(positions[i]);
            if (dimension.getBlock(wx, wy, wz) == states[i]) dimension.updateNeighbors(wx, wy, wz);
        }
        return result;
    }

    public Result placeInFeature(StructureTemplate template, FeatureContext context,
                                 int x, int y, int z, StructureTransform transform, Rule rule) {
        return place(template, x, y, z, transform, rule, (wx, wy, wz, state) -> {
            if (rule == Rule.KEEP_EXISTING) context.setIfAir(wx, wy, wz, state);
            else context.set(wx, wy, wz, state);
            return true;
        });
    }
}
