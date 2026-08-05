package de.skyengine.game.world.item;

import de.skyengine.game.world.block.Identifier;

/**
 * Feuerzeug: zündet TNT per Rechtsklick und verliert dabei einen Punkt Haltbarkeit.
 *
 * <p>In Minecraft ist es vor allem ein Feuer-Anzünder; SkyEngine hat weder Feuer noch Kerzen
 * noch Lagerfeuer, deshalb bleibt genau der eine Zündweg übrig, der hier existiert. Die
 * eigentliche Interaktion sitzt — wie beim Eimer — im {@code GameContainer}: {@link Item} hat
 * keinen Rechtsklick-Hook, und einen für einen einzigen Nutzer einzuführen wäre der teurere Weg.
 *
 * <p>Eigene Klasse statt {@link SimpleItem} allein wegen {@link #DURABILITY}: der Verschleiß
 * braucht eine Obergrenze, und die gehört zum Item, nicht in den Aufrufer. Werkzeuge holen sie
 * aus ihrem {@code ToolTier}, dem gehört das Feuerzeug aber nicht an.
 */
public final class FlintAndSteelItem extends Item {

    /** MC-Wert (64 Nutzungen). */
    public static final int DURABILITY = 64;

    private final String iconTexture;

    public FlintAndSteelItem(Identifier id, String iconTexture) {
        super(id, 1);
        this.iconTexture = iconTexture;
    }

    @Override
    public String getIconTexture() {
        return this.iconTexture;
    }
}
