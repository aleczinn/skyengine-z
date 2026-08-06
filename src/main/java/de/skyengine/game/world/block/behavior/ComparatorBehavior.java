package de.skyengine.game.world.block.behavior;

import de.skyengine.audio.SoundManager;
import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.Capabilities;
import de.skyengine.game.world.block.entity.ComparatorBlockEntity;
import de.skyengine.game.world.block.entity.ItemStorage;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.ComparatorMode;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.chunk.Chunk;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.redstone.RedstonePower;
import de.skyengine.game.world.tick.TickPriority;

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
 * POWERED im State steuert nur die Optik; die Ausgangsstärke liegt wie in Vanilla persistent
 * in der {@link ComparatorBlockEntity}. Änderungen takten mit 1 Redstone-Tick (2 Game-Ticks).
 * Container-Mutationen stoßen wie in Vanilla unmittelbar
 * {@code World.updateComparatorOutputs} an.
 */
public final class ComparatorBehavior implements BlockBehavior {

    /**
     * Initialer Abgleich nach Chunk-Load. Neue Saves besitzen bereits OutputSignal; alte Saves
     * bekommen beim Deserialisieren erstmals eine ComparatorBlockEntity mit Ausgang 0.
     */
    public static void reconcileLoadedChunk(World world, Chunk chunk) {
        for (BlockEntity blockEntity : chunk.blockEntities()) {
            if (!(blockEntity instanceof ComparatorBlockEntity)) continue;
            int x = blockEntity.getPos().x(), y = blockEntity.getPos().y(), z = blockEntity.getPos().z();
            BlockState state = Blocks.getState(world.getBlock(x, y, z));
            ComparatorBehavior behavior = state.getBlock().getBehavior(ComparatorBehavior.class);
            if (behavior != null) behavior.onNeighborUpdate(world, x, y, z, state);
        }
    }

    @Override
    public boolean reconcileRedstoneOnChunkBoundary() {
        return true;
    }

    @Override
    public BlockState onPlace(PlacementContext ctx, BlockState state) {
        return state.with(Properties.FACING, Direction.fromYaw(ctx.playerYaw()))
                .with(Properties.MODE, ComparatorMode.COMPARE)
                .with(Properties.POWERED, false);
    }

    @Override
    public boolean onUse(World world, int x, int y, int z, BlockState state) {
        ComparatorMode next = state.get(Properties.MODE) == ComparatorMode.COMPARE
                ? ComparatorMode.SUBTRACT : ComparatorMode.COMPARE;
        BlockState toggled = state.with(Properties.MODE, next);
        world.setBlock(x, y, z, toggled.getId(), true);
        SoundManager sound = world.getSoundManager();
        if (sound != null) {
            sound.playComparatorClick(next == ComparatorMode.SUBTRACT,
                    x + 0.5, y + 0.5, z + 0.5);
        }
        refreshOutputState(world, x, y, z, toggled);
        return true;
    }

