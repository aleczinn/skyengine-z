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
    void timeCommandExposesSubcommandsPresetsAndContextualHints() {
        CommandDispatcher dispatcher = dispatcher();
        dispatcher.register(new TimeCommand());
        CommandContext context = new CommandContext(new SimpleItemStorage(1));

        assertTrue(dispatcher.suggest(context, "/time s").contains("/time set"));
        assertTrue(dispatcher.suggest(context, "/time set n").contains("/time set noon"));
        assertEquals(" <set|add|query|speed>", dispatcher.hint("/time"));
        assertEquals(" <time>", dispatcher.hint("/time set"));
        assertEquals(" <factor>", dispatcher.hint("/time speed"));
    }

    @Test
    void timeArgumentsDistinguishClockValuesRawTicksAndDurations() {
        TimeCommand.ParsedTime clock = TimeCommand.parseSetTime("9");
        TimeCommand.ParsedTime raw = TimeCommand.parseSetTime("9t");
        TimeCommand.ParsedTime precise = TimeCommand.parseSetTime("21:30");

        assertTrue(clock.clockTime());
        assertEquals(3_000.0, clock.ticks());
        assertFalse(raw.clockTime());
        assertEquals(9.0, raw.ticks());
        assertEquals(15_500.0, precise.ticks(), 0.000001);
        assertEquals(2_000.0, TimeCommand.parseDuration("2h"), 0.000001);
        assertEquals(500.0, TimeCommand.parseDuration("30m"), 0.000001);
    }

    private static CommandDispatcher dispatcher() {
        CommandDispatcher dispatcher = new CommandDispatcher();
        dispatcher.register(new GiveCommand());
        return dispatcher;
    }
}
