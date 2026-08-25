package de.skyengine.game.command;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.entity.SimpleItemStorage;
import de.skyengine.game.world.structure.StructurePlacement;
import de.skyengine.game.world.structure.StructureTemplate;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class StructureCommandTest {
    @BeforeAll static void bootstrap() { BlocksTestBootstrap.ensureBootstrapped(); }

    @Test
    void listUsesTenSeparateMessagesPerPage() {
        FakeStructures structures = new FakeStructures();
        for (int i = 0; i < 23; i++) structures.paths.add("trees/tree_" + String.format("%02d", i) + ".structure");
        CommandResult result = command().execute(context(structures), List.of("list", "2"));
        assertTrue(result.success());
        assertEquals(11, result.messages().size());
        assertEquals("Structures Seite 2/3:", result.messages().getFirst());
        assertTrue(result.messages().get(1).endsWith("trees/tree_10.structure"));
        assertTrue(result.messages().getLast().endsWith("trees/tree_19.structure"));
        assertFalse(command().execute(context(structures), List.of("list", "4")).success());
    }

    @Test
    void oldPasteAndPositionSubcommandsAreRemoved() {
        FakeStructures structures = new FakeStructures();
        assertFalse(command().execute(context(structures), List.of("paste")).success());
        assertFalse(command().execute(context(structures), List.of("pos1")).success());
    }

    @Test
    void anchorSupportsPlayerCoordinatesResetAndSaveOption() {
        FakeStructures structures = new FakeStructures();
        assertTrue(command().execute(context(structures), List.of("anchor", "1", "2", "3")).success());
        assertArrayEquals(new int[]{1, 2, 3}, structures.anchorPosition);
        assertTrue(command().execute(context(structures), List.of("anchor", "reset")).success());
        assertTrue(structures.anchorReset);
        assertTrue(command().execute(context(structures),
                List.of("save", "test:tree", "anchor=player")).success());
        assertTrue(structures.playerAnchor);
    }

    @Test
    void worldEditPlacementCommandsParseCoordinatesRulesAndHistory() {
        FakeStructures structures = new FakeStructures();
        assertTrue(new WorldEditCommand("preview").execute(context(structures),
                List.of("10", "64", "-5", "replace=keep")).success());
        assertArrayEquals(new Integer[]{10, 64, -5}, structures.placementPosition);
        assertEquals(StructurePlacement.Rule.KEEP_EXISTING, structures.placementRule);

        assertTrue(new WorldEditCommand("paste").execute(context(structures),
                List.of("replace=all")).success());
        assertArrayEquals(new Integer[]{null, null, null}, structures.placementPosition);
        assertEquals(StructurePlacement.Rule.REPLACE_ALL, structures.placementRule);
        assertTrue(new WorldEditCommand("undo").execute(context(structures), List.of("3")).success());
        assertEquals(3, structures.undoAmount);
        assertTrue(new WorldEditCommand("redo").execute(context(structures), List.of()).success());
        assertEquals(1, structures.redoAmount);
    }

    @Test
    void worldEditRejectsFreeAngleAndMalformedPlacement() {
        FakeStructures structures = new FakeStructures();
        assertFalse(new WorldEditCommand("rotate").execute(context(structures), List.of("40")).success());
        assertFalse(new WorldEditCommand("paste").execute(context(structures), List.of("1", "2")).success());
        assertFalse(new WorldEditCommand("preview").execute(context(structures),
                List.of("replace=unknown")).success());
    }

    private static StructureCommand command() { return new StructureCommand(); }
    private static CommandContext context(FakeStructures structures) {
        return new CommandContext(new SimpleItemStorage(1), null, structures);
    }

    private static final class FakeStructures implements CommandContext.StructureAccess {
        final List<String> paths = new ArrayList<>();
        String loaded;
        int[] anchorPosition;
        boolean anchorReset;
        boolean playerAnchor;
        Integer[] placementPosition;
        StructurePlacement.Rule placementRule;
        int undoAmount;
        int redoAmount;
        @Override public void anchor() { playerAnchor = true; }
        @Override public void anchor(int x, int y, int z) { anchorPosition = new int[]{x, y, z}; }
        @Override public void resetAnchor() { anchorReset = true; }
        @Override public StructureTemplate save(String reference, boolean includeAir, boolean overwrite) {
            return template(Identifier.of(reference.replace(".structure", "")));
        }
        @Override public StructureTemplate load(String reference) {
            loaded = reference;
            return template(Identifier.of(reference.replace(".structure", "")));
        }
        @Override public List<String> templates() { return List.copyOf(paths); }
        @Override public String wand() { return "wand"; }
        @Override public String rotate(int degrees) {
            if (degrees % 90 != 0) throw new IllegalArgumentException("rotation");
            return "rotate";
        }
        @Override public String flip() { return "flip"; }
        @Override public String preview(Integer x, Integer y, Integer z, StructurePlacement.Rule rule) {
            placementPosition = new Integer[]{x, y, z}; placementRule = rule; return "preview";
        }
        @Override public void clearPreview() {}
        @Override public StructurePlacement.Result paste(Integer x, Integer y, Integer z,
                                                          StructurePlacement.Rule rule) {
            placementPosition = new Integer[]{x, y, z}; placementRule = rule;
            return new StructurePlacement.Result(1, 0, 0);
        }
        @Override public String undo(int amount) { undoAmount = amount; return "undo"; }
        @Override public String redo(int amount) { redoAmount = amount; return "redo"; }
        private static StructureTemplate template(Identifier id) {
            return new StructureTemplate(id, 1, 1, 1, 0, 0, 0,
                    List.of(new StructureTemplate.Cell(0, 0, 0, Blocks.STONE)));
        }
    }
}
