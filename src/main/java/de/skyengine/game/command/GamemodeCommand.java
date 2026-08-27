package de.skyengine.game.command;

import de.skyengine.core.i18n.I18n;
import de.skyengine.game.Gamemode;

import java.util.List;
import java.util.Locale;

public final class GamemodeCommand implements Command {
    private static final List<String> MODES = List.of("creative", "spectator", "survival");

    @Override public String name() { return "gamemode"; }
    @Override public String usage() { return "<creative|spectator|survival>"; }

    @Override public CommandResult execute(CommandContext context, List<String> arguments) {
        if (arguments.size() != 1) return CommandResult.error(I18n.tr("command.gamemode.usage"));
        if (context.player() == null) return CommandResult.error(I18n.tr("command.no_player"));
        String value = arguments.getFirst().toLowerCase(Locale.ROOT);
        if (!MODES.contains(value)) return CommandResult.error(I18n.tr("command.gamemode.unknown", value));
        Gamemode mode = Gamemode.valueOf(value.toUpperCase(Locale.ROOT));
        context.player().gamemode(mode);
        return CommandResult.success(I18n.tr("command.gamemode.success",
                I18n.tr("command.gamemode.mode." + value)));
    }

    @Override public List<String> suggest(CommandContext context, List<String> arguments, String current) {
        if (!arguments.isEmpty()) return List.of();
        String prefix = current.toLowerCase(Locale.ROOT);
        return MODES.stream().filter(mode -> mode.startsWith(prefix)).toList();
    }
}
