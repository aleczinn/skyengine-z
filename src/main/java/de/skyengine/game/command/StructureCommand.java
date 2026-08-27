package de.skyengine.game.command;

import de.skyengine.core.i18n.I18n;

import java.util.ArrayList;
import java.util.List;

/** Verwaltung nativer Templates; Bearbeitung und Placement liegen unter den //-Befehlen. */
public final class StructureCommand implements Command {
    private static final int PAGE_SIZE = 10;
    private static final java.util.Map<String, String> SAVE_FLAGS = java.util.Map.of(
            "-a", "anchor", "--anchor", "anchor");
    private static final java.util.Set<String> SAVE_OPTIONS = java.util.Set.of("air", "overwrite");

    @Override public String prefix() { return "//"; }
    @Override public String name() { return "structure"; }
    @Override public String usage() { return "<save|load|list>"; }

    @Override
    public CommandResult execute(CommandContext context, List<String> arguments) {
        if (context.structures() == null) return CommandResult.error(I18n.tr("command.structure.no_world"));
        if (arguments.isEmpty()) return CommandResult.error(I18n.tr("command.structure.usage"));
        try {
            return switch (arguments.getFirst().toLowerCase()) {
                case "save" -> save(context, arguments);
                case "load" -> load(context, arguments);
                case "list" -> list(context, arguments);
                default -> CommandResult.error(I18n.tr(
                        "command.structure.unknown_subcommand", arguments.getFirst()));
            };
        } catch (Exception e) {
            return CommandResult.error(I18n.tr("command.structure.error_prefix", e.getMessage()));
        }
    }

    private static CommandResult save(CommandContext context, List<String> args) throws Exception {
        TrailingArguments.Parsed parsed = TrailingArguments.parse(
                args.subList(1, args.size()), SAVE_FLAGS, SAVE_OPTIONS);
        if (parsed.positionals().size() != 1) {
            return CommandResult.error(I18n.tr("command.structure.save_usage"));
        }
        String air = parsed.option("air", "ignore").toLowerCase();
        if (!air.equals("ignore") && !air.equals("include")) return CommandResult.error(
                I18n.tr("command.structure.invalid_air"));
        String overwrite = parsed.option("overwrite", "false").toLowerCase();
        if (!overwrite.equals("true") && !overwrite.equals("false")) return CommandResult.error(
                I18n.tr("command.structure.invalid_overwrite"));
        var template = context.structures().save(parsed.positionals().getFirst(), air.equals("include"),
                Boolean.parseBoolean(overwrite), parsed.flag("anchor"));
        return CommandResult.success(I18n.tr(
                "command.structure.save_success", template.id(), template.cells().size()));
    }

    private static CommandResult load(CommandContext context, List<String> args) throws Exception {
        if (args.size() != 2) return CommandResult.error(I18n.tr("command.structure.load_usage"));
        var template = context.structures().load(args.get(1));
        return CommandResult.success(I18n.tr("command.structure.load_success", template.id(),
                template.sizeX(), template.sizeY(), template.sizeZ()));
    }

    private static CommandResult list(CommandContext context, List<String> args) throws Exception {
        if (args.size() > 2) return CommandResult.error(I18n.tr("command.structure.list_usage"));
        int page = args.size() == 2 ? integer(args.get(1)) : 1;
        List<String> paths = context.structures().templates().stream().sorted().toList();
        if (paths.isEmpty()) return CommandResult.success(I18n.tr("command.structure.list_empty"));
        int pages = (paths.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        if (page < 1 || page > pages) return CommandResult.error(
                I18n.tr("command.structure.invalid_page", pages));
        int start = (page - 1) * PAGE_SIZE, end = Math.min(start + PAGE_SIZE, paths.size());
        List<String> lines = new ArrayList<>(end - start + 1);
        lines.add(I18n.tr("command.structure.list_header", page, pages));
        for (int i = start; i < end; i++) lines.add("  " + paths.get(i));
        return CommandResult.success(lines);
    }

    static int integer(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(
                I18n.tr("command.structure.invalid_integer", value)); }
    }

    @Override
    public List<String> suggest(CommandContext context, List<String> arguments, String current) {
        if (arguments.isEmpty()) return List.of("save", "load", "list").stream()
                .filter(v -> v.startsWith(current.toLowerCase())).toList();
        if (arguments.getFirst().equals("load") && arguments.size() == 1 && context.structures() != null) {
            try {
                return context.structures().templates().stream()
                        .filter(v -> v.startsWith(current.toLowerCase())).toList();
            } catch (Exception ignored) { return List.of(); }
        }
        return List.of();
    }

    @Override
    public CommandSyntax syntax(List<String> arguments) {
        CommandSyntax.Group action = CommandSyntax.Group.required("save|load|list");
        if (arguments.isEmpty()) return CommandSyntax.of(List.of(action), "");
        return switch (arguments.getFirst().toLowerCase()) {
            case "save" -> CommandSyntax.of(List.of(action,
                    CommandSyntax.Group.required(I18n.tr("command.structure.syntax_name"))),
                    I18n.tr("command.structure.save_args"));
            case "load" -> CommandSyntax.of(List.of(action,
                    CommandSyntax.Group.required(I18n.tr("command.structure.syntax_name"))), "");
            case "list" -> CommandSyntax.of(List.of(action,
                    CommandSyntax.Group.optional(I18n.tr("command.structure.syntax_page"))), "");
            default -> CommandSyntax.of(List.of(action), "");
        };
    }

    @Override
    public boolean isOptionToken(String token) {
        return TrailingArguments.optionToken(token, SAVE_FLAGS.keySet(), SAVE_OPTIONS);
    }
}
