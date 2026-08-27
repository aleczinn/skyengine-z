package de.skyengine.game.command;

import de.skyengine.core.i18n.I18n;
import java.util.List;

public final class KillCommand implements Command {
    @Override public String name() { return "kill"; }

    @Override public CommandResult execute(CommandContext context, List<String> arguments) {
        if (!arguments.isEmpty()) return CommandResult.error(I18n.tr("command.kill.usage"));
        if (context.player() == null) return CommandResult.error(I18n.tr("command.no_player"));
        context.player().kill();
        return CommandResult.success(I18n.tr("command.kill.success"));
    }
}
