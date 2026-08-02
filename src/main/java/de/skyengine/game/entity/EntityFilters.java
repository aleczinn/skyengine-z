package de.skyengine.game.entity;

import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Benannte Entity-Filter für datengetriebene Sensoren (Druckplatten-Sektion
 * {@code "sensor": { "entity_filter": [...] }}). Die Namen sind der Modding-Vertrag:
 * ContentSources können über {@link #register} eigene Filter beisteuern.
 *
 * <p>Die drei Mob-Filter ({@code mob}, {@code hostile_mob}, {@code passive_mob}) sind
 * vorbereitet und matchen bewusst NICHTS — die Engine hat (noch) keine Mobs. Sobald
 * Mob-Klassen existieren, werden sie HIER angebunden; Platten mit diesen Filtern
 * (Diamant/Smaragd) funktionieren dann ohne weitere Änderung.
 */
public final class EntityFilters {

    private static final Logger LOGGER = LogManager.getLogger(EntityFilters.class.getName());

    private static final Map<String, Predicate<Entity>> FILTERS = new HashMap<>();

    static {
        FILTERS.put("all", entity -> true);
        FILTERS.put("player", entity -> entity instanceof EntityPlayer);
        FILTERS.put("item", entity -> entity instanceof ItemEntity);
        FILTERS.put("tnt", entity -> entity instanceof PrimedTntEntity);
        FILTERS.put("falling_block", entity -> entity instanceof FallingBlockEntity);
        /* Mobs gibt es noch nicht — Kategorien reserviert (s. Klassenkommentar).
           "mob" meint dabei jedes Lebewesen außer dem Spieler (MC: Steinplatte
           reagiert auf Spieler UND Mobs, nicht auf Items). */
        FILTERS.put("mob", entity -> false);
        FILTERS.put("hostile_mob", entity -> false);
        FILTERS.put("passive_mob", entity -> false);
    }

    /** Registriert einen eigenen Filter (Modding). Doppelte Namen werfen — nichts überschreibt still. */
    public static void register(String name, Predicate<Entity> filter) {
        if (FILTERS.putIfAbsent(name, filter) != null) {
            throw new IllegalStateException("Entity-Filter doppelt registriert: " + name);
        }
    }

    /**
     * ODER-Verknüpfung mehrerer Filter-Namen. Unbekannte Namen werden mit Warnung
     * übersprungen; null/leer (oder nur Unbekannte) fällt auf {@code all} zurück.
     */
    public static Predicate<Entity> combine(String[] names) {
        if (names == null || names.length == 0) return FILTERS.get("all");
        Predicate<Entity> combined = null;
        for (String name : names) {
            Predicate<Entity> filter = FILTERS.get(name);
            if (filter == null) {
                LOGGER.warning("Unbekannter entity_filter '" + name + "' — Eintrag ignoriert");
                continue;
            }
            combined = combined == null ? filter : combined.or(filter);
        }
        return combined != null ? combined : FILTERS.get("all");
    }

    private EntityFilters() {}
}
