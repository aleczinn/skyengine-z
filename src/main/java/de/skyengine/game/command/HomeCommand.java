package de.skyengine.game.command;

import de.skyengine.core.i18n.I18n;
import java.util.List;

public final class HomeCommand implements Command {
    @Override public String name() { return "home"; }

    @Override public CommandResult execute(CommandContext context, List<String> arguments) {
        if (!arguments.isEmpty()) return CommandResult.error(I18n.tr("command.home.usage"));
        if (context.player() == null) return CommandResult.error(I18n.tr("command.no_player"));
        return switch (context.player().home()) {
            case TELEPORTED -> CommandResult.success(I18n.tr("command.home.success"));
            case QUEUED -> CommandResult.success(I18n.tr("command.home.queued"));
            case NOT_SET -> CommandResult.error(I18n.tr("command.home.not_set"));
            case BUSY -> CommandResult.error(I18n.tr("command.teleport_busy"));
        };
    }
}
