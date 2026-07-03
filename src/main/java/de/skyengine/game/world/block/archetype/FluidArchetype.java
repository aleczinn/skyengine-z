package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.behavior.FluidBehavior;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.shape.BlockShape;
import de.skyengine.game.world.block.shape.ShapeProvider;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;

/**
 * Fluid (Wasser/Lava): LEVEL-Property (0=Quelle, 1..7 fließend) + FALLING (fallende Säule),
 * nie opak, keine Kollision. Die Geometrie kommt NICHT aus einem Modell, sondern dynamisch
 * aus dem {@code ChunkMesher} (nachbarabhängige Oberfläche). Fluss-Parameter und Texturlayer
 * stecken in {@link FluidInfo}.
 */
public final class FluidArchetype implements Archetype {

    /* Keine Kollision UND kein Umriss: der normale Raycast (Abbau/Platzieren) ignoriert Fluids
       und trifft Blöcke dahinter. Der Eimer nutzt einen fluid-bewussten Raycast (includeFluids). */
    private static final ShapeProvider NO_COLLISION = new ShapeProvider() {
        @Override public BlockShape collision(BlockState state) { return BlockShape.EMPTY; }
        @Override public BlockShape outline(BlockState state) { return BlockShape.EMPTY; }
    };

    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        String still = def.textures.getOrDefault("still", "game/textures/block/water_still.png");
        String flow = def.textures.getOrDefault("flow", "game/textures/block/water_flow.png");
        boolean lava = def.id != null && def.id.contains("lava");
        int spread = def.fluid_spread != null ? def.fluid_spread : 7;
        int dropOff = def.drop_off != null ? def.drop_off : (lava ? 2 : 1);
        int tick = def.fluid_tick != null ? def.fluid_tick : (lava ? 30 : 5);

        cfg.property(Properties.LEVEL)
                .property(Properties.FALLING)
                .shapes(NO_COLLISION)
                .opaque(state -> false)
                .behavior(new FluidBehavior())
                .fluid(new FluidInfo(still, flow, spread, dropOff, tick, lava));
    }
}
