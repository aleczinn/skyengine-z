package de.skyengine.game.world.save;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.BitSet;

/**
 * Eine Region-Datei ({@code r.<rx>.<rz>.srg}): 16×16 Chunk-Snapshots in 4-KiB-Sektoren.
 *
 * <p>Layout: Sektor 0 = Header (Magic „SKYR", headerVersion, 256 Einträge
 * {@code sektorOffset << 8 | sektorAnzahl}, 0 = Chunk nicht vorhanden). Danach Daten-Sektoren;
 * pro Chunk-Eintrag {@code int compressedLength, int rawLength, int crc32(RAW), byte compression,
 * byte[] compressedData}. CRC läuft über den UNKOMPRIMIERTEN Payload — ein späterer
 * Kompressionswechsel (neues compression-Byte) ändert die Prüfsumme nicht.
 *
 * <p>Crash-Sicherheit: es wird NIE in referenzierte Sektoren fremder Einträge geschrieben,
 * und die Reihenfolge ist immer erst Daten (+force), dann Header-Eintrag (+force). Ein
 * Abbruch dazwischen lässt schlimmstenfalls den EINEN Chunk auf seinem alten Stand (oder,
 * bei halbem Datenschrieb, am CRC scheitern) — nie die Region.
 *
 * <p>Freie Sektoren: keine persistente FreeList (wäre ein zweiter, crash-anfälliger
 * Wahrheits-Zustand neben dem Header). Stattdessen wird beim Öffnen aus den 256
 * Header-Einträgen eine Belegungs-Map ({@link BitSet}) abgeleitet; wächst ein Chunk aus
 * seinen Sektoren heraus, wird zuerst eine freie Lücke wiederverwendet (First-Fit), sonst
 * angehängt. Die Datei schrumpft nie (Kompaktierung = späteres Feature), wächst aber auch
 * nicht unbegrenzt. Kein eigenes Locking — der Aufrufer ({@code WorldStorage}) serialisiert.
 *
 * <p><b>Batch-Modus</b> ({@link #RegionFile(File, boolean)} mit {@code syncEachWrite=false}):
 * für Massen-Schreiben beim Welt-Import und regenerierbare Caches. Die beiden {@code force()} pro Chunk
 * entfallen (bei 256 Chunks sind das 512 fsyncs pro Datei — der mit Abstand größte Zeitposten),
 * stattdessen wird explizit mit {@link #flush()} oder beim {@link #close()} geflusht. Damit gilt
 * die oben beschriebene Pro-Chunk-Crash-Sicherheit bis zum Batch-Commit nicht. Der normale
 * Weltspeicher benutzt weiterhin ausschließlich den Standard-Konstruktor.
 */
public final class RegionFile implements AutoCloseable {

    public static final int CHUNKS_PER_AXIS = 16;
    public static final int CHUNK_ENTRIES = CHUNKS_PER_AXIS * CHUNKS_PER_AXIS;

    private static final int SECTOR_SIZE = 4096;
    private static final int MAGIC = 0x534B5952; // "SKYR"
    private static final byte HEADER_VERSION = 1;
    /* Byte-Offset der Eintrags-Tabelle im Header-Sektor (Magic 4 B + Version 1 B + 3 B Padding). */
    private static final int ENTRY_TABLE_OFFSET = 8;
    /* Chunk-Eintrags-Kopf vor den Daten: compressedLength, rawLength, crc32, compression. */
    private static final int CHUNK_HEADER_BYTES = 4 + 4 + 4 + 1;
    private static final byte COMPRESSION_DEFLATE = 1;
    /* Obergrenze der Sektoranzahl pro Chunk (8 Bit im Header-Eintrag). */
    private static final int MAX_SECTORS_PER_CHUNK = 255;
    private final RandomAccessFile file;
    private final int[] entries = new int[CHUNK_ENTRIES];
    /* Belegte Sektoren, beim Öffnen aus dem Header abgeleitet (Sektor 0 = Header selbst). */
    private final BitSet usedSectors = new BitSet();
    /* false = Batch-Modus: kein fsync je Schreibvorgang, nur einer beim close(). */
    private final boolean syncEachWrite;

