package de.skyengine.game.world.block.state;

import java.util.List;

/**
 * Eine Block-Eigenschaft mit endlicher Wertemenge, z.B. FACING (north/south/east/west),
 * HALF (bottom/top) oder COLOR. Das kartesische Produkt aller Properties eines Blocks
 * ergibt seine BlockStates.
 */
public final class Property<T> {

    private final String name;
    private final List<T> values;

    private Property(String name, List<T> values) {
        this.name = name;
        this.values = List.copyOf(values);
    }

    public static <T> Property<T> of(String name, List<T> values) {
        if (values.isEmpty()) throw new IllegalArgumentException("Property '" + name + "' braucht mindestens einen Wert");
        return new Property<>(name, values);
    }

    @SafeVarargs
    public static <T> Property<T> of(String name, T... values) {
        return of(name, List.of(values));
    }

    public static Property<Boolean> ofBoolean(String name) {
        return of(name, List.of(Boolean.FALSE, Boolean.TRUE));
    }

    public static <E extends Enum<E>> Property<E> ofEnum(String name, Class<E> type) {
        return of(name, List.of(type.getEnumConstants()));
    }

    public String getName() {
        return name;
    }

    public List<T> getValues() {
        return values;
    }

    @Override
    public String toString() {
        return "Property[" + this.name + "]";
    }
}