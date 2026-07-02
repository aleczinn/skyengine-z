package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.archetype.FluidInfo;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;

/**
 * Fluss-Verhalten für Wasser/Lava (Minecraft-artig, vereinfacht). Arbeitet über geplante Ticks:
 * <ul>
 *   <li>Quelle (LEVEL 0) bleibt bestehen und speist Nachbarn.</li>
 *   <li>Fließendes Fluid bezieht seinen Stand aus dem höchsten horizontalen Nachbarn (sonst trocknet es).</li>
 *   <li>Fällt nach unten (FALLING) wenn darunter Luft ist; sonst breitet es sich horizontal aus.</li>
 *   <li>Zwei benachbarte Wasserquellen erzeugen dazwischen eine neue Quelle (unendliches Wasser).</li>
 *   <li>Wasser+Lava-Kontakt: Lavaquelle→Obsidian, fließende Lava→Cobblestone, Lava fällt in Wasser→Stein.</li>
 *   <li>Ersetzbare Blöcke (Pflanzen) werden weggespült und droppen ihr Item.</li>
 * </ul>
 * Parameter (Reichweite, Tick-Takt, Lava-Flag) kommen aus {@link FluidInfo}.
 */
public final class FluidBehavior implements BlockBehavior {

    @Override
    public void onPlaced(World world, int x, int y, int z, BlockState state) {
        FluidInfo info = state.getBlock().getFluidInfo();
        world.scheduleTickEarlier(x, y, z, reactionDelay(world, x, y, z, info));
    }

