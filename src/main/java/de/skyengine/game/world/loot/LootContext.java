package de.skyengine.game.world.loot;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.item.ItemStack;

import java.util.random.RandomGenerator;

/** Alle zur Auswertung einer Block-Loot-Tabelle erforderlichen Laufzeitdaten. */
public record LootContext(World world, int x, int y, int z, BlockState state, ItemStack tool,
                          Cause cause, float explosionRadius, RandomGenerator random) {

    public enum Cause { PLAYER, EXPLOSION, PISTON, SUPPORT, FLUID }

    public boolean hasExplosionDecay() {
        return cause == Cause.EXPLOSION && explosionRadius > 0.0F;
    }

    public LootContext withRandom(RandomGenerator replacement) {
        return new LootContext(world, x, y, z, state, tool, cause, explosionRadius, replacement);
    }
}
