package de.skyengine.graphics.gui.text;

import de.skyengine.graphics.color.Color4;
import de.skyengine.graphics.gui.font.FontStyle;

/**
 * Ein zusammenhängendes Textstück mit einheitlichem Stil — das Ergebnis des
 * {@link RichText}-Parsers.
 *
 * @param color null = Farbe des Aufrufers übernehmen (Basisfarbe)
 */
public record Span(String text, FontStyle style, Color4 color) {}
