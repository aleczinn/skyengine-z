package de.skyengine.game.command;

import de.skyengine.core.i18n.I18n;
import de.skyengine.game.world.dimension.DimensionDefinition;
import java.util.List;

public final class SetHomeCommand implements Command {
    @Override public String name() { return "sethome"; }

    @Override public CommandResult execute(CommandContext context, List<String> arguments) {
        if (!arguments.isEmpty()) return CommandResult.error(I18n.tr("command.sethome.usage"));
        if (context.player() == null) return CommandResult.error(I18n.tr("command.no_player"));
        CommandContext.Position position = context.player().setHome();
        return CommandResult.success(I18n.tr("command.sethome.success",
                DimensionDefinition.displayName(position.dimension()),
                position.x(), position.y(), position.z()));
    }
}
