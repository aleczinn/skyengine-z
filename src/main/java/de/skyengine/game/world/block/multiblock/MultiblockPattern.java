package de.skyengine.game.world.block.multiblock;

import de.skyengine.game.world.Dimension;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Deklaratives Multiblock-Muster (z.B. große Maschinen). Aufgebaut aus Y-Layern von Zeilen
 * (Z) mit Zeichen (X); ein Key bildet Zeichen → Block-Prädikat ab. {@code ' '}/{@code '?'}
 * sind Platzhalter (egal). Multiblocks bestehen so aus normalen BlockStates.
 */
public final class MultiblockPattern {

    private final String[][] layers;   // [y][z] = Zeile aus X-Zeichen
    private final Map<Character, Predicate<BlockState>> key;
    private final int sx, sy, sz;

    private MultiblockPattern(String[][] layers, Map<Character, Predicate<BlockState>> key) {
        this.layers = layers;
        this.key = key;
        this.sy = layers.length;
        this.sz = sy > 0 ? layers[0].length : 0;
        this.sx = (sy > 0 && sz > 0) ? layers[0][0].length() : 0;
    }

    /** Prüft das Muster mit Ursprung an (ox,oy,oz) = unterste, „erste" Ecke. */
    public boolean matches(Dimension world, int ox, int oy, int oz) {
        for (int y = 0; y < this.sy; y++) {
            for (int z = 0; z < this.sz; z++) {
                String row = this.layers[y][z];
                for (int x = 0; x < this.sx; x++) {
                    char c = row.charAt(x);
                    if (c == ' ' || c == '?') continue;
                    Predicate<BlockState> predicate = this.key.get(c);
                    if (predicate == null) return false;
                    BlockState state = Blocks.getState(world.getBlock(ox + x, oy + y, oz + z));
                    if (!predicate.test(state)) return false;
                }
            }
        }
        return true;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final java.util.List<String[]> layers = new java.util.ArrayList<>();
        private final Map<Character, Predicate<BlockState>> key = new HashMap<>();

        /** Ein Y-Layer: Zeilen in +Z-Reihenfolge, Zeichen in +X-Reihenfolge. */
        public Builder layer(String... rows) { this.layers.add(rows); return this; }

        public Builder where(char c, Predicate<BlockState> predicate) { this.key.put(c, predicate); return this; }

        public MultiblockPattern build() {
            return new MultiblockPattern(this.layers.toArray(new String[0][]), this.key);
        }
    }
}
