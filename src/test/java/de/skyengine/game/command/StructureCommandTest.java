package de.skyengine.game.command;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.entity.SimpleItemStorage;
import de.skyengine.game.world.structure.StructurePlacement;
import de.skyengine.game.world.structure.StructureTemplate;
import de.skyengine.game.world.structure.StructureTransform;
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
    void pasteAcceptsIdCoordinatesAndTransform() {
        FakeStructures structures = new FakeStructures();
        CommandResult result = command().execute(context(structures),
                List.of("paste", "trees/oak.structure", "-12", "64", "7", "rotation=90", "mirror=front_back"));
        assertTrue(result.success());
        assertEquals("trees/oak.structure", structures.loaded);
        assertArrayEquals(new int[]{-12, 64, 7}, structures.pastePosition);
        assertEquals(StructureTransform.Rotation.CLOCKWISE_90, structures.transform.rotation());
        assertEquals(StructureTransform.Mirror.FRONT_BACK, structures.transform.mirror());
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

    private static StructureCommand command() { return new StructureCommand(); }
    private static CommandContext context(FakeStructures structures) {
        return new CommandContext(new SimpleItemStorage(1), null, structures);
    }

    private static final class FakeStructures implements CommandContext.StructureAccess {
        final List<String> paths = new ArrayList<>();
        String loaded;
        int[] pastePosition;
        int[] anchorPosition;
        boolean anchorReset;
        boolean playerAnchor;
        StructureTransform transform;

        @Override public void pos1() {}
        @Override public void pos2() {}
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
        @Override public StructurePlacement.Result paste(StructureTransform transform, StructurePlacement.Rule rule) {
            this.transform = transform;
            return new StructurePlacement.Result(1, 0, 0);
        }
        @Override public StructurePlacement.Result pasteAt(int x, int y, int z, StructureTransform transform,
                                                           StructurePlacement.Rule rule) {
            this.pastePosition = new int[]{x, y, z};
            return paste(transform, rule);
        }
        @Override public List<String> templates() { return List.copyOf(paths); }
        private static StructureTemplate template(Identifier id) {
            return new StructureTemplate(id, 1, 1, 1, 0, 0, 0,
                    List.of(new StructureTemplate.Cell(0, 0, 0, Blocks.STONE)));
        }
    }
}
