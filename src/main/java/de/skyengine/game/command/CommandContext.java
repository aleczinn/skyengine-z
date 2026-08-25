package de.skyengine.game.command;

import de.skyengine.game.world.block.entity.SimpleItemStorage;
import de.skyengine.game.world.block.Identifier;

import java.util.List;
import de.skyengine.game.world.structure.StructurePlacement;
import de.skyengine.game.world.structure.StructureTemplate;

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
        void anchor();
        void anchor(int x, int y, int z);
        void resetAnchor();
        StructureTemplate save(String reference, boolean includeAir, boolean overwrite) throws Exception;
        StructureTemplate load(String reference) throws Exception;
        List<String> templates() throws Exception;
        String wand();
        String rotate(int degrees);
        String flip();
        String preview(Integer x, Integer y, Integer z, StructurePlacement.Rule rule);
        void clearPreview();
        StructurePlacement.Result paste(Integer x, Integer y, Integer z,
                                        StructurePlacement.Rule rule) throws Exception;
        String undo(int amount);
        String redo(int amount);
    }
}
