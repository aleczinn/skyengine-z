package de.skyengine.game.world.block.archetype;

import de.skyengine.game.world.block.behavior.RedstoneWireBehavior;
import de.skyengine.game.world.block.json.BlockDefinition;
import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.game.world.block.model.BlockModels;
import de.skyengine.game.world.block.model.ModelGenerator;
import de.skyengine.game.world.block.model.ModelLoader;
import de.skyengine.game.world.block.shape.BlockShape;
import de.skyengine.game.world.block.shape.ShapeProvider;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Properties;
import de.skyengine.game.world.block.state.RedstoneSide;
import de.skyengine.game.world.redstone.RedstoneColors;

import java.util.ArrayList;
import java.util.List;

/**
 * Redstone-Staub: 4× Tri-State-Verbindung × power 0..15 = <b>1296 States</b> — power MUSS in
 * den State, denn die Sektions-Palette ist die Persistenz des Clock-Zustands. Die Geometrie
 * hängt aber nur an den 81 Verbindungs-Kombinationen: der {@link ModelGenerator} cached sie
 * und erzeugt je State nur Quad-Kopien mit dem power-Tint (MC-Farbformel dunkelrot→hellrot).
 * Multipart wäre der Vanilla-Weg, kann hier aber weder {@code side|up}-ODER noch den
 * power-Tint ausdrücken — der Generator ist die kleinere Lösung.
 */
public final class RedstoneWireArchetype implements Archetype {

    /** MC-Umriss: flache 1-px-Platte; keine Kollision. */
    private static final BlockShape OUTLINE = BlockShape.box(0, 0, 0, 1, 1 / 16.0, 1);

    @Override
    public void configure(BlockConfig.Builder cfg, BlockDefinition def) {
        String base = "block/" + def.id.substring(def.id.indexOf(':') + 1);
        cfg.property(Properties.WIRE_NORTH)
                .property(Properties.WIRE_EAST)
                .property(Properties.WIRE_SOUTH)
                .property(Properties.WIRE_WEST)
                .property(Properties.POWER)
                .behavior(new RedstoneWireBehavior())
                .model(new WireModelGenerator(base))
                .shapes(new ShapeProvider() {
                    @Override public BlockShape collision(BlockState state) { return BlockShape.EMPTY; }
                    @Override public BlockShape outline(BlockState state) { return OUTLINE; }
                })
                .opaque(state -> false);
    }

    /**
     * Setzt die Staub-Geometrie aus den Vanilla-Teilmodellen zusammen (Multipart-Logik aus
     * MCs redstone_wire.json in Java) und färbt sie nach power. Läuft nur beim Registry-Bake
     * auf einem Thread — der Geometrie-Cache braucht keine Synchronisierung.
     */
    private static final class WireModelGenerator implements ModelGenerator {

        /** MC-Farbformel je power p (f = p/15): r = 0.6f+0.4 (bzw. 0.3 bei p=0), g/b quadratisch. */
        private final String base;
        private final BakedQuad[][] geometry = new BakedQuad[81][];

        WireModelGenerator(String base) {
            this.base = base;
        }

        @Override
        public BakedQuad[] bake(BlockState state) {
            RedstoneSide n = state.get(Properties.WIRE_NORTH);
            RedstoneSide e = state.get(Properties.WIRE_EAST);
            RedstoneSide s = state.get(Properties.WIRE_SOUTH);
            RedstoneSide w = state.get(Properties.WIRE_WEST);
            int key = n.ordinal() + e.ordinal() * 3 + s.ordinal() * 9 + w.ordinal() * 27;
            BakedQuad[] geo = this.geometry[key];
            if (geo == null) {
                geo = buildGeometry(n, e, s, w);
                this.geometry[key] = geo;
            }
            int tint = RedstoneColors.forPower(state.get(Properties.POWER));
            BakedQuad[] out = new BakedQuad[geo.length];
            for (int i = 0; i < geo.length; i++) {
                BakedQuad q = geo[i];
                /* Vertices werden geteilt (read-only) — nur der Tint unterscheidet die States. */
                out[i] = new BakedQuad(q.vertices(), q.textureLayer(), q.cullFace(), q.face(),
                        q.brightness(), tint, BakedQuad.TINT_NONE);
            }
            return out;
        }

        /** Multipart-Regeln aus MCs redstone_wire.json: Punkt bei Isolation oder Ecke, Streifen je Seite, Wand-Teil bei UP. */
        private BakedQuad[] buildGeometry(RedstoneSide n, RedstoneSide e, RedstoneSide s, RedstoneSide w) {
            boolean cn = n.isConnected(), ce = e.isConnected(), cs = s.isConnected(), cw = w.isConnected();
            List<BakedQuad[]> parts = new ArrayList<>();
            boolean dot = (!cn && !ce && !cs && !cw)
                    || (cn && ce) || (ce && cs) || (cs && cw) || (cw && cn);
            if (dot) parts.add(ModelLoader.bake(this.base + "_dot", 0, 0).quads());
            if (cn) parts.add(ModelLoader.bake(this.base + "_side0", 0, 0).quads());
            if (cs) parts.add(ModelLoader.bake(this.base + "_side_alt0", 0, 0).quads());
            if (ce) parts.add(ModelLoader.bake(this.base + "_side_alt1", 0, 270).quads());
            if (cw) parts.add(ModelLoader.bake(this.base + "_side1", 0, 270).quads());
            if (n == RedstoneSide.UP) parts.add(ModelLoader.bake(this.base + "_up", 0, 0).quads());
            if (e == RedstoneSide.UP) parts.add(ModelLoader.bake(this.base + "_up", 0, 90).quads());
            if (s == RedstoneSide.UP) parts.add(ModelLoader.bake(this.base + "_up", 0, 180).quads());
            if (w == RedstoneSide.UP) parts.add(ModelLoader.bake(this.base + "_up", 0, 270).quads());
            return BlockModels.concat(parts.toArray(new BakedQuad[0][]));
        }

    }
}
