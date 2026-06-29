package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.archetype.FluidInfo;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;

/**
 * Fluss-Verhalten für Wasser/Lava (Minecraft-artig, vereinfacht). Arbeitet über geplante Ticks:
 * <ul>
 *   <li>Quelle (LEVEL 0) bleibt bestehen und speist Nachbarn.</li>
 *   <li>Fließendes Fluid bezieht seinen Stand aus dem höchsten horizontalen Nachbarn (sonst trocknet es).</li>
 *   <li>Fällt nach unten (FALLING) wenn darunter Luft ist; sonst breitet es sich horizontal aus.</li>
 *   <li>Zwei benachbarte Wasserquellen erzeugen dazwischen eine neue Quelle (unendliches Wasser).</li>
 *   <li>Wasser+Lava-Kontakt: Lavaquelle→Obsidian/Stein, fließende Lava→Cobblestone.</li>
 * </ul>
 * Parameter (Reichweite, Tick-Takt, Lava-Flag) kommen aus {@link FluidInfo}.
 */
public final class FluidBehavior implements BlockBehavior {

    @Override
    public void onPlaced(World world, int x, int y, int z, BlockState state) {
        world.scheduleTick(x, y, z, delay(state));
    }

    @Override
    public BlockState onNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        world.scheduleTick(x, y, z, delay(state));
        return state;
    }

    @Override
    public void scheduledTick(World world, int x, int y, int z, BlockState state) {
        Block fluid = state.getBlock();
        FluidInfo info = fluid.getFluidInfo();
        if (info == null) return;

        /* 1) Wasser/Lava-Reaktion hat Vorrang (kann diesen Block ersetzen). */
        if (reaction(world, x, y, z, state, info)) return;

        boolean source = isSource(state);

        /* 2) Eigenen Stand prüfen (Quelle bleibt; fließendes Fluid aus Nachbarn ableiten). */
        if (!source) {
            boolean fedAbove = isSameFluid(world.getBlock(x, y + 1, z), fluid);
            if (fedAbove) {
                if (!state.get(Properties.FALLING)) {
                    world.setBlock(x, y, z, fluidState(fluid, 1, true));
                    return;
                }
            } else {
                int best = Integer.MAX_VALUE; // kleinster Levelwert (höchstes Fluid) unter den Stützen
                for (Direction d : Direction.horizontal()) {
                    short ns = world.getBlock(x + d.offsetX(), y, z + d.offsetZ());
                    if (!isSameFluid(ns, fluid)) continue;
                    BlockState neighbor = Blocks.getState(ns);
                    if (neighbor.get(Properties.FALLING)) continue;     // fallende Säule stützt nicht horizontal
                    best = Math.min(best, isSource(neighbor) ? 0 : neighbor.get(Properties.LEVEL));
                }

                /* Unendliche Wasserquelle: ≥2 horizontale Quell-Nachbarn -> selbst Quelle. */
                if (!info.lava && countSourceNeighbors(world, x, y, z, fluid) >= 2) {
                    if (!source) world.setBlock(x, y, z, fluidState(fluid, 0, false));
                    return;
                }

                if (best == Integer.MAX_VALUE) {           // keine Stütze -> trocknen
                    world.setBlock(x, y, z, Blocks.AIR);
                    return;
                }
                int newLevel = best + 1;
                if (newLevel > info.spread) {              // außer Reichweite -> trocknen
                    world.setBlock(x, y, z, Blocks.AIR);
                    return;
                }
                if (newLevel != state.get(Properties.LEVEL) || state.get(Properties.FALLING)) {
                    world.setBlock(x, y, z, fluidState(fluid, newLevel, false));
                    return;                                // Folge-Tick durch das Nachbar-Update
                }
            }
        }

        /* 3) Ausbreitung: erst nach unten; ist unten dicht, dann horizontal. */
        short below = world.getBlock(x, y - 1, z);
        if (below == Blocks.AIR) {
            world.setBlock(x, y - 1, z, fluidState(fluid, 1, true));
            world.scheduleTick(x, y - 1, z, info.tickDelay);
            return;
        }
        if (isSameFluid(below, fluid)) return; // fällt bereits in vorhandenes Fluid

        int eff = isSource(state) ? 0 : state.get(Properties.LEVEL);
        int spreadLevel = eff + 1;
        if (spreadLevel > info.spread) return;
        for (Direction d : Direction.horizontal()) {
            int nx = x + d.offsetX(), nz = z + d.offsetZ();
            if (world.getBlock(nx, y, nz) == Blocks.AIR) {
                world.setBlock(nx, y, nz, fluidState(fluid, spreadLevel, false));
                world.scheduleTick(nx, y, nz, info.tickDelay);
            }
        }
    }

    /** Wasser+Lava-Kontakt. Gibt true zurück, wenn dieser Block dabei ersetzt wurde. */
    private boolean reaction(World world, int x, int y, int z, BlockState state, FluidInfo info) {
        if (info.lava) {
            boolean waterAdjacent = false, waterSourceAdjacent = false;
            for (Direction d : Direction.values()) {
                short ns = world.getBlock(x + d.offsetX(), y + d.offsetY(), z + d.offsetZ());
                BlockState neighbor = Blocks.getState(ns);
                if (isWater(neighbor)) {
                    waterAdjacent = true;
                    if (isSource(neighbor)) waterSourceAdjacent = true;
                }
            }
            if (waterAdjacent) {
                short result = isSource(state)
                        ? (waterSourceAdjacent ? Blocks.OBSIDIAN : Blocks.STONE)
                        : Blocks.COBBLESTONE;
                world.setBlock(x, y, z, result);
                return true;
            }
            return false;
        }

        /* Wasser selbst bleibt; benachbarte Lava prompt reagieren lassen. */
        for (Direction d : Direction.values()) {
            int nx = x + d.offsetX(), ny = y + d.offsetY(), nz = z + d.offsetZ();
            if (isLava(Blocks.getState(world.getBlock(nx, ny, nz)))) {
                world.scheduleTick(nx, ny, nz, 1);
            }
        }
        return false;
    }

    private int countSourceNeighbors(World world, int x, int y, int z, Block fluid) {
        int count = 0;
        for (Direction d : Direction.horizontal()) {
            short ns = world.getBlock(x + d.offsetX(), y, z + d.offsetZ());
            if (isSameFluid(ns, fluid) && isSource(Blocks.getState(ns))) count++;
        }
        return count;
    }

    private static int delay(BlockState state) {
        FluidInfo info = state.getBlock().getFluidInfo();
        return info != null ? info.tickDelay : 5;
    }

    private static short fluidState(Block fluid, int level, boolean falling) {
        return fluid.getDefaultState()
                .with(Properties.LEVEL, level)
                .with(Properties.FALLING, falling)
                .getId();
    }

    private static boolean isSource(BlockState s) {
        return s.isFluid() && !s.get(Properties.FALLING) && s.get(Properties.LEVEL) == 0;
    }

    private static boolean isSameFluid(short id, Block fluid) {
        BlockState s = Blocks.getState(id);
        return s.isFluid() && s.getBlock() == fluid;
    }

    private static boolean isWater(BlockState s) {
        FluidInfo info = s.getBlock().getFluidInfo();
        return info != null && !info.lava;
    }

    private static boolean isLava(BlockState s) {
        FluidInfo info = s.getBlock().getFluidInfo();
        return info != null && info.lava;
    }
}
