package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.Capabilities;
import de.skyengine.game.world.block.entity.ItemStorage;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.ComparatorMode;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.redstone.RedstonePower;

/**
 * Komparator: variabler Signal-Durchlass mit Container-Messung. Konvention wie der
 * Verstärker: <b>FACING = Ausgangsrichtung</b>, Eingang hinten, Seiten-Eingänge nur von
 * echten Redstone-Komponenten ({@code RedstonePower.isSideInputSource}).
 *
 * <ul>
 *   <li>Hinterer Eingang: Redstone-Signal ODER Container-Füllstand — direkt hinter dem
 *       Eingang bzw. durch EINEN opaken Block hindurch (MC). Formel: leer 0, sonst
 *       {@code floor(1 + 14 · Füllgrad)}.</li>
 *   <li>compare: Ausgang = Eingang, wenn Eingang ≥ stärkste Seite, sonst 0.</li>
 *   <li>subtract: Ausgang = max(0, Eingang − stärkste Seite).</li>
 * </ul>
 *
 * Der Ausgangswert liegt als POWER im State (Palette persistiert ihn gratis); Änderungen
 * takten mit 1 Redstone-Tick (2 Game-Ticks) über den Scheduler, tolerant validierend.
 * Container-Mutationen erzeugen keine Nachbar-Updates — Hopper und Container-GUIs stoßen
 * {@code World.updateComparatorOutputs} an (Live-Updates bei offenem GUI erst beim
 * Schließen, dokumentierte Vereinfachung).
 */
public final class ComparatorBehavior implements BlockBehavior {

    @Override
    public BlockState onPlace(PlacementContext ctx, BlockState state) {
        return state.with(Properties.FACING, Direction.fromYaw(ctx.playerYaw()))
                .with(Properties.MODE, ComparatorMode.COMPARE)
                .with(Properties.POWER, 0);
    }

    @Override
    public boolean onUse(World world, int x, int y, int z, BlockState state) {
        ComparatorMode next = state.get(Properties.MODE) == ComparatorMode.COMPARE
                ? ComparatorMode.SUBTRACT : ComparatorMode.COMPARE;
        BlockState toggled = state.with(Properties.MODE, next);
        /* Modus-Wechsel ändert sofort auch den Soll-Ausgang mit. */
        int output = computeOutput(world, x, y, z, toggled);
        world.setBlock(x, y, z, toggled.with(Properties.POWER, output).getId(), true);
        notifyStrongTarget(world, x, y, z, toggled);
        return true;
    }

    @Override
    public BlockState onNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        if (computeOutput(world, x, y, z, state) != state.get(Properties.POWER)
                && !world.isTickScheduled(x, y, z)) {
            world.scheduleTick(x, y, z, 2);
        }
        return state;
    }

    @Override
    public void scheduledTick(World world, int x, int y, int z, BlockState state) {
        if (!state.getValues().containsKey(Properties.MODE)) return;   // tolerantes Feuern
        int output = computeOutput(world, x, y, z, state);
        if (output == state.get(Properties.POWER)) return;
        world.setBlock(x, y, z, state.with(Properties.POWER, output).getId(), true);
        notifyStrongTarget(world, x, y, z, state);
    }

    /** Soll-Ausgang aus hinterem Eingang (Signal/Container) und den Seiten. */
    private static int computeOutput(World world, int x, int y, int z, BlockState state) {
        Direction out = state.get(Properties.FACING);
        int rear = rearInput(world, x, y, z, out.opposite());
        int side = Math.max(sideInput(world, x, y, z, out.rotateYCW()),
                sideInput(world, x, y, z, out.rotateYCCW()));
        if (state.get(Properties.MODE) == ComparatorMode.SUBTRACT) {
            return Math.max(0, rear - side);
        }
        return rear >= side ? rear : 0;
    }

    /** Hinterer Eingang: Container-Messung hat Vorrang vor dem Redstone-Signal (MC). */
    private static int rearInput(World world, int x, int y, int z, Direction back) {
        int bx = x + back.offsetX(), by = y + back.offsetY(), bz = z + back.offsetZ();
        int container = containerSignal(world, bx, by, bz);
        if (container < 0 && Blocks.getState(world.getBlock(bx, by, bz)).isOpaqueCube()) {
            /* Durch EINEN opaken Block hindurch messen (MC). */
            container = containerSignal(world, bx + back.offsetX(), by + back.offsetY(), bz + back.offsetZ());
        }
        if (container >= 0) return container;
        return RedstonePower.emittedSignal(world, bx, by, bz, back.opposite(), false);
    }

    /** Füllstands-Signal eines Containers oder −1 (kein Container). */
    private static int containerSignal(World world, int x, int y, int z) {
        BlockEntity be = world.getBlockEntity(x, y, z);
        if (be == null) return -1;
        ItemStorage storage = be.getCapability(Capabilities.ITEM_STORAGE, null).orElse(null);
        if (storage == null) return -1;
        float fullness = 0f;
        boolean any = false;
        for (int i = 0; i < storage.size(); i++) {
            ItemStack stack = storage.get(i);
            if (stack.isEmpty()) continue;
            any = true;
            fullness += (float) stack.getCount() / stack.getMaxStackSize();
        }
        if (!any) return 0;
        return (int) Math.floor(1 + 14f * (fullness / storage.size()));
    }

    /** Seiten-Eingang: nur echte Redstone-Komponenten zählen (MC). */
    private static int sideInput(World world, int x, int y, int z, Direction side) {
        int sx = x + side.offsetX(), sz = z + side.offsetZ();
        BlockState neighbor = Blocks.getState(world.getBlock(sx, y, sz));
        if (!RedstonePower.isSideInputSource(neighbor)) return 0;
        return RedstonePower.emittedSignal(world, sx, y, sz, side.opposite(), false);
    }

    private static void notifyStrongTarget(World world, int x, int y, int z, BlockState state) {
        Direction out = state.get(Properties.FACING);
        int tx = x + out.offsetX(), tz = z + out.offsetZ();
        if (Blocks.getState(world.getBlock(tx, y, tz)).isOpaqueCube()) {
            world.updateNeighbors(tx, y, tz);
        }
    }

    /* --- Ausgang: POWER, schwach UND stark, nur in FACING-Richtung --- */

    @Override
    public int weakPower(World world, int x, int y, int z, BlockState state, Direction side) {
        return side == state.get(Properties.FACING) ? state.get(Properties.POWER) : 0;
    }

    @Override
    public int strongPower(World world, int x, int y, int z, BlockState state, Direction side) {
        return weakPower(world, x, y, z, state, side);
    }

    /** Staub verbindet sich nur mit Ein- und Ausgang. */
    @Override
    public boolean connectsRedstoneWire(BlockState state, Direction side) {
        Direction facing = state.get(Properties.FACING);
        return side == facing || side == facing.opposite();
    }
}
