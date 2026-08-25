package de.skyengine.game.command;

import de.skyengine.game.world.block.entity.SimpleItemStorage;
import de.skyengine.game.world.block.Identifier;

import java.util.List;
import de.skyengine.game.world.structure.StructurePlacement;
import de.skyengine.game.world.structure.StructureTemplate;
import de.skyengine.game.world.structure.StructureTransform;

/** Die fuer Singleplayer-Befehle verfuegbare Spielumgebung. */
public record CommandContext(SimpleItemStorage inventory, DimensionAccess dimensions, StructureAccess structures) {

    public CommandContext(SimpleItemStorage inventory) {
        this(inventory, null, null);
    }

    public CommandContext(SimpleItemStorage inventory, DimensionAccess dimensions) {
        this(inventory, dimensions, null);
    }

    public interface DimensionAccess {
        Identifier current();
        List<Identifier> available();
        boolean request(Identifier target);
    }

    public interface StructureAccess {
        void pos1();
        void pos2();
        void anchor();
        void anchor(int x, int y, int z);
        void resetAnchor();
        StructureTemplate save(String reference, boolean includeAir, boolean overwrite) throws Exception;
        StructureTemplate load(String reference) throws Exception;
        StructurePlacement.Result paste(StructureTransform transform, StructurePlacement.Rule rule) throws Exception;
        StructurePlacement.Result pasteAt(int x, int y, int z, StructureTransform transform,
                                          StructurePlacement.Rule rule) throws Exception;
        List<String> templates() throws Exception;
    }
}
