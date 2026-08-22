package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.archetype.FluidInfo;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.chunk.FluidGeometry;
import de.skyengine.game.world.loot.LootContext;

import java.util.Random;

/**
 * Fluss-Verhalten für Wasser/Lava (Minecraft-artig, vereinfacht). Arbeitet über geplante Ticks:
 * <ul>
 *   <li>Quelle (LEVEL 0) bleibt bestehen und speist Nachbarn.</li>
 *   <li>Fließendes Fluid bezieht seinen Stand aus dem höchsten horizontalen Nachbarn (sonst trocknet es).</li>
 *   <li>Fällt nach unten (FALLING) wenn darunter Luft ist; sonst breitet es sich horizontal aus.</li>
 *   <li>Zwei benachbarte Wasserquellen erzeugen dazwischen eine neue Quelle (unendliches Wasser).</li>
 *   <li>Wasser/Lava reagieren erst bei echtem Kontakt: Wasser an Lavaquelle→Obsidian,
 *       Wasser an fließender Lava→Cobblestone, Lava von oben in Wasser→Stein.</li>
 *   <li>Ersetzbare Blöcke (Pflanzen) werden weggespült und droppen ihr Item.</li>
 * </ul>
 * Parameter (Reichweite, Tick-Takt, Lava-Flag) kommen aus {@link FluidInfo}.
 */
public final class FluidBehavior implements BlockBehavior {

    @Override
    public void onPlaced(World world, int x, int y, int z, BlockState state) {
        FluidInfo info = state.getBlock().getFluidInfo();
        world.scheduleTickEarlier(x, y, z, info.tickDelay);
    }

