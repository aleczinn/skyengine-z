package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.Dimension;
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
 *       Kopfüber-Treppe). Das ist die strenge Variante für Tür, Dioden und Staub.</li>
 *   <li>{@code place_on_center_top}: es genügt, dass die MITTE der Oberseite trägt — MCs
 *       {@code canSupportCenter}. Nur die Druckplatte nutzt das, und nur dadurch lässt sie
 *       sich in MC auf einen Zaunpfosten setzen.</li>
 *   <li>Mehreres gesetzt = alle Bedingungen (UND).</li>
 *   <li>States mit {@code HALF=TOP} (obere Tür-Hälfte) überspringen die Prüfung — sie stehen
 *       auf ihrer unteren Hälfte; deren Entfernung regelt die Gegenhälften-Logik.</li>
 * </ul>
 */
public final class SupportBehavior implements BlockBehavior {

    private static final float EPS = 0.001F;

    /**
     * MCs {@code CENTER_SUPPORT_SHAPE = box(7,0,7,9,10,9)} — das mittlere 2×2-Pixel-Quadrat der
     * Oberseite. Ein Zaunpfosten (6..10 px) deckt es ab, eine Treppenstufe (endet bei 8 px)
     * gerade nicht.
     */
    private static final float CENTER_MIN = 7F / 16F;
    private static final float CENTER_MAX = 9F / 16F;

    private final Set<Identifier> allowedGround; // null = keine Block-Einschränkung
    private final boolean requireFullTop;
    private final boolean requireCenterTop;

    public SupportBehavior(List<String> allowedGroundIds, boolean requireFullTop,
                           boolean requireCenterTop) {
        this.allowedGround = allowedGroundIds == null ? null
                : allowedGroundIds.stream().map(Identifier::of).collect(Collectors.toSet());
        this.requireFullTop = requireFullTop;
        this.requireCenterTop = requireCenterTop;
    }

    @Override
    public boolean canPlace(PlacementContext ctx, BlockState state) {
        if (isUpperHalf(state)) return true;
        return this.isValidSupport(ctx.world(), ctx.x(), ctx.y() - 1, ctx.z(),
                Blocks.getState(ctx.world().getBlock(ctx.x(), ctx.y() - 1, ctx.z())), state);
    }

    @Override
    public BlockState onNeighborUpdate(Dimension world, int x, int y, int z, BlockState state) {
        if (isUpperHalf(state)) return state;
        if (this.isValidSupport(world, x, y - 1, z,
                Blocks.getState(world.getBlock(x, y - 1, z)), state)) return state;
        return Blocks.getState(Blocks.AIR); // Stütze ungültig -> zerbricht (kein Drop)
    }

    private boolean isValidSupport(Dimension world, int supportX, int supportY, int supportZ,
                                   BlockState support, BlockState supported) {
        if (this.allowedGround != null && !this.allowedGround.contains(support.getBlock().getIdentifier())) {
            return false;
        }
        boolean fullTop = support.getBlock() == Blocks.getState(Blocks.MOVING_PISTON).getBlock()
                ? world.getCollisionShape(supportX, supportY, supportZ).isFaceFull(
                        de.skyengine.game.world.block.Direction.UP)
                : hasFullTopFace(support);
        if (this.requireFullTop && !fullTop
                && !isRedstoneWireOnHopper(supported, support)) return false;
        return !this.requireCenterTop || hasCenterTopFace(support);
    }

    /** Vanillas einziger Sonderfall fuer Redstone-Staub: Er darf auf einem Hopper liegen. */
    private static boolean isRedstoneWireOnHopper(BlockState supported, BlockState support) {
        return supported.getValues().containsKey(Properties.WIRE_NORTH)
                && support.getBlock().getBehavior(HopperBehavior.class) != null;
    }

    /** Volle tragende Oberseite: Vollwürfel oder Kollisionsbox über ganz x/z bis y=1. */
    private static boolean hasFullTopFace(BlockState state) {
        return state.getCollisionShape().isFaceFull(de.skyengine.game.world.block.Direction.UP);
    }

    /** Stuetze, auf der Vanilla Redstone-Staub ueberleben laesst: volle Oberseite oder Hopper. */
    public static boolean canSupportRedstoneWire(BlockState state) {
        return hasFullTopFace(state) || state.getBlock().getBehavior(HopperBehavior.class) != null;
    }

    /**
     * Tragende MITTE der Oberseite (MCs {@code canSupportCenter}) — deutlich schwächer als
     * {@link #hasFullTopFace}: es reicht, dass eine Kollisionsbox das mittlere 2×2-Pixel-Quadrat
     * auf Höhe 1 belegt. Zaun und Mauer erfüllen das über ihren Pfosten, Truhe (endet bei 0.875),
     * untere Stufe und normale Treppe nicht.
     */
    private static boolean hasCenterTopFace(BlockState state) {
        return hasTopFace(state, CENTER_MIN, CENTER_MAX);
    }

    /** Deckt eine Kollisionsbox bis y=1 den x/z-Bereich {@code [min, max]} ab? */
    private static boolean hasTopFace(BlockState state, float min, float max) {
        if (state.isOpaqueCube()) return true;
        for (AABB box : state.getBlock().getCollisionShape(state).boxes()) {
            if (box.maxY >= 1F - EPS
                    && box.minX <= min + EPS && box.maxX >= max - EPS
                    && box.minZ <= min + EPS && box.maxZ >= max - EPS) {
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
