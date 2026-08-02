package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;

import java.util.HashMap;
import java.util.Map;

/**
 * Druckplatte: liegt eine Entity darauf, geht POWERED an; nach {@code releaseTicks} ohne
 * Berührung wieder aus.
 *
 * <p>Ohne Redstone wirkt das Signal nur auf die direkten Nachbarn — das Aufwecken übernimmt der
 * ganz normale Nachbar-Update-Ring, den {@code setBlock(..., true)} auslöst. Tür und Falltür
 * lesen es in ihrem {@code onNeighborUpdate}.
 *
 * <p><b>Warum eine Map und kein reiner Timer:</b> es gibt keine Abfrage „welche Entities stecken
 * in dieser Box". Die Platte merkt sich deshalb, wann sie zuletzt berührt wurde, und der geplante
 * Tick verlängert sich selbst, solange jemand draufsteht. Der Inhalt ist bewusst transient: er
 * überlebt einen Neustart nicht, aber der geplante Tick tut es (Tick-Persistenz v2) — eine beim
 * Beenden gedrückte Platte fällt danach also von selbst zurück.
 */
public final class PressurePlateBehavior implements BlockBehavior {

    private final int releaseTicks;
    /** Position -> Spielzeit der letzten Berührung. Nur Tick-Thread, deshalb ungesichert. */
    private final Map<Long, Long> lastTouch = new HashMap<>();

    public PressurePlateBehavior(int releaseTicks) {
        this.releaseTicks = Math.max(1, releaseTicks);
    }

    @Override
    public void onEntityInside(World world, int x, int y, int z, BlockState state) {
        this.lastTouch.put(key(x, y, z), world.getGameTime());
        if (state.get(Properties.POWERED)) return;

        /* true = Nachbar-Update: nur so erfahren Tür und Falltür überhaupt davon. */
        world.setBlock(x, y, z, state.with(Properties.POWERED, true).getId(), true);
        world.scheduleTick(x, y, z, this.releaseTicks);
        world.updateNeighbors(x, y - 1, z);   // zweiter Ring um den stark gepowerten Träger
    }

    @Override
    public void scheduledTick(World world, int x, int y, int z, BlockState state) {
        if (!state.get(Properties.POWERED)) return;

        Long touched = this.lastTouch.get(key(x, y, z));
        if (touched != null && world.getGameTime() - touched <= 1) {
            world.scheduleTick(x, y, z, this.releaseTicks);   // steht noch jemand drauf
            return;
        }
        this.lastTouch.remove(key(x, y, z));
        world.setBlock(x, y, z, state.with(Properties.POWERED, false).getId(), true);
        world.updateNeighbors(x, y - 1, z);
    }

    @Override
    public void onBreak(World world, int x, int y, int z, BlockState state) {
        /* Sonst bliebe der Eintrag einer gedrückt abgebauten Platte für immer stehen (Leak). */
        this.lastTouch.remove(key(x, y, z));
    }

    /* --- Redstone: gedrückt = 15 in alle Richtungen (schwach), stark nur nach unten --- */

    @Override
    public int weakPower(World world, int x, int y, int z, BlockState state, Direction side) {
        return state.get(Properties.POWERED) ? 15 : 0;
    }

    @Override
    public int strongPower(World world, int x, int y, int z, BlockState state, Direction side) {
        return state.get(Properties.POWERED) && side == Direction.DOWN ? 15 : 0;
    }

    @Override
    public boolean connectsRedstoneWire(BlockState state, Direction side) {
        return true;
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }
}