    @Override
    public BlockState onNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        return this.neighborUpdate(world, x, y, z, state);
    }

    @Override
    public BlockState onNeighborShapeUpdate(World world, int x, int y, int z, BlockState state,
                                            Direction direction, BlockState neighborState) {
        return this.neighborUpdate(world, x, y, z, state);
    }

    private BlockState neighborUpdate(World world, int x, int y, int z, BlockState state) {
        FluidInfo info = state.getBlock().getFluidInfo();
        if (info == null) return state; // Shape-Hook kann im selben Update bereits zu Stein konvertieren.
        /* Lava+Wasser reagiert synchron - updateStateAt wendet den zurückgegebenen Fremd-State
           direkt an. Deckt beide Fälle im selben Tick ab (kein sichtbarer Lava-Frame im
           Cobble-Generator): Lava fließt in wasserangrenzende Zelle (eigenes Update direkt nach
           setBlock) und Wasser erreicht bestehende Lava (Nachbar-Update). Die AUSBREITUNG tickt
           dagegen immer im eigenen Takt (info.tickDelay) - kein beschleunigtes Ticken neben dem
           Gegen-Fluid, sonst rast die Wasserfront über ein Lavafeld und konvertiert alles quasi
           instant statt Ring für Ring (MC-Pacing). */
        if (info.lava && waterAdjacent(world, x, y, z)) {
            world.playFluidExtinguish(x, y, z);
            return Blocks.getState(isSource(state) ? Blocks.OBSIDIAN : Blocks.COBBLESTONE);
        }
        world.scheduleTickEarlier(x, y, z, info.tickDelay);
        return state;
    }

    @Override
    public void animateTick(World world, int x, int y, int z, BlockState state, Random random) {
        FluidInfo info = state.getBlock().getFluidInfo();
        if (info == null) return;
        if (!info.lava) {
            if (!isSource(state) && !state.get(Properties.FALLING) && random.nextInt(64) == 0) {
                world.playWaterAmbient(x, y, z);
            }
            return;
        }
        if (world.getBlock(x, y + 1, z) != Blocks.AIR) return;
        if (random.nextInt(100) == 0) world.playLavaPop(x, y, z);
        if (random.nextInt(200) == 0) world.playLavaAmbient(x, y, z);
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
                if (!state.get(Properties.FALLING) || state.get(Properties.LEVEL) != 0) {
                    this.updateOwnState(world, x, y, z, state,
                            Blocks.getState(fluidState(fluid, 0, true)), info);
                    return;
                }
            } else {
                int best = Integer.MAX_VALUE; // kleinster Levelwert (höchstes Fluid) unter den Stützen
                for (Direction d : Direction.horizontalValues()) {
                    int nx = x + d.offsetX(), nz = z + d.offsetZ();
                    int ns = world.getBlock(nx, y, nz);
                    if (!isSameFluid(ns, fluid)) continue;
                    BlockState neighbor = Blocks.getState(ns);
                    if (neighbor.get(Properties.FALLING)) {
                        /* Eine fallende Säule stützt nur dort, wo sie auf festem Boden aufkommt
                           (Pfütze am Fuß) - mitten im freien Fall (Luft/Fluid darunter) nicht,
                           sonst würde der Wasserfall in der Luft breiter. */
                        int nbelow = world.getBlock(nx, y - 1, nz);
                        if (canFluidReplace(nbelow) || isSameFluid(nbelow, fluid)) continue;
                        best = 0;
                    } else {
                        best = Math.min(best, isSource(neighbor) ? 0 : neighbor.get(Properties.LEVEL));
                    }
                }

                /* Unendliche Wasserquelle: ≥2 horizontale Quell-Nachbarn UND kein Fall nach unten
                   möglich (Boden solid oder selbst eine Quelle) -> selbst Quelle. */
                int srcBelow = world.getBlock(x, y - 1, z);
                boolean noFall = Blocks.isSolid(srcBelow)
                        || (isSameFluid(srcBelow, fluid) && isSource(Blocks.getState(srcBelow)));
                if (!info.lava && noFall && countSourceNeighbors(world, x, y, z, fluid) >= 2) {
                    this.updateOwnState(world, x, y, z, state,
                            Blocks.getState(fluidState(fluid, 0, false)), info);
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
                    this.updateOwnState(world, x, y, z, state,
                            Blocks.getState(fluidState(fluid, newLevel, false)), info);
                    return;                                // Folge-Tick durch das Nachbar-Update
                }
            }
        }

        /* 3) Abfließen nach unten hat Vorrang - aber nur, wenn der Block darunter ersetzbar ist
           (Luft/Pflanze/eigenes fließendes Fluid). Eine eigene QUELLE darunter zählt wie Boden:
           nur eine Quelle breitet sich darüber seitlich aus, fließendes Fluid ruht (Vanilla).
           Gegen-Fluid darunter zählt ebenfalls wie Boden (Wasser fließt über Lavaseen). */
        int below = world.getBlock(x, y - 1, z);
        BlockState belowState = Blocks.getState(below);
        boolean belowSameFluid = isSameFluid(below, fluid);
        boolean belowSameSource = belowSameFluid && isSource(belowState);

        if (info.lava && isWater(belowState)) {
            /* Lava fließt/fällt in Wasser -> Stein an der Wasserposition (Vanilla). */
            if (world.setBlock(x, y - 1, z, Blocks.STONE)) {
                world.playFluidExtinguish(x, y - 1, z);
            }
            return;
        }
        if ((belowSameFluid && !belowSameSource) || canFluidReplace(below)) {
            if (canFluidReplace(below)) {       // freier Raum/Pflanze -> als fallende Säule weiter
                if (below != Blocks.AIR) dropBlockItem(world, x, y - 1, z, belowState);
                world.setBlock(x, y - 1, z, fluidState(fluid, 0, true));
                world.scheduleTickEarlier(x, y - 1, z, info.tickDelay);
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
        Direction[] dirs = Direction.horizontalValues();
        boolean[] flow = new boolean[dirs.length];
        int[] slope = new int[dirs.length];
        int minSlope = Integer.MAX_VALUE;
        SlopeSearch search = new SlopeSearch();
        for (int i = 0; i < dirs.length; i++) {
            Direction d = dirs[i];
            int nx = x + d.offsetX(), nz = z + d.offsetZ();
            if (!world.isPositionEditable(nx, y, nz)) {
                search.encounteredUnready = true;
                continue;
            }
            int ns = world.getBlock(nx, y, nz);
            /* Eigenes fließendes Fluid zählt bei der Gefälle-Suche weiter mit (hält minSlope auf
               der etablierten Fließrichtung, sonst flutet die zweitbeste Richtung die Terrasse),
               wird aber nicht überschrieben. Quellen blockieren (Vanilla canPassThrough). */
            boolean sameFlowing = isSameFluid(ns, fluid) && !isSource(Blocks.getState(ns));
            if (!canFluidReplace(ns) && !sameFlowing) continue; // nicht passierbar
            flow[i] = canFluidReplace(ns);
            slope[i] = canDescend(world, nx, y, nz, fluid, search)
                    ? 0
                    : slopeDistance(world, nx, y, nz, fluid, 1, slopeFind, d.opposite(), search);
            minSlope = Math.min(minSlope, slope[i]);
        }
        for (int i = 0; i < dirs.length; i++) {
            if (!flow[i] || slope[i] != minSlope) continue;
            Direction d = dirs[i];
            int nx = x + d.offsetX(), nz = z + d.offsetZ();
            int target = world.getBlock(nx, y, nz); // erneut lesen: Nachbar-Updates können die Zelle geändert haben
            if (!canFluidReplace(target)) continue;
            /* Zielzelle in nicht-READY Chunk: eigenen Tick wiederholen statt den Fluss
               endgültig sterben zu lassen (der Folge-Tick fiele sonst auf Luft und wird
               verworfen). Drop erst NACH erfolgreichem Schreiben — sonst Item-Duplikat. */
            if (!world.setBlock(nx, y, nz, fluidState(fluid, spreadLevel, false))) {
                world.scheduleTick(x, y, z, info.tickDelay);
                continue;
            }
            if (target != Blocks.AIR) dropBlockItem(world, nx, y, nz, Blocks.getState(target));
            world.scheduleTickEarlier(nx, y, nz, info.tickDelay);
        }
        if (search.encounteredUnready) world.scheduleTick(x, y, z, info.tickDelay);
    }

    /**
     * Loch an dieser Stelle (Minecraft-Definition): Das Fluid kann den Block darunter ersetzen.
     * Eigenes fließendes Fluid zählt ebenfalls, eine Quelle blockiert dagegen die Suche. Vom
     * Gegen-Fluid ist nur Wasser unter Lava ein gültiges Ziel, weil dort die Stein-Reaktion greift.
     */
    private static boolean canDescend(World world, int x, int y, int z, Block fluid,
                                      SlopeSearch search) {
        if (y <= 0) return false;
        if (!world.isPositionEditable(x, y - 1, z)) {
            search.encounteredUnready = true;
            return false;
        }
        BlockState below = Blocks.getState(world.getBlock(x, y - 1, z));
        if (below.isFluid()) {
            if (below.getBlock() == fluid) return !isSource(below);
            FluidInfo info = fluid.getFluidInfo();
            return info.lava && isWater(below); // Lava darf nur von oben Wasser durch Stein ersetzen
        }
        return canFluidReplace(below.getId());
    }

    /** Rekursive Gefälle-Suche: kürzeste horizontale Distanz zu einem Abgrund (max. maxDist Schritte). */
    private static int slopeDistance(World world, int x, int y, int z, Block fluid,
                                     int dist, int maxDist, Direction cameFrom,
                                     SlopeSearch search) {
        int min = Integer.MAX_VALUE;
        for (Direction d : Direction.horizontalValues()) {
            if (d == cameFrom) continue;
            int nx = x + d.offsetX(), nz = z + d.offsetZ();
            if (!world.isPositionEditable(nx, y, nz)) {
                search.encounteredUnready = true;
                continue;
            }
            int ns = world.getBlock(nx, y, nz);
            boolean sameFlowing = isSameFluid(ns, fluid) && !isSource(Blocks.getState(ns));
            if (!canFluidReplace(ns) && !sameFlowing) continue;
            if (canDescend(world, nx, y, nz, fluid, search)) return dist;
            if (dist >= maxDist) continue;
            min = Math.min(min, slopeDistance(world, nx, y, nz, fluid,
                    dist + 1, maxDist, d.opposite(), search));
        }
        return min;
    }

    /** Wasser oben/seitlich angrenzend? (Unten nicht: dort gilt die Stein-Regel im Abfluss.) */
    private static boolean waterAdjacent(World world, int x, int y, int z) {
        for (Direction d : Direction.sharedValues()) {
            if (d == Direction.DOWN) continue;
            int ns = world.getBlock(x + d.offsetX(), y + d.offsetY(), z + d.offsetZ());
            if (isWater(Blocks.getState(ns))) return true;
        }
        return false;
    }

    /** Wasser+Lava-Kontakt (Fallback, falls kein Nachbar-Update lief, z.B. nach Chunk-Load).
     *  Gibt true zurück, wenn dieser Block dabei ersetzt wurde. */
    private boolean reaction(World world, int x, int y, int z, BlockState state, FluidInfo info) {
        if (info.lava) {
            if (waterAdjacent(world, x, y, z)) {
                /* Lava-Quelle + Wasser seitlich/oben -> Obsidian; fließende Lava + Wasser -> Cobblestone. */
                if (world.setBlock(x, y, z, isSource(state) ? Blocks.OBSIDIAN : Blocks.COBBLESTONE)) {
                    world.playFluidExtinguish(x, y, z);
                    return true;
                }
            }
            return false;
        }

        /* Wasser selbst bleibt; benachbarte Lava prompt reagieren lassen. */
        for (Direction d : Direction.sharedValues()) {
            int nx = x + d.offsetX(), ny = y + d.offsetY(), nz = z + d.offsetZ();
            if (isLava(Blocks.getState(world.getBlock(nx, ny, nz)))) {
                world.scheduleTickEarlier(nx, ny, nz, 1);
            }
        }
        return false;
    }

    private int countSourceNeighbors(World world, int x, int y, int z, Block fluid) {
        int count = 0;
        for (Direction d : Direction.horizontalValues()) {
            int ns = world.getBlock(x + d.offsetX(), y, z + d.offsetZ());
            if (isSameFluid(ns, fluid) && isSource(Blocks.getState(ns))) count++;
        }
        return count;
    }

    /**
     * Kann fließendes Fluid diesen Block ersetzen (Vanilla "replaceable")? Luft immer; sonst nur
     * nicht-solide Blöcke ohne Kollisionsform (Cross-Pflanzen: Gras, Blumen, Farn). Andere Fluids
     * nie (Level-Logik/Reaktion), Blöcke mit Kollision (z.B. Türen, solid=false) ebenfalls nie.
     */
    private static boolean canFluidReplace(int id) {
        if (id == Blocks.AIR) return true;
        BlockState s = Blocks.getState(id);
        return !s.isFluid() && !s.isSolid() && s.getCollisionShape().isEmpty();
    }

    /** Droppt das Item des weggespülten Blocks (Pflanze, Staub), falls eines registriert ist. */
    private static void dropBlockItem(World world, int x, int y, int z, BlockState state) {
        world.dropBlockLoot(x, y, z, state, LootContext.Cause.FLUID);
    }

    /**
     * Aktualisiert nur den Zustand einer bereits vorhandenen Fluidzelle. Shape-Updates wecken
     * angrenzende Fluide; der eigene Folgetick wird mit dem zustandsabhängigen Delay geplant,
     * ohne dass ein allgemeines Eigen-Update ihn wieder auf den Basistakt vorzieht.
     */
    private void updateOwnState(World world, int x, int y, int z, BlockState oldState,
                                BlockState newState, FluidInfo info) {
        if (!world.setBlockWithShapeUpdates(x, y, z, newState.getId())) return;
        int delay = info.tickDelay;
        if (info.lava && !oldState.get(Properties.FALLING) && !newState.get(Properties.FALLING)
                && newState.get(Properties.LEVEL) > oldState.get(Properties.LEVEL)
                && world.random().nextInt(4) != 0) {
            delay *= 4;
        }
        world.scheduleTick(x, y, z, delay);
    }

    private static int fluidState(Block fluid, int level, boolean falling) {
        return fluid.getDefaultState()
                .with(Properties.LEVEL, level)
                .with(Properties.FALLING, falling)
                .getId();
    }

    private static boolean isSource(BlockState s) {
        return s.isFluid() && !s.get(Properties.FALLING) && s.get(Properties.LEVEL) == 0;
    }

    /**
     * Fließrichtung der Fluid-Zelle (x,y,z) für Entity-Strömung, unnormiert in {@code out[0]/out[1]}.
     * Gleiche Formel wie der Render-Flow im Top-Face von {@code FluidGeometry.build} (Vanilla
     * FlowingFluid.getFlow): pro Himmelsrichtung zieht nur gleiches Fluid (Level-Differenz) bzw.
     * eine Abfall-Kante (freie Zelle mit gleichem Fluid eine Ebene tiefer); solide Nachbarn und
     * leere Zellen tragen nichts bei. Bewusst dupliziert: der Mesher sampelt aus Thread-Gründen
     * über Chunks, Entities über die World.
     */
    public static void flowVector(World world, int x, int y, int z, double[] out) {
        out[0] = 0;
        out[1] = 0;
        BlockState state = Blocks.getState(world.getBlock(x, y, z));
        if (!state.isFluid()) return;
        Block fluid = state.getBlock();
        double own = FluidGeometry.fluidHeight(state);

        for (Direction d : Direction.horizontalValues()) {
            int nx = x + d.offsetX(), nz = z + d.offsetZ();
            int nid = world.getBlock(nx, y, nz);
            double diff = 0;
            if (isSameFluid(nid, fluid)) {
                diff = own - FluidGeometry.fluidHeight(Blocks.getState(nid));
            } else if (!Blocks.getState(nid).isSolid()) {
                int bid = world.getBlock(nx, y - 1, nz);
                if (isSameFluid(bid, fluid)) { // Abfall-Kante: zieht stark bergab
                    diff = own - (FluidGeometry.fluidHeight(Blocks.getState(bid)) - FluidGeometry.SOURCE_HEIGHT);
                }
            }
            out[0] += d.offsetX() * diff;
            out[1] += d.offsetZ() * diff;
        }
    }

    private static boolean isSameFluid(int id, Block fluid) {
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

    private static final class SlopeSearch {
        private boolean encounteredUnready;
    }
}
