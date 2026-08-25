package de.skyengine.game.world.structure;

import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.BlockStateCodec;
import de.skyengine.mcimport.nbt.NbtCompound;
import de.skyengine.mcimport.nbt.NbtList;
import de.skyengine.mcimport.nbt.NbtReader;
import de.skyengine.mcimport.nbt.NbtTag;
import de.skyengine.mcimport.nbt.NbtWriter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Kanonischer Reader/Writer fuer das versionierte Voxel-Stories-Format {@code .structure}. */
public final class StructureSerializer {
    public static final int MAGIC = 0x56535452; // VSTR
    public static final int FORMAT_VERSION = 1;
    private static final int COMPRESSION_GZIP = 1;

    public static void write(Path target, StructureTemplate template) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) throw new IOException("Structure-Datei ohne Elternordner: " + target);
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, normalized.getFileName().toString(), ".tmp");
        boolean moved = false;
        try {
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                output.writeInt(MAGIC);
                output.writeShort(FORMAT_VERSION);
                output.writeByte(COMPRESSION_GZIP);
                GZIPOutputStream gzip = new GZIPOutputStream(output);
                NbtWriter.write(new DataOutputStream(gzip), "VoxelStructure", toNbt(template));
                gzip.finish();
            }
            try {
                Files.move(temp, normalized, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, normalized, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temp);
        }
    }

    public static StructureTemplate read(Path path, Identifier expectedId) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return read(in, expectedId);
        }
    }

    public static StructureTemplate read(InputStream source, Identifier expectedId) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(source);
        DataInputStream header = new DataInputStream(buffered);
        if (header.readInt() != MAGIC) throw new IOException("Keine .structure-Datei (VSTR-Magic fehlt)");
        int version = header.readUnsignedShort();
        if (version != FORMAT_VERSION) throw new IOException("Nicht unterstuetzte .structure-Version " + version);
        int compression = header.readUnsignedByte();
        if (compression != COMPRESSION_GZIP) throw new IOException("Unbekannte Structure-Kompression " + compression);
        try (DataInputStream payload = new DataInputStream(new GZIPInputStream(buffered))) {
            return fromNbt(NbtReader.read(payload), expectedId);
        }
    }

    private static NbtCompound toNbt(StructureTemplate template) {
        Map<String, Integer> palette = new LinkedHashMap<>();
        for (StructureTemplate.Cell cell : template.cells()) {
            String encoded = BlockStateCodec.encode(Blocks.getState(cell.state()));
            palette.putIfAbsent(encoded, palette.size());
        }
        NbtList paletteTags = new NbtList((byte) 8);
        for (String state : palette.keySet()) paletteTags.add(new NbtTag.NbtString(state));
        int[] blocks = new int[template.cells().size() * 4];
        int i = 0;
        for (StructureTemplate.Cell cell : template.cells()) {
            blocks[i++] = cell.x(); blocks[i++] = cell.y(); blocks[i++] = cell.z();
            blocks[i++] = palette.get(BlockStateCodec.encode(Blocks.getState(cell.state())));
        }
        return new NbtCompound()
                .put("Id", new NbtTag.NbtString(template.id().toString()))
                .put("Size", new NbtTag.NbtIntArray(new int[]{template.sizeX(), template.sizeY(), template.sizeZ()}))
                .put("Anchor", new NbtTag.NbtIntArray(new int[]{template.anchorX(), template.anchorY(), template.anchorZ()}))
                .put("Palette", paletteTags)
                .put("Blocks", new NbtTag.NbtIntArray(blocks));
    }

    private static StructureTemplate fromNbt(NbtCompound root, Identifier expectedId) throws IOException {
        Identifier stored = Identifier.of(root.requireString("Id"));
        if (expectedId != null && !stored.equals(expectedId)) {
            throw new IOException("Structure-ID " + stored + " stimmt nicht mit Ressource " + expectedId + " ueberein");
        }
        int[] size = root.getIntArray("Size"), anchor = root.getIntArray("Anchor"), blocks = root.getIntArray("Blocks");
        if (size == null || size.length != 3) throw new IOException("Structure Size muss drei Werte haben");
        if (anchor == null || anchor.length != 3) throw new IOException("Structure Anchor muss drei Werte haben");
        if (blocks == null || blocks.length % 4 != 0) throw new IOException("Structure Blocks ist kein 4er-Array");
        NbtList paletteTags = root.requireList("Palette");
        if (paletteTags.elementType() != 8) throw new IOException("Structure Palette ist keine String-Liste");
        int[] palette = new int[paletteTags.size()];
        for (int i = 0; i < palette.length; i++) {
            if (!(paletteTags.get(i) instanceof NbtTag.NbtString string)) throw new IOException("Ungueltiger Paletteintrag " + i);
            BlockState state = BlockStateCodec.decode(string.value());
            if (state == null) throw new IOException("Unbekannter BlockState in Structure: " + string.value());
            palette[i] = state.getId();
        }
        List<StructureTemplate.Cell> cells = new ArrayList<>(blocks.length / 4);
        for (int i = 0; i < blocks.length; i += 4) {
            int paletteIndex = blocks[i + 3];
            if (paletteIndex < 0 || paletteIndex >= palette.length) throw new IOException("Paletteindex ausserhalb: " + paletteIndex);
            cells.add(new StructureTemplate.Cell(blocks[i], blocks[i + 1], blocks[i + 2], palette[paletteIndex]));
        }
        try {
            return new StructureTemplate(stored, size[0], size[1], size[2],
                    anchor[0], anchor[1], anchor[2], cells);
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungueltiges StructureTemplate: " + e.getMessage(), e);
        }
    }

    private StructureSerializer() {}
}
