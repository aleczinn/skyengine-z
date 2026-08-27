package de.skyengine.mcimport.mapping;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.registry.Registries;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.BlockStateCodec;
import de.skyengine.mcimport.mca.McBlockState;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Übersetzt Minecraft-BlockStates in SkyEngine-Runtime-State-IDs. Auflösung dreistufig:
 * <ol>
 *   <li><b>Alias</b> aus {@code block_map.json} (z.B. {@code minecraft:deepslate} →
 *       {@code skyengine:stone}, Umbenennungen wie {@code grass} → {@code short_grass}),</li>
 *   <li><b>Identität</b>: {@code skyengine:<pfad>} falls registriert — deckt die große
 *       Mehrheit ab und wächst automatisch mit neuen Engine-Blöcken,</li>
 *   <li><b>unbekannt</b> → Luft; der {@code McMappingReport} macht das vollständig sichtbar.</li>
 * </ol>
 *
 * <p>Property-Behandlung: unbekannte Property-NAMEN (waterlogged, snowy, distance, lit, …)
 * verwirft {@link BlockStateCodec#decode} selbst; die half-WERTE {@code lower/upper}
 * (MC-Türen, Doppelpflanzen) werden auf {@code bottom/top} remappt — der Codec würde
 * falsche Werte LAUTLOS auf den Default fallen lassen. Fluide werden gesondert übersetzt
 * (MC packt Fallen als level ≥ 8; die Engine hat dafür die FALLING-Property).
 *
 * <p>Engine-Registry muss gebootstrapped sein ({@code Blocks.bootstrap}) — Aufrufer-Pflicht.
 * Der Cache ist threadsicher (M6 konvertiert später parallel).
 */
public final class BlockMapper {

    private static final int AIR = 0;

    private final Map<String, String> aliases;
    /* Cache pro kanonischem State-String; null-Ergebnisse (unbekannt) als AIR-Sentinel. */
    private final Map<String, Integer> cache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> known = new ConcurrentHashMap<>();

    public BlockMapper(Map<String, String> aliases) {
        this.aliases = Map.copyOf(aliases);
    }

    /** Lädt die Alias-Tabelle {@code /block_map.json} vom mcimport-Klassenpfad. */
    public static BlockMapper loadDefault() throws IOException {
        try (InputStream in = BlockMapper.class.getResourceAsStream("/block_map.json")) {
            if (in == null) throw new IOException("block_map.json nicht im Klassenpfad gefunden");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                Map<String, String> aliases = new Gson().fromJson(reader,
                        new TypeToken<Map<String, String>>() {}.getType());
                return new BlockMapper(aliases);
            }
        }
    }

    /** Runtime-State-ID (Luft bei unbekanntem Block — s. {@link #isKnown}). */
    public int map(McBlockState state) {
        return this.cache.computeIfAbsent(state.toString(), key -> resolve(state));
    }

    /** true, wenn der MC-Block auf einen Engine-Block abbildet (auch bewusst auf Luft). */
    public boolean isKnown(McBlockState state) {
        map(state); // füllt known mit
        return this.known.getOrDefault(state.toString(), false);
    }

    private int resolve(McBlockState mc) {
        String name = mc.name();
        if (name.equals("minecraft:air")) {
            this.known.put(mc.toString(), true);
            return AIR;
        }

        /* Stufe 1: Alias; Stufe 2: Identität. */
        String target = this.aliases.get(name);
        if (target == null) {
            String path = name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name;
            String candidate = "skyengine:" + path;
            if (Registries.BLOCK.contains(Identifier.of(candidate))) target = candidate;
        }
        if (target == null) {
            this.known.put(mc.toString(), false);
            return AIR; // Stufe 3: unbekannt
        }
        this.known.put(mc.toString(), true);
        if (Identifier.of(target).equals(Identifier.of("air"))) return AIR;

        Block block = Registries.BLOCK.get(Identifier.of(target));
        if (block == null) {
            /* Alias zeigt ins Leere — Konfigurationsfehler, sichtbar machen. */
            this.known.put(mc.toString(), false);
            return AIR;
        }

        String encoded = block.isFluid() ? encodeFluid(target, mc) : encodeState(target, mc);
        BlockState state = BlockStateCodec.decode(encoded);
        return state == null ? AIR : state.getId();
    }

    /* MC-Fluid-Level: 0 = Quelle, 1..7 fließend, >= 8 = fallende Säule (Engine: FALLING). */
    private static String encodeFluid(String target, McBlockState mc) {
        int level = parseIntProperty(mc, "level");
        if (level >= 8) return target + "[falling=true,level=0]";
        return target + "[falling=false,level=" + level + "]";
    }

    private static String encodeState(String target, McBlockState mc) {
        if (mc.properties().isEmpty()) return target;
        StringBuilder sb = new StringBuilder(target).append('[');
        boolean first = true;
        for (Map.Entry<String, String> entry : mc.properties().entrySet()) {
            if (!first) sb.append(',');
            first = false;
            String value = entry.getValue();
            /* MC-half lower/upper (Türen, Doppelpflanzen) -> Engine bottom/top. Treppen
               nutzen in beiden Welten top/bottom und bleiben unberührt. */
            if (entry.getKey().equals("half")) {
                value = switch (value) {
                    case "lower" -> "bottom";
                    case "upper" -> "top";
                    default -> value;
                };
            }
            sb.append(entry.getKey()).append('=').append(value);
        }
        return sb.append(']').toString();
    }

    private static int parseIntProperty(McBlockState mc, String key) {
        try {
            return Integer.parseInt(mc.properties().getOrDefault(key, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
