package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.archetype.FluidInfo;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.chunk.FluidGeometry;
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
 *   <li>Hohlraum-Regel mit Druck-Bedingung: eine Luftzelle, die horizontal Wasser UND Lava berührt,
 *       wird zu Cobblestone - aber nur, wenn mindestens eines der beiden Fluids sie reichweitenmäßig
 *       noch erreichen könnte ("Druck", effLevel + dropOff <= spread). Enden beide Fluids mit
 *       maximaler Reichweite an der Lücke, passiert nichts (Vanilla-"Druck"-Regel).</li>
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
        FluidInfo info = state.getBlock().getFluidInfo();
        /* Lava+Wasser reagiert synchron - updateStateAt wendet den zurückgegebenen Fremd-State
           direkt an. Deckt beide Fälle im selben Tick ab (kein sichtbarer Lava-Frame im
           Cobble-Generator): Lava fließt in wasserangrenzende Zelle (eigenes Update direkt nach
           setBlock) und Wasser erreicht bestehende Lava (Nachbar-Update). Die AUSBREITUNG tickt
           dagegen immer im eigenen Takt (info.tickDelay) - kein beschleunigtes Ticken neben dem
           Gegen-Fluid, sonst rast die Wasserfront über ein Lavafeld und konvertiert alles quasi
           instant statt Ring für Ring (MC-Pacing). */
        if (info.lava && waterAdjacent(world, x, y, z)) {
            return Blocks.getState(isSource(state) ? Blocks.OBSIDIAN : Blocks.COBBLESTONE);
        }
        world.scheduleTickEarlier(x, y, z, info.tickDelay);
        return state;
    }

    @Override
    public void scheduledTick(World world, int x, int y, int z, BlockState state) {
        Block fluid = state.getBlock();
        FluidInfo info = fluid.getFluidInfo();
        if (info == null) return;

        /* 1) Wasser/Lava-Reaktion hat Vorrang (kann diesen Block ersetzen). */
        if (reaction(world, x, y, z, state, info)) return;

        /* Hohlraum-Regel (Vanilla): eine ersetzbare Zelle zwischen Wasser und Lava wird zu
           Cobblestone - im LAVA-Takt (tickDelay 30), wie das Minecraft-Generator-Delay.
           Druck-Bedingung: nur wenn mindestens eines der beiden Fluids die Zelle reichweitenmäßig
           noch erreichen KÖNNTE (unabhängig davon, wohin die Gefälle-Suche real lenkt) - zwei
           Fluids am Reichweiten-Ende erzeugen keinen Cobble. Wasser stößt nur den Tick ruhender
           Lava an, erzeugt den Cobble aber nicht selbst. */
        for (Direction d : Direction.horizontalValues()) {
            int nx = x + d.offsetX(), nz = z + d.offsetZ();
            if (!canFluidReplace(world.getBlock(nx, y, nz))) continue;
            for (Direction d2 : Direction.horizontalValues()) {
                if (d2 == d.opposite()) continue;
                int ox = nx + d2.offsetX(), oz = nz + d2.offsetZ();
                BlockState os = Blocks.getState(world.getBlock(ox, y, oz));
                FluidInfo oi = os.getBlock().getFluidInfo();
                if (oi == null || oi.lava == info.lava) continue;
                if (!hasPressure(state, info) && !hasPressure(os, oi)) continue;
                if (info.lava) {
                    /* Nachbarzelle in nicht-READY Chunk: eigenen Tick wiederholen statt die
                       Hohlraum-Regel still zu verlieren (Takt bleibt der eigene, s. Skill). */
                    if (!world.setBlock(nx, y, nz, Blocks.COBBLESTONE)) {
                        world.scheduleTick(x, y, z, info.tickDelay);
                    }
                } else {
                    world.scheduleTick(ox, y, oz, oi.tickDelay); // ruhende Lava im eigenen Takt wecken
                }
                break;
            }
        }

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
        int below = world.getBlock(x, y - 1, z);
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
        for (int i = 0; i < dirs.length; i++) {
            Direction d = dirs[i];
            int nx = x + d.offsetX(), nz = z + d.offsetZ();
            int ns = world.getBlock(nx, y, nz);
            /* Eigenes fließendes Fluid zählt bei der Gefälle-Suche weiter mit (hält minSlope auf
               der etablierten Fließrichtung, sonst flutet die zweitbeste Richtung die Terrasse),
               wird aber nicht überschrieben. Quellen blockieren (Vanilla canPassThrough). */
            boolean sameFlowing = isSameFluid(ns, fluid) && !isSource(Blocks.getState(ns));
            if (!canFluidReplace(ns) && !sameFlowing) continue; // nicht passierbar
            /* Misch-Zellen (grenzen horizontal ans Gegen-Fluid) bleiben frei: dort erzeugt die
               Hohlraum-Regel im Lava-Takt Cobblestone, statt dass das Fluid hineinfließt. */
            flow[i] = canFluidReplace(ns)
                    && !oppositeFluidAdjacent(world, nx, y, nz, info.lava, d.opposite());
            slope[i] = canDescend(world, nx, y, nz)
                    ? 0
                    : slopeDistance(world, nx, y, nz, fluid, 1, slopeFind, d.opposite());
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
        for (Direction d : Direction.horizontalValues()) {
            if (d == cameFrom) continue;
            int nx = x + d.offsetX(), nz = z + d.offsetZ();
            int ns = world.getBlock(nx, y, nz);
            if (!canFluidReplace(ns) && !isSameFluid(ns, fluid)) continue; // nicht passierbar (Look-Through durch eigenes Fluid)
            if (canDescend(world, nx, y, nz)) return dist;                 // Loch gefunden
            if (dist >= maxDist) continue;
            min = Math.min(min, slopeDistance(world, nx, y, nz, fluid, dist + 1, maxDist, d.opposite()));
        }
        return min;
    }

    /** Grenzt horizontal (außer cameFrom) das Gegen-Fluid an? Für die Hohlraum-Regel. */
    private static boolean oppositeFluidAdjacent(World world, int x, int y, int z,
                                                 boolean selfLava, Direction cameFrom) {
        for (Direction d : Direction.horizontalValues()) {
            if (d == cameFrom) continue;
            FluidInfo ni = Blocks.getState(world.getBlock(x + d.offsetX(), y, z + d.offsetZ()))
                    .getBlock().getFluidInfo();
            if (ni != null && ni.lava != selfLava) return true;
        }
        return false;
    }

    /** Kann sich dieses Fluid reichweitenmäßig noch einen Block weiter ausbreiten ("Druck")? */
    private static boolean hasPressure(BlockState s, FluidInfo info) {
        int eff = (isSource(s) || s.get(Properties.FALLING)) ? 0 : s.get(Properties.LEVEL);
        return eff + info.dropOff <= info.spread;
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
                world.setBlock(x, y, z, isSource(state) ? Blocks.OBSIDIAN : Blocks.COBBLESTONE);
                return true;
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

    /** Droppt das Item des weggespülten Blocks (Pflanze), falls eines registriert ist. */
    private static void dropBlockItem(World world, int x, int y, int z, BlockState state) {
        Item item = Items.get(state.getBlock().getIdentifier());
        if (item != null) world.spawnItem(x + 0.5, y + 0.5, z + 0.5, new ItemStack(item, 1));
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
}
