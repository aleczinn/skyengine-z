package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.BlockPos;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.entity.BlockEntities;
import de.skyengine.game.world.block.entity.PistonMovingBlockEntity;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.PistonType;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;
import de.skyengine.game.world.redstone.RedstonePower;

/**
 * Kolben-Basis: Platzierung (schaut den Spieler an) und die Schub-Zustandsmaschine.
 *
 * <p><b>Ablauf Extend</b> (scheduledTick): {@link PistonResolver} liefert die Kette (max. 12);
 * alle Writes mit {@code updateNeighbors=false} in Reihenfolge fern → nah — jede Zielzelle
 * wird ein {@code moving_piston} mit dem transportierten State, die Kopf-Zelle die
 * Source-BE mit dem {@code piston_head}-State, die Basis geht sofort auf
 * {@code extended=true}. Danach gezielte Nachbar-Ringe. Die BEs materialisieren nach
 * 2 Ticks selbst ({@link PistonMovingBlockEntity}).
 *
 * <p><b>Ablauf Retract:</b> die Source-BE sitzt an der KOPF-Zelle (transportiert den
 * Pull-Block des klebrigen Kolbens oder Luft; der zurückgleitende Arm ist reine
 * Renderer-Optik). Die Basis bleibt während der Animation ein echter
 * {@code piston[extended=true]}-Block im Chunk-Mesh — als BE-gerenderter Würfel (flaches
 * Zell-Licht ohne AO/Smooth-Lighting) blitzte sie sichtbar auf — und wird erst vom
 * finish der BE eingefahren.
 *
 * <p><b>Flicker-Regel:</b> laufende Bewegungen werden nie abgebrochen — trifft ein Tick auf
 * eine busy Kopf-/Basis-Zelle, verpufft er; die Source-BE plant nach der Materialisierung
 * genau EINEN Re-Evaluations-Tick auf die Basis. Signal wie MC über die 5 Seiten ohne die
 * Blickrichtung, ohne Quasi-Connectivity.
 *
 * <p><b>Timing:</b> Flanke → 1 Game-Tick Reaktions-Delay (Scheduler-Minimum) → 2 Game-Ticks
 * Animation → Materialisierung = 3 Game-Ticks (1,5 Redstone-Ticks), ≈ MC. MCs
 * Halbtick-Tricks (Block-Events/Update-Reihenfolge INNERHALB eines Ticks) existieren im
 * deterministischen Redstone dieser Engine bewusst nicht.
 */
public final class PistonBehavior implements BlockBehavior {

    private final boolean sticky;

    public PistonBehavior(boolean sticky) {
        this.sticky = sticky;
    }

    public boolean isSticky() {
        return this.sticky;
    }

    @Override
    public BlockState onPlace(PlacementContext ctx, BlockState state) {
        return state.with(Properties.FACING_ALL, facingToPlayer(ctx))
                .with(Properties.EXTENDED, false);
    }

    /** 6-Richtungs-Facing zum Spieler (Engine-Konvention: positiver Pitch = runterschauen). */
    static Direction facingToPlayer(PlacementContext ctx) {
        if (ctx.playerPitch() > 45) return Direction.UP;
        if (ctx.playerPitch() < -45) return Direction.DOWN;
        return Direction.fromYaw(ctx.playerYaw()).opposite();
    }

