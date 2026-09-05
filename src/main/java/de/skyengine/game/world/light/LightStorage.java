package de.skyengine.game.world.light;

import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.chunk.ChunkSection;
import de.skyengine.shared.world.LightPlane;
import de.skyengine.shared.world.ImmutableByteArray;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicLongArray;

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
    /* Element is byte[] while mutable or ImmutableByteArray when adopted from replication. */
    private final AtomicReferenceArray<Object> data = new AtomicReferenceArray<>(Chunk.SECTIONS);
    private record FrozenPlane(long revision, Object source, LightPlane plane) { }
    private final AtomicReferenceArray<FrozenPlane> frozen = new AtomicReferenceArray<>(Chunk.SECTIONS);
    private final AtomicLongArray revisions = new AtomicLongArray(Chunk.SECTIONS);
    private final byte[] uniform = new byte[Chunk.SECTIONS];

    /** Liest einen Lichtwert 0..15. Chunk-lokale Koordinaten (x/z 0..31, y 0..511). */
    public int get(int x, int y, int z) {
        int section = y >> ChunkSection.SHIFT;
        Object value = this.data.get(section);
        if (value == null) return this.uniform[section];
        int cell = cellIndex(x, y & ChunkSection.MASK, z);
        int b = value instanceof byte[] arr ? arr[cell >> 1]
                : ((ImmutableByteArray) value).get(cell >> 1);
        return (cell & 1) == 0 ? (b & 0xF) : ((b >> 4) & 0xF);
    }

    /** Schreibt einen Lichtwert 0..15 (materialisiert die Section bei Bedarf). */
    public void set(int x, int y, int z, int value) {
        int section = y >> ChunkSection.SHIFT;
        Object current = this.data.get(section);
        byte[] arr;
        if (current == null) {
            if (value == this.uniform[section]) return;
            arr = this.materialize(section);
        } else if (current instanceof ImmutableByteArray frozen) {
            byte[] copy = frozen.copy();
            if (!this.data.compareAndSet(section, current, copy)) {
                set(x, y, z, value);
                return;
            }
            arr = copy;
        } else {
            arr = (byte[]) current;
        }
        int cell = cellIndex(x, y & ChunkSection.MASK, z);
        int i = cell >> 1;
        int old = (cell & 1) == 0 ? arr[i] & 0xF : arr[i] >>> 4 & 0xF;
        if (old == value) return;
        if ((cell & 1) == 0) {
            arr[i] = (byte) ((arr[i] & 0xF0) | value);
        } else {
            arr[i] = (byte) ((arr[i] & 0x0F) | (value << 4));
        }
        this.revisions.incrementAndGet(section);
        this.frozen.set(section, null);
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
        while (true) {
            long revision = this.revisions.get(section);
            Object packed = this.data.get(section);
            FrozenPlane cached = this.frozen.get(section);
            if (cached != null && cached.revision() == revision && cached.source() == packed) {
                return cached.plane();
            }
            LightPlane plane;
            if (packed instanceof ImmutableByteArray immutable) {
                plane = LightPlane.shared(LightPlane.Mode.PACKED_NIBBLES, immutable);
            } else if (packed instanceof byte[] mutable) {
                plane = LightPlane.takeOwnership(LightPlane.Mode.PACKED_NIBBLES, mutable.clone());
            } else {
                plane = new LightPlane(this.uniform[section] == 15
                        ? LightPlane.Mode.UNIFORM_FULL : LightPlane.Mode.UNIFORM_ZERO, null);
            }
            if (revision != this.revisions.get(section) || packed != this.data.get(section)) continue;
            FrozenPlane captured = new FrozenPlane(revision, packed, plane);
            this.frozen.set(section, captured);
            if (revision == this.revisions.get(section) && packed == this.data.get(section)) return plane;
        }
    }

    /** Setzt eine komplette Section auf einen uniformen Wert (Initial-Lighting). */
    public void setUniform(int section, int value) {
        if (this.data.get(section) == null && this.uniform[section] == value) return;
        this.uniform[section] = (byte) value;
        this.data.set(section, null);
        this.revisions.incrementAndGet(section);
        this.frozen.set(section, null);
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
        this.revisions.incrementAndGet(section);
        this.frozen.set(section, null);
    }

    /** Installs immutable network data directly; the first later mutation is copy-on-write. */
    public void installImmutableSection(int section, ImmutableByteArray packedNibbles) {
        if (section < 0 || section >= Chunk.SECTIONS) {
            throw new IllegalArgumentException("Invalid light section " + section);
        }
        if (packedNibbles == null || packedNibbles.length() != NIBBLES) {
            throw new IllegalArgumentException("Invalid packed light length: "
                    + (packedNibbles == null ? -1 : packedNibbles.length()));
        }
        this.uniform[section] = 0;
        this.data.set(section, packedNibbles);
        this.revisions.incrementAndGet(section);
        this.frozen.set(section, null);
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
        if (this.data.compareAndSet(section, null, arr)) return arr;
        Object winner = this.data.get(section);
        if (winner instanceof byte[] bytes) return bytes;
        byte[] copy = ((ImmutableByteArray) winner).copy();
        return this.data.compareAndSet(section, winner, copy) ? copy : materialize(section);
    }

    private static int cellIndex(int x, int localY, int z) {
        return (localY << (ChunkSection.SHIFT * 2)) | (z << ChunkSection.SHIFT) | x;
    }
}
