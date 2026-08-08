package de.skyengine.game.command;

import de.skyengine.core.i18n.I18n;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.registry.Registries;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** {@code /give <item> [amount]} mit verlustfreiem Splitten auf freie Inventar-Slots. */
public final class GiveCommand implements Command {

    @Override
    public String name() {
        return "give";
    }

    @Override
    public String usage() {
        return "<item> [amount]";
    }

    @Override
    public CommandResult execute(CommandContext context, List<String> arguments) {
        if (arguments.isEmpty() || arguments.size() > 2) {
            return CommandResult.error(I18n.tr("command.give.usage"));
        }
        Item item = Items.get(Identifier.of(arguments.getFirst()));
        if (item == null) {
            return CommandResult.error(I18n.tr("command.give.unknown_item", arguments.getFirst()));
        }
        int amount = 1;
        if (arguments.size() == 2) {
            try {
                amount = Integer.parseInt(arguments.get(1));
            } catch (NumberFormatException ignored) {
                return CommandResult.error(I18n.tr("command.give.invalid_amount", arguments.get(1)));
            }
            if (amount <= 0) {
                return CommandResult.error(I18n.tr("command.give.invalid_amount", arguments.get(1)));
            }
        }

        ItemStack remaining = context.inventory().insert(new ItemStack(item, amount));
        int inserted = amount - remaining.getCount();
        if (inserted == 0) return CommandResult.error(I18n.tr("command.give.inventory_full"));
        return CommandResult.success(I18n.tr("command.give.success", inserted, item.getDisplayName()));
    }

    @Override
    public List<String> suggest(CommandContext context, List<String> arguments, String current) {
        if (!arguments.isEmpty()) return List.of();
        String prefix = normalize(current);
        return Registries.ITEM.values().stream()
                .map(Item::getId)
                .filter(id -> normalize(id.toString()).startsWith(prefix)
                        || normalize(id.path()).startsWith(prefix))
                .map(Identifier::toString)
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    /** Minecraft-artige Suche: Trenner beeinflussen die getippte Kurzform nicht. */
    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }
}
