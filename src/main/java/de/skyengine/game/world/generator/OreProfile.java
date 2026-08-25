package de.skyengine.game.world.generator;

import de.skyengine.game.world.block.Blocks;

import java.util.List;

/** Deterministische Erzverteilung fuer einen 32x32-Quellchunk. */
public record OreProfile(List<Distribution> distributions) {

    public OreProfile {
        distributions = List.copyOf(distributions);
    }

    public record Distribution(String key, int block, int attempts, int veinSize,
                               int minY, int maxY, boolean extremeHillsOnly) {}

    public static OreProfile normal() {
        return new OreProfile(List.of(
                new Distribution("coal", Blocks.COAL_ORE, 32, 16, 16, 192, false),
                new Distribution("iron", Blocks.IRON_ORE, 32, 9, 1, 128, false),
                new Distribution("copper", Blocks.COPPER_ORE, 24, 10, 32, 128, false),
                new Distribution("gold", Blocks.GOLD_ORE, 8, 8, 1, 64, false),
                new Distribution("redstone", Blocks.REDSTONE_ORE, 16, 8, 1, 32, false),
                new Distribution("lapis", Blocks.LAPIS_ORE, 4, 7, 1, 64, false),
                new Distribution("diamond", Blocks.DIAMOND_ORE, 4, 7, 1, 24, false),
                new Distribution("emerald", Blocks.EMERALD_ORE, 4, 3, 32, 192, true)
        ));
    }

    public static OreProfile rich() {
        return new OreProfile(normal().distributions.stream()
                .map(d -> new Distribution(d.key, d.block, d.attempts * 4,
                        (d.veinSize * 3 + 1) / 2, d.minY, d.maxY, false))
                .toList());
    }
}
