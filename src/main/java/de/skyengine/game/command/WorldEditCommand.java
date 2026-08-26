package de.skyengine.game.command;

import de.skyengine.core.i18n.I18n;
import de.skyengine.game.world.structure.StructurePlacement;

import java.util.List;

/** Kleine WorldEdit-artige Befehlssuite fuer das native Structure-Clipboard. */
public final class WorldEditCommand implements Command {
    private final String name;

    public WorldEditCommand(String name) { this.name = name; }

    @Override public String prefix() { return "//"; }
    @Override public String name() { return name; }
    @Override public String usage() {
        return switch (name) {
            case "rotate" -> "<vielfaches-von-90>";
            case "preview" -> "[clear|x y z] [replace=all|keep]";
            case "paste" -> "[x y z] [replace=all|keep]";
            case "undo", "redo" -> "[anzahl]";
            default -> "";
        };
    }

    @Override
    public CommandResult execute(CommandContext context, List<String> arguments) {
        if (context.structures() == null) return CommandResult.error(I18n.tr("command.worldedit.no_world"));
        try {
            return switch (name) {
                case "wand" -> arguments.isEmpty() ? CommandResult.success(context.structures().wand())
                        : CommandResult.error("Verwendung: //wand");
                case "pos1" -> arguments.isEmpty() ? CommandResult.success(context.structures().pos1())
                        : CommandResult.error(I18n.tr("command.worldedit.pos1_usage"));
                case "pos2" -> arguments.isEmpty() ? CommandResult.success(context.structures().pos2())
                        : CommandResult.error(I18n.tr("command.worldedit.pos2_usage"));
                case "rotate" -> rotate(context, arguments);
                case "flip" -> arguments.isEmpty() ? CommandResult.success(context.structures().flip())
                        : CommandResult.error("Verwendung: //flip");
                case "preview" -> preview(context, arguments);
                case "paste" -> paste(context, arguments);
                case "undo" -> history(context, arguments, false);
                case "redo" -> history(context, arguments, true);
                default -> CommandResult.error("Unbekannter Editor-Befehl: //" + name);
            };
        } catch (Exception e) {
            return CommandResult.error("Editor-Fehler: " + e.getMessage());
        }
    }

    private static CommandResult rotate(CommandContext context, List<String> args) {
        if (args.size() != 1) return CommandResult.error("Verwendung: //rotate <vielfaches-von-90>");
        return CommandResult.success(context.structures().rotate(StructureCommand.integer(args.getFirst())));
    }

    private static CommandResult preview(CommandContext context, List<String> args) {
        if (args.size() == 1 && args.getFirst().equalsIgnoreCase("clear")) {
            context.structures().clearPreview();
            return CommandResult.success("Structure-Vorschau entfernt");
        }
        ParsedPlacement parsed = placementArguments(args, "//preview");
        return CommandResult.success(context.structures().preview(parsed.x, parsed.y, parsed.z, parsed.rule));
    }

    private static CommandResult paste(CommandContext context, List<String> args) throws Exception {
        ParsedPlacement parsed = placementArguments(args, "//paste");
        StructurePlacement.Result result = context.structures().paste(parsed.x, parsed.y, parsed.z, parsed.rule);
        return result.complete() ? CommandResult.success("Structure platziert: " + result.written() + " Zellen")
                : CommandResult.error("Structure konnte nicht vollstaendig platziert werden: " + result.failed());
    }

    private static CommandResult history(CommandContext context, List<String> args, boolean redo) {
        if (args.size() > 1) return CommandResult.error("Verwendung: //" + (redo ? "redo" : "undo") + " [anzahl]");
        int amount = args.isEmpty() ? 1 : StructureCommand.integer(args.getFirst());
        String result = redo ? context.structures().redo(amount) : context.structures().undo(amount);
        return CommandResult.success(result);
    }

    private static ParsedPlacement placementArguments(List<String> args, String command) {
        int index = 0;
        Integer x = null, y = null, z = null;
        if (!args.isEmpty() && !args.getFirst().contains("=")) {
            if (args.size() < 3) throw new IllegalArgumentException("Verwendung: " + command + " [x y z] [replace=all|keep]");
            x = StructureCommand.integer(args.get(index++));
            y = StructureCommand.integer(args.get(index++));
            z = StructureCommand.integer(args.get(index++));
        }
        List<String> options = args.subList(index, args.size());
        StructureCommand.validateOptions(options, "replace");
        String replace = StructureCommand.option(options, "replace", "all").toLowerCase();
        if (!replace.equals("all") && !replace.equals("keep")) throw new IllegalArgumentException("replace muss all oder keep sein");
        return new ParsedPlacement(x, y, z, replace.equals("keep")
                ? StructurePlacement.Rule.KEEP_EXISTING : StructurePlacement.Rule.REPLACE_ALL);
    }

    @Override
    public List<String> suggest(CommandContext context, List<String> arguments, String current) {
        if (name.equals("preview") && arguments.isEmpty() && "clear".startsWith(current.toLowerCase())) {
            return List.of("clear");
        }
        return List.of();
    }

    private record ParsedPlacement(Integer x, Integer y, Integer z, StructurePlacement.Rule rule) {}
}
