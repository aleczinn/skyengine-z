package de.skyengine.game.world.chunk.palette;

import de.skyengine.shared.world.ImmutableIntArray;
import de.skyengine.shared.world.ImmutableLongArray;

import java.util.Arrays;

/**
 * Paletten-komprimierter Block-Speicher einer Sektion. Eine lokale Palette bildet die
 * wenigen vorkommenden State-IDs auf kleine Indizes ab, die bit-gepackt in einem
 * {@link BitStorage} liegen. Solange nur ein einziger Wert vorkommt (z.B. reiner Stein),
 * wird gar kein Index-Speicher allokiert (Single-Value-Optimierung).
 *
 * <p>bitsPerEntry wächst automatisch mit der Palette (max. 15 Bit bei 32³ Zellen). API in
 * {@code int} State-IDs — die Palette entkoppelt die Speichergröße von der ID-Breite.
 */
public final class PalettedContainer {

    private final int size;
    private int[] palette;
    private ImmutableIntArray frozenPalette;
    private int paletteSize;
    private BitStorage storage;   // null => Single-Value (palette[0])
    private int nonAir;

    public PalettedContainer(int size, int fill) {
        this.size = size;
        this.palette = new int[4];
        this.palette[0] = fill;
        this.paletteSize = 1;
        this.nonAir = fill == 0 ? 0 : size;
    }

    /**
     * Rebuild aus persistierten Daten (Chunk-Load): stellt Palette, Index-Speicher und
     * nonAir-Zähler exakt wieder her (inkl. bitsPerEntry-Zustand — ein set()-Neuaufbau
     * könnte den nicht reproduzieren). Die Palette wird kopiert, der Storage übernommen.
     */
    public PalettedContainer(int size, int[] palette, int paletteSize, BitStorage storage, int nonAir) {
        if (paletteSize < 1 || paletteSize > palette.length) {
            throw new IllegalArgumentException("Ungültige Paletten-Größe: " + paletteSize);
        }
        this.size = size;
        this.palette = Arrays.copyOf(palette, Math.max(4, paletteSize));
        this.paletteSize = paletteSize;
        this.storage = storage;
        this.nonAir = nonAir;
    }

    private PalettedContainer(int size, ImmutableIntArray palette, BitStorage storage, int nonAir) {
        if (palette.length() < 1) throw new IllegalArgumentException("Ungültige Paletten-Größe: 0");
        this.size = size;
        this.frozenPalette = palette;
        this.paletteSize = palette.length();
        this.storage = storage;
        this.nonAir = nonAir;
    }

    /** Adopts immutable replicated data and copies only the component first mutated. */
    public static PalettedContainer adoptImmutable(int size, ImmutableIntArray palette,
                                                   int bitsPerEntry,
                                                   ImmutableLongArray packedIndices,
                                                   int nonAir) {
        BitStorage storage = bitsPerEntry == 0 ? null
                : BitStorage.adoptImmutable(bitsPerEntry, size, packedIndices);
        return new PalettedContainer(size, palette, storage, nonAir);
    }

    public int get(int index) {
        if (this.storage == null) return paletteAt(0);
        return paletteAt(this.storage.get(index));
    }

    public void set(int index, int stateId) {
        int old = get(index);
        if (old == stateId) return;

        int id = idFor(stateId);
        this.storage.set(index, id);

        if (old == 0 && stateId != 0) this.nonAir++;
        else if (old != 0 && stateId == 0) this.nonAir--;
    }

    public boolean isEmpty() {
        return this.nonAir == 0;
    }

    public boolean isSingleValue() {
        return this.storage == null;
    }

    public int singleValue() {
        if (this.storage != null) throw new IllegalStateException("Container ist nicht einwertig");
        return paletteAt(0);
    }

    /* Liefert (oder vergibt) den Paletten-Index einer State-ID; vergrößert Palette/Storage.
       Linearer Scan über die (typisch winzige) Palette - allokationsfrei und ohne Boxing. */
    private int idFor(int stateId) {
        for (int i = 0; i < this.paletteSize; i++) {
            if (paletteAt(i) == stateId) return i;
        }

        ensureMutablePalette();
        int id = this.paletteSize;
        if (id == this.palette.length) this.palette = Arrays.copyOf(this.palette, this.palette.length * 2);
        this.palette[this.paletteSize++] = stateId;
        ensureBits(bitsFor(this.paletteSize));
        return id;
    }

    private void ensureBits(int bits) {
        if (this.storage == null) {
            /* Erste Diversifizierung: alle Zellen = Index 0 (= ursprünglicher Fill). */
            this.storage = new BitStorage(bits, this.size);
        } else if (bits > this.storage.bitsPerEntry()) {
            BitStorage next = new BitStorage(bits, this.size);
            for (int i = 0; i < this.size; i++) next.set(i, this.storage.get(i));
            this.storage = next;
        }
    }

    private static int bitsFor(int paletteSize) {
        int bits = 32 - Integer.numberOfLeadingZeros(Math.max(1, paletteSize - 1));
        return Math.max(1, bits);
    }

    /* --- Zugriff für Persistenz (Phase: Chunk-Save) --- */

    public int[] paletteEntries() {
        if (this.palette != null) return Arrays.copyOf(this.palette, this.paletteSize);
        return this.frozenPalette.copy();
    }

    /** Immutable section payload. Unchanged palettes/words are shared by later revisions. */
    public FrozenData freezeData() {
        if (this.frozenPalette == null) {
            int[] exact = Arrays.copyOf(this.palette, this.paletteSize);
            this.frozenPalette = ImmutableIntArray.takeOwnership(exact);
            this.palette = null;
        }
        ImmutableLongArray indices = this.storage == null
                ? ImmutableLongArray.takeOwnership(new long[0]) : this.storage.freezeData();
        return new FrozenData(this.frozenPalette, indices,
                this.storage == null ? 0 : this.storage.bitsPerEntry(), this.nonAir);
    }

    public BitStorage storage() {
        return this.storage;
    }

    public int nonAir() {
        return this.nonAir;
    }

    private int paletteAt(int index) {
        return this.palette != null ? this.palette[index] : this.frozenPalette.get(index);
    }

    private void ensureMutablePalette() {
        if (this.palette != null) return;
        int capacity = Math.max(4, this.paletteSize * 2);
        this.palette = Arrays.copyOf(this.frozenPalette.copy(), capacity);
        this.frozenPalette = null;
    }

    public record FrozenData(ImmutableIntArray palette, ImmutableLongArray packedIndices,
                             int bitsPerEntry, int nonAir) { }
}
