package de.skyengine.game.world.structure;

import de.skyengine.game.world.block.Identifier;
import de.skyengine.mcimport.mapping.BlockMapper;
import de.skyengine.mcimport.mca.McBlockState;
import de.skyengine.mcimport.nbt.NbtCompound;
import de.skyengine.mcimport.nbt.NbtReader;
import de.skyengine.mcimport.nbt.NbtTag;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Importiert Sponge-Schematic v2/v3 einmalig in das kanonische StructureTemplate. */
public final class SchematicImporter {
    public enum UnknownBlocks { ERROR, IGNORE }
    public record Options(boolean includeAir, UnknownBlocks unknownBlocks) {
        public static final Options NATURAL_FEATURE = new Options(false, UnknownBlocks.ERROR);
    }
    public record Result(StructureTemplate template, List<String> warnings) {}

    private final BlockMapper mapper;

    public SchematicImporter(BlockMapper mapper) { this.mapper = mapper; }

    public static SchematicImporter createDefault() throws IOException {
        return new SchematicImporter(BlockMapper.loadDefault());
    }

    public Result importFile(Path file, Identifier id, Options options) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            return importStream(input, id, options);
        }
    }

    public Result importStream(InputStream input, Identifier id, Options options) throws IOException {
        NbtCompound root = NbtReader.readAuto(input);
        NbtCompound schematic = root.getCompound("Schematic");
        if (schematic != null) root = schematic;
        int width = root.requireInt("Width"), height = root.requireInt("Height"), length = root.requireInt("Length");
        if (width <= 0 || height <= 0 || length <= 0) throw new IOException("Ungueltige Schematic-Groesse");
        if ((long) width * height * length > StructureTemplate.MAX_VOLUME) {
            throw new IOException("Schematic ist zu gross: " + width + "x" + height + "x" + length);
        }
        NbtCompound paletteTag = root.requireCompound("Palette");
        int maxPalette = root.getInt("PaletteMax", paletteTag.size());
        McBlockState[] palette = new McBlockState[Math.max(maxPalette, paletteTag.size())];
        for (Map.Entry<String, NbtTag> entry : paletteTag.entries().entrySet()) {
            int index = integral(entry.getValue(), "Palette/" + entry.getKey());
            if (index < 0 || index >= palette.length) throw new IOException("Ungueltiger Paletteindex " + index);
            palette[index] = parseState(entry.getKey());
        }
        byte[] data = root.getByteArray("BlockData");
        if (data == null) throw new IOException("Schematic BlockData fehlt");
        int cellCount = Math.multiplyExact(Math.multiplyExact(width, height), length);
        int[] indices = decodeVarInts(data, cellCount);
        ArrayList<StructureTemplate.Cell> cells = new ArrayList<>();
        ArrayList<String> warnings = new ArrayList<>();
        for (int linear = 0; linear < indices.length; linear++) {
            int paletteIndex = indices[linear];
            if (paletteIndex < 0 || paletteIndex >= palette.length || palette[paletteIndex] == null) {
                throw new IOException("BlockData verweist auf fehlenden Paletteindex " + paletteIndex);
            }
            McBlockState source = palette[paletteIndex];
            if (source.name().equals("minecraft:structure_void")) continue;
            boolean air = source.name().equals("minecraft:air") || source.name().equals("minecraft:cave_air")
                    || source.name().equals("minecraft:void_air");
            if (air && !options.includeAir()) continue;
            if (air) {
                int x = linear % width;
                int z = (linear / width) % length;
                int y = linear / (width * length);
                cells.add(new StructureTemplate.Cell(x, y, z, de.skyengine.game.world.block.Blocks.AIR));
                continue;
            }
            if (!mapper.isKnown(source)) {
                String warning = "Unbekannter Minecraft-BlockState: " + source;
                if (options.unknownBlocks() == UnknownBlocks.ERROR) throw new IOException(warning);
                if (!warnings.contains(warning)) warnings.add(warning);
                continue;
            }
            int x = linear % width;
            int z = (linear / width) % length;
            int y = linear / (width * length);
            cells.add(new StructureTemplate.Cell(x, y, z, mapper.map(source)));
        }
        int[] offset = root.getIntArray("Offset");
        int anchorX = offset != null && offset.length == 3 ? Math.clamp(-offset[0], 0, width - 1) : 0;
        int anchorY = offset != null && offset.length == 3 ? Math.clamp(-offset[1], 0, height - 1) : 0;
        int anchorZ = offset != null && offset.length == 3 ? Math.clamp(-offset[2], 0, length - 1) : 0;
        try {
            return new Result(new StructureTemplate(id, width, height, length,
                    anchorX, anchorY, anchorZ, cells), List.copyOf(warnings));
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungueltige Schematic: " + e.getMessage(), e);
        }
    }

    private static McBlockState parseState(String encoded) throws IOException {
        int bracket = encoded.indexOf('[');
        String name = bracket < 0 ? encoded : encoded.substring(0, bracket);
        if (name.isBlank()) throw new IOException("Leerer BlockState in Schematic-Palette");
        LinkedHashMap<String, String> properties = new LinkedHashMap<>();
        if (bracket >= 0) {
            if (!encoded.endsWith("]")) throw new IOException("Ungueltiger BlockState " + encoded);
            String body = encoded.substring(bracket + 1, encoded.length() - 1);
            if (!body.isBlank()) for (String assignment : body.split(",")) {
                int equals = assignment.indexOf('=');
                if (equals <= 0) throw new IOException("Ungueltige Property in " + encoded);
                properties.put(assignment.substring(0, equals), assignment.substring(equals + 1));
            }
        }
        return new McBlockState(name, Map.copyOf(properties));
    }

    private static int integral(NbtTag tag, String field) throws IOException {
        return switch (tag) {
            case NbtTag.NbtByte value -> value.value();
            case NbtTag.NbtShort value -> value.value();
            case NbtTag.NbtInt value -> value.value();
            case NbtTag.NbtLong value when value.value() >= Integer.MIN_VALUE && value.value() <= Integer.MAX_VALUE -> (int) value.value();
            default -> throw new IOException(field + " ist keine Ganzzahl");
        };
    }

    private static int[] decodeVarInts(byte[] data, int expected) throws IOException {
        int[] values = new int[expected];
        int offset = 0;
        for (int i = 0; i < expected; i++) {
            int value = 0, shift = 0;
            while (true) {
                if (offset >= data.length) throw new IOException("BlockData endet nach " + i + " von " + expected + " Zellen");
                int current = data[offset++] & 0xff;
                value |= (current & 0x7f) << shift;
                if ((current & 0x80) == 0) break;
                shift += 7;
                if (shift > 28) throw new IOException("Zu langer VarInt in BlockData");
            }
            values[i] = value;
        }
        if (offset != data.length) throw new IOException("BlockData enthaelt " + (data.length - offset) + " ueberzaehlige Bytes");
        return values;
    }
}
