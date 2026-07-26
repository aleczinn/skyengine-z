package de.skyengine.mcimport.mca;

import java.util.Map;

/**
 * Neutraler Minecraft-BlockState: {@code minecraft:<name>} + Property-Strings, wie sie
 * in der Section-Palette stehen. Bewusst OHNE Engine-Wissen — die Übersetzung nach
 * SkyEngine passiert erst im Mapping (M5).
 */
public record McBlockState(String name, Map<String, String> properties) {

    /** Kanonische Darstellung {@code name[prop=val,...]} (Properties in Map-Reihenfolge). */
    @Override
    public String toString() {
        if (this.properties.isEmpty()) return this.name;
        StringBuilder sb = new StringBuilder(this.name).append('[');
        boolean first = true;
        for (Map.Entry<String, String> entry : this.properties.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.append(']').toString();
    }
}
