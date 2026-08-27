package de.skyengine.game.world.structure;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.mcimport.mapping.BlockMapper;
import de.skyengine.mcimport.mca.McBlockState;
import de.skyengine.mcimport.nbt.NbtCompound;
import de.skyengine.mcimport.nbt.NbtList;
import de.skyengine.mcimport.nbt.NbtReader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Offline-Importer fuer das alte MCEdit-/WorldEdit-Format {@code .schematic}. */
public final class LegacySchematicImporter {

    private final BlockMapper mapper;

    public LegacySchematicImporter(BlockMapper mapper) {
        this.mapper = mapper;
    }

    public SchematicImporter.Result importFile(Path file, Identifier id,
                                                SchematicImporter.Options options) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            return importStream(input, id, options);
        }
    }

    public SchematicImporter.Result importStream(InputStream input, Identifier id,
                                                  SchematicImporter.Options options) throws IOException {
        NbtCompound root = NbtReader.readAuto(input);
        NbtCompound nested = root.getCompound("Schematic");
        if (nested != null) root = nested;
        int width = root.requireInt("Width");
        int height = root.requireInt("Height");
        int length = root.requireInt("Length");
        int volume = checkedVolume(width, height, length);
        byte[] blocks = root.getByteArray("Blocks");
        byte[] data = root.getByteArray("Data");
        byte[] addBlocks = root.getByteArray("AddBlocks");
        if (blocks == null || blocks.length != volume) {
            throw new IOException("Legacy-Schematic Blocks hat " + (blocks == null ? 0 : blocks.length)
                    + " statt " + volume + " Eintraegen");
        }
        if (data == null || data.length != volume) {
            throw new IOException("Legacy-Schematic Data hat " + (data == null ? 0 : data.length)
                    + " statt " + volume + " Eintraegen");
        }
        if (addBlocks != null && addBlocks.length < (volume + 1) / 2) {
            throw new IOException("Legacy-Schematic AddBlocks ist zu kurz");
        }

        ArrayList<StructureTemplate.Cell> cells = new ArrayList<>();
        ArrayList<String> warnings = new ArrayList<>();
        warnList(root, "Entities", warnings);
        warnList(root, "TileEntities", warnings);
        for (int linear = 0; linear < volume; linear++) {
            int legacyId = blocks[linear] & 0xff;
            if (addBlocks != null) {
                int packed = addBlocks[linear >> 1] & 0xff;
                legacyId |= ((linear & 1) == 0 ? packed & 0x0f : packed >>> 4) << 8;
            }
            int metadata = data[linear] & 0x0f;
            McBlockState source = LegacyBlockStateMapper.map(legacyId, metadata);
            int x = linear % width;
            int z = (linear / width) % length;
            int y = linear / (width * length);
            if (source == null) {
                String message = "Unbekannter Legacy-Block " + legacyId + ':' + metadata
                        + " bei " + x + ' ' + y + ' ' + z;
                if (options.unknownBlocks() == SchematicImporter.UnknownBlocks.ERROR) {
                    throw new IOException(message);
                }
                if (!warnings.contains(message)) warnings.add(message);
                continue;
            }
            if (source.name().equals("minecraft:air")) {
                if (options.includeAir()) cells.add(new StructureTemplate.Cell(x, y, z, Blocks.AIR));
                continue;
            }
            if (!this.mapper.isKnown(source)) {
                String message = "Nicht abbildbarer Legacy-BlockState " + source + " ("
                        + legacyId + ':' + metadata + ") bei " + x + ' ' + y + ' ' + z;
                if (options.unknownBlocks() == SchematicImporter.UnknownBlocks.ERROR) {
                    throw new IOException(message);
                }
                if (!warnings.contains(message)) warnings.add(message);
                continue;
            }
            cells.add(new StructureTemplate.Cell(x, y, z, this.mapper.map(source)));
        }

        int anchorX = anchor(root, "WEOffsetX", "WEOriginX", width);
        int anchorY = anchor(root, "WEOffsetY", "WEOriginY", height);
        int anchorZ = anchor(root, "WEOffsetZ", "WEOriginZ", length);
        try {
            return new SchematicImporter.Result(new StructureTemplate(id, width, height, length,
                    anchorX, anchorY, anchorZ, cells), List.copyOf(warnings));
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungueltige Legacy-Schematic: " + e.getMessage(), e);
        }
    }

    private static int checkedVolume(int width, int height, int length) throws IOException {
        if (width <= 0 || height <= 0 || length <= 0) throw new IOException("Ungueltige Schematic-Groesse");
        long volume = (long) width * height * length;
        if (volume > StructureTemplate.MAX_VOLUME) {
            throw new IOException("Schematic ist zu gross: " + width + 'x' + height + 'x' + length);
        }
        return (int) volume;
    }

    private static int anchor(NbtCompound root, String offsetKey, String originKey, int size) {
        int offset = root.getInt(offsetKey, Integer.MIN_VALUE);
        if (offset != Integer.MIN_VALUE) return Math.clamp(-offset, 0, size - 1);
        int origin = root.getInt(originKey, Integer.MIN_VALUE);
        return origin == Integer.MIN_VALUE ? 0 : Math.clamp(-origin, 0, size - 1);
    }

    private static void warnList(NbtCompound root, String key, List<String> warnings) {
        NbtList list = root.getList(key);
        if (list != null && list.size() > 0) {
            warnings.add(key + " werden vom Structure-Format noch nicht importiert (" + list.size() + ")");
        }
    }

    /** Abbildung des klassischen Anvil-ID/Meta-Schemas auf moderne Minecraft-State-Namen. */
    static final class LegacyBlockStateMapper {
        private static final String[] WOOD = {"oak", "spruce", "birch", "jungle"};
        private static final String[] WOOD2 = {"acacia", "dark_oak"};

        static McBlockState map(int id, int meta) {
            return switch (id) {
                case 0 -> state("air");
                case 1 -> state(switch (meta & 7) {
                    case 1 -> "granite"; case 2 -> "polished_granite"; case 3 -> "diorite";
                    case 4 -> "polished_diorite"; case 5 -> "andesite"; case 6 -> "polished_andesite";
                    default -> "stone";
                });
                case 2 -> state("grass_block");
                case 3 -> state((meta & 3) == 2 ? "podzol" : (meta & 3) == 1 ? "coarse_dirt" : "dirt");
                case 4 -> state("cobblestone");
                case 5 -> state(wood(meta & 7) + "_planks");
                case 7 -> state("bedrock");
                case 8, 9 -> state("water", Map.of("level", Integer.toString(meta & 7)));
                case 10, 11 -> state("lava", Map.of("level", Integer.toString(meta & 7)));
                case 12 -> state((meta & 1) == 1 ? "red_sand" : "sand");
                case 13 -> state("gravel");
                case 14 -> state("gold_ore");
                case 15 -> state("iron_ore");
                case 16 -> state("coal_ore");
                case 17 -> log(WOOD[meta & 3], meta);
                case 18 -> state(WOOD[meta & 3] + "_leaves");
                case 19 -> state("sponge");
                case 20 -> state("glass");
                case 21 -> state("lapis_ore");
                case 22 -> state("lapis_block");
                case 24 -> state((meta & 3) == 1 ? "chiseled_sandstone" : (meta & 3) == 2
                        ? "cut_sandstone" : "sandstone");
                case 31 -> switch (meta) {
                    case 0 -> state("dead_bush"); case 2 -> state("fern"); default -> state("short_grass");
                };
                case 32 -> state("dead_bush");
                case 37 -> state("dandelion");
                case 38 -> state(switch (meta) {
                    case 1 -> "blue_orchid"; case 2 -> "allium"; case 3 -> "azure_bluet";
                    case 4 -> "red_tulip"; case 5 -> "orange_tulip"; case 6 -> "white_tulip";
                    case 7 -> "pink_tulip"; case 8 -> "oxeye_daisy"; default -> "poppy";
                });
                case 39 -> state("brown_mushroom");
                case 40 -> state("red_mushroom");
                case 47 -> state("bookshelf");
                case 48 -> state("mossy_cobblestone");
                case 49 -> state("obsidian");
                case 78 -> state("snow");
                case 79 -> state("ice");
                case 80 -> state("snow_block");
                case 81 -> state("cactus");
                case 82 -> state("clay");
                case 87 -> state("netherrack");
                case 88 -> state("soul_sand");
                case 89 -> state("glowstone");
                case 103 -> state("melon");
                case 110 -> state("mycelium");
                case 112 -> state("nether_bricks");
                case 121 -> state("end_stone");
                case 129 -> state("emerald_ore");
                case 133 -> state("emerald_block");
                case 152 -> state("redstone_block");
                case 159 -> state(terracotta(meta));
                case 161 -> state(WOOD2[meta & 1] + "_leaves");
                case 162 -> log(WOOD2[meta & 1], meta);
                case 172 -> state("terracotta");
                default -> null;
            };
        }

        private static McBlockState log(String wood, int meta) {
            int axis = meta & 12;
            return state(wood + "_log", Map.of("axis", axis == 4 ? "x" : axis == 8 ? "z" : "y"));
        }

        private static String wood(int index) {
            return index >= 0 && index < 6 ? (index < 4 ? WOOD[index] : WOOD2[index - 4]) : "oak";
        }

        private static String terracotta(int meta) {
            String[] colors = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink",
                    "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
            return colors[meta & 15] + "_terracotta";
        }

        private static McBlockState state(String path) {
            return state(path, Map.of());
        }

        private static McBlockState state(String path, Map<String, String> properties) {
            return new McBlockState("minecraft:" + path, properties);
        }

        private LegacyBlockStateMapper() {}
    }
}
