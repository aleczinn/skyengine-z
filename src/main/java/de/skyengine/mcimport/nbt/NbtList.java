package de.skyengine.mcimport.nbt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** NBT-Liste (homogen getypt). {@link #compoundAt} wirft bei falschem Element-Typ. */
public final class NbtList implements NbtTag {

    private final byte elementType;
    private final List<NbtTag> values = new ArrayList<>();

    public NbtList(byte elementType) {
        this.elementType = elementType;
    }

    public NbtList add(NbtTag tag) {
        this.values.add(tag);
        return this;
    }

    /** NBT-Typ-ID der Elemente (0 bei leerer Liste möglich). */
    public byte elementType() {
        return this.elementType;
    }

    public int size() {
        return this.values.size();
    }

    public NbtTag get(int index) {
        return this.values.get(index);
    }

    /** Element als Compound (Pflichtzugriff — Listen von Compounds sind der Normalfall). */
    public NbtCompound compoundAt(int index) throws IOException {
        if (this.values.get(index) instanceof NbtCompound c) return c;
        throw new IOException("NBT-Listen-Element " + index + " ist kein Compound (Element-Typ "
                + this.elementType + ")");
    }
}
