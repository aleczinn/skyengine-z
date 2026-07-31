package de.skyengine.game.world.item;

import de.skyengine.game.world.block.Identifier;

/**
 * Ein Reiter des Creative-Inventars. Definiert wird er in {@code game/creative_tabs.json}, seinen
 * Inhalt bekommt er über das Feld {@code creative_tab} der einzelnen Block-/Item-JSONs
 * (siehe {@link CreativeTabs}).
 *
 * @param id    Tab-Kennung, zugleich i18n-Key-Bestandteil ({@code creative.tab.<id>})
 * @param icon  Item, dessen Icon auf dem Reiter sitzt (bei {@link Type#SEARCH} ungenutzt)
 * @param type  Sonderrolle des Tabs
 */
public record CreativeTab(String id, Identifier icon, Type type) {

    public enum Type {
        /** Normaler Tab: zeigt die ihm zugeordneten Items. */
        ITEMS,
        /** Suche: Textfeld über allen Items, statt einer festen Liste. */
        SEARCH,
        /** Survival-Inventar: zeigt das Spielerinventar statt einer Item-Liste. */
        INVENTORY;

        static Type of(String name) {
            if (name == null) return ITEMS;
            return switch (name.toLowerCase()) {
                case "search" -> SEARCH;
                case "inventory" -> INVENTORY;
                default -> ITEMS;
            };
        }
    }

    /** i18n-Key des Reiter-Namens (Tooltip und Überschrift). */
    public String translationKey() {
        return "creative.tab." + this.id;
    }
}
