package de.skyengine.game.world.loot;

/** Bereits validierte und aufgelöste Loot-Tabelle ohne JSON-Arbeit im Hot Path. */
@FunctionalInterface
public interface LootTable {
    void generate(LootContext context, LootSink sink);
}
