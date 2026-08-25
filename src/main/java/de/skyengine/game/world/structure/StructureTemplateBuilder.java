package de.skyengine.game.world.structure;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Identifier;

import java.util.ArrayList;
import java.util.List;

/** Erzeugt Templates aus Weltregionen. Fehlende Zellen sind IGNORE, AIR ist optional explizit. */
public final class StructureTemplateBuilder {

    public StructureTemplate capture(Dimension dimension, StructureSelection selection,
                                     Identifier id, boolean includeAir) {
        if (!selection.complete()) throw new IllegalArgumentException("Die Structure-Auswahl ist unvollstaendig");
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
                    cells.add(new StructureTemplate.Cell(x - bounds.minX(), y - bounds.minY(),
                            z - bounds.minZ(), state));
                }
            }
        }
        var anchor = selection.effectiveAnchor();
        return new StructureTemplate(id, bounds.sizeX(), bounds.sizeY(), bounds.sizeZ(),
                anchor.x() - bounds.minX(), anchor.y() - bounds.minY(),
                anchor.z() - bounds.minZ(), cells);
    }
}
