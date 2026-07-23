package de.skyengine.game.world.tick;

import de.skyengine.game.world.World;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry der persistierbaren Tick-Typen: bildet die {@code tickTypeId} aus dem
 * Chunk-Payload auf die Wiederherstellungs-Logik ab. Heute existiert nur {@link #BLOCK}
 * (= Dispatch über {@code Block.scheduledTick} an der Position — dort laufen Fluide,
 * fallender Sand und verzögerte Block-Reaktionen). Künftige Systeme mit EIGENEM Dispatch
 * (BlockEntity-Ticks, Maschinen, Redstone) registrieren eigene IDs — das Save-Format
 * (payloadVersion 2) nimmt sie ohne Formatänderung auf.
 */
public final class ScheduledTickTypes {

    /** Stellt einen geladenen Tick im jeweiligen System wieder her (Tick-Thread). */
    @FunctionalInterface
    public interface ScheduledTickRestorer {
        void restore(World world, int x, int y, int z, int remainingTicks);
    }

    public static final String BLOCK = "block";

    private static final Map<String, ScheduledTickRestorer> TYPES = new HashMap<>();

    static {
        register(BLOCK, (world, x, y, z, remaining) -> world.scheduleTick(x, y, z, remaining));
    }

    /** Registriert einen Tick-Typ. Doppelte IDs werfen — nichts darf fremde Typen still überschreiben. */
    public static void register(String id, ScheduledTickRestorer restorer) {
        if (TYPES.putIfAbsent(id, restorer) != null) {
            throw new IllegalStateException("Tick-Typ doppelt registriert: " + id);
        }
    }

    /** Restorer zur ID oder null (unbekannter Typ, z.B. Save aus neuerer Engine-Version). */
    public static ScheduledTickRestorer get(String id) {
        return TYPES.get(id);
    }

    private ScheduledTickTypes() {}
}
