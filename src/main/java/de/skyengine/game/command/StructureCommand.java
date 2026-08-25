package de.skyengine.game.command;

import java.util.ArrayList;
import java.util.List;

/** Verwaltung nativer Templates; Bearbeitung und Placement liegen unter den //-Befehlen. */
public final class StructureCommand implements Command {
    private static final int PAGE_SIZE = 10;

    @Override public String name() { return "structure"; }
    @Override public String usage() { return "<anchor|save|load|list> ..."; }

    @Override
    public CommandResult execute(CommandContext context, List<String> arguments) {
        if (context.structures() == null) return CommandResult.error("Keine Welt fuer Structure-Befehle geoeffnet");
        if (arguments.isEmpty()) return CommandResult.error("Verwendung: /structure " + usage());
        try {
            return switch (arguments.getFirst().toLowerCase()) {
                case "anchor" -> anchor(context, arguments);
                case "save" -> save(context, arguments);
                case "load" -> load(context, arguments);
                case "list" -> list(context, arguments);
                default -> CommandResult.error("Unbekannter Structure-Unterbefehl: " + arguments.getFirst());
            };
        } catch (Exception e) {
            return CommandResult.error("Structure-Fehler: " + e.getMessage());
        }
    }

    private static CommandResult anchor(CommandContext context, List<String> args) {
        if (args.size() == 1) {
            context.structures().anchor();
            return CommandResult.success("Structure-Anker auf Spielerposition gesetzt");
        }
        if (args.size() == 2 && args.get(1).equalsIgnoreCase("reset")) {
            context.structures().resetAnchor();
            return CommandResult.success("Structure-Anker auf Position 1 zurueckgesetzt");
        }
        if (args.size() == 4) {
            int x = integer(args.get(1)), y = integer(args.get(2)), z = integer(args.get(3));
            context.structures().anchor(x, y, z);
            return CommandResult.success("Structure-Anker gesetzt: " + x + " " + y + " " + z);
        }
        return CommandResult.error("Verwendung: /structure anchor [<x> <y> <z>|reset]");
    }

    private static CommandResult save(CommandContext context, List<String> args) throws Exception {
        if (args.size() < 2) return CommandResult.error(
                "Verwendung: /structure save <id> [air=include] [overwrite=true] [anchor=player]");
        List<String> options = args.subList(2, args.size());
        validateOptions(options, "air", "overwrite", "anchor");
        String anchor = option(options, "anchor", "selection");
        if (anchor.equalsIgnoreCase("player")) context.structures().anchor();
        else if (!anchor.equalsIgnoreCase("selection")) return CommandResult.error("anchor muss player oder selection sein");
        String air = option(options, "air", "ignore").toLowerCase();
        if (!air.equals("ignore") && !air.equals("include")) return CommandResult.error("air muss ignore oder include sein");
        String overwrite = option(options, "overwrite", "false").toLowerCase();
        if (!overwrite.equals("true") && !overwrite.equals("false")) return CommandResult.error("overwrite muss true oder false sein");
        var template = context.structures().save(args.get(1), air.equals("include"), Boolean.parseBoolean(overwrite));
        return CommandResult.success("Structure " + template.id() + " gespeichert ("
                + template.cells().size() + " Zellen)");
    }

    private static CommandResult load(CommandContext context, List<String> args) throws Exception {
        if (args.size() != 2) return CommandResult.error("Verwendung: /structure load <id>");
        var template = context.structures().load(args.get(1));
        return CommandResult.success("Structure " + template.id() + " geladen ("
                + template.sizeX() + "x" + template.sizeY() + "x" + template.sizeZ() + ")");
    }

    private static CommandResult list(CommandContext context, List<String> args) throws Exception {
        if (args.size() > 2) return CommandResult.error("Verwendung: /structure list [seite]");
        int page = args.size() == 2 ? integer(args.get(1)) : 1;
        List<String> paths = context.structures().templates().stream().sorted().toList();
        if (paths.isEmpty()) return CommandResult.success("Structures: keine");
        int pages = (paths.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        if (page < 1 || page > pages) return CommandResult.error("Structure-Seite muss zwischen 1 und " + pages + " liegen");
        int start = (page - 1) * PAGE_SIZE, end = Math.min(start + PAGE_SIZE, paths.size());
        List<String> lines = new ArrayList<>(end - start + 1);
        lines.add("Structures Seite " + page + "/" + pages + ":");
        for (int i = start; i < end; i++) lines.add("  " + paths.get(i));
        return CommandResult.success(lines);
    }

    static String option(List<String> args, String key, String fallback) {
        String prefix = key.toLowerCase() + "=";
        for (String arg : args) if (arg.toLowerCase().startsWith(prefix)) return arg.substring(prefix.length());
        return fallback;
    }

    static void validateOptions(List<String> args, String... allowed) {
        for (String arg : args) {
            int equals = arg.indexOf('=');
            if (equals <= 0) throw new IllegalArgumentException("Unerwartetes Argument: " + arg);
            String key = arg.substring(0, equals).toLowerCase();
            boolean valid = false;
            for (String candidate : allowed) if (candidate.equals(key)) { valid = true; break; }
            if (!valid) throw new IllegalArgumentException("Unbekannte Option: " + key);
        }
    }

    static int integer(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Ungueltige Ganzzahl: " + value); }
    }

    @Override
    public List<String> suggest(CommandContext context, List<String> arguments, String current) {
        if (arguments.isEmpty()) return List.of("anchor", "save", "load", "list").stream()
                .filter(v -> v.startsWith(current.toLowerCase())).toList();
        if (arguments.getFirst().equals("load") && arguments.size() == 1 && context.structures() != null) {
            try {
                return context.structures().templates().stream()
                        .filter(v -> v.startsWith(current.toLowerCase())).toList();
            } catch (Exception ignored) { return List.of(); }
        }
        if (arguments.getFirst().equals("anchor") && arguments.size() == 1 && "reset".startsWith(current)) {
            return List.of("reset");
        }
        return List.of();
    }
}
