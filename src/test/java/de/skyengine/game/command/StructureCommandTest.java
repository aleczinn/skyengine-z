package de.skyengine.game.command;

import de.skyengine.core.i18n.I18n;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.entity.SimpleItemStorage;
import de.skyengine.game.world.block.state.BlockHalf;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.structure.StructurePlacement;
import de.skyengine.game.world.structure.StructureTemplate;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.*;

final class StructureCommandTest {
    @BeforeAll static void bootstrap() {
        BlocksTestBootstrap.ensureBootstrapped();
        I18n.load("en_us");
    }

    @Test
    void listUsesTenSeparateMessagesPerPage() {
        FakeStructures structures = new FakeStructures();
        for (int i = 0; i < 23; i++) structures.paths.add("trees/tree_" + String.format("%02d", i) + ".structure");
        CommandResult result = command().execute(context(structures), List.of("list", "2"));
        assertTrue(result.success());
        assertEquals(11, result.messages().size());
        assertEquals("Structures page 2/3:", result.messages().getFirst());
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
    void saveUsesPlayerByDefaultAndMarkedAnchorOnlyWithTrailingFlag() {
        FakeStructures structures = new FakeStructures();
        assertTrue(command().execute(context(structures), List.of("save", "test:tree")).success());
        assertFalse(structures.saveWithAnchor);
        assertFalse(structures.savedIncludeAir);
        assertFalse(structures.savedOverwrite);

        assertTrue(command().execute(context(structures),
                List.of("save", "test:tree", "-a", "air=include", "overwrite=true")).success());
        assertTrue(structures.saveWithAnchor);
        assertTrue(structures.savedIncludeAir);
        assertTrue(structures.savedOverwrite);

        assertFalse(command().execute(context(structures), List.of("save", "-a", "test:tree")).success());
        assertFalse(command().execute(context(structures), List.of("save", "test:tree", "anchor=player")).success());
        assertFalse(command().execute(context(structures), List.of("anchor", "1", "2", "3")).success());
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
        assertFalse(structures.selectPastedBounds);

        assertTrue(new WorldEditCommand("paste").execute(context(structures),
                List.of("10", "64", "-5", "--selection", "replace=keep")).success());
        assertArrayEquals(new Integer[]{10, 64, -5}, structures.placementPosition);
        assertEquals(StructurePlacement.Rule.KEEP_EXISTING, structures.placementRule);
        assertTrue(structures.selectPastedBounds);

        structures.selectPastedBounds = false;
        assertTrue(new WorldEditCommand("paste").execute(context(structures),
                List.of("1", "2", "3", "-s")).success());
        assertTrue(structures.selectPastedBounds);

        assertTrue(new WorldEditCommand("paste").execute(context(structures),
                List.of("4", "5", "6", "--air", "-s", "replace=all")).success());
        assertEquals(StructurePlacement.Rule.IGNORE_AIR, structures.placementRule);
        assertTrue(structures.selectPastedBounds);
        assertTrue(new WorldEditCommand("paste").execute(context(structures),
                List.of("-a", "replace=keep")).success());
        assertEquals(StructurePlacement.Rule.KEEP_EXISTING, structures.placementRule);
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
        assertFalse(new WorldEditCommand("preview").execute(context(structures),
                List.of("--selection")).success());
        assertFalse(new WorldEditCommand("paste").execute(context(structures),
                List.of("-s", "--selection")).success());
        assertFalse(new WorldEditCommand("paste").execute(context(structures),
                List.of("-a", "--air")).success());
        assertFalse(new WorldEditCommand("paste").execute(context(structures),
                List.of("-a", "1", "2", "3")).success());
        assertFalse(new WorldEditCommand("paste").execute(context(structures),
                List.of("replace=keep", "1", "2", "3")).success());
        assertFalse(new WorldEditCommand("preview").execute(context(structures),
                List.of("--air")).success());
    }

    @Test
    void structureMessagesAreLocalizedInGermanAndEnglish() {
        FakeStructures structures = new FakeStructures();
        I18n.load("de_de");
        try {
            CommandResult loaded = command().execute(context(structures), List.of("load", "test:tree"));
            CommandResult pasted = new WorldEditCommand("paste").execute(context(structures), List.of());
            assertTrue(loaded.messages().getFirst().startsWith("Struktur "));
            assertTrue(pasted.messages().getFirst().startsWith("Struktur "));
            assertFalse(loaded.messages().getFirst().contains("Structure"));
        } finally {
            I18n.load("en_us");
        }
        assertTrue(command().execute(context(structures), List.of("load", "test:tree"))
                .messages().getFirst().startsWith("Structure "));
    }

    @Test
    void worldEditCopyResizeSetAndReplaceUseTheSharedPlayerSession() {
        FakeStructures structures = new FakeStructures();
        assertTrue(new WorldEditCommand("copy").execute(context(structures), List.of()).success());
        assertEquals(1, structures.copyCount);
        assertFalse(structures.copyWithAnchor);
        assertTrue(new WorldEditCommand("copy").execute(context(structures), List.of("--anchor")).success());
        assertEquals(2, structures.copyCount);
        assertTrue(structures.copyWithAnchor);
        assertTrue(new WorldEditCommand("copy").execute(context(structures), List.of("-a")).success());
        assertFalse(new WorldEditCommand("copy").execute(context(structures), List.of("anchor")).success());
        assertTrue(new WorldEditCommand("expand").execute(context(structures), List.of("12")).success());
        assertEquals(12, structures.expandAmount);
        assertTrue(new WorldEditCommand("contract").execute(context(structures), List.of("3")).success());
        assertEquals(3, structures.contractAmount);
        assertFalse(new WorldEditCommand("expand").execute(context(structures), List.of("0")).success());

        assertTrue(new WorldEditCommand("set").execute(context(structures), List.of("stone")).success());
        assertEquals(Blocks.STONE, structures.setBlockState);
        assertTrue(new WorldEditCommand("set").execute(context(structures), List.of("voxelstories:air")).success());
        assertEquals(Blocks.AIR, structures.setBlockState);
        assertFalse(new WorldEditCommand("set").execute(context(structures),
                List.of("voxelstories:stone[unknown=true]")).success());

        assertTrue(new WorldEditCommand("set").execute(context(structures),
                List.of("oak_stairs[facing=east,half=top,shape=straight]")).success());
        var stairs = Blocks.getState(structures.setBlockState);
        assertEquals(Direction.EAST, stairs.get(Properties.FACING));
        assertEquals(BlockHalf.TOP, stairs.get(Properties.HALF));

        assertTrue(new WorldEditCommand("replace").execute(context(structures),
                List.of("oak_stairs[facing=east]", "stone")).success());
        assertTrue(structures.replaceMatcher.test(stairs.getId()));
        assertFalse(structures.replaceMatcher.test(Blocks.STONE));
        assertTrue(new WorldEditCommand("replace").execute(context(structures), List.of("air")).success());
        assertTrue(structures.replaceMatcher.test(Blocks.STONE));
        assertFalse(structures.replaceMatcher.test(Blocks.AIR));
    }

    @Test
    void worldEditTargetCutDirectionalAndRegenCommandsReachTheSharedSession() {
        FakeStructures structures = new FakeStructures();
        assertTrue(new WorldEditCommand("hpos1").execute(context(structures), List.of()).success());
        assertTrue(new WorldEditCommand("hpos2").execute(context(structures), List.of()).success());
        assertEquals(1, structures.hpos1Count);
        assertEquals(1, structures.hpos2Count);

        assertTrue(new WorldEditCommand("cut").execute(context(structures), List.of("--anchor")).success());
        assertTrue(structures.cutWithAnchor);
        assertTrue(new WorldEditCommand("stack").execute(context(structures), List.of("4")).success());
        assertEquals(4, structures.stackCount);
        assertTrue(new WorldEditCommand("move").execute(context(structures), List.of("7")).success());
        assertEquals(7, structures.moveDistance);
        assertTrue(new WorldEditCommand("regen").execute(context(structures), List.of()).success());
        assertEquals(1, structures.regenCount);
        assertFalse(new WorldEditCommand("stack").execute(context(structures), List.of("0")).success());
        assertFalse(new WorldEditCommand("regen").execute(context(structures), List.of("extra")).success());
    }

    private static StructureCommand command() { return new StructureCommand(); }
    private static CommandContext context(FakeStructures structures) {
        return new CommandContext(new SimpleItemStorage(1), null, structures);
    }

    private static final class FakeStructures implements CommandContext.StructureAccess {
        final List<String> paths = new ArrayList<>();
        String loaded;
        boolean saveWithAnchor;
        boolean savedIncludeAir;
        boolean savedOverwrite;
        Integer[] placementPosition;
        StructurePlacement.Rule placementRule;
        int undoAmount;
        int redoAmount;
        int copyCount;
        int expandAmount;
        int contractAmount;
        int setBlockState = -1;
        IntPredicate replaceMatcher;
        boolean copyWithAnchor;
        boolean cutWithAnchor;
        int hpos1Count;
        int hpos2Count;
        int stackCount;
        int moveDistance;
        int regenCount;
        boolean selectPastedBounds;
        @Override public String hpos1() { hpos1Count++; return "hpos1"; }
        @Override public String hpos2() { hpos2Count++; return "hpos2"; }
        @Override public StructureTemplate save(String reference, boolean includeAir, boolean overwrite,
                                                boolean useAnchor) {
            savedIncludeAir = includeAir;
            savedOverwrite = overwrite;
            saveWithAnchor = useAnchor;
            return template(Identifier.of(reference.replace(".structure", "")));
        }
        @Override public StructureTemplate load(String reference) {
            loaded = reference;
            return template(Identifier.of(reference.replace(".structure", "")));
        }
        @Override public List<String> templates() { return List.copyOf(paths); }
        @Override public String wand() { return "wand"; }
        @Override public String copy(boolean useAnchor) {
            copyCount++;
            copyWithAnchor = useAnchor;
            return "copy";
        }
        @Override public StructurePlacement.Result cut(boolean useAnchor) {
            cutWithAnchor = useAnchor;
            return new StructurePlacement.Result(1, 0, 0);
        }
        @Override public String expand(int amount) { expandAmount = amount; return "expand"; }
        @Override public String contract(int amount) { contractAmount = amount; return "contract"; }
        @Override public StructurePlacement.Result set(int state) {
            setBlockState = state;
            return new StructurePlacement.Result(1, 0, 0);
        }
        @Override public StructurePlacement.Result replace(IntPredicate matcher, int state) {
            replaceMatcher = matcher;
            setBlockState = state;
            return new StructurePlacement.Result(1, 0, 0);
        }
        @Override public StructurePlacement.Result stack(int count) {
            stackCount = count;
            return new StructurePlacement.Result(count, 0, 0);
        }
        @Override public StructurePlacement.Result move(int distance) {
            moveDistance = distance;
            return new StructurePlacement.Result(distance, 0, 0);
        }
        @Override public StructurePlacement.Result regen() {
            regenCount++;
            return new StructurePlacement.Result(1, 0, 0);
        }
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
                                                          StructurePlacement.Rule rule,
                                                          boolean selectBounds) {
            placementPosition = new Integer[]{x, y, z}; placementRule = rule;
            selectPastedBounds = selectBounds;
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
