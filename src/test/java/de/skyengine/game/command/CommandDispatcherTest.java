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
        assertEquals("skyengine:stone", inventory.get(0).getItem().getId().toString());
        assertEquals(1, inventory.get(0).getCount());
    }

    @Test
    void giveSplitsLargeAmountsAndDiscardsOverflow() {
        SimpleItemStorage inventory = new SimpleItemStorage(2);
        CommandDispatcher dispatcher = dispatcher();
        CommandContext context = new CommandContext(inventory);

        CommandResult result = dispatcher.execute(context, "/give skyengine:stone 130");

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
        assertTrue(dispatcher.suggest(context, "/give skyengine:sto")
                .contains("/give skyengine:stone"));
        assertTrue(dispatcher.suggest(context, "/give sto")
                .contains("/give skyengine:stone"));
        assertTrue(dispatcher.suggest(context, "/give ends")
                .contains("/give skyengine:end_stone"));
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

    private static CommandDispatcher dispatcher() {
        CommandDispatcher dispatcher = new CommandDispatcher();
        dispatcher.register(new GiveCommand());
        return dispatcher;
    }
}
