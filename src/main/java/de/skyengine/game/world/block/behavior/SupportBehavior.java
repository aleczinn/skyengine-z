package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.block.state.BlockHalf;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Datengetriebene Stütz-/Platzierungsregel (Block-JSON {@code place_on} /
 * {@code place_on_full_top}): Platzieren nur auf gültigem Träger, und wird der Träger
 * ungültig, entfernt sich der Block selbst (wie {@link PlantBehavior}, kein Drop).
 *
 * <ul>
 *   <li>{@code place_on}: Träger muss einer der gelisteten Blöcke sein (Cactus: Sand/Cactus).</li>
 *   <li>{@code place_on_full_top}: Träger braucht eine volle tragende Oberseite — Vollblock
 *       oder Kollisionsbox, die x/z komplett abdeckt und bis y=1 reicht (Top-Slab,
 *       Kopfüber-Treppe).</li>
 *   <li>Beides gesetzt = beide Bedingungen (UND).</li>
 *   <li>States mit {@code HALF=TOP} (obere Tür-Hälfte) überspringen die Prüfung — sie stehen
 *       auf ihrer unteren Hälfte; deren Entfernung regelt die Gegenhälften-Logik.</li>
 * </ul>
 */
public final class SupportBehavior implements BlockBehavior {

    private static final float EPS = 0.001F;

    private final Set<Identifier> allowedGround; // null = keine Block-Einschränkung
    private final boolean requireFullTop;

    public SupportBehavior(List<String> allowedGroundIds, boolean requireFullTop) {
        this.allowedGround = allowedGroundIds == null ? null
                : allowedGroundIds.stream().map(Identifier::of).collect(Collectors.toSet());
        this.requireFullTop = requireFullTop;
    }

    @Override
    public boolean canPlace(PlacementContext ctx, BlockState state) {
        if (isUpperHalf(state)) return true;
        return this.isValidSupport(Blocks.getState(ctx.world().getBlock(ctx.x(), ctx.y() - 1, ctx.z())));
    }

    @Override
    public BlockState onNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        if (isUpperHalf(state)) return state;
        if (this.isValidSupport(Blocks.getState(world.getBlock(x, y - 1, z)))) return state;
        return Blocks.getState(Blocks.AIR); // Stütze ungültig -> zerbricht (kein Drop)
    }

    private boolean isValidSupport(BlockState support) {
        if (this.allowedGround != null && !this.allowedGround.contains(support.getBlock().getIdentifier())) {
            return false;
        }
        return !this.requireFullTop || hasFullTopFace(support);
    }

    /** Volle tragende Oberseite: Vollwürfel oder Kollisionsbox über ganz x/z bis y=1. */
    private static boolean hasFullTopFace(BlockState state) {
        if (state.isOpaqueCube()) return true;
        for (AABB box : state.getBlock().getCollisionShape(state).boxes()) {
            if (box.maxY >= 1F - EPS
                    && box.minX <= EPS && box.maxX >= 1F - EPS
                    && box.minZ <= EPS && box.maxZ >= 1F - EPS) {
                return true;
            }
        }
        return false;
    }

    /** Obere Hälfte eines Zwei-Block-Multiblocks (Tür)? Steht auf der unteren Hälfte. */
    private static boolean isUpperHalf(BlockState state) {
        return state.getValues().containsKey(Properties.HALF)
                && state.get(Properties.HALF) == BlockHalf.TOP;
    }
}
