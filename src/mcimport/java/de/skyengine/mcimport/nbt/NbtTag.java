package de.skyengine.mcimport.nbt;

/**
 * Typisierter NBT-AST (statt Object-Tree mit Cast-Orgien): jeder Tag ist ein eigener
 * Typ, Primitive/Arrays als kompakte Records. Container ({@link NbtCompound},
 * {@link NbtList}) sind eigene Klassen mit typisierten Accessorn.
 *
 * <p>Autark — keine Engine-Klassen (der Importer soll die Engine nicht brauchen, um
 * Minecraft-Daten zu LESEN; die Übersetzung nach SkyEngine passiert erst im Mapping).
 */
public sealed interface NbtTag permits NbtCompound, NbtList,
        NbtTag.NbtByte, NbtTag.NbtShort, NbtTag.NbtInt, NbtTag.NbtLong,
        NbtTag.NbtFloat, NbtTag.NbtDouble, NbtTag.NbtString,
        NbtTag.NbtByteArray, NbtTag.NbtIntArray, NbtTag.NbtLongArray {

    record NbtByte(byte value) implements NbtTag {}
    record NbtShort(short value) implements NbtTag {}
    record NbtInt(int value) implements NbtTag {}
    record NbtLong(long value) implements NbtTag {}
    record NbtFloat(float value) implements NbtTag {}
    record NbtDouble(double value) implements NbtTag {}
    record NbtString(String value) implements NbtTag {}
    record NbtByteArray(byte[] value) implements NbtTag {}
    record NbtIntArray(int[] value) implements NbtTag {}
    record NbtLongArray(long[] value) implements NbtTag {}
}