    @Override
    public BlockState onNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        boolean want = hasSignal(world, x, y, z, state.get(Properties.FACING_ALL));
        if (want != state.get(Properties.EXTENDED) && !world.isTickScheduled(x, y, z)) {
            world.scheduleTick(x, y, z, 1);
        }
        return state;
    }

    @Override
    public void scheduledTick(World world, int x, int y, int z, BlockState state) {
        Direction f = state.get(Properties.FACING_ALL);
        boolean want = hasSignal(world, x, y, z, f);
        boolean extended = state.get(Properties.EXTENDED);
        if (want && !extended) {
            this.extend(world, x, y, z, state, f);
        } else if (!want && extended) {
            this.retract(world, x, y, z, state, f);
        }
    }

    private void extend(World world, int x, int y, int z, BlockState state, Direction f) {
        PistonResolver.Result result = PistonResolver.resolveExtend(world, x, y, z, f);
        if (result.blocked()) {
            /* Fremde Animation im Weg: pollen — ihr Ende erzeugt bei konstantem Signal
               kein Nachbar-Update mehr, das uns wecken würde. */
            if (result.blockedByMoving()) world.scheduleTick(x, y, z, 2);
            return;
        }

        /* Destroy-Zelle: Drop + onBreak; überschrieben wird sie gleich vom äußersten Ziel-Write. */
        for (long pos : result.destroys()) {
            int dx = BlockPos.unpackX(pos), dy = BlockPos.unpackY(pos), dz = BlockPos.unpackZ(pos);
            BlockState broken = Blocks.getState(world.getBlock(dx, dy, dz));
            broken.getBlock().onBreak(world, dx, dy, dz, broken);
            Item drop = Items.forBlock(broken.getBlock());
            if (drop != null) world.spawnItem(dx + 0.5, dy + 0.5, dz + 0.5, new ItemStack(drop, 1));
        }

        /* Kette fern -> nah: das Ziel jeder Quelle ist frei bzw. gerade geräumt. */
        for (long src : result.moves()) {
            int sx = BlockPos.unpackX(src), sy = BlockPos.unpackY(src), sz = BlockPos.unpackZ(src);
            int movedId = world.getBlock(sx, sy, sz);
            spawnMoving(world, sx + f.offsetX(), sy + f.offsetY(), sz + f.offsetZ(),
                    movedId, f, true, false, this.sticky);
        }

        /* Kopf-Zelle = Source-BE mit dem piston_head-State, Basis sofort ausgefahren. */
        int hx = x + f.offsetX(), hy = y + f.offsetY(), hz = z + f.offsetZ();
        BlockState head = Blocks.getState(Blocks.PISTON_HEAD)
                .with(Properties.FACING_ALL, f)
                .with(Properties.PISTON_TYPE, this.sticky ? PistonType.STICKY : PistonType.NORMAL);
        spawnMoving(world, hx, hy, hz, head.getId(), f, true, true, this.sticky);
        world.setBlock(x, y, z, state.with(Properties.EXTENDED, true).getId(), false);

        world.updateNeighbors(x, y, z);
        world.updateNeighbors(hx, hy, hz);
        for (int i = result.moves().length - 1; i >= 0; i--) {
            long src = result.moves()[i];
            world.updateNeighbors(BlockPos.unpackX(src) + f.offsetX(),
                    BlockPos.unpackY(src) + f.offsetY(), BlockPos.unpackZ(src) + f.offsetZ());
        }
    }

    private void retract(World world, int x, int y, int z, BlockState state, Direction f) {
        int hx = x + f.offsetX(), hy = y + f.offsetY(), hz = z + f.offsetZ();
        BlockState head = Blocks.getState(world.getBlock(hx, hy, hz));
        if (head.getBlock().getBlockEntityType() == BlockEntities.PISTON_MOVING) {
            /* Busy (eigene Extend-Animation oder fremder Schub): pollen statt abbrechen. */
            world.scheduleTick(x, y, z, 2);
            return;
        }
        boolean validHead = head.getValues().containsKey(Properties.PISTON_TYPE)
                && head.get(Properties.FACING_ALL) == f;
        if (!validHead || !world.isPositionEditable(hx, hy, hz)) {
            /* Kopf verloren (weggesprengt/inkonsistent): heilen statt animieren. */
            world.setBlock(x, y, z, state.with(Properties.EXTENDED, false).getId(), true);
            return;
        }

        /* Die Source-BE sitzt an der KOPF-Zelle; die Basis bleibt während der Animation ein
           echter piston[extended=true]-Block. Bewusst so: als BE-gerenderter Würfel (flaches
           Zell-Licht, kein AO/Smooth-Lighting) blitzte die Basis beim Einfahren sichtbar
           auf — im Chunk-Mesh bleibt sie durchgehend korrekt beleuchtet. Erst das finish
           der BE fährt sie ein. Transportiert wird der Pull-Block (sticky) oder Luft
           (der zurückgleitende Arm ist reine Renderer-Optik). */
        long pull = this.sticky
                ? PistonResolver.resolvePull(world, hx + f.offsetX(), hy + f.offsetY(), hz + f.offsetZ())
                : PistonResolver.NO_PULL;
        int movedId = Blocks.AIR;
        if (pull != PistonResolver.NO_PULL) {
            int px = BlockPos.unpackX(pull), py = BlockPos.unpackY(pull), pz = BlockPos.unpackZ(pull);
            movedId = world.getBlock(px, py, pz);
            spawnMoving(world, hx, hy, hz, movedId, f, false, true, this.sticky);
            world.setBlock(px, py, pz, Blocks.AIR, false);
            world.updateNeighbors(px, py, pz);
        } else {
            spawnMoving(world, hx, hy, hz, Blocks.AIR, f, false, true, this.sticky);
        }
        world.updateNeighbors(hx, hy, hz);
    }

    /** Setzt einen moving_piston und konfiguriert die frisch angelegte BE. */
    private static void spawnMoving(World world, int x, int y, int z, int movedStateId,
                                    Direction facing, boolean extending, boolean source, boolean sticky) {
        world.setBlock(x, y, z, Blocks.MOVING_PISTON, false);
        if (world.getBlockEntity(x, y, z) instanceof PistonMovingBlockEntity be) {
            be.configure(movedStateId, facing, extending, source, sticky);
        }
    }

    /**
     * Beim Abbau einer AUSGEFAHRENEN Basis verschwindet der Arm mit — der fertige Kopf
     * genauso wie eine noch laufende eigene Extend-Animation. Bereits geschobene Moving-BEs
     * weiter außen laufen zu Ende (MC-Verhalten).
     */
    @Override
    public void onBreak(World world, int x, int y, int z, BlockState state) {
        if (!state.get(Properties.EXTENDED)) return;
        Direction f = state.get(Properties.FACING_ALL);
        int hx = x + f.offsetX(), hy = y + f.offsetY(), hz = z + f.offsetZ();
        BlockState head = Blocks.getState(world.getBlock(hx, hy, hz));
        boolean matchingHead = head.getValues().containsKey(Properties.PISTON_TYPE)
                && head.get(Properties.FACING_ALL) == f;
        boolean matchingMoving = head.getBlock().getBlockEntityType() == BlockEntities.PISTON_MOVING
                && world.getBlockEntity(hx, hy, hz) instanceof PistonMovingBlockEntity mb
                && mb.isSource() && mb.getFacing() == f;
        if (matchingHead || matchingMoving) {
            /* Bei einer laufenden Animation transportiert die BE ggf. einen Pull-Block —
               dessen onBreak (MovingPistonBehavior) droppt ihn, statt ihn zu verschlucken. */
            if (matchingMoving) head.getBlock().onBreak(world, hx, hy, hz, head);
            world.setBlock(hx, hy, hz, Blocks.AIR, true);
        }
    }

    /** Signal aus einer der 5 Seiten ohne die Blickrichtung (MC, ohne Quasi-Connectivity). */
    private static boolean hasSignal(World world, int x, int y, int z, Direction facing) {
        for (Direction d : Direction.values()) {
            if (d == facing) continue;
            if (RedstonePower.emittedSignal(world, x + d.offsetX(), y + d.offsetY(), z + d.offsetZ(),
                    d.opposite(), false) > 0) {
                return true;
            }
        }
        return false;
    }
}
