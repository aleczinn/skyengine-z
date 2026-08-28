package de.skyengine.game.world.item;

import de.skyengine.core.i18n.I18n;
import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.entity.Capability;
import de.skyengine.graphics.gui.text.RichText;
import de.skyengine.graphics.gui.text.TextColors;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Ein registrierbarer Gegenstand. Basisklasse - Blöcke bekommen automatisch ein {@link BlockItem}
 * mit derselben {@link Identifier}. Eigene Items (Werkzeuge, Materialien) erben hiervon.
 */
public class Item {

    public static final int DEFAULT_MAX_STACK = 64;

    private final Identifier id;
    private final int maxStackSize;
    private Item craftingRemainder;

    public Item(Identifier id) {
        this(id, DEFAULT_MAX_STACK);
    }

    public Item(Identifier id, int maxStackSize) {
        this.id = id;
        this.maxStackSize = maxStackSize;
    }

    public Identifier getId() {
        return id;
    }

    public int getMaxStackSize() {
        return maxStackSize;
    }

    /** Item, das beim Verbrauch als Zutat zurueckbleibt, etwa der leere Eimer. */
    public Item getCraftingRemainder() {
        return this.craftingRemainder;
    }

    void setCraftingRemainder(Item craftingRemainder) {
        this.craftingRemainder = craftingRemainder;
    }

    /** i18n-Key des Anzeigenamens ({@code item.<namespace>.<pfad>}; BlockItems überschreiben auf {@code block.}). */
    public String translationKey() {
        return "item." + this.id.namespace() + "." + this.id.path();
    }

    /** Formatierter, übersetzter Anzeigename für Tooltip und HUD. */
    public RichText getDisplayNameText() {
        return RichText.parse(I18n.tr(this.translationKey()));
    }

    /** Sichtbarer Anzeigename ohne Rich-Text-Codes, etwa für Suche und Logs. */
    public String getDisplayName() {
        return RichText.strip(I18n.tr(this.translationKey()));
    }

    /** Konventioneller i18n-Key der optionalen Beschreibung. */
    public String descriptionTranslationKey() {
        return "description." + this.translationKey();
    }

    /**
     * Ergänzt die Zeilen unter dem Namen. Der Default liest die optionale lokalisierte
     * Beschreibung; Unterklassen dürfen anschließend aktuelle Stack-/Weltwerte anfügen.
     */
    public void appendTooltip(ItemStack stack, TooltipContext context, List<RichText> lines) {
        String key = this.descriptionTranslationKey();
        if (!I18n.has(key)) return;
        Map<String, String> variables = new LinkedHashMap<>();
        this.appendTooltipVariables(stack, context, variables);
        String description = TooltipTemplate.resolve(I18n.tr(key), variables);
        lines.addAll(RichText.parseLines(description, TextColors.GRAY));
    }

    /** Liefert benannte Werte für Platzhalter in der Beschreibung, etwa {@code %energy%}. */
    protected void appendTooltipVariables(ItemStack stack, TooltipContext context,
                                          Map<String, String> variables) {
    }

    /**
     * Texturpfad für ein flaches 2D-Icon (Nicht-Block-Items wie Eimer). {@code null} bei Block-Items,
     * die als 3D-Würfel/aus {@code icon_flat} gerendert werden. Der Pfad muss vor dem TextureArray-Bau
     * über {@code BlockTextures.layerOf} registriert sein.
     */
    public String getIconTexture() {
        return null;
    }

    /**
     * Der Block, den ein Rechtsklick mit diesem Item platziert, oder {@code null} (nicht
     * platzierbar). {@code BlockItem} liefert seinen Block; ein Material-Item kann über das
     * JSON-Feld {@code places_block} einen fremden Block platzieren (Redstone-Staub) —
     * der Platzierungspfad in {@code GameContainer} fragt nur noch diese Methode.
     */
    public Block getPlacedBlock() {
        return null;
    }

    /** Stack-backed capability exposed by this item (for example an RF container). */
    public <C> Optional<C> getCapability(Capability<C> capability, ItemStack stack) {
        return Optional.empty();
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
