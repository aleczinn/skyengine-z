package de.skyengine.game.command;

import de.skyengine.core.i18n.I18n;
import java.util.List;

public final class TeleportCommand implements Command {
    @Override public String name() { return "tp"; }
    @Override public String usage() { return "<x> <y> <z>"; }

    @Override public CommandResult execute(CommandContext context, List<String> arguments) {
        if (arguments.size() != 3) return CommandResult.error(I18n.tr("command.tp.usage"));
        if (context.player() == null) return CommandResult.error(I18n.tr("command.no_player"));
        try {
            double x = coordinate(arguments.get(0));
            double y = coordinate(arguments.get(1));
            double z = coordinate(arguments.get(2));
            return context.player().teleport(x, y, z)
                    ? CommandResult.success(I18n.tr("command.tp.success", number(x), number(y), number(z)))
                    : CommandResult.error(I18n.tr("command.teleport_busy"));
        } catch (IllegalArgumentException e) {
            return CommandResult.error(I18n.tr("command.tp.invalid"));
        }
    }

    private static double coordinate(String value) {
        double parsed = Double.parseDouble(value);
        if (!Double.isFinite(parsed)) throw new IllegalArgumentException();
        return parsed;
    }

    private static String number(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }
}
