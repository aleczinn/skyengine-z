package de.skyengine.game.command;

import de.skyengine.game.world.block.entity.SimpleItemStorage;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.Gamemode;

import java.util.List;
import java.util.function.IntPredicate;
import de.skyengine.game.world.structure.StructurePlacement;
import de.skyengine.game.world.structure.StructureTemplate;

/** Die fuer Singleplayer-Befehle verfuegbare Spielumgebung. */
public record CommandContext(SimpleItemStorage inventory, DimensionAccess dimensions,
                             StructureAccess structures, PlayerAccess player, WorldAccess world) {

    public CommandContext(SimpleItemStorage inventory) {
        this(inventory, null, null, null, null);
    }

    public CommandContext(SimpleItemStorage inventory, DimensionAccess dimensions) {
        this(inventory, dimensions, null, null, null);
    }

    public CommandContext(SimpleItemStorage inventory, DimensionAccess dimensions,
                          StructureAccess structures) {
        this(inventory, dimensions, structures, null, null);
    }

    public record Position(Identifier dimension, double x, double y, double z) {}

    public enum HomeResult { TELEPORTED, QUEUED, NOT_SET, BUSY }

    public interface PlayerAccess {
        Position position();
        void kill();
        Gamemode gamemode();
        void gamemode(Gamemode gamemode);
        boolean teleport(double x, double y, double z);
        Position setHome();
        HomeResult home();
    }

    public interface WorldAccess {
        Position setSpawnPoint();
        List<String> biomeNames();
        boolean locateBiome(String name);
    }

    public interface DimensionAccess {
        Identifier current();
        List<Identifier> available();
        boolean request(Identifier target);
    }

    public interface StructureAccess {
        default String pos1() { throw new UnsupportedOperationException("//pos1 ist hier nicht verfuegbar"); }
        default String pos2() { throw new UnsupportedOperationException("//pos2 ist hier nicht verfuegbar"); }
        default String hpos1() { throw new UnsupportedOperationException("//hpos1 ist hier nicht verfuegbar"); }
        default String hpos2() { throw new UnsupportedOperationException("//hpos2 ist hier nicht verfuegbar"); }
        StructureTemplate save(String reference, boolean includeAir, boolean overwrite,
                               boolean useAnchor) throws Exception;
        StructureTemplate load(String reference) throws Exception;
        List<String> templates() throws Exception;
        String wand();
        String copy(boolean useAnchor);
        StructurePlacement.Result cut(boolean useAnchor);
        String expand(int amount);
        String contract(int amount);
        StructurePlacement.Result set(int state);
        StructurePlacement.Result replace(IntPredicate matcher, int state);
        StructurePlacement.Result stack(int count);
        StructurePlacement.Result move(int distance);
        StructurePlacement.Result regen();
        String rotate(int degrees);
        String flip();
        String preview(Integer x, Integer y, Integer z, StructurePlacement.Rule rule);
        void clearPreview();
        StructurePlacement.Result paste(Integer x, Integer y, Integer z,
                                        StructurePlacement.Rule rule,
                                        boolean selectBounds) throws Exception;
        String undo(int amount);
        String redo(int amount);
    }
}
