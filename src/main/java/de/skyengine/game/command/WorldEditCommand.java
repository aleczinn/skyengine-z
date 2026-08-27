package de.skyengine.game.command;

import de.skyengine.core.i18n.I18n;
import de.skyengine.game.world.structure.StructurePlacement;
import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.registry.Registries;

import java.util.List;

/** Kleine WorldEdit-artige Befehlssuite fuer Selektion, Clipboard und History. */
public final class WorldEditCommand implements Command {
    private final String name;

    public WorldEditCommand(String name) { this.name = name; }

    @Override public String prefix() { return "//"; }
    @Override public String name() { return name; }
    @Override public String usage() {
        return switch (name) {
            case "rotate" -> "<vielfaches-von-90>";
            case "copy", "cut" -> "[-a|--anchor]";
            case "set" -> "<block>";
            case "replace" -> "[von] <block>";
            case "expand", "contract", "stack", "move" -> "<wert>";
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
                case "hpos1" -> arguments.isEmpty() ? CommandResult.success(context.structures().hpos1())
                        : CommandResult.error(I18n.tr("command.worldedit.hpos1_usage"));
                case "hpos2" -> arguments.isEmpty() ? CommandResult.success(context.structures().hpos2())
                        : CommandResult.error(I18n.tr("command.worldedit.hpos2_usage"));
                case "copy" -> copy(context, arguments);
                case "cut" -> cut(context, arguments);
                case "expand" -> resize(context, arguments, false);
                case "contract" -> resize(context, arguments, true);
                case "set" -> set(context, arguments);
                case "replace" -> replace(context, arguments);
                case "stack" -> directionalEdit(context, arguments, true);
                case "move" -> directionalEdit(context, arguments, false);
                case "regen" -> arguments.isEmpty() ? editResult(context.structures().regen(),
                        "command.worldedit.regen_success", "command.worldedit.regen_failed")
                        : CommandResult.error(I18n.tr("command.worldedit.regen_usage"));
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
            return CommandResult.error(I18n.tr("command.worldedit.error_prefix", e.getMessage()));
        }
    }

    private static CommandResult cut(CommandContext context, List<String> args) {
        boolean anchor;
        if (args.isEmpty()) anchor = false;
        else if (args.size() == 1 && (args.getFirst().equalsIgnoreCase("-a")
                || args.getFirst().equalsIgnoreCase("--anchor"))) anchor = true;
        else return CommandResult.error(I18n.tr("command.worldedit.cut_usage"));
        return editResult(context.structures().cut(anchor), "command.worldedit.cut_success",
                "command.worldedit.cut_failed");
    }

    private static CommandResult copy(CommandContext context, List<String> args) {
        if (args.isEmpty()) return CommandResult.success(context.structures().copy(false));
        if (args.size() == 1 && (args.getFirst().equalsIgnoreCase("-a")
                || args.getFirst().equalsIgnoreCase("--anchor"))) {
            return CommandResult.success(context.structures().copy(true));
        }
        return CommandResult.error(I18n.tr("command.worldedit.copy_usage"));
    }

    private static CommandResult resize(CommandContext context, List<String> args, boolean contract) {
        if (args.size() != 1) return CommandResult.error(I18n.tr(
                "command.worldedit.resize_usage", contract ? "contract" : "expand"));
        int amount = StructureCommand.integer(args.getFirst());
        if (amount <= 0) throw new IllegalArgumentException(I18n.tr("command.worldedit.positive_value"));
        return CommandResult.success(contract ? context.structures().contract(amount)
                : context.structures().expand(amount));
    }

    private static CommandResult set(CommandContext context, List<String> args) {
        if (args.size() != 1) return CommandResult.error(I18n.tr("command.worldedit.set_usage"));
        int state = WorldEditBlockParser.parse(args.getFirst()).getId();
        return editResult(context.structures().set(state), "command.worldedit.set_success",
                "command.worldedit.set_failed");
    }

    private static CommandResult replace(CommandContext context, List<String> args) {
        if (args.size() < 1 || args.size() > 2) {
            return CommandResult.error(I18n.tr("command.worldedit.replace_usage"));
        }
        int target = WorldEditBlockParser.parse(args.getLast()).getId();
        java.util.function.IntPredicate matcher = args.size() == 1
                ? state -> state != de.skyengine.game.world.block.Blocks.AIR
                : WorldEditBlockParser.parseMatcher(args.getFirst())::matches;
        return editResult(context.structures().replace(matcher, target),
                "command.worldedit.replace_success", "command.worldedit.replace_failed");
    }

    private static CommandResult directionalEdit(CommandContext context, List<String> args, boolean stack) {
        if (args.size() != 1) return CommandResult.error(I18n.tr(
                stack ? "command.worldedit.stack_usage" : "command.worldedit.move_usage"));
        int amount = StructureCommand.integer(args.getFirst());
        if (amount <= 0) throw new IllegalArgumentException(I18n.tr("command.worldedit.positive_value"));
        StructurePlacement.Result result = stack ? context.structures().stack(amount)
                : context.structures().move(amount);
        return editResult(result, stack ? "command.worldedit.stack_success" : "command.worldedit.move_success",
                stack ? "command.worldedit.stack_failed" : "command.worldedit.move_failed");
    }

    private static CommandResult editResult(StructurePlacement.Result result, String success, String failed) {
        return result.complete() ? CommandResult.success(I18n.tr(success, result.written()))
                : CommandResult.error(I18n.tr(failed, result.failed()));
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
        if ((name.equals("set") && arguments.isEmpty())
                || name.equals("replace") && arguments.size() <= 1) {
            String needle = current.toLowerCase(java.util.Locale.ROOT).replace("_", "").replace("-", "");
            return Registries.BLOCK.values().stream().map(Block::getIdentifier)
                    .filter(id -> normalize(id.toString()).startsWith(needle)
                            || normalize(id.path()).startsWith(needle))
                    .map(Identifier::toString).sorted().toList();
        }
        return List.of();
    }

    private static String normalize(String value) {
        return value.toLowerCase(java.util.Locale.ROOT).replace("_", "").replace("-", "");
    }

    private record ParsedPlacement(Integer x, Integer y, Integer z, StructurePlacement.Rule rule) {}
}