    @Override
    public BlockState onNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        FluidInfo info = state.getBlock().getFluidInfo();
        world.scheduleTickEarlier(x, y, z, reactionDelay(world, x, y, z, info));
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
                    int nx = x + d.offsetX(), nz = z + d.offsetZ();
                    short ns = world.getBlock(nx, y, nz);
                    if (!isSameFluid(ns, fluid)) continue;
                    BlockState neighbor = Blocks.getState(ns);
                    if (neighbor.get(Properties.FALLING)) {
                        /* Eine fallende Säule stützt nur dort, wo sie auf festem Boden aufkommt
                           (Pfütze am Fuß) - mitten im freien Fall (Luft/Fluid darunter) nicht,
                           sonst würde der Wasserfall in der Luft breiter. */
                        short nbelow = world.getBlock(nx, y - 1, nz);
                        if (canFluidReplace(nbelow) || isSameFluid(nbelow, fluid)) continue;
                        best = 0;
                    } else {
                        best = Math.min(best, isSource(neighbor) ? 0 : neighbor.get(Properties.LEVEL));
                    }
                }

                /* Unendliche Wasserquelle: ≥2 horizontale Quell-Nachbarn UND kein Fall nach unten
                   möglich (Boden solid oder selbst eine Quelle) -> selbst Quelle. */
                short srcBelow = world.getBlock(x, y - 1, z);
                boolean noFall = Blocks.isSolid(srcBelow)
                        || (isSameFluid(srcBelow, fluid) && isSource(Blocks.getState(srcBelow)));
                if (!info.lava && noFall && countSourceNeighbors(world, x, y, z, fluid) >= 2) {
                    world.setBlock(x, y, z, fluidState(fluid, 0, false));
                    return;
                }

                if (best == Integer.MAX_VALUE) {           // keine Stütze -> trocknen
                    world.setBlock(x, y, z, Blocks.AIR);
                    return;
                }
                int newLevel = best + info.dropOff;
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

        /* 3) Abfließen nach unten hat Vorrang - aber nur, wenn der Block darunter ersetzbar ist
           (Luft/Pflanze/eigenes fließendes Fluid). Eine eigene QUELLE darunter zählt wie Boden:
           nur eine Quelle breitet sich darüber seitlich aus, fließendes Fluid ruht (Vanilla).
           Gegen-Fluid darunter zählt ebenfalls wie Boden (Wasser fließt über Lavaseen). */
        short below = world.getBlock(x, y - 1, z);
        BlockState belowState = Blocks.getState(below);
        boolean belowSameFluid = isSameFluid(below, fluid);
        boolean belowSameSource = belowSameFluid && isSource(belowState);

        if (info.lava && isWater(belowState)) {
            /* Lava fließt/fällt in Wasser -> Stein an der Wasserposition (Vanilla). */
            world.setBlock(x, y - 1, z, Blocks.STONE);
            return;
        }
        if ((belowSameFluid && !belowSameSource) || canFluidReplace(below)) {
            if (canFluidReplace(below)) {       // freier Raum/Pflanze -> als fallende Säule weiter
                if (below != Blocks.AIR) dropBlockItem(world, x, y - 1, z, belowState);
                world.setBlock(x, y - 1, z, fluidState(fluid, 1, true));
                world.scheduleTickEarlier(x, y - 1, z, reactionDelay(world, x, y - 1, z, info));
            }
            /* Vanilla: >=3 horizontale Quell-Nachbarn -> trotz Abfluss zusätzlich seitwärts
               (dichte Quell-Pools bleiben an der Oberfläche geschlossen). */
            if (countSourceNeighbors(world, x, y, z, fluid) < 3) return;
        } else if (belowSameSource && !source) {
            return;                             // fließend über eigener Quelle: ruht (Vanilla)
        }

        /* Fester Boden darunter -> horizontale Ausbreitung. Quelle/aufkommende fallende Säule mit
           voller Stärke (effektives Level 0). */
        int eff = (isSource(state) || state.get(Properties.FALLING)) ? 0 : state.get(Properties.LEVEL);
        int spreadLevel = eff + info.dropOff;
        if (spreadLevel > info.spread) return;

        /* Gefälle-Suche: pro Richtung die kürzeste Distanz zu einem Loch (nicht-solid direkt
           darunter) bestimmen und nur in die Richtung(en) mit dem kleinsten Wert fließen. Eine
           bereits gefüllte Säule zählt weiterhin als Loch und hält den Fluss dorthin priorisiert. */
        int slopeFind = info.lava ? 2 : 4;
        Direction[] dirs = Direction.horizontal();
        boolean[] flow = new boolean[dirs.length];
        int[] slope = new int[dirs.length];
        int minSlope = Integer.MAX_VALUE;
        for (int i = 0; i < dirs.length; i++) {
            Direction d = dirs[i];
            int nx = x + d.offsetX(), nz = z + d.offsetZ();
            if (!canFluidReplace(world.getBlock(nx, y, nz))) continue; // nicht passierbar
            flow[i] = true;
            slope[i] = canDescend(world, nx, y, nz)
                    ? 0
                    : slopeDistance(world, nx, y, nz, fluid, 1, slopeFind, d.opposite());
            minSlope = Math.min(minSlope, slope[i]);
        }
        for (int i = 0; i < dirs.length; i++) {
            if (!flow[i] || slope[i] != minSlope) continue;
            Direction d = dirs[i];
            int nx = x + d.offsetX(), nz = z + d.offsetZ();
            short target = world.getBlock(nx, y, nz); // erneut lesen: Nachbar-Updates können die Zelle geändert haben
            if (!canFluidReplace(target)) continue;
            if (target != Blocks.AIR) dropBlockItem(world, nx, y, nz, Blocks.getState(target));
            world.setBlock(nx, y, nz, fluidState(fluid, spreadLevel, false));
            world.scheduleTickEarlier(nx, y, nz, reactionDelay(world, nx, y, nz, info));
        }
    }

    /**
     * Loch an dieser Stelle (Minecraft-Definition): der Block direkt darunter ist NICHT solid, das
     * Fluid kann dort also abfließen. Luft und Fluid sind nicht-solid (zählen als Loch), feste Blöcke
     * nicht. Eine bereits mit Fluid gefüllte Säule zählt damit weiterhin als Loch und hält den Fluss
     * dorthin priorisiert - das verhindert flächiges Vollfluten der ebenen Umgebung.
     */
    private static boolean canDescend(World world, int x, int y, int z) {
        return !Blocks.isSolid(world.getBlock(x, y - 1, z));
    }

    /** Rekursive Gefälle-Suche: kürzeste horizontale Distanz zu einem Abgrund (max. maxDist Schritte). */
    private static int slopeDistance(World world, int x, int y, int z, Block fluid,
                                     int dist, int maxDist, Direction cameFrom) {
        int min = Integer.MAX_VALUE;
        for (Direction d : Direction.horizontal()) {
            if (d == cameFrom) continue;
            int nx = x + d.offsetX(), nz = z + d.offsetZ();
            short ns = world.getBlock(nx, y, nz);
            if (!canFluidReplace(ns) && !isSameFluid(ns, fluid)) continue; // nicht passierbar (Look-Through durch eigenes Fluid)
            if (canDescend(world, nx, y, nz)) return dist;                 // Loch gefunden
            if (dist >= maxDist) continue;
            min = Math.min(min, slopeDistance(world, nx, y, nz, fluid, dist + 1, maxDist, d.opposite()));
        }
        return min;
    }

    /** Tickdelay für eine neu platzierte Fluidzelle: grenzt das Gegen-Fluid an, sofort reagieren. */
    private static int reactionDelay(World world, int x, int y, int z, FluidInfo info) {
        for (Direction d : Direction.values()) {
            BlockState n = Blocks.getState(world.getBlock(x + d.offsetX(), y + d.offsetY(), z + d.offsetZ()));
            FluidInfo ni = n.getBlock().getFluidInfo();
            if (ni != null && ni.lava != info.lava) return 1; // gegensätzliches Fluid -> direkte Reaktion
        }
        return info.tickDelay;
    }

    /** Wasser+Lava-Kontakt. Gibt true zurück, wenn dieser Block dabei ersetzt wurde. */
    private boolean reaction(World world, int x, int y, int z, BlockState state, FluidInfo info) {
        if (info.lava) {
            boolean waterAdjacent = false;
            for (Direction d : Direction.values()) {
                if (d == Direction.DOWN) continue; // Wasser nur unterhalb: Stein-Regel im Abfluss-Branch, keine Selbst-Umwandlung
                short ns = world.getBlock(x + d.offsetX(), y + d.offsetY(), z + d.offsetZ());
                if (isWater(Blocks.getState(ns))) {
                    waterAdjacent = true;
                    break;
                }
            }
            if (waterAdjacent) {
                /* Lava-Quelle + Wasser seitlich/oben -> Obsidian; fließende Lava + Wasser -> Cobblestone. */
                world.setBlock(x, y, z, isSource(state) ? Blocks.OBSIDIAN : Blocks.COBBLESTONE);
                return true;
            }
            return false;
        }

        /* Wasser selbst bleibt; benachbarte Lava prompt reagieren lassen. */
        for (Direction d : Direction.values()) {
            int nx = x + d.offsetX(), ny = y + d.offsetY(), nz = z + d.offsetZ();
            if (isLava(Blocks.getState(world.getBlock(nx, ny, nz)))) {
                world.scheduleTickEarlier(nx, ny, nz, 1);
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

    /**
     * Kann fließendes Fluid diesen Block ersetzen (Vanilla "replaceable")? Luft immer; sonst nur
     * nicht-solide Blöcke ohne Kollisionsform (Cross-Pflanzen: Gras, Blumen, Farn). Andere Fluids
     * nie (Level-Logik/Reaktion), Blöcke mit Kollision (z.B. Türen, solid=false) ebenfalls nie.
     */
    private static boolean canFluidReplace(short id) {
        if (id == Blocks.AIR) return true;
        BlockState s = Blocks.getState(id);
        return !s.isFluid() && !s.isSolid() && s.getCollisionShape().isEmpty();
    }

    /** Droppt das Item des weggespülten Blocks (Pflanze), falls eines registriert ist. */
    private static void dropBlockItem(World world, int x, int y, int z, BlockState state) {
        Item item = Items.get(state.getBlock().getIdentifier());
        if (item != null) world.spawnItem(x + 0.5, y + 0.5, z + 0.5, new ItemStack(item, 1));
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
