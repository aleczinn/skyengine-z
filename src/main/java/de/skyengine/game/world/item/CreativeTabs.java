package de.skyengine.game.world.item;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.registry.Registries;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reiter des Creative-Inventars und ihr Inhalt. Die Tabs selbst stehen in
 * {@code game/creative_tabs.json} (Reihenfolge = Anzeigereihenfolge), die Zuordnung der Items
 * kommt aus dem Feld {@code creative_tab} der jeweiligen Block-/Item-JSON bzw. bei den in Java
 * registrierten Items (Werkzeuge, Eimer, Essen) aus {@link Items#bootstrap()}.
 *
 * <p><b>Reihenfolge-Invariante:</b> {@link #assign} puffert nur {@code Identifier -> Tab-IDs};
 * die eigentlichen Listen entstehen erst in {@link #build()} durch einen Lauf über
 * {@code Registries.ITEM.values()}. Die Zuordnung darf deshalb schon gemeldet werden, bevor das
 * zugehörige Item überhaupt existiert (Blöcke melden sich beim Laden, ihr BlockItem entsteht erst
 * später mit derselben {@link Identifier}).
 *
 * <p><b>Anzeigereihenfolge:</b> Ein Tab darf in der JSON unter {@code items} eine kuratierte
 * Reihenfolge vorgeben — dasselbe Prinzip wie Minecrafts handgeschriebene Tab-Listen. Die
 * Registry-Reihenfolge (= alphabetisch nach Dateiname) ist damit nur noch der <b>Fallback</b>:
 * nicht gelistete Items hängen stabil hinten dran und werden beim Bauen aufgezählt. Sortiert wird
 * ausschließlich diese Anzeigeliste — die Registry selbst bleibt unangetastet, weil an ihrer
 * Reihenfolge die Runtime-State-IDs und damit die Weltspeicher hängen.
 *
 * <p>Items ohne Zuordnung landen im Sammel-Tab {@value #FALLBACK} und werden beim Bauen
 * aufgezählt — ein stilles Verschlucken wäre im Spiel nicht auffindbar. Die Warnung erscheint
 * schon in {@code gradlew saveTest}, also ohne GL-Kontext.
 */
public final class CreativeTabs {

    private static final Logger LOGGER = LogManager.getLogger(CreativeTabs.class.getName());
    private static final Gson GSON = new Gson();

    /** Sammel-Tab für Items ohne {@code creative_tab}. Im Sollzustand leer und damit unsichtbar. */
    public static final String FALLBACK = "misc";

    private static final LinkedHashMap<String, CreativeTab> TABS = new LinkedHashMap<>();
    /** Kuratierte Anzeigereihenfolge je Tab-ID (leer/fehlend = Registry-Reihenfolge). */
    private static final Map<String, List<Identifier>> ORDER = new LinkedHashMap<>();
    /** Je Tab die IDs aus WÖRTLICH geschriebenen Einträgen — nur die sind tippfehlergeprüft. */
    private static final Map<String, Set<Identifier>> LITERALS = new LinkedHashMap<>();
    /** Je Tab: Muster-Text -> seine Expansionen (für die „traf gar nichts"-Prüfung). */
    private static final Map<String, Map<String, List<Identifier>>> PATTERNS = new LinkedHashMap<>();
    private static final Map<Identifier, List<String>> PENDING = new LinkedHashMap<>();
    private static final LinkedHashMap<String, List<Item>> CONTENTS = new LinkedHashMap<>();
    private static final Map<Identifier, List<CreativeTab>> TABS_OF_ITEM = new LinkedHashMap<>();
    private static final List<Item> ALL = new ArrayList<>();

    /** Gson-DTO der {@code creative_tabs.json}. */
    private static final class TabFile {
        List<TabDef> tabs;
        /** Dateiweite Achsen für die Platzhalter-Expansion, z.B. {@code color}, {@code wood}. */
        Map<String, List<String>> axes;
    }

    private static final class TabDef {
        String id;
        String icon;
        String type;
        /**
         * Anzeigereihenfolge. Ein Eintrag ist entweder ein String (Namespace optional,
         * {@code "oak_log"} == {@code "skyengine:oak_log"}, mit {@code {achse}}-Platzhaltern)
         * oder eine Gruppe {@code {"for": "<achse>", "items": [...]}}.
         */
        List<JsonElement> items;
    }

    /** Lädt die Tab-Definitionen einer Inhaltsquelle; die Datei ist optional. */
    public static void loadDefinitions(File file) {
        if (file == null || !file.isFile()) return;
        try (FileReader reader = new FileReader(file)) {
            TabFile parsed = GSON.fromJson(reader, TabFile.class);
            if (parsed == null || parsed.tabs == null) {
                LOGGER.error("creative_tabs.json ohne 'tabs': " + file.getPath());
                return;
            }
            Map<String, List<String>> axes = parsed.axes != null ? parsed.axes : Map.of();
            for (TabDef def : parsed.tabs) {
                if (def.id == null || def.id.isBlank()) {
                    LOGGER.error("Creative-Tab ohne 'id' in " + file.getPath());
                    continue;
                }
                Identifier icon = def.icon != null && !def.icon.isBlank()
                        ? Identifier.of(def.icon) : null;
                /* put statt add: eine spätere Quelle darf einen gleichnamigen Tab ersetzen. */
                TABS.put(def.id, new CreativeTab(def.id, icon, CreativeTab.Type.of(def.type)));

                if (def.items != null) {
                    List<Identifier> order = new ArrayList<>();
                    Set<Identifier> literals = new HashSet<>();
                    Map<String, List<Identifier>> patterns = new LinkedHashMap<>();
                    for (JsonElement entry : def.items) {
                        collect(entry, axes, Map.of(), order, literals, patterns);
                    }
                    ORDER.put(def.id, order);
                    LITERALS.put(def.id, literals);
                    PATTERNS.put(def.id, patterns);
                }
            }
        } catch (Exception e) {
            LOGGER.error("creative_tabs.json fehlerhaft: " + file.getPath(), e);
        }
    }

    /**
     * Wertet einen Eintrag der {@code items}-Liste aus und hängt seine IDs an {@code order}.
     *
     * <p>Ein String expandiert über jeden enthaltenen {@code {achse}}-Platzhalter (Achse als
     * INNERE Schleife: {@code "{color}_wool"} = alle 16 Wollen am Stück). Eine Gruppe
     * {@code {"for": "wood", "items": [...]}} legt die Achse dagegen als ÄUSSERE Schleife
     * darum — erst die komplette innere Liste für {@code oak}, dann für {@code spruce}, … Genau
     * das ergibt MCs „Familie zuerst, Variante danach".
     *
     * <p>{@code bindings} sind die von umgebenden Gruppen bereits festgelegten Achsenwerte. Als
     * Muster-Schlüssel dient der UNGEBUNDENE Text, damit die Treffer eines Musters über alle
     * Durchläufe der Gruppe zusammengezählt werden — sonst schlüge die Warnung unten bei jeder
     * echten Lücke an (es gibt z.B. keinen {@code stripped_oak_log}).
     */
    private static void collect(JsonElement entry, Map<String, List<String>> axes,
                                Map<String, String> bindings, List<Identifier> order,
                                Set<Identifier> literals, Map<String, List<Identifier>> patterns) {
        if (entry == null || entry.isJsonNull()) return;

        if (entry.isJsonPrimitive()) {
            String template = entry.getAsString();
            if (template.isBlank()) return;

            List<Identifier> ids = new ArrayList<>();
            expand(bind(template, bindings), axes, ids);
            order.addAll(ids);
            if (template.indexOf('{') < 0) {
                literals.addAll(ids);
            } else {
                patterns.computeIfAbsent(template, k -> new ArrayList<>()).addAll(ids);
            }
            return;
        }

        if (!entry.isJsonObject()) {
            LOGGER.error("Creative-Reihenfolge: Eintrag ist weder String noch Gruppe: " + entry);
            return;
        }
        JsonObject group = entry.getAsJsonObject();
        JsonElement axisName = group.get("for");
        JsonElement inner = group.get("items");
        if (axisName == null || !inner.isJsonArray()) {
            LOGGER.error("Creative-Reihenfolge: Gruppe braucht 'for' und 'items': " + entry);
            return;
        }
        List<String> values = axes.get(axisName.getAsString());
        if (values == null) {
            LOGGER.error("Creative-Reihenfolge: unbekannte Achse '" + axisName.getAsString() + "'");
            return;
        }
        JsonArray items = inner.getAsJsonArray();
        for (String value : values) {
            Map<String, String> next = new HashMap<>(bindings);
            next.put(axisName.getAsString(), value);
            for (JsonElement e : items) collect(e, axes, next, order, literals, patterns);
        }
    }

    /** Ersetzt die von umgebenden Gruppen festgelegten Platzhalter. */
    private static String bind(String template, Map<String, String> bindings) {
        String out = template;
        for (Map.Entry<String, String> e : bindings.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue());
        }
        return out;
    }

    /** Expandiert alle verbliebenen {@code {achse}}-Platzhalter rekursiv nach {@code out}. */
    private static void expand(String template, Map<String, List<String>> axes, List<Identifier> out) {
        int start = template.indexOf('{');
        int end = start < 0 ? -1 : template.indexOf('}', start);
        if (start < 0 || end < 0) {
            out.add(Identifier.of(template));
            return;
        }
        String axis = template.substring(start + 1, end);
        List<String> values = axes.get(axis);
        if (values == null) {
            LOGGER.error("Creative-Reihenfolge: unbekannte Achse '" + axis + "' in '" + template + "'");
            return;
        }
        String head = template.substring(0, start), tail = template.substring(end + 1);
        for (String value : values) expand(head + value + tail, axes, out);
    }

    /** Meldet die Tab-Zugehörigkeit eines Items an (leere/null-Liste = keine Zuordnung). */
    public static void assign(Identifier id, List<String> tabIds) {
        if (id == null || tabIds == null || tabIds.isEmpty()) return;
        PENDING.put(id, tabIds);
    }

    /** Bequemlichkeit für die in Java registrierten Items (Werkzeuge, Eimer, Essen). */
    public static void assign(Identifier id, String tabId) {
        assign(id, List.of(tabId));
    }

    /**
     * Liest das JSON-Feld {@code creative_tab}: ein String ODER eine Liste von Strings. Fehlt es
     * oder ist es kein passender Typ, kommt eine leere Liste zurück.
     */
    public static List<String> parse(JsonElement element) {
        if (element == null || element.isJsonNull()) return List.of();
        if (element.isJsonPrimitive()) return List.of(element.getAsString());
        if (!element.isJsonArray()) return List.of();

        List<String> out = new ArrayList<>();
        for (JsonElement e : element.getAsJsonArray()) {
            if (e.isJsonPrimitive()) out.add(e.getAsString());
        }
        return out;
    }

    /**
     * Baut die Tab-Inhalte aus den gemeldeten Zuordnungen. Muss laufen, wenn ALLE Items
     * registriert sind — also am Ende von {@link Items#bootstrap()}.
     */
    public static void build() {
        CONTENTS.clear();
        ALL.clear();
        for (String id : TABS.keySet()) CONTENTS.put(id, new ArrayList<>());

        List<String> untagged = new ArrayList<>();
        List<String> unknownTabs = new ArrayList<>();

        for (Item item : Registries.ITEM.values()) {
            if (Items.isCommandOnly(item.getId())) continue;
            ALL.add(item);
            boolean placed = false;
            for (String tabId : PENDING.getOrDefault(item.getId(), List.of())) {
                List<Item> target = CONTENTS.get(tabId);
                if (target == null) {
                    unknownTabs.add(item.getId() + " -> " + tabId);
                    continue;
                }
                target.add(item);
                placed = true;
            }
            if (placed) continue;

            untagged.add(item.getId().toString());
            List<Item> fallback = CONTENTS.get(FALLBACK);
            if (fallback != null) fallback.add(item);
        }

        List<String> unlisted = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        List<String> deadPatterns = new ArrayList<>();
        for (Map.Entry<String, List<Item>> entry : CONTENTS.entrySet()) {
            List<Identifier> order = ORDER.get(entry.getKey());
            if (order == null || order.isEmpty()) continue;

            Map<Identifier, Integer> rank = new HashMap<>();
            /* putIfAbsent: eine doppelt gelistete ID zählt an ihrer ERSTEN Stelle. */
            for (int i = 0; i < order.size(); i++) rank.putIfAbsent(order.get(i), i);

            /* List.sort ist stabil — nicht gelistete Items behalten untereinander die
               Registry-Reihenfolge und landen geschlossen am Ende, statt still zwischen
               die kuratierten zu rutschen. */
            List<Item> content = entry.getValue();
            content.sort(Comparator.comparingInt(
                    (Item item) -> rank.getOrDefault(item.getId(), Integer.MAX_VALUE)));

            Set<Identifier> present = new HashSet<>();
            for (Item item : content) {
                present.add(item.getId());
                if (!rank.containsKey(item.getId())) unlisted.add(entry.getKey() + "/" + item.getId());
            }
            /* Nur wörtlich geschriebene IDs einzeln prüfen. */
            for (Identifier id : LITERALS.getOrDefault(entry.getKey(), Set.of())) {
                if (!present.contains(id)) unmatched.add(entry.getKey() + "/" + id);
            }
            /* Muster dagegen als Ganzes: eine einzelne Lücke ist erlaubt (es gibt keinen
               stripped_oak_log), aber ein Muster, das GAR NICHTS trifft, ist ein Tippfehler. */
            for (Map.Entry<String, List<Identifier>> p : PATTERNS
                    .getOrDefault(entry.getKey(), Map.of()).entrySet()) {
                boolean any = false;
                for (Identifier id : p.getValue()) {
                    if (present.contains(id)) { any = true; break; }
                }
                if (!any) deadPatterns.add(entry.getKey() + "/\"" + p.getKey() + "\"");
            }
        }

        if (!unknownTabs.isEmpty()) {
            LOGGER.warning(unknownTabs.size() + " Zuordnungen auf unbekannte Creative-Tabs: "
                    + String.join(", ", unknownTabs));
        }
        if (!untagged.isEmpty()) {
            LOGGER.warning(untagged.size() + " Items ohne creative_tab (landen in '" + FALLBACK
                    + "'): " + String.join(", ", untagged));
        }
        if (!unlisted.isEmpty()) {
            LOGGER.warning(unlisted.size() + " Items nicht in der Reihenfolge der creative_tabs.json"
                    + " (haengen hinten an): " + String.join(", ", unlisted));
        }
        if (!unmatched.isEmpty()) {
            LOGGER.warning(unmatched.size() + " IDs in der creative_tabs.json ohne passendes Item"
                    + " im Reiter: " + String.join(", ", unmatched));
        }
        if (!deadPatterns.isEmpty()) {
            LOGGER.warning(deadPatterns.size() + " Muster in der creative_tabs.json ohne einen"
                    + " einzigen Treffer: " + String.join(", ", deadPatterns));
        }

        /* Rückwärts-Abfrage für den Tooltip aus CONTENTS (der Wahrheit) statt aus PENDING:
           PENDING kennt weder den misc-Fallback noch verworfene Zuordnungen auf unbekannte Tabs.
           Einmal vorberechnet, damit der Render-Pfad nichts durchsuchen muss. */
        TABS_OF_ITEM.clear();
        for (Map.Entry<String, List<Item>> e : CONTENTS.entrySet()) {
            CreativeTab tab = TABS.get(e.getKey());
            if (tab == null) continue;
            for (Item item : e.getValue()) {
                TABS_OF_ITEM.computeIfAbsent(item.getId(), k -> new ArrayList<>()).add(tab);
            }
        }

        /* Grundmenge des Such-Reiters: alle Reiter nacheinander in Anzeigereihenfolge statt in
           Registry-Reihenfolge (die ist alphabetisch nach Dateiname und wirkt zusammengewürfelt).
           Ein Item in mehreren Reitern erscheint nur an seiner ERSTEN Stelle. */
        ALL.clear();
        Set<Identifier> listed = new HashSet<>();
        for (List<Item> content : CONTENTS.values()) {
            for (Item item : content) {
                if (listed.add(item.getId())) ALL.add(item);
            }
        }
        /* Sicherheitsnetz: aus der Suche darf nichts verschwinden, auch wenn ein Item wider
           Erwarten in keinem Reiter landet. */
        for (Item item : Registries.ITEM.values()) {
            if (Items.isCommandOnly(item.getId())) continue;
            if (listed.add(item.getId())) ALL.add(item);
        }

        StringBuilder counts = new StringBuilder();
        for (Map.Entry<String, List<Item>> e : CONTENTS.entrySet()) {
            if (!counts.isEmpty()) counts.append(", ");
            counts.append(e.getKey()).append('=').append(e.getValue().size());
        }
        LOGGER.info(TABS.size() + " Creative-Tabs, " + ALL.size() + " Items [" + counts + "]");
    }

    /** Alle Tabs in Anzeigereihenfolge. */
    public static List<CreativeTab> tabs() {
        return List.copyOf(TABS.values());
    }

    /** Inhalt eines Tabs in Registry-Reihenfolge (leer bei unbekannter ID). */
    public static List<Item> items(String tabId) {
        return Collections.unmodifiableList(CONTENTS.getOrDefault(tabId, List.of()));
    }

    /**
     * Reiter, in denen dieses Item steckt — in Anzeigereihenfolge, leer bei unbekannter ID.
     * Quelle für die Reiter-Zeilen im Item-Tooltip des Creative-Inventars.
     */
    public static List<CreativeTab> tabsOf(Identifier id) {
        return Collections.unmodifiableList(TABS_OF_ITEM.getOrDefault(id, List.of()));
    }

    /**
     * Alle registrierten Items — Grundmenge des Such-Tabs. Reihenfolge: die Reiter nacheinander
     * in Anzeigereihenfolge, jedes Item genau einmal (an seiner ersten Stelle).
     */
    public static List<Item> all() {
        return Collections.unmodifiableList(ALL);
    }

    private CreativeTabs() {}
}
