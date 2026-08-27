package de.skyengine.game.world.structure;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.BlockEntityType;
import de.skyengine.game.world.block.entity.DataTag;
import de.skyengine.game.world.block.registry.Registries;

import java.util.ArrayList;
import java.util.List;

/** Erzeugt Templates aus Weltregionen. Fehlende Zellen sind IGNORE, AIR ist optional explizit. */
public final class StructureTemplateBuilder {

    public StructureTemplate capture(Dimension dimension, WorldEditSelection selection,
                                     Identifier id, boolean includeAir, BlockPos anchor) {
        if (!selection.complete()) throw new IllegalArgumentException("Die Selektion ist unvollstaendig");
        if (!dimension.getDimensionId().equals(selection.dimension())) {
            throw new IllegalArgumentException("Die Auswahl gehoert zu einer anderen Dimension");
        }
        StructureBounds bounds = selection.bounds();
        List<StructureTemplate.Cell> cells = new ArrayList<>();
        for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                    int state = dimension.getBlock(x, y, z);
                    if (state == Blocks.AIR && !includeAir) continue;
                    BlockEntityType<?> stateType = Blocks.getState(state).getBlock().getBlockEntityType();
                    if (stateType != null && !stateType.isStructureSerializable()) {
                        Identifier type = Registries.BLOCK_ENTITY.idOf(stateType);
                        throw new IllegalArgumentException("Kurzlebige BlockEntity " + type
                                + " kann bei " + x + " " + y + " " + z + " nicht gespeichert werden");
                    }
                    StructureTemplate.BlockEntitySnapshot snapshot = null;
                    BlockEntity entity = dimension.getBlockEntity(x, y, z);
                    if (entity != null) {
                        Identifier type = Registries.BLOCK_ENTITY.idOf(entity.getType());
                        if (type == null) throw new IllegalArgumentException("BlockEntity ohne Registry-Typ bei "
                                + x + " " + y + " " + z);
                        DataTag data = new DataTag();
                        entity.save(data);
                        snapshot = new StructureTemplate.BlockEntitySnapshot(type, data);
                    }
                    cells.add(new StructureTemplate.Cell(x - bounds.minX(), y - bounds.minY(),
                            z - bounds.minZ(), state, snapshot));
                }
            }
        }
        if (!selection.contains(anchor)) throw new IllegalArgumentException("Structure-Anker liegt ausserhalb der Selektion");
        return new StructureTemplate(id, bounds.sizeX(), bounds.sizeY(), bounds.sizeZ(),
                anchor.x() - bounds.minX(), anchor.y() - bounds.minY(),
                anchor.z() - bounds.minZ(), cells);
    }
}