    public RegionFile(File path) throws IOException {
        this(path, true);
    }

    public RegionFile(File path, boolean syncEachWrite) throws IOException {
        this.syncEachWrite = syncEachWrite;
        this.file = new RandomAccessFile(path, "rw");
        this.usedSectors.set(0);
        if (this.file.length() == 0) {
            writeFreshHeader();
        } else {
            readHeader(path);
        }
    }

    private void writeFreshHeader() throws IOException {
        byte[] header = new byte[SECTOR_SIZE];
        header[0] = (byte) (MAGIC >>> 24);
        header[1] = (byte) (MAGIC >>> 16);
        header[2] = (byte) (MAGIC >>> 8);
        header[3] = (byte) MAGIC;
        header[4] = HEADER_VERSION;
        this.file.seek(0);
        this.file.write(header);
        this.sync();
    }

    /** fsync — im Batch-Modus ein No-Op (dort wird einmal beim close() geflusht). */
    private void sync() throws IOException {
        if (this.syncEachWrite) this.file.getChannel().force(false);
    }

    /** Expliziter Batch-Commit fuer regenerierbare Caches. Normale World-Regionen nutzen
        weiterhin den crash-sicheren Sync pro Eintrag. */
    public void flush() throws IOException {
        this.file.getChannel().force(false);
    }

    private void readHeader(File path) throws IOException {
        this.file.seek(0);
        if (this.file.readInt() != MAGIC) {
            throw new IOException("Keine SkyEngine-Region-Datei: " + path);
        }
        byte version = this.file.readByte();
        if (version != HEADER_VERSION) {
            throw new IOException("Unbekannte Region-Header-Version " + version + ": " + path);
        }
        this.file.seek(ENTRY_TABLE_OFFSET);
        for (int i = 0; i < CHUNK_ENTRIES; i++) {
            int entry = this.file.readInt();
            this.entries[i] = entry;
            if (entry != 0) {
                this.usedSectors.set(entry >>> 8, (entry >>> 8) + (entry & 0xFF));
            }
        }
    }

    /** Lokale Chunk-Koordinaten 0..15 -> Tabellen-Index. */
    private static int index(int lx, int lz) {
        return lz * CHUNKS_PER_AXIS + lx;
    }

    public boolean has(int lx, int lz) {
        return this.entries[index(lx, lz)] != 0;
    }

    /**
     * Liest den ROHEN (dekomprimierten) Chunk-Payload oder null, wenn kein Eintrag existiert.
     * Wirft {@link IOException} bei CRC-Fehler oder korrupten Längenfeldern — der Aufrufer
     * behandelt den Chunk dann als ungültig (Fallback Regeneration).
     */
    public byte[] read(int lx, int lz) throws IOException {
        int entry = this.entries[index(lx, lz)];
        if (entry == 0) return null;
        int sectorOffset = entry >>> 8;
        int sectorCount = entry & 0xFF;

        this.file.seek((long) sectorOffset * SECTOR_SIZE);
        int compressedLength = this.file.readInt();
        int rawLength = this.file.readInt();
        int crc = this.file.readInt();
        byte compression = this.file.readByte();

        if (compressedLength <= 0 || CHUNK_HEADER_BYTES + compressedLength > sectorCount * SECTOR_SIZE) {
            throw new IOException("Korruptes Längenfeld (compressed=" + compressedLength
                    + ", Sektoren=" + sectorCount + ")");
        }
        if (rawLength <= 0 || rawLength > ChunkSerializer.MAX_RAW_LENGTH) {
            throw new IOException("Korruptes Längenfeld (raw=" + rawLength + ")");
        }
        if (compression != COMPRESSION_DEFLATE) {
            throw new IOException("Unbekannte Kompression " + compression);
        }

        byte[] compressed = new byte[compressedLength];
        this.file.readFully(compressed);
        byte[] raw = ChunkSerializer.decompress(compressed, rawLength);
        if (ChunkSerializer.crc32(raw) != crc) {
            throw new IOException("CRC-Fehler in Chunk (" + lx + ", " + lz + ")");
        }
        return raw;
    }

