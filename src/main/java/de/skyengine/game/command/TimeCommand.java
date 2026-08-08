package de.skyengine.game.command;

import de.skyengine.core.i18n.I18n;
import de.skyengine.game.world.World;
import de.skyengine.game.world.environment.DayNightCycle;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Steuerung der persistenten Tageszeit und ihrer Laufgeschwindigkeit. */
public final class TimeCommand implements Command {

    private static final List<String> SUBCOMMANDS = List.of("set", "add", "query", "speed");
    private static final List<String> PRESETS = List.of("day", "noon", "night", "midnight");
    private static final Map<String, Double> PRESET_TICKS = Map.of(
            "day", 1000.0,
            "noon", 6000.0,
            "night", 13_000.0,
            "midnight", 18_000.0);

    @Override
    public String name() {
        return "time";
    }

    @Override
    public String usage(List<String> arguments, boolean trailingSpace) {
        if (arguments.isEmpty()) return "<set|add|query|speed>";
        return switch (arguments.getFirst().toLowerCase(Locale.ROOT)) {
            case "set" -> arguments.size() == 1 ? "<time>" : "";
            case "add" -> arguments.size() == 1 ? "<ticks|duration>" : "";
            case "speed" -> arguments.size() == 1 ? "<factor>" : "";
            case "query" -> "";
            default -> "<set|add|query|speed>";
        };
    }

    @Override
    public CommandResult execute(CommandContext context, List<String> arguments) {
        World world = context.world();
        if (world == null) return CommandResult.error(I18n.tr("command.time.no_world"));
        if (arguments.isEmpty()) return CommandResult.error(I18n.tr("command.time.usage"));
        String action = arguments.getFirst().toLowerCase(Locale.ROOT);
        return switch (action) {
            case "set" -> this.set(world, arguments);
            case "add" -> this.add(world, arguments);
            case "query" -> arguments.size() == 1 ? query(world)
                    : CommandResult.error(I18n.tr("command.time.usage"));
            case "speed" -> this.speed(world, arguments);
            default -> CommandResult.error(I18n.tr("command.time.usage"));
        };
    }

    private CommandResult set(World world, List<String> arguments) {
        if (arguments.size() != 2) return CommandResult.error(I18n.tr("command.time.set_usage"));
        ParsedTime parsed = parseSetTime(arguments.get(1));
        if (parsed == null) return CommandResult.error(I18n.tr("command.time.invalid", arguments.get(1)));
        double value = parsed.clockTime
                ? Math.floor(world.getDayTime() / DayNightCycle.DAY_LENGTH) * DayNightCycle.DAY_LENGTH
                        + parsed.ticks
                : parsed.ticks;
        world.setDayTime(value);
        return CommandResult.success(I18n.tr("command.time.set_success",
                DayNightCycle.formatClock(value), (long) DayNightCycle.wrappedTicks(value)));
    }

    private CommandResult add(World world, List<String> arguments) {
        if (arguments.size() != 2) return CommandResult.error(I18n.tr("command.time.add_usage"));
        Double ticks = parseDuration(arguments.get(1));
        if (ticks == null) return CommandResult.error(I18n.tr("command.time.invalid", arguments.get(1)));
        world.addDayTime(ticks);
        return CommandResult.success(I18n.tr("command.time.add_success", ticks,
                DayNightCycle.formatClock(world.getDayTime())));
    }

    private CommandResult speed(World world, List<String> arguments) {
        if (arguments.size() != 2) return CommandResult.error(I18n.tr("command.time.speed_usage"));
        try {
            double speed = Double.parseDouble(arguments.get(1));
            if (!Double.isFinite(speed) || speed < 0 || speed > 1000) throw new NumberFormatException();
            world.setDayTimeSpeed(speed);
            return CommandResult.success(I18n.tr("command.time.speed_success", speed));
        } catch (NumberFormatException ignored) {
            return CommandResult.error(I18n.tr("command.time.invalid_speed", arguments.get(1)));
        }
    }

    private static CommandResult query(World world) {
        double time = world.getDayTime();
        long day = (long) Math.floor(time / DayNightCycle.DAY_LENGTH);
        return CommandResult.success(I18n.tr("command.time.query_success",
                DayNightCycle.formatClock(time), (long) DayNightCycle.wrappedTicks(time), day,
                world.getDayTimeSpeed()));
    }

    @Override
    public List<String> suggest(CommandContext context, List<String> arguments, String current) {
        String prefix = current.toLowerCase(Locale.ROOT);
        if (arguments.isEmpty()) return SUBCOMMANDS.stream().filter(value -> value.startsWith(prefix)).toList();
        if (arguments.size() == 1 && "set".equalsIgnoreCase(arguments.getFirst())) {
            return PRESETS.stream().filter(value -> value.startsWith(prefix)).toList();
        }
        return List.of();
    }

    static ParsedTime parseSetTime(String input) {
        String value = input.toLowerCase(Locale.ROOT);
        Double preset = PRESET_TICKS.get(value);
        if (preset != null) return new ParsedTime(preset, true);
        if (value.endsWith("t")) {
            Double ticks = parseNumber(value.substring(0, value.length() - 1));
            return ticks == null ? null : new ParsedTime(ticks, false);
        }
        int colon = value.indexOf(':');
        if (colon >= 0) {
            try {
                int hour = Integer.parseInt(value.substring(0, colon));
                int minute = Integer.parseInt(value.substring(colon + 1));
                if (hour < 0 || hour > 24 || minute < 0 || minute > 59 || hour == 24 && minute != 0) return null;
                return new ParsedTime(DayNightCycle.clockToTicks(hour, minute), true);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        Double number = parseNumber(value);
        if (number == null || number < 0) return null;
        return number <= 24 ? new ParsedTime(DayNightCycle.clockToTicks((int) Math.floor(number),
                (int) Math.round(number % 1.0 * 60.0)), true) : new ParsedTime(number, false);
    }

    static Double parseDuration(String input) {
        String value = input.toLowerCase(Locale.ROOT);
        double multiplier = 1.0;
        if (value.endsWith("h")) {
            multiplier = DayNightCycle.TICKS_PER_HOUR;
            value = value.substring(0, value.length() - 1);
        } else if (value.endsWith("m")) {
            multiplier = DayNightCycle.TICKS_PER_HOUR / 60.0;
            value = value.substring(0, value.length() - 1);
        } else if (value.endsWith("t")) {
            value = value.substring(0, value.length() - 1);
        }
        Double number = parseNumber(value);
        return number == null ? null : number * multiplier;
    }

    private static Double parseNumber(String value) {
        try {
            double number = Double.parseDouble(value);
            return Double.isFinite(number) ? number : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    record ParsedTime(double ticks, boolean clockTime) {
    }
}
