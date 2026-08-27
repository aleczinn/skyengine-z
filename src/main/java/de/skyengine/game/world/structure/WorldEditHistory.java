package de.skyengine.game.world.structure;

import de.skyengine.core.i18n.I18n;
import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.DataTag;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/** UUID-lokale, dimensionsgetrennte History fuer alle mutierenden WorldEdit-Operationen. */
public final class WorldEditHistory {
    public static final int MAX_OPERATIONS = 20;
    public static final int MAX_CELLS = 1_000_000;

    public record Result(int operations, int cells) {}

    private final Map<Identifier, DimensionHistory> dimensions = new HashMap<>();

    public StructurePlacement.Result apply(Dimension dimension, StructurePlacement placement,
                                           StructurePlacement.Plan plan) {
        if (plan.count() > MAX_CELLS) throw new IllegalStateException(
                I18n.tr("command.worldedit.edit_too_large", plan.count()));
        DataTag[] beforeTags = captureTags(dimension, plan.positions());
        StructurePlacement.Result result = placement.applyPlan(dimension, plan, true);
        if (plan.count() == 0) return result;
        DataTag[] afterTags = captureTags(dimension, plan.positions());
        DimensionHistory history = dimensions.computeIfAbsent(dimension.getDimensionId(), ignored -> new DimensionHistory());
        history.redo.clear();
        history.undo.addLast(new Transaction(plan, beforeTags, afterTags));
        trim(history);
        return result;
    }

    public Result undo(Dimension dimension, StructurePlacement placement, int amount) {
        return move(dimension, placement, amount, false);
    }

    public Result redo(Dimension dimension, StructurePlacement placement, int amount) {
        return move(dimension, placement, amount, true);
    }

    private Result move(Dimension dimension, StructurePlacement placement, int amount, boolean redo) {
        if (amount <= 0) throw new IllegalArgumentException(I18n.tr("command.worldedit.positive_value"));
        DimensionHistory history = dimensions.computeIfAbsent(dimension.getDimensionId(), ignored -> new DimensionHistory());
        ArrayDeque<Transaction> source = redo ? history.redo : history.undo;
        ArrayDeque<Transaction> target = redo ? history.undo : history.redo;
        int operations = 0, cells = 0;
        while (operations < amount && !source.isEmpty()) {
            Transaction transaction = source.removeLast();
            placement.applyPlan(dimension, transaction.plan, redo);
            restoreTags(dimension, transaction.plan.positions(), redo ? transaction.afterTags : transaction.beforeTags);
            target.addLast(transaction);
            operations++;
            cells += transaction.plan.count();
        }
        trim(history);
        return new Result(operations, cells);
    }

    private static DataTag[] captureTags(Dimension dimension, long[] positions) {
        DataTag[] result = new DataTag[positions.length];
        for (int i = 0; i < positions.length; i++) {
            BlockEntity entity = dimension.getBlockEntity(BlockPos.unpackX(positions[i]),
                    BlockPos.unpackY(positions[i]), BlockPos.unpackZ(positions[i]));
            if (entity == null) continue;
            DataTag tag = new DataTag();
            entity.save(tag);
            result[i] = tag.copy();
        }
        return result;
    }

    private static void restoreTags(Dimension dimension, long[] positions, DataTag[] tags) {
        for (int i = 0; i < positions.length; i++) {
            if (tags[i] == null) continue;
            BlockEntity entity = dimension.getBlockEntity(BlockPos.unpackX(positions[i]),
                    BlockPos.unpackY(positions[i]), BlockPos.unpackZ(positions[i]));
            if (entity != null) {
                entity.load(tags[i].copy());
                entity.setChanged();
            }
        }
    }

    private static void trim(DimensionHistory history) {
        while (history.undo.size() > MAX_OPERATIONS) history.undo.removeFirst();
        int cells = history.undo.stream().mapToInt(t -> t.plan.count()).sum()
                + history.redo.stream().mapToInt(t -> t.plan.count()).sum();
        while (cells > MAX_CELLS && history.undo.size() > 1) cells -= history.undo.removeFirst().plan.count();
        while (cells > MAX_CELLS && !history.redo.isEmpty()) cells -= history.redo.removeFirst().plan.count();
    }

    private record Transaction(StructurePlacement.Plan plan, DataTag[] beforeTags, DataTag[] afterTags) {}
    private static final class DimensionHistory {
        final ArrayDeque<Transaction> undo = new ArrayDeque<>();
        final ArrayDeque<Transaction> redo = new ArrayDeque<>();
    }
}
