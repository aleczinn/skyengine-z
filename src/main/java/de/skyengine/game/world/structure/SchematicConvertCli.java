package de.skyengine.game.world.structure;

import de.skyengine.core.file.GameDirectory;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Identifier;

import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Offline-Content-Pipeline: Sponge .schem -> kanonische, globale .structure-Dateien. */
public final class SchematicConvertCli {

    public static void main(String[] args) {
        int result = run(args, System.out, System.err);
        if (result != 0) System.exit(result);
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 2 || !(args[0].equalsIgnoreCase("convert") || args[0].equalsIgnoreCase("batch"))) {
            usage(err);
            return 2;
        }
        try {
            Blocks.bootstrap(new File(de.skyengine.core.file.Files.RESOURCES_PATH, "game/blocks"));
            Mode mode = args[0].equalsIgnoreCase("batch") ? Mode.BATCH : Mode.CONVERT;
            Path input = Path.of(args[1]).toAbsolutePath().normalize();
            Options options = Options.parse(args, 2);
            Path output = options.output == null
                    ? GameDirectory.resolve("bin/structures").toPath().toAbsolutePath().normalize()
                    : options.output.toAbsolutePath().normalize();
            Files.createDirectories(output);
            de.skyengine.mcimport.mapping.BlockMapper mapper = de.skyengine.mcimport.mapping.BlockMapper.loadDefault();
            SchematicImporter importer = new SchematicImporter(mapper);
            LegacySchematicImporter legacyImporter = new LegacySchematicImporter(mapper);
            if (mode == Mode.CONVERT) {
                if (options.id == null) throw new IllegalArgumentException("Einzelimport benoetigt --id=<namespace:path>");
                convert(input, options.id, output, options, importer, legacyImporter, out);
                return 0;
            }
            if (options.id != null) throw new IllegalArgumentException("--id ist nur bei convert erlaubt");
            return batch(input, output, options, importer, legacyImporter, out, err);
        } catch (IllegalArgumentException e) {
            err.println("Argumentfehler:");
            err.println("  " + e.getMessage());
            usage(err);
            return 2;
        } catch (Exception e) {
            err.println("Konvertierungsfehler:");
            err.println("  " + e.getMessage());
            return 1;
        }
    }

    private static int batch(Path input, Path output, Options options, SchematicImporter importer,
                             LegacySchematicImporter legacyImporter,
                             PrintStream out, PrintStream err) throws Exception {
        if (!Files.isDirectory(input)) throw new IllegalArgumentException("Batch-Quelle ist kein Ordner: " + input);
        List<Path> files;
        try (var walk = Files.walk(input)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(SchematicConvertCli::isSchematic)
                    .sorted(Comparator.comparing(Path::toString)).toList();
        }
        if (files.isEmpty()) {
            out.println("Keine .schem- oder .schematic-Dateien gefunden: " + input);
            return 0;
        }
        int converted = 0, failed = 0;
        for (Path source : files) {
            String relative = input.relativize(source).toString().replace('\\', '/');
            relative = stripSchematicExtension(relative);
            String path = normalizePath(relative);
            if (!options.prefix.isBlank()) path = options.prefix + "/" + path;
            Identifier id = new Identifier(options.namespace, path);
            try {
                convert(source, id, output, options, importer, legacyImporter, out);
                converted++;
            } catch (Exception e) {
                failed++;
                err.println("Fehler in " + source + ":");
                err.println("  " + e.getMessage());
            }
        }
        out.println("Batch abgeschlossen: " + converted + " konvertiert, " + failed + " fehlgeschlagen");
        return failed == 0 ? 0 : 1;
    }

    private static void convert(Path input, Identifier id, Path output, Options options,
                                SchematicImporter importer, LegacySchematicImporter legacyImporter,
                                PrintStream out) throws Exception {
        if (!Files.isRegularFile(input)) throw new IllegalArgumentException("Schematic nicht gefunden: " + input);
        SchematicImporter.Options importOptions = new SchematicImporter.Options(options.includeAir,
                SchematicImporter.UnknownBlocks.ERROR);
        SchematicImporter.Result result = input.getFileName().toString().toLowerCase(Locale.ROOT)
                .endsWith(".schematic")
                ? legacyImporter.importFile(input, id, importOptions)
                : importer.importFile(input, id, importOptions);
        Path target = output.resolve(id.path() + ".structure").normalize();
        if (!target.startsWith(output)) throw new IllegalArgumentException("Structure-ID verlaesst den Ausgabeordner: " + id);
        if (Files.isRegularFile(target)) {
            StructureTemplate existing = StructureSerializer.read(target, null);
            if (!existing.id().equals(id)) {
                throw new IllegalArgumentException("Ausgabepfad gehoert bereits zu " + existing.id() + ": " + target);
            }
            if (!options.overwrite) throw new IllegalArgumentException("Zieldatei existiert bereits: " + target);
        }
        StructureSerializer.write(target, result.template());
        out.println("Konvertiert: " + input);
        out.println("  ID: " + id);
        out.println("  Ziel: " + target);
        out.println("  Groesse: " + result.template().sizeX() + "x" + result.template().sizeY()
                + "x" + result.template().sizeZ() + ", " + result.template().cells().size() + " Zellen");
        for (String warning : result.warnings()) out.println("  Warnung: " + warning);
    }

    private static String normalizePath(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._/-]+", "_")
                .replaceAll("_+", "_").replaceAll("^[/_.-]+|[/_.-]+$", "");
        if (normalized.isBlank() || normalized.contains("..")) {
            throw new IllegalArgumentException("Dateipfad ergibt keine gueltige Structure-ID: " + value);
        }
        return normalized;
    }

    private static boolean isSchematic(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".schem") || name.endsWith(".schematic");
    }

    private static String stripSchematicExtension(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".schematic")) return path.substring(0, path.length() - ".schematic".length());
        return path.substring(0, path.length() - ".schem".length());
    }

    private static void usage(PrintStream stream) {
        stream.println("Verwendung:");
        stream.println("  schematicConvert convert <input.schem|input.schematic> --id=<namespace:path> [Optionen]");
        stream.println("  schematicConvert batch <input-ordner> [--namespace="
                + Identifier.DEFAULT_NAMESPACE + "] [--prefix=pfad] [Optionen]");
        stream.println("Optionen: --output=<ordner> --air=ignore|include --overwrite");
    }

    private enum Mode { CONVERT, BATCH }

    private static final class Options {
        Identifier id;
        String namespace = Identifier.DEFAULT_NAMESPACE;
        String prefix = "";
        Path output;
        boolean includeAir;
        boolean overwrite;

        static Options parse(String[] args, int start) {
            Options result = new Options();
            List<String> unknown = new ArrayList<>();
            for (int i = start; i < args.length; i++) {
                String arg = args[i];
                if (arg.equals("--overwrite")) result.overwrite = true;
                else if (arg.startsWith("--id=")) result.id = Identifier.of(value(arg));
                else if (arg.startsWith("--namespace=")) result.namespace = value(arg).toLowerCase(Locale.ROOT);
                else if (arg.startsWith("--prefix=")) result.prefix = normalizePath(value(arg));
                else if (arg.startsWith("--output=")) result.output = Path.of(value(arg));
                else if (arg.startsWith("--air=")) {
                    String air = value(arg).toLowerCase(Locale.ROOT);
                    if (!air.equals("ignore") && !air.equals("include")) {
                        throw new IllegalArgumentException("--air muss ignore oder include sein");
                    }
                    result.includeAir = air.equals("include");
                } else unknown.add(arg);
            }
            if (!unknown.isEmpty()) throw new IllegalArgumentException("Unbekannte Optionen: " + String.join(", ", unknown));
            if (!result.namespace.matches("[a-z0-9._-]+")) throw new IllegalArgumentException("Ungueltiger Namespace");
            return result;
        }

        private static String value(String argument) {
            String value = argument.substring(argument.indexOf('=') + 1);
            if (value.isBlank()) throw new IllegalArgumentException("Leerer Optionswert: " + argument);
            return value;
        }
    }

    private SchematicConvertCli() {}
}
