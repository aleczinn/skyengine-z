package de.skyengine.game.world.chunk.palette;

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

    public int get(int index) {
        if (this.storage == null) return this.palette[0];
        return this.palette[this.storage.get(index)];
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

    /* Liefert (oder vergibt) den Paletten-Index einer State-ID; vergrößert Palette/Storage.
       Linearer Scan über die (typisch winzige) Palette - allokationsfrei und ohne Boxing. */
    private int idFor(int stateId) {
        for (int i = 0; i < this.paletteSize; i++) {
            if (this.palette[i] == stateId) return i;
        }

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
        return Arrays.copyOf(this.palette, this.paletteSize);
    }

    public BitStorage storage() {
        return this.storage;
    }
}
