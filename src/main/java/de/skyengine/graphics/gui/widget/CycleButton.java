package de.skyengine.graphics.gui.widget;

import de.skyengine.core.i18n.I18n;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Button, der beim Klick durch feste Werte zykliert (Booleans, Enums, MSAA-Stufen, ...).
 * Anzeige: „Name: Wert".
 */
public final class CycleButton<T> extends Button {

    private final String name;
    private final T[] values;
    private final Function<T, String> labelOf;
    private final Consumer<T> onChange;
    private int index;
    /** Optionaler Tooltip je Wert (Pendant zu {@link #labelOf}). */
    private Function<T, String> tooltipOf;

    public CycleButton(String name, float w, float h, T[] values, T current,
                       Function<T, String> labelOf, Consumer<T> onChange) {
        super("", w, h, null);
        this.name = name;
        this.values = values;
        this.labelOf = labelOf;
        this.onChange = onChange;
        this.index = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) {
                this.index = i;
                break;
            }
        }
        this.updateLabel();
    }

    private void updateLabel() {
        this.setLabel(this.name + ": " + this.labelOf.apply(this.values[this.index]));
    }

    /**
     * Tooltip je Wert (Markup erlaubt) — chainbar, damit der ohnehin lange Konstruktor nicht
     * noch einen Parameter bekommt.
     */
    public CycleButton<T> tooltipOf(Function<T, String> tooltipOf) {
        this.tooltipOf = tooltipOf;
        return this;
    }

    @Override
    public java.util.List<de.skyengine.graphics.gui.text.RichText> tooltip() {
        if (this.tooltipOf == null) return super.tooltip();
        return de.skyengine.graphics.gui.text.RichText.parseLines(
                this.tooltipOf.apply(this.values[this.index]));
    }

    @Override
    protected void onPress() {
        this.index = (this.index + 1) % this.values.length;
        this.updateLabel();
        if (this.onChange != null) this.onChange.accept(this.values[this.index]);
    }

    /** Fertiger AN/AUS-Cycle für boolesche Einstellungen. */
    public static CycleButton<Boolean> onOff(String name, float w, float h, boolean current, Consumer<Boolean> onChange) {
        return new CycleButton<>(name, w, h, new Boolean[]{Boolean.TRUE, Boolean.FALSE}, current,
                v -> I18n.tr(v ? "gui.on" : "gui.off"), onChange);
    }
}