    @Override
    public BlockState onNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        int output = computeOutput(world, x, y, z, state);
        if ((output != outputSignal(world, x, y, z)
                || state.get(Properties.POWERED) != shouldTurnOn(world, x, y, z, state))
                && !world.willTickThisTick(x, y, z)) {
            world.scheduleTick(x, y, z, 2,
                    shouldPrioritize(world, x, y, z, state)
                            ? TickPriority.HIGH : TickPriority.NORMAL);
        }
        return state;
    }

    @Override
    public void scheduledTick(World world, int x, int y, int z, BlockState state) {
        if (!state.getValues().containsKey(Properties.MODE)) return;   // tolerantes Feuern
        refreshOutputState(world, x, y, z, state);
    }

    /** Exakte Reihenfolge von Vanilla ComparatorBlock#refreshOutputState. */
    private static void refreshOutputState(World world, int x, int y, int z, BlockState state) {
        int output = computeOutput(world, x, y, z, state);
        int oldOutput = outputSignal(world, x, y, z);
        BlockEntity blockEntity = world.getBlockEntity(x, y, z);
        if (blockEntity instanceof ComparatorBlockEntity comparator) {
            comparator.setOutputSignal(output);
        }

        /* Compare benachrichtigt selbst bei unveränderter Stärke; Subtract unterdrückt das. */
        if (oldOutput == output && state.get(Properties.MODE) != ComparatorMode.COMPARE) return;

        boolean shouldPower = shouldTurnOn(world, x, y, z, state);
        if (state.get(Properties.POWERED) != shouldPower) {
            state = state.with(Properties.POWERED, shouldPower);
            world.setBlock(x, y, z, state.getId(), true);
        }
        notifyStrongTarget(world, x, y, z, state);
    }

    private static int outputSignal(World world, int x, int y, int z) {
        BlockEntity blockEntity = world.getBlockEntity(x, y, z);
        return blockEntity instanceof ComparatorBlockEntity comparator
                ? comparator.getOutputSignal() : 0;
    }

    /** Vanillas separate POWERED-Bedingung; insbesondere Compare-Gleichstand bleibt an. */
    private static boolean shouldTurnOn(World world, int x, int y, int z, BlockState state) {
        Direction out = state.get(Properties.FACING);
        int rear = rearInput(world, x, y, z, out.opposite());
        if (rear == 0) return false;
        int side = Math.max(sideInput(world, x, y, z, out.rotateYCW()),
                sideInput(world, x, y, z, out.rotateYCCW()));
        return rear > side
                || rear == side && state.get(Properties.MODE) == ComparatorMode.COMPARE;
    }

    /**
     * Vanilla DiodeBlock#shouldPrioritize, auf die Engine-Konvention FACING=Ausgang uebersetzt:
     * Eine Diode in der Ausgangszelle priorisiert den Tick, ausser sie zeigt zurueck auf uns.
     */
    private static boolean shouldPrioritize(World world, int x, int y, int z, BlockState state) {
        Direction out = state.get(Properties.FACING);
        int nx = x + out.offsetX(), nz = z + out.offsetZ();
        BlockState neighbor = Blocks.getState(world.getBlock(nx, y, nz));
        boolean diode = neighbor.getValues().containsKey(Properties.DELAY)
                || neighbor.getValues().containsKey(Properties.MODE);
        return diode && neighbor.get(Properties.FACING) != out.opposite();
    }

    /** Soll-Ausgang aus hinterem Eingang (Signal/Container) und den Seiten. */
    static int computeOutput(World world, int x, int y, int z, BlockState state) {
        Direction out = state.get(Properties.FACING);
        int rear = rearInput(world, x, y, z, out.opposite());
        int side = Math.max(sideInput(world, x, y, z, out.rotateYCW()),
                sideInput(world, x, y, z, out.rotateYCCW()));
        if (state.get(Properties.MODE) == ComparatorMode.SUBTRACT) {
            return Math.max(0, rear - side);
        }
        return rear >= side ? rear : 0;
    }

    /** Hinterer Eingang entsprechend Vanilla ComparatorBlock#getInputSignal. */
    private static int rearInput(World world, int x, int y, int z, Direction back) {
        int bx = x + back.offsetX(), by = y + back.offsetY(), bz = z + back.offsetZ();
        BlockState directState = Blocks.getState(world.getBlock(bx, by, bz));
        int signal = RedstonePower.emittedSignal(world, bx, by, bz, back.opposite(), false);

        /* Eine direkte Analogquelle ersetzt das normale Eingangssignal, auch wenn sie 0 liefert. */
        int directAnalog = containerSignal(world, bx, by, bz);
        if (directAnalog >= 0) return directAnalog;

        /* Vanilla schaut nur bei Signal < 15 durch genau EINEN leitenden Vollblock. Dort zaehlen
           eine Analogquelle und exakt ein passend ausgerichtetes Item Frame. Existiert eine
           solche Quelle, ERSETZT ihr Wert das am Vollblock empfangene Redstone-Signal; nur
           Analogquelle und Rahmen werden untereinander per Maximum kombiniert. */
        if (signal < 15 && directState.isRedstoneConductor()) {
            int fx = bx + back.offsetX(), fy = by + back.offsetY(), fz = bz + back.offsetZ();
            int farAnalog = containerSignal(world, fx, fy, fz);
            int frameAnalog = world.getItemFrameAnalogSignal(fx, fy, fz, back);
            int analog = Math.max(farAnalog, frameAnalog);
            if (analog >= 0) signal = analog;
        }
        return signal;
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
            if (!stack.isEmpty()) {
                any = true;
                fullness += (float) stack.getCount() / stack.getMaxStackSize();
            }
        }
        if (!any) return 0;
        return (int) Math.floor(1 + 14f * (fullness / storage.size()));
    }

    /** Seiten-Eingang: nur echte Redstone-Komponenten zählen (MC). */
    private static int sideInput(World world, int x, int y, int z, Direction side) {
        int sx = x + side.offsetX(), sz = z + side.offsetZ();
        BlockState neighbor = Blocks.getState(world.getBlock(sx, y, sz));
        /* Vanillas SignalGetter.getControlInputSignal liest Staub direkt aus dessen POWER-
           Property. Die sichtbare none/side/up-Form ist hier absichtlich irrelevant: sonst
           bricht die seitliche Rückkopplung einer Subtract-Comparator-Clock ab. */
        if (RedstonePower.isWire(neighbor)) return neighbor.get(Properties.POWER);
        if (!RedstonePower.isSideInputSource(neighbor)) return 0;
        return RedstonePower.emittedSignal(world, sx, y, sz, side.opposite(), false);
    }

    /* Zweiter Ring um die Zelle vor dem Ausgang — bedingungslos wie MCs
       DiodeBlock.updateNeighborsInFront, auch wenn dort Luft steht (s. RepeaterBehavior). */
    private static void notifyStrongTarget(World world, int x, int y, int z, BlockState state) {
        Direction out = state.get(Properties.FACING);
        int tx = x + out.offsetX(), tz = z + out.offsetZ();
        world.updateNeighbors(tx, y, tz);
    }

    /* --- Ausgang: POWERED-gated BE-Stärke, schwach UND stark, nur in FACING-Richtung --- */

    @Override
    public int weakPower(World world, int x, int y, int z, BlockState state, Direction side) {
        return side == state.get(Properties.FACING) && state.get(Properties.POWERED)
                ? outputSignal(world, x, y, z) : 0;
    }

    @Override
    public int strongPower(World world, int x, int y, int z, BlockState state, Direction side) {
        return weakPower(world, x, y, z, state, side);
    }

    /** Vanilla: anders als beim Repeater verbindet sich Staub an allen vier Comparator-Seiten. */
    @Override
    public boolean connectsRedstoneWire(BlockState state, Direction side) {
        return side.axis() != Direction.Axis.Y;
    }

    @Override
    public boolean isRedstoneSignalSource() {
        return true;
    }
}
