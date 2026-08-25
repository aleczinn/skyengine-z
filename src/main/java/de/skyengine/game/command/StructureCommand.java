package de.skyengine.game.command;

import de.skyengine.game.world.structure.StructurePlacement;
import de.skyengine.game.world.structure.StructureTransform;

import java.util.ArrayList;
import java.util.List;

/** In-Engine-Authoring-Werkzeug auf der kanonischen Structure-API. */
public final class StructureCommand implements Command {
    private static final int PAGE_SIZE = 10;

    @Override public String name() { return "structure"; }
    @Override public String usage() { return "<pos1|pos2|anchor|save|load|paste|list> ..."; }

    @Override
    public CommandResult execute(CommandContext context, List<String> arguments) {
        if (context.structures() == null) return CommandResult.error("Keine Welt fuer Structure-Befehle geoeffnet");
        if (arguments.isEmpty()) return CommandResult.error("Verwendung: /structure " + usage());
        try {
            return switch (arguments.getFirst().toLowerCase()) {
                case "pos1" -> position(context, true, arguments);
                case "pos2" -> position(context, false, arguments);
                case "anchor" -> anchor(context, arguments);
                case "save" -> save(context, arguments);
                case "load" -> load(context, arguments);
                case "paste" -> paste(context, arguments);
                case "list" -> list(context, arguments);
                default -> CommandResult.error("Unbekannter Structure-Unterbefehl: " + arguments.getFirst());
            };
        } catch (Exception e) {
            return CommandResult.error("Structure-Fehler: " + e.getMessage());
        }
    }

    private static CommandResult position(CommandContext context, boolean first, List<String> args) {
        if (args.size() != 1) return CommandResult.error("Verwendung: /structure " + (first ? "pos1" : "pos2"));
        if (first) context.structures().pos1(); else context.structures().pos2();
        return CommandResult.success("Structure-Position " + (first ? "1" : "2") + " gesetzt");
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
            int x = coordinate(args.get(1)), y = coordinate(args.get(2)), z = coordinate(args.get(3));
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
        else if (!anchor.equalsIgnoreCase("selection")) {
            return CommandResult.error("anchor muss player oder selection sein");
        }
        String airOption = option(options, "air", "ignore").toLowerCase();
        if (!airOption.equals("ignore") && !airOption.equals("include")) {
            return CommandResult.error("air muss ignore oder include sein");
        }
        String overwriteOption = option(options, "overwrite", "false").toLowerCase();
        if (!overwriteOption.equals("true") && !overwriteOption.equals("false")) {
            return CommandResult.error("overwrite muss true oder false sein");
        }
        boolean air = airOption.equals("include");
        boolean overwrite = Boolean.parseBoolean(overwriteOption);
        var template = context.structures().save(args.get(1), air, overwrite);
        return CommandResult.success("Structure " + template.id() + " gespeichert ("
                + template.cells().size() + " Zellen)");
    }

    private static CommandResult load(CommandContext context, List<String> args) throws Exception {
        if (args.size() != 2) return CommandResult.error("Verwendung: /structure load <id>");
        var template = context.structures().load(args.get(1));
        return CommandResult.success("Structure " + template.id() + " geladen ("
                + template.sizeX() + "x" + template.sizeY() + "x" + template.sizeZ() + ")");
    }

    private static CommandResult paste(CommandContext context, List<String> args) throws Exception {
        int index = 1;
        if (index < args.size() && !args.get(index).contains("=") && !isInteger(args.get(index))) {
            context.structures().load(args.get(index++));
        }

        Integer x = null, y = null, z = null;
        if (index < args.size() && !args.get(index).contains("=")) {
            if (args.size() - index < 3) return CommandResult.error(pasteUsage());
            x = coordinate(args.get(index++));
            y = coordinate(args.get(index++));
            z = coordinate(args.get(index++));
        }
        List<String> options = args.subList(index, args.size());
        validateOptions(options, "rotation", "mirror", "replace");
        StructureTransform transform = transform(options);
        String replace = option(options, "replace", "all").toLowerCase();
        if (!replace.equals("all") && !replace.equals("keep")) {
            return CommandResult.error("replace muss all oder keep sein");
        }
        StructurePlacement.Rule rule = replace.equals("keep")
                ? StructurePlacement.Rule.KEEP_EXISTING : StructurePlacement.Rule.REPLACE_ALL;
        StructurePlacement.Result result = x == null
                ? context.structures().paste(transform, rule)
                : context.structures().pasteAt(x, y, z, transform, rule);
        return result.complete()
                ? CommandResult.success("Structure platziert: " + result.written() + " Zellen")
                : CommandResult.error("Structure teilweise platziert: " + result.written()
                        + " geschrieben, " + result.failed() + " nicht geladene/ungueltige Zellen");
    }

