package de.skyengine.game.command;

import de.skyengine.core.i18n.I18n;
import de.skyengine.game.world.dimension.DimensionDefinition;
import java.util.List;

public final class SetSpawnPointCommand implements Command {
    @Override public String name() { return "setspawnpoint"; }

    @Override public CommandResult execute(CommandContext context, List<String> arguments) {
        if (!arguments.isEmpty()) return CommandResult.error(I18n.tr("command.setspawnpoint.usage"));
        if (context.world() == null) return CommandResult.error(I18n.tr("command.no_world"));
        CommandContext.Position position = context.world().setSpawnPoint();
        return CommandResult.success(I18n.tr("command.setspawnpoint.success",
                DimensionDefinition.displayName(position.dimension()),
                (int) position.x(), (int) position.y(), (int) position.z()));
    }
}
