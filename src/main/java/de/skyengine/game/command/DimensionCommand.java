package de.skyengine.game.command;

import de.skyengine.core.i18n.I18n;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.dimension.DimensionDefinition;

import java.util.List;

/** Entwicklungsbefehl fuer einen sicheren Dimensionswechsel. */
public final class DimensionCommand implements Command {
    @Override public String name() { return "dimension"; }
    @Override public String usage() { return "<id>"; }

    @Override
    public CommandResult execute(CommandContext context, List<String> arguments) {
        if (arguments.size() != 1) return CommandResult.error(I18n.tr("command.dimension.usage"));
        if (context.dimensions() == null) return CommandResult.error(I18n.tr("command.dimension.no_world"));
        Identifier target = Identifier.of(arguments.getFirst());
        if (!context.dimensions().available().contains(target)) {
            return CommandResult.error(I18n.tr("command.dimension.unknown", target));
        }
        if (target.equals(context.dimensions().current())) {
            return CommandResult.error(I18n.tr("command.dimension.already_active",
                    DimensionDefinition.displayName(target)));
        }
        return context.dimensions().request(target)
                ? CommandResult.success(I18n.tr("command.dimension.queued",
                        DimensionDefinition.displayName(target)))
                : CommandResult.error(I18n.tr("command.dimension.failed"));
    }

    @Override
    public List<String> suggest(CommandContext context, List<String> arguments, String current) {
        if (!arguments.isEmpty() || context.dimensions() == null) return List.of();
        String prefix = current.toLowerCase();
        return context.dimensions().available().stream().map(Identifier::toString)
                .filter(id -> id.startsWith(prefix)).sorted().toList();
    }
}
