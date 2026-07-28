package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.behavior.PillarPlacementBehavior;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.state.Properties;

/** Säule/Stamm: AXIS-Property aus der Platzierungsfläche; voller Würfel (Optik via Blockstate). */
public final class PillarArchetype implements Archetype {

    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        /* AXIS=Y als Default: ohne das wäre es der erste Enum-Wert X, also eine LIEGENDE Säule —
           sichtbar bei Icons, Items und überall, wo ein State nicht über getPlacementState läuft.
           Auch gespeicherte Blöcke ohne axis-Property landen hier (BlockStateCodec.decode). */
        cfg.property(Properties.AXIS)
                .defaultValue(Properties.AXIS, Direction.Axis.Y)
                .behavior(new PillarPlacementBehavior());
    }
}