    /**
     * Schreibt einen ROHEN Chunk-Payload (komprimiert + prüfsummt intern). Reihenfolge:
     * erst Daten-Sektoren (+force), dann Header-Eintrag (+force) — s. Klassen-Doku.
     */
    public void write(int lx, int lz, byte[] rawPayload) throws IOException {
        byte[] compressed = ChunkSerializer.compress(rawPayload);
        int totalBytes = CHUNK_HEADER_BYTES + compressed.length;
        int sectorCount = (totalBytes + SECTOR_SIZE - 1) / SECTOR_SIZE;
        if (sectorCount > MAX_SECTORS_PER_CHUNK) {
            throw new IOException("Chunk-Payload zu groß: " + totalBytes + " B (" + sectorCount + " Sektoren)");
        }

        int idx = index(lx, lz);
        int oldEntry = this.entries[idx];
        int oldOffset = oldEntry >>> 8;
        int oldCount = oldEntry & 0xFF;

        /* Ziel-Sektoren: in-place, wenn es passt; sonst freie Lücke (First-Fit); sonst Append. */
        int sectorOffset;
        if (oldEntry != 0 && sectorCount <= oldCount) {
            sectorOffset = oldOffset;
        } else {
            sectorOffset = findFreeRun(sectorCount);
        }

        /* Daten schreiben und auf Sektor-Grenze auffüllen (deterministischer Datei-Inhalt). */
        byte[] block = new byte[sectorCount * SECTOR_SIZE];
        block[0] = (byte) (compressed.length >>> 24);
        block[1] = (byte) (compressed.length >>> 16);
        block[2] = (byte) (compressed.length >>> 8);
        block[3] = (byte) compressed.length;
        int raw = rawPayload.length;
        block[4] = (byte) (raw >>> 24);
        block[5] = (byte) (raw >>> 16);
        block[6] = (byte) (raw >>> 8);
        block[7] = (byte) raw;
        int crc = ChunkSerializer.crc32(rawPayload);
        block[8] = (byte) (crc >>> 24);
        block[9] = (byte) (crc >>> 16);
        block[10] = (byte) (crc >>> 8);
        block[11] = (byte) crc;
        block[12] = COMPRESSION_DEFLATE;
        System.arraycopy(compressed, 0, block, CHUNK_HEADER_BYTES, compressed.length);

        this.file.seek((long) sectorOffset * SECTOR_SIZE);
        this.file.write(block);
        this.sync();

        /* Commit-Punkt: Header-Eintrag umbiegen. */
        int newEntry = (sectorOffset << 8) | sectorCount;
        this.file.seek(ENTRY_TABLE_OFFSET + (long) idx * 4);
        this.file.writeInt(newEntry);
        this.sync();
        this.entries[idx] = newEntry;

        /* Belegungs-Map nachziehen: erst jetzt gelten die alten Sektoren als frei. */
        if (oldEntry != 0 && oldOffset != sectorOffset) {
            this.usedSectors.clear(oldOffset, oldOffset + oldCount);
        } else if (oldEntry != 0 && sectorCount < oldCount) {
            this.usedSectors.clear(oldOffset + sectorCount, oldOffset + oldCount);
        }
        this.usedSectors.set(sectorOffset, sectorOffset + sectorCount);
    }

    /* First-Fit über die Belegungs-Map; hinter dem letzten belegten Sektor ist immer Platz. */
    private int findFreeRun(int sectorCount) {
        int start = this.usedSectors.nextClearBit(1);
        while (true) {
            int end = this.usedSectors.nextSetBit(start);
            if (end < 0 || end - start >= sectorCount) return start;
            start = this.usedSectors.nextClearBit(end);
        }
    }

    @Override
    public void close() throws IOException {
        /* Batch-Modus: der EINE Flush für alles Geschriebene (Daten + Header). */
        if (!this.syncEachWrite) this.file.getChannel().force(true);
        this.file.close();
    }
}