    private static CommandResult list(CommandContext context, List<String> args) throws Exception {
        if (args.size() > 2) return CommandResult.error("Verwendung: /structure list [seite]");
        int page = args.size() == 2 ? coordinate(args.get(1)) : 1;
        List<String> paths = context.structures().templates().stream().sorted().toList();
        if (paths.isEmpty()) return CommandResult.success("Structures: keine");
        int pages = (paths.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        if (page < 1 || page > pages) return CommandResult.error("Structure-Seite muss zwischen 1 und " + pages + " liegen");
        int start = (page - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, paths.size());
        List<String> lines = new ArrayList<>(end - start + 1);
        lines.add("Structures Seite " + page + "/" + pages + ":");
        for (int i = start; i < end; i++) {
            lines.add("  " + paths.get(i));
        }
        return CommandResult.success(lines);
    }

    static StructureTransform transform(List<String> args) {
        StructureTransform.Rotation rotation = switch (option(args, "rotation", "0").toLowerCase()) {
            case "0", "none" -> StructureTransform.Rotation.NONE;
            case "90" -> StructureTransform.Rotation.CLOCKWISE_90;
            case "180" -> StructureTransform.Rotation.CLOCKWISE_180;
            case "270" -> StructureTransform.Rotation.CLOCKWISE_270;
            default -> throw new IllegalArgumentException("Rotation muss 0, 90, 180 oder 270 sein");
        };
        StructureTransform.Mirror mirror = switch (option(args, "mirror", "none").toLowerCase()) {
            case "none" -> StructureTransform.Mirror.NONE;
            case "left_right", "lr" -> StructureTransform.Mirror.LEFT_RIGHT;
            case "front_back", "fb" -> StructureTransform.Mirror.FRONT_BACK;
            default -> throw new IllegalArgumentException("Mirror muss none, left_right oder front_back sein");
        };
        return new StructureTransform(rotation, mirror);
    }

    static String option(List<String> args, String key, String fallback) {
        String prefix = key.toLowerCase() + "=";
        for (String arg : args) if (arg.toLowerCase().startsWith(prefix)) return arg.substring(prefix.length());
        return fallback;
    }

    private static void validateOptions(List<String> args, String... allowed) {
        for (String arg : args) {
            int equals = arg.indexOf('=');
            if (equals <= 0) throw new IllegalArgumentException("Unerwartetes Argument: " + arg);
            String key = arg.substring(0, equals).toLowerCase();
            boolean valid = false;
            for (String candidate : allowed) if (candidate.equals(key)) { valid = true; break; }
            if (!valid) throw new IllegalArgumentException("Unbekannte Option: " + key);
        }
    }

    private static int coordinate(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Ungueltige Ganzzahl: " + value); }
    }

    private static boolean isInteger(String value) {
        try { Integer.parseInt(value); return true; }
        catch (NumberFormatException ignored) { return false; }
    }

    private static String pasteUsage() {
        return "Verwendung: /structure paste [id] [x y z] [rotation=...] [mirror=...] [replace=...]";
    }

    @Override
    public List<String> suggest(CommandContext context, List<String> arguments, String current) {
        if (arguments.isEmpty()) return List.of("pos1", "pos2", "anchor", "save", "load", "paste", "list").stream()
                .filter(v -> v.startsWith(current.toLowerCase())).toList();
        if ((arguments.getFirst().equals("load") || arguments.getFirst().equals("paste"))
                && arguments.size() == 1 && context.structures() != null) {
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
