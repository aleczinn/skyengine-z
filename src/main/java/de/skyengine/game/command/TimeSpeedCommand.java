package de.skyengine.game.command;

import de.skyengine.core.i18n.I18n;
import de.skyengine.game.world.World;

import java.util.List;

/** Direkter Kurzweg für {@code /time speed}; 1 ist das normale Vanilla-Tempo. */
public final class TimeSpeedCommand implements Command {

    @Override
    public String name() {
        return "timespeed";
    }

    @Override
    public String usage(List<String> arguments, boolean trailingSpace) {
        return arguments.isEmpty() ? "<factor>" : "";
    }

    @Override
    public CommandResult execute(CommandContext context, List<String> arguments) {
        World world = context.world();
        if (world == null) return CommandResult.error(I18n.tr("command.time.no_world"));
        if (arguments.size() != 1) return CommandResult.error(I18n.tr("command.timespeed.usage"));
        return TimeCommand.speed(world, List.of("speed", arguments.getFirst()), "command.timespeed.usage");
    }
}
