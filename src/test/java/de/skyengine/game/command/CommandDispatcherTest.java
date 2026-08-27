package de.skyengine.game.command;

import de.skyengine.core.i18n.I18n;
import de.skyengine.game.world.block.entity.SimpleItemStorage;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CommandDispatcherTest {

    @BeforeAll
    static void bootstrap() {
        I18n.load("en_us");
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void giveDefaultsToOneItem() {
        SimpleItemStorage inventory = new SimpleItemStorage(1);
        CommandDispatcher dispatcher = dispatcher();

        CommandResult result = dispatcher.execute(new CommandContext(inventory), "/give stone");

        assertTrue(result.success());
        assertEquals("voxelstories:stone", inventory.get(0).getItem().getId().toString());
        assertEquals(1, inventory.get(0).getCount());
    }

    @Test
    void giveSplitsLargeAmountsAndDiscardsOverflow() {
        SimpleItemStorage inventory = new SimpleItemStorage(2);
        CommandDispatcher dispatcher = dispatcher();
        CommandContext context = new CommandContext(inventory);

        CommandResult result = dispatcher.execute(context, "/give voxelstories:stone 130");

        assertTrue(result.success());
        assertEquals(64, inventory.get(0).getCount());
        assertEquals(64, inventory.get(1).getCount());
        assertFalse(dispatcher.execute(context, "/give stone 1").success());
        assertEquals(128, inventory.get(0).getCount() + inventory.get(1).getCount());
    }

    @Test
    void suggestsCommandsAndRegistryItemIds() {
        CommandDispatcher dispatcher = dispatcher();
        CommandContext context = new CommandContext(new SimpleItemStorage(1));

        assertEquals(List.of("/give"), dispatcher.suggest(context, "/g"));
        assertTrue(dispatcher.suggest(context, "/give voxelstories:sto")
                .contains("/give voxelstories:stone"));
        assertTrue(dispatcher.suggest(context, "/give sto")
                .contains("/give voxelstories:stone"));
        assertTrue(dispatcher.suggest(context, "/give ends")
                .contains("/give voxelstories:end_stone"));
        assertEquals(" <item> [amount]", dispatcher.hint("/give"));
        assertEquals(" [amount]", dispatcher.hint("/give stone"));
    }

    @Test
    void dispatchesDoubleSlashNamespaceSeparately() {
        CommandDispatcher dispatcher = new CommandDispatcher();
        dispatcher.register(new Command() {
            @Override public String prefix() { return "//"; }
            @Override public String name() { return "paste"; }
            @Override public CommandResult execute(CommandContext context, List<String> arguments) {
                return CommandResult.success("ok");
            }
        });
        assertTrue(dispatcher.execute(new CommandContext(new SimpleItemStorage(1)), "//paste").success());
        assertFalse(dispatcher.execute(new CommandContext(new SimpleItemStorage(1)), "/paste").success());
        assertEquals(List.of("//paste"), dispatcher.suggest(
                new CommandContext(new SimpleItemStorage(1)), "//p"));
    }

    @Test
    void structureAndPlacementHintsUseDoubleSlashAndGroupedTrailingArguments() {
        CommandDispatcher dispatcher = new CommandDispatcher();
        dispatcher.register(new StructureCommand());
        dispatcher.register(new WorldEditCommand("paste"));

        assertEquals(List.of("//structure"), dispatcher.suggest(
                new CommandContext(new SimpleItemStorage(1)), "//str"));
        assertEquals(List.of(), dispatcher.suggest(
                new CommandContext(new SimpleItemStorage(1)), "/str"));
        CommandContext emptyContext = new CommandContext(new SimpleItemStorage(1));
        assertTrue(dispatcher.execute(emptyContext, "/structure list").messages().getFirst()
                .startsWith("Unknown command"));
        assertTrue(dispatcher.execute(emptyContext, "//structures list").messages().getFirst()
                .startsWith("Unknown command"));
        assertEquals("No world is open for structure commands",
                dispatcher.execute(emptyContext, "//structure list").messages().getFirst());
        assertEquals(" <save|load|list>", dispatcher.hint("//structure"));
        assertEquals(" <name> [args: -a Use anchor, air=include Include air, overwrite=true Overwrite]",
                dispatcher.hint("//structure save"));
        assertEquals(" [args: -a Use anchor, air=include Include air, overwrite=true Overwrite]",
                dispatcher.hint("//structure save trees/oak"));

        String pasteArgs = " [args: -a Ignore air, -s Select structure, "
                + "replace=keep Keep existing blocks]";
        assertEquals(" [x y z]" + pasteArgs, dispatcher.hint("//paste"));
        assertEquals(" [y z]" + pasteArgs, dispatcher.hint("//paste -5"));
        assertEquals(pasteArgs, dispatcher.hint("//paste 0 64 3"));
        assertEquals("", dispatcher.hint("//paste 0 64 3 -a"));
    }

    private static CommandDispatcher dispatcher() {
        CommandDispatcher dispatcher = new CommandDispatcher();
        dispatcher.register(new GiveCommand());
        return dispatcher;
    }
}
