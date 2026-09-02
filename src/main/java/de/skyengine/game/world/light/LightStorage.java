package de.skyengine.game.world.light;

import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.shared.world.LightPlane;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Licht-Speicher einer Ebene eines Chunks: pro Section ein lazy materialisiertes Nibble-Array
 * (32³ Zellen = 16 KB). Nicht materialisierte Sections haben einen uniformen Wert (0 unter dem
 * Terrain, 15 für Voll-Himmel-Sections darüber) — das ist der Normalfall und der Grund, warum
 * Luft- und Untergrund-Sections nichts kosten. Ohne diesen Kurzschluss läge der Bedarf bei
 * 16 × 16 KB pro Chunk statt bei den typischen 2-5 materialisierten Sections.
 *
 * <p><b>Threading (bewusst lock-frei):</b> Byte-Schreib-/
 * Lesezugriffe reißen nicht; die Array-Referenzen liegen in einer {@link AtomicReferenceArray}
 * (sichere Publikation der Materialisierung). Nebenläufige Reader (Mesher) können transient
 * veraltete Werte sehen — die {@link LightEngine} markiert geänderte Sections dirty, der Remesh
 * konvergiert. Kein PalettedContainer-artiges Strukturwachstum, daher kein Lock-Zwang. Hier
 * nachträglich Locks einzuziehen würde die Architektur brechen, nicht absichern.</p>
 *
 * <p>Es gibt zwei Ebenen, je eine Instanz dieser Klasse pro Chunk ({@code Chunk.light} für das
 * Himmelslicht, {@code Chunk.blockLight} für Fackeln/Lava) — die Klasse selbst weiß davon nichts.
 * Ein späterer RGB-Satz käme genauso als weitere Ebenen daneben, nicht in dieses Nibble-Array.
 * Der Uniform-Wert ist ebenenabhängig: Himmelslicht trägt über dem Terrain 15, Blocklicht
 * überall 0 — deshalb kosten Sections ohne Leuchtblock nichts.</p>
 */
public final class LightStorage {

    private static final int NIBBLES = ChunkSection.VOLUME / 2; // 16 KB pro Section

    /* Index = Section; null = uniformer Wert aus uniform[] */
    private final AtomicReferenceArray<byte[]> data = new AtomicReferenceArray<>(Chunk.SECTIONS);
    private final byte[] uniform = new byte[Chunk.SECTIONS];

    /** Liest einen Lichtwert 0..15. Chunk-lokale Koordinaten (x/z 0..31, y 0..511). */
    public int get(int x, int y, int z) {
        int section = y >> ChunkSection.SHIFT;
        byte[] arr = this.data.get(section);
        if (arr == null) return this.uniform[section];
        int cell = cellIndex(x, y & ChunkSection.MASK, z);
        int b = arr[cell >> 1];
        return (cell & 1) == 0 ? (b & 0xF) : ((b >> 4) & 0xF);
    }

    /** Schreibt einen Lichtwert 0..15 (materialisiert die Section bei Bedarf). */
    public void set(int x, int y, int z, int value) {
        int section = y >> ChunkSection.SHIFT;
        byte[] arr = this.data.get(section);
        if (arr == null) {
            if (value == this.uniform[section]) return;
            arr = this.materialize(section);
        }
        int cell = cellIndex(x, y & ChunkSection.MASK, z);
        int i = cell >> 1;
        if ((cell & 1) == 0) {
            arr[i] = (byte) ((arr[i] & 0xF0) | value);
        } else {
            arr[i] = (byte) ((arr[i] & 0x0F) | (value << 4));
        }
    }

    /** Uniformer Wert einer Section oder -1, wenn sie materialisiert ist. */
    public int uniformValue(int section) {
        return this.data.get(section) != null ? -1 : this.uniform[section];
    }

    /** Immutable transport snapshot without materialising a uniform section. */
    public LightPlane snapshotSection(int section) {
        if (section < 0 || section >= Chunk.SECTIONS) {
            throw new IllegalArgumentException("Invalid light section " + section);
        }
        byte[] packed = this.data.get(section);
        if (packed != null) return new LightPlane(LightPlane.Mode.PACKED_NIBBLES, packed);
        return new LightPlane(this.uniform[section] == 15
                ? LightPlane.Mode.UNIFORM_FULL : LightPlane.Mode.UNIFORM_ZERO, null);
    }

    /** Setzt eine komplette Section auf einen uniformen Wert (Initial-Lighting). */
    public void setUniform(int section, int value) {
        this.uniform[section] = (byte) value;
        this.data.set(section, null);
    }

    /**
     * Installs one complete nibble-packed section from a trusted persistence/network snapshot.
     * The payload is copied so callers cannot mutate light data after publication.
     */
    public void installPackedSection(int section, byte[] packedNibbles) {
        if (section < 0 || section >= Chunk.SECTIONS) {
            throw new IllegalArgumentException("Invalid light section " + section);
        }
        if (packedNibbles == null || packedNibbles.length != NIBBLES) {
            throw new IllegalArgumentException("Invalid packed light length: "
                    + (packedNibbles == null ? -1 : packedNibbles.length));
        }
        this.uniform[section] = 0;
        this.data.set(section, packedNibbles.clone());
    }

    /** Anzahl materialisierter Sections — nur für Debug-Telemetrie. */
    public int materializedSections() {
        int n = 0;
        for (int s = 0; s < Chunk.SECTIONS; s++) {
            if (this.data.get(s) != null) n++;
        }
        return n;
    }

    private byte[] materialize(int section) {
        byte[] arr = new byte[NIBBLES];
        byte u = this.uniform[section];
        if (u != 0) Arrays.fill(arr, (byte) ((u << 4) | u));
        /* CAS: verliert ein nebenläufiger Materialisierer, nutzt er das Gewinner-Array */
        return this.data.compareAndSet(section, null, arr) ? arr : this.data.get(section);
    }

    private static int cellIndex(int x, int localY, int z) {
        return (localY << (ChunkSection.SHIFT * 2)) | (z << ChunkSection.SHIFT) | x;
    }
}
