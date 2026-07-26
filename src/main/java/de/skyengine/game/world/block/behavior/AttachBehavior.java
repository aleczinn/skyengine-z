package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.state.AttachFace;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;

import java.util.Set;

/**
 * Hängt einen Block an die angeklickte Fläche (Fackel, später Hebel/Knopf/Leiter): die
 * getroffene Face bestimmt {@link Properties#ATTACH}, bei WALL zusätzlich
 * {@link Properties#FACING} — die Richtung, in die der Block vom Träger WEG zeigt.
 *
 * <p>Verschwindet der Träger, entfernt sich der Block selbst (gleiches Muster wie
 * {@link PlantBehavior}/{@link SupportBehavior}; {@code World.updateStateAt} kaskadiert das).
 *
 * @param allowed erlaubte Trägerflächen — eine Fackel kann nicht von der Decke hängen,
 *                ein Hebel schon. Nicht erlaubte Flächen lehnt {@link #canPlace} ab.
 */
public record AttachBehavior(Set<AttachFace> allowed) implements BlockBehavior {

    @Override
    public BlockState onPlace(PlacementContext ctx, BlockState state) {
        if (ctx.faceY() > 0) return state.with(Properties.ATTACH, AttachFace.FLOOR);
        if (ctx.faceY() < 0) return state.with(Properties.ATTACH, AttachFace.CEILING);
        /* Seitliche Face: die Normale zeigt vom Träger weg — genau die Blickrichtung des Blocks. */
        return state.with(Properties.ATTACH, AttachFace.WALL)
                .with(Properties.FACING, horizontalOf(ctx.faceX(), ctx.faceZ()));
    }

    /** Getroffene Seiten-Normale -> horizontale Richtung (Fallback NORTH bei 0/0). */
    private static Direction horizontalOf(int faceX, int faceZ) {
        if (faceX > 0) return Direction.EAST;
        if (faceX < 0) return Direction.WEST;
        if (faceZ > 0) return Direction.SOUTH;
        return Direction.NORTH;
    }

    @Override
    public boolean canPlace(PlacementContext ctx, BlockState state) {
        if (!this.allowed.contains(state.get(Properties.ATTACH))) return false;
        return hasSupport(ctx.world(), ctx.x(), ctx.y(), ctx.z(), state);
    }

    @Override
    public BlockState onNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        if (hasSupport(world, x, y, z, state)) return state;
        return Blocks.getState(Blocks.AIR); // Träger weg -> zerbricht (kein Drop)
    }

    /** Der Träger liegt der Ausrichtung genau gegenüber: Boden unten, Decke oben, Wand hinten. */
    private static boolean hasSupport(World world, int x, int y, int z, BlockState state) {
        return switch (state.get(Properties.ATTACH)) {
            case FLOOR -> Blocks.getState(world.getBlock(x, y - 1, z)).isSolid();
            case CEILING -> Blocks.getState(world.getBlock(x, y + 1, z)).isSolid();
            case WALL -> {
                Direction back = state.get(Properties.FACING).opposite();
                yield Blocks.getState(world.getBlock(
                        x + back.offsetX(), y + back.offsetY(), z + back.offsetZ())).isSolid();
            }
        };
    }
}
