package de.skyengine.game.world.block.state;

import de.skyengine.game.world.block.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Gemeinsame Property-Konstanten. Da {@link Property} per Identität verglichen
 * wird, MUSS jedes Property genau einmal existieren - deshalb hier zentral.
 */
public final class Properties {

    /** Horizontale Ausrichtung (N/E/S/W) für Treppen u.a. */
    public static final Property<Direction> FACING =
            Property.of("facing", List.of(Direction.horizontal()));

    public static final Property<SlabType> SLAB_TYPE =
            Property.ofEnum("type", SlabType.class);

    /**
     * Rolle in einer Doppeltruhe. Heißt wie in MC "type" und teilt sich den Namen mit
     * {@link #SLAB_TYPE} — unkritisch, weil Properties per Identität verglichen werden und
     * {@code BlockStateCodec} beim Dekodieren nur die Properties DES BLOCKS nach Namen durchsucht.
     */
    public static final Property<ChestType> CHEST_TYPE =
            Property.ofEnum("type", ChestType.class);

    public static final Property<BlockHalf> HALF =
            Property.ofEnum("half", BlockHalf.class);

    public static final Property<StairShape> STAIR_SHAPE =
            Property.ofEnum("shape", StairShape.class);

    /** Achse (Pillar/Log: X/Y/Z) aus der Platzierungsfläche. */
    public static final Property<Direction.Axis> AXIS =
            Property.ofEnum("axis", Direction.Axis.class);

    /** Fluid-Stand 0..15 (0 = Quelle, 1..7 = fließend). */
    public static final Property<Integer> LEVEL = Property.of("level", levels());

    /** Fluid fällt als volle Säule nach unten (von oben gespeist). */
    public static final Property<Boolean> FALLING = Property.ofBoolean("falling");

    /** Offen/geschlossen (Türen, Trapdoors, Fence-Gates). */
    public static final Property<Boolean> OPEN = Property.ofBoolean("open");

    /** Türanschlag links/rechts. */
    public static final Property<DoorHinge> HINGE = Property.ofEnum("hinge", DoorHinge.class);

    /**
     * Trägerfläche für hängende Blöcke (Fackel, später Hebel/Knopf). Zusammen mit
     * {@link #FACING} wie in Vanilla: bei WALL gibt FACING die Richtung an, in die der Block
     * vom Träger weg zeigt; bei FLOOR/CEILING ist FACING bedeutungslos.
     */
    public static final Property<AttachFace> ATTACH = Property.ofEnum("face", AttachFace.class);

    /**
     * Gibt der Block gerade ein Redstone-Signal ab bzw. hat er eines gespeichert
     * (Knopf, Druckplatte, Hebel, Verstärker; Tür/Falltür als Flanken-Speicher)?
     * Die Signalstärke selbst läuft über die weak-/strongPower-Hooks in
     * {@code BlockBehavior}, die Abfragen über {@code RedstonePower}.
     */
    public static final Property<Boolean> POWERED = Property.ofBoolean("powered");

    /** Signalstärke des Redstone-Staubs 0..15 (0 = stromlos). */
    public static final Property<Integer> POWER = Property.of("power", levels());

    /** Verstärker-Verzögerung in Redstone-Ticks (1..4 = 2..8 Game-Ticks). */
    public static final Property<Integer> DELAY = Property.of("delay", List.of(1, 2, 3, 4));

    /** Leuchtet gerade (Redstone-Lampe, Redstone-Fackel)? Steuert auch die Luminanz. */
    public static final Property<Boolean> LIT = Property.ofBoolean("lit");

    /*
     * Staub-Verbindungen (none/side/up je Himmelsrichtung). Die Namen kollidieren BEWUSST
     * mit den Boolean-Connection-Properties oben — unkritisch aus demselben Grund wie bei
     * CHEST_TYPE: Properties werden per Identität verglichen und der BlockStateCodec sucht
     * beim Dekodieren nur in den Properties DES Blocks.
     */
    public static final Property<RedstoneSide> WIRE_NORTH = Property.ofEnum("north", RedstoneSide.class);
    public static final Property<RedstoneSide> WIRE_EAST = Property.ofEnum("east", RedstoneSide.class);
    public static final Property<RedstoneSide> WIRE_SOUTH = Property.ofEnum("south", RedstoneSide.class);
    public static final Property<RedstoneSide> WIRE_WEST = Property.ofEnum("west", RedstoneSide.class);

