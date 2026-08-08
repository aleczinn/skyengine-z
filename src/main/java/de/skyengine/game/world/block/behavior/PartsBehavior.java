package de.skyengine.game.world.block.behavior;

import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Direction;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.BlockStateCodec;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.state.Property;
import de.skyengine.game.world.loot.LootContext;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ein Block, der mehrere Zellen belegt (Tür, hohe Pflanze, später Bett) — deklarativ aus der
 * Block-JSON statt als eigenes Behavior je Blockart:
 *
 * <pre>
 * "parts": {
 *   "property": "half",
 *   "offsets": { "bottom": [0,0,0], "top": [0,1,0] }
 * }
 * </pre>
 *
 * Genau ein Offset muss {@code [0,0,0]} sein — dieser Teil ist der Ursprung und wird platziert,
 * die übrigen setzt {@link #onPlaced}. Mit {@code "relative_to": "facing"} zählen die Offsets in
 * der Blickrichtung des Blocks ({@code +z} = vorwärts, {@code +x} = rechts) statt in Weltachsen;
 * so liegt der Bett-Kopf immer korrekt, egal wie das Bett steht.
 *
 * <p>Fehlt ein Geschwisterteil, entfernt sich der Rest über {@link #onNeighborUpdate} selbst —
 * dasselbe Kaskaden-Muster wie bisher bei Tür und tall_grass. Ein eigenes {@code onBreak} gibt es
 * bewusst NICHT: die Kaskade räumt bereits auf, und ein zweiter Pfad würde nur doppelt arbeiten.
 */
public final class PartsBehavior implements BlockBehavior {

    private static final Logger LOGGER = LogManager.getLogger(PartsBehavior.class.getName());

    private final String property;
    private final String origin;
    private final Map<String, int[]> offsets;
    private final boolean relativeToFacing;

    private PartsBehavior(String property, String origin, Map<String, int[]> offsets, boolean relativeToFacing) {
        this.property = property;
        this.origin = origin;
        this.offsets = offsets;
        this.relativeToFacing = relativeToFacing;
    }

    /** {@code null} bei ungültiger Deklaration (Grund steht dann im Log). */
    public static PartsBehavior of(BlockDefinition.PartsDef def, String blockId) {
        if (def.property == null || def.offsets == null || def.offsets.size() < 2) {
            LOGGER.error("parts in " + blockId + " braucht 'property' und mindestens zwei 'offsets'");
            return null;
        }
        String origin = null;
        Map<String, int[]> offsets = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : def.offsets.entrySet()) {
            int[] o = e.getValue();
            if (o == null || o.length != 3) {
                LOGGER.error("parts-Offset '" + e.getKey() + "' in " + blockId + " braucht [dx,dy,dz]");
                return null;
            }
            offsets.put(e.getKey(), o);
            if (o[0] == 0 && o[1] == 0 && o[2] == 0) {
                if (origin != null) {
                    LOGGER.error("parts in " + blockId + " hat mehr als einen Ursprung [0,0,0]");
                    return null;
                }
                origin = e.getKey();
            }
        }
        if (origin == null) {
            LOGGER.error("parts in " + blockId + " hat keinen Ursprung [0,0,0]");
            return null;
        }
        boolean relative = "facing".equalsIgnoreCase(def.relative_to);
        return new PartsBehavior(def.property, origin, offsets, relative);
    }

    /** Platziert wird immer der Ursprungs-Teil; die übrigen kommen in {@link #onPlaced}. */
    @Override
    public BlockState onPlace(PlacementContext ctx, BlockState state) {
        return with(state, this.origin);
    }

    /** Passt nur, wenn alle übrigen Zellen frei sind. */
    @Override
    public boolean canPlace(PlacementContext ctx, BlockState state) {
        Direction facing = facingOf(state);
        for (Map.Entry<String, int[]> e : this.offsets.entrySet()) {
            if (e.getKey().equals(this.origin)) continue;
            int[] o = rotate(e.getValue(), facing);
            if (ctx.world().getBlock(ctx.x() + o[0], ctx.y() + o[1], ctx.z() + o[2]) != Blocks.AIR) {
                return false;
            }
        }
        return true;
    }

    /**
     * Setzt die übrigen Teile, nachdem der Ursprung validiert platziert wurde. Ohne
     * Nachbar-Kaskade — die löst {@code World.placeBlock} erst danach aus, sonst würde sich der
     * Ursprung selbst entfernen, bevor seine Geschwister existieren.
     */
    @Override
    public void onPlaced(World world, int x, int y, int z, BlockState state) {
        Direction facing = facingOf(state);
        for (Map.Entry<String, int[]> e : this.offsets.entrySet()) {
            if (e.getKey().equals(this.origin)) continue;
            int[] o = rotate(e.getValue(), facing);
            int px = x + o[0], py = y + o[1], pz = z + o[2];
            if (world.getBlock(px, py, pz) == Blocks.AIR) {
                world.setBlock(px, py, pz, with(state, e.getKey()).getId(), false);
            }
        }
    }

    /** Fehlt ein Geschwisterteil, entfernt sich dieser Teil selbst (kein Drop). */
    @Override
    public BlockState onNeighborUpdate(World world, int x, int y, int z, BlockState state) {
        String self = partOf(state);
        if (self == null) return state;   // z.B. bereits zu Luft geworden
        int[] own = this.offsets.get(self);
        if (own == null) return state;

        Direction facing = facingOf(state);
        int[] mine = rotate(own, facing);
        int ox = x - mine[0], oy = y - mine[1], oz = z - mine[2];

        for (Map.Entry<String, int[]> e : this.offsets.entrySet()) {
            if (e.getKey().equals(self)) continue;
            int[] o = rotate(e.getValue(), facing);
            BlockState other = Blocks.getState(world.getBlock(ox + o[0], oy + o[1], oz + o[2]));
            if (other.getBlock() != state.getBlock() || !e.getKey().equals(partOf(other))) {
                return Blocks.getState(Blocks.AIR);
            }
        }
        return state;
    }

    @Override
    public long canonicalLootPosition(LootContext context) {
        String self = partOf(context.state());
        int[] own = self == null ? null : this.offsets.get(self);
        if (own == null) return de.skyengine.game.world.block.BlockPos.asLong(context.x(), context.y(), context.z());
        int[] rotated = rotate(own, facingOf(context.state()));
        return de.skyengine.game.world.block.BlockPos.asLong(
                context.x() - rotated[0], context.y() - rotated[1], context.z() - rotated[2]);
    }

    /** Das verschwundene Geschwisterteil besitzt bereits den einzigen gemeinsamen Drop. */
    @Override
    public boolean dropsWhenUnsupported() {
        return false;
    }

    /**
     * Rechnet einen Offset in Weltachsen um. Ohne {@code relative_to: "facing"} bleibt er, wie er
     * ist; sonst zählt {@code +z} als vorwärts in Blickrichtung und {@code +x} als rechts davon.
     * Bewusst public und pur — der Rest dieser Klasse braucht eine Welt und ist nur im laufenden
     * Spiel prüfbar, diese Umrechnung dagegen nicht.
     */
    public static int[] rotate(int[] offset, Direction facing) {
        if (facing == null) return offset;
        Direction right = facing.rotateYCW();
        return new int[]{
                offset[2] * facing.offsetX() + offset[0] * right.offsetX(),
                offset[1],
                offset[2] * facing.offsetZ() + offset[0] * right.offsetZ(),
        };
    }

    private Direction facingOf(BlockState state) {
        if (!this.relativeToFacing) return null;
        return state.getValues().containsKey(Properties.FACING) ? state.get(Properties.FACING) : null;
    }

    /** Aktueller Teil-Wert dieses States, oder {@code null} wenn der Block die Property nicht hat. */
    private String partOf(BlockState state) {
        for (Map.Entry<Property<?>, Object> e : state.getValues().entrySet()) {
            if (e.getKey().getName().equals(this.property)) return BlockStateCodec.valueString(e.getValue());
        }
        return null;
    }

    /** Setzt die Teil-Property auf den Wert mit dieser Textform (wie {@code BlockStateCodec}). */
    @SuppressWarnings("unchecked")
    private BlockState with(BlockState state, String value) {
        for (Property<?> p : state.getValues().keySet()) {
            if (!p.getName().equals(this.property)) continue;
            for (Object candidate : p.getValues()) {
                if (BlockStateCodec.valueString(candidate).equals(value)) {
                    return state.with((Property<Object>) p, candidate);
                }
            }
        }
        return state;
    }
}
