package de.skyengine.mcimport.mca;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Read-only-Leser für Vanilla-Region-Dateien ({@code r.<rx>.<rz>.mca}). Kennt bewusst
 * KEIN NBT — liefert die dekomprimierten Payload-Bytes; das Parsen übernimmt der
 * {@code NbtReader} unabhängig davon (die Kompression gehört hierher, weil das
 * Kompressions-Byte Teil des MCA-Formats ist).
 *
 * <p>Format: 8-KiB-Header = 1024 × int Offsets ({@code sektorOffset << 8 | sektorAnzahl},
 * Sektor = 4096 B, Index {@code (x&31) + (z&31)*32}) + 1024 × int Timestamps (nur
 * informativ). Chunk-Eintrag: {@code int länge} (inkl. Kompressions-Byte) +
 * {@code byte kompression} (1 = GZIP, 2 = Zlib, 3 = roh) + Daten. Custom-Kompression
 * (127) und ausgelagerte .mcc-Dateien (Bit 0x80) werden mit klarer Fehlermeldung
 * abgelehnt — kein stilles Fallback.
 */
public final class McRegionFile implements AutoCloseable {

    private static final int SECTOR_SIZE = 4096;
    private static final int CHUNKS_PER_AXIS = 32;

    private static final byte COMPRESSION_GZIP = 1;
    private static final byte COMPRESSION_ZLIB = 2;
    private static final byte COMPRESSION_NONE = 3;

    private final RandomAccessFile file;
    private final int[] offsets = new int[CHUNKS_PER_AXIS * CHUNKS_PER_AXIS];
    private final int[] timestamps = new int[CHUNKS_PER_AXIS * CHUNKS_PER_AXIS];

    public McRegionFile(File path) throws IOException {
        this.file = new RandomAccessFile(path, "r"); // strikt read-only!
        if (this.file.length() < 2L * SECTOR_SIZE) {
            throw new IOException("Region-Datei zu klein für den 8-KiB-Header: " + path);
        }
        for (int i = 0; i < this.offsets.length; i++) this.offsets[i] = this.file.readInt();
        for (int i = 0; i < this.timestamps.length; i++) this.timestamps[i] = this.file.readInt();
    }

    private static int index(int lx, int lz) {
        return (lx & 31) + (lz & 31) * CHUNKS_PER_AXIS;
    }

    public boolean has(int lx, int lz) {
        return this.offsets[index(lx, lz)] != 0;
    }

    /** Letzte Änderungszeit (Unix-Sekunden) des Chunks, 0 wenn nicht vorhanden. Nur informativ. */
    public int timestamp(int lx, int lz) {
        return this.timestamps[index(lx, lz)];
    }

    /**
     * Liest die DEKOMPRIMIERTEN Payload-Bytes des Chunks (NBT-Dokument) oder null,
     * wenn kein Eintrag existiert. Wirft bei korrupten Längen, unbekannter oder
     * ausgelagerter Kompression.
     */
    public byte[] readChunkData(int lx, int lz) throws IOException {
        int entry = this.offsets[index(lx, lz)];
        if (entry == 0) return null;
        int sectorOffset = entry >>> 8;
        int sectorCount = entry & 0xFF;

        this.file.seek((long) sectorOffset * SECTOR_SIZE);
        int length = this.file.readInt();
        if (length < 1 || length > sectorCount * SECTOR_SIZE) {
            throw new IOException("Korrupte Chunk-Länge " + length + " (Sektoren: " + sectorCount + ")");
        }
        byte compression = this.file.readByte();
        if ((compression & 0x80) != 0) {
            throw new IOException("Chunk liegt in ausgelagerter .mcc-Datei (Kompression "
                    + compression + ") — nicht unterstützt");
        }

        byte[] compressed = new byte[length - 1];
        this.file.readFully(compressed);

        InputStream stream = switch (compression) {
            case COMPRESSION_GZIP -> new GZIPInputStream(new ByteArrayInputStream(compressed));
            case COMPRESSION_ZLIB -> new InflaterInputStream(new ByteArrayInputStream(compressed));
            case COMPRESSION_NONE -> new ByteArrayInputStream(compressed);
            default -> throw new IOException("Unbekannte Chunk-Kompression " + compression
                    + " — nicht unterstützt");
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, compressed.length * 4));
        byte[] buffer = new byte[8192];
        int n;
        while ((n = stream.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    @Override
    public void close() throws IOException {
        this.file.close();
    }
}