    /**
     * Volle 6-Richtungs-Ausrichtung (Kolben; später Beobachter/Trichter). Teilt sich den
     * Namen mit dem horizontalen {@link #FACING} — unkritisch aus demselben Grund wie bei
     * CHEST_TYPE (Identitäts-Vergleich, Codec sucht nur in den Properties DES Blocks).
     * Erster Enum-Wert (UP) wäre der Default — Archetypen setzen defaultValue(NORTH).
     */
    public static final Property<Direction> FACING_ALL = Property.ofEnum("facing", Direction.class);

    /** Kolben ausgefahren? */
    public static final Property<Boolean> EXTENDED = Property.ofBoolean("extended");

    /** Kopf-Variante des Kolbens (normal/klebrig) — Namensteilung mit "type" wie CHEST_TYPE. */
    public static final Property<PistonType> PISTON_TYPE = Property.ofEnum("type", PistonType.class);

    /** Komparator-Modus (compare/subtract) — Namensteilung "mode" gibt es sonst nicht. */
    public static final Property<ComparatorMode> MODE = Property.ofEnum("mode", ComparatorMode.class);

    /** Verstärker gesperrt (seitliche Diode mit Signal hält den Ausgang eingefroren). */
    public static final Property<Boolean> LOCKED = Property.ofBoolean("locked");

    /**
     * Trichter aktiv (Redstone-Signal DEAKTIVIERT ihn — invers zu POWERED, wie MC).
     * Achtung: Boolean-Default wäre false, der Archetyp setzt defaultValue(true).
     */
    public static final Property<Boolean> ENABLED = Property.ofBoolean("enabled");

    /** Dispenser/Dropper hat auf eine Redstone-Flanke bereits einen 4-Tick-Tick eingeplant. */
    public static final Property<Boolean> TRIGGERED = Property.ofBoolean("triggered");

    /**
     * Kolben-Kopf mit kurzem Arm (12 statt 16 px, ohne den 4-px-Überstand in die Basis-Zelle).
     * Der MATERIALISIERTE Kopf ist immer lang (short=false) — der Renderer wählt während der
     * Animation die kurze Variante, solange der Kopf in Basisnähe ist, sonst ragte der
     * Überstand hinten aus der Basis (MC-Parität).
     */
    public static final Property<Boolean> SHORT = Property.ofBoolean("short");

    /**
     * Reine Render-Variante des technischen Moving-Piston-Blocks: Beim Einfahren sitzt Vanillas
     * Source-BE in der Basiszelle. Das Chunk-Mesh zeichnet dort weiterhin die ausgefahrene Basis,
     * damit sie beim Wechsel vom statischen Block zum BE-Renderer weder doppelt noch anders
     * beleuchtet erscheint. Logik und Kollision bleiben vollständig in der BlockEntity.
     */
    public static final Property<Boolean> RETRACTING_SOURCE =
            Property.ofBoolean("retracting_source");

    /** Staub-Verbindungs-Property zur horizontalen Richtung. */
    public static Property<RedstoneSide> wireSide(Direction direction) {
        return switch (direction) {
            case NORTH -> WIRE_NORTH;
            case EAST -> WIRE_EAST;
            case SOUTH -> WIRE_SOUTH;
            case WEST -> WIRE_WEST;
            default -> throw new IllegalArgumentException("Staub verbindet nur horizontal: " + direction);
        };
    }

    private static List<Integer> levels() {
        List<Integer> list = new ArrayList<>(16);
        for (int i = 0; i < 16; i++) list.add(i);
        return list;
    }

    /** Verbindungs-Properties (Zäune, Panes, Walls, Pipes, Cables, Netzwerke). */
    public static final Property<Boolean> NORTH = Property.ofBoolean("north");
    public static final Property<Boolean> EAST = Property.ofBoolean("east");
    public static final Property<Boolean> SOUTH = Property.ofBoolean("south");
    public static final Property<Boolean> WEST = Property.ofBoolean("west");
    public static final Property<Boolean> UP = Property.ofBoolean("up");
    public static final Property<Boolean> DOWN = Property.ofBoolean("down");

    /** Verbindungs-Property passend zur Richtung (alle 6 Achsen). */
    public static Property<Boolean> connection(Direction direction) {
        return switch (direction) {
            case NORTH -> NORTH;
            case EAST -> EAST;
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            case UP -> UP;
            case DOWN -> DOWN;
        };
    }

    private Properties() {}
}
