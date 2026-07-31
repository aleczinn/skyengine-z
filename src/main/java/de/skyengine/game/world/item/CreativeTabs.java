package de.skyengine.game.world.item;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.registry.Registries;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reiter des Creative-Inventars und ihr Inhalt. Die Tabs selbst stehen in
 * {@code game/creative_tabs.json} (Reihenfolge = Anzeigereihenfolge), die Zuordnung der Items
 * kommt aus dem Feld {@code creative_tab} der jeweiligen Block-/Item-JSON bzw. bei den in Java
 * registrierten Items (Werkzeuge, Eimer, Essen) aus {@link Items#bootstrap()}.
 *
 * <p><b>Reihenfolge-Invariante:</b> {@link #assign} puffert nur {@code Identifier -> Tab-IDs};
 * die eigentlichen Listen entstehen erst in {@link #build()} durch einen Lauf über
 * {@code Registries.ITEM.values()}. Damit ist „Reihenfolge im Tab = Registry-Reihenfolge"
 * deterministisch, ohne dass irgendwo sortiert werden müsste — und die Zuordnung darf schon
 * gemeldet werden, bevor das zugehörige Item überhaupt existiert (Blöcke melden sich beim
 * Laden, ihr BlockItem entsteht erst später mit derselben {@link Identifier}).
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
    private static final Map<Identifier, List<String>> PENDING = new LinkedHashMap<>();
    private static final LinkedHashMap<String, List<Item>> CONTENTS = new LinkedHashMap<>();
    private static final List<Item> ALL = new ArrayList<>();

    /** Gson-DTO der {@code creative_tabs.json}. */
    private static final class TabFile {
        List<TabDef> tabs;
    }

    private static final class TabDef {
        String id;
        String icon;
        String type;
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
            for (TabDef def : parsed.tabs) {
                if (def.id == null || def.id.isBlank()) {
                    LOGGER.error("Creative-Tab ohne 'id' in " + file.getPath());
                    continue;
                }
                Identifier icon = def.icon != null && !def.icon.isBlank()
                        ? Identifier.of(def.icon) : null;
                /* put statt add: eine spätere Quelle darf einen gleichnamigen Tab ersetzen. */
                TABS.put(def.id, new CreativeTab(def.id, icon, CreativeTab.Type.of(def.type)));
            }
        } catch (Exception e) {
            LOGGER.error("creative_tabs.json fehlerhaft: " + file.getPath(), e);
        }
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

        if (!unknownTabs.isEmpty()) {
            LOGGER.warning(unknownTabs.size() + " Zuordnungen auf unbekannte Creative-Tabs: "
                    + String.join(", ", unknownTabs));
        }
        if (!untagged.isEmpty()) {
            LOGGER.warning(untagged.size() + " Items ohne creative_tab (landen in '" + FALLBACK
                    + "'): " + String.join(", ", untagged));
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

    /** Alle registrierten Items in Registry-Reihenfolge — Grundmenge des Such-Tabs. */
    public static List<Item> all() {
        return Collections.unmodifiableList(ALL);
    }

    private CreativeTabs() {}
}
