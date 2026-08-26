package de.skyengine.game.command;

import de.skyengine.core.i18n.I18n;
import java.util.List;
import java.util.Locale;

public final class BiomeCommand implements Command {
    @Override public String name() { return "biome"; }
    @Override public String usage() { return "<name>"; }

    @Override public CommandResult execute(CommandContext context, List<String> arguments) {
        if (arguments.size() != 1) return CommandResult.error(I18n.tr("command.biome.usage"));
        if (context.world() == null) return CommandResult.error(I18n.tr("command.no_world"));
        String name = arguments.getFirst().toLowerCase(Locale.ROOT);
        if (!context.world().biomeNames().contains(name)) {
            return CommandResult.error(I18n.tr("command.biome.unknown", name));
        }
        context.world().locateBiome(name);
        return CommandResult.success(I18n.tr("command.biome.searching", name));
    }

    @Override public List<String> suggest(CommandContext context, List<String> arguments, String current) {
        if (!arguments.isEmpty() || context.world() == null) return List.of();
        String prefix = current.toLowerCase(Locale.ROOT);
        return context.world().biomeNames().stream().filter(name -> name.startsWith(prefix)).toList();
    }
}
