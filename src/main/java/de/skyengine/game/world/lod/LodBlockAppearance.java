package de.skyengine.game.world.lod;

import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.archetype.FluidInfo;
import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.chunk.FluidGeometry;

/**
 * LOD-Darstellung pro BlockState-ID: Top-Layer, Seiten-Layer und Tint — einmalig nach dem
 * Registry-Bake aus den real gebackenen Modellen aufgelöst (nie Layer raten!). Fluide haben
 * kein gebackenes Modell und liefern ihre Still-Textur aus der {@link FluidInfo}; Wasser
 * bekommt den {@link FluidGeometry#WATER_TINT} (Texturen sind grau), Lava bleibt neutral.
 * Immutable — wird von den LOD-Worker-Jobs nur gelesen.
 */
public final class LodBlockAppearance {

    private final int[] topLayers;
    private final int[] sideLayers;
    private final int[] tints;

    /** Erst nach BlockRegistry.bake() erzeugen (World.init). */
    public LodBlockAppearance() {
        int count = BlockRegistry.getStateCount();
        this.topLayers = new int[count];
        this.sideLayers = new int[count];
        this.tints = new int[count];

        for (int id = 0; id < count; id++) {
            BlockState state = BlockRegistry.getState(id);
            this.tints[id] = BakedQuad.WHITE;

            FluidInfo fluid = state.getBlock().getFluidInfo();
            if (fluid != null) {
                this.topLayers[id] = fluid.stillLayer;
                this.sideLayers[id] = fluid.stillLayer;
                if (!fluid.lava) this.tints[id] = FluidGeometry.WATER_TINT;
                continue;
            }

            int top = -1, side = -1;
            for (BakedQuad quad : state.getModel()) {
                if (top < 0 && quad.cullFace() == 0) top = quad.textureLayer();
                if (side < 0 && quad.cullFace() >= 2) side = quad.textureLayer();
            }
            /* Fallbacks: fehlt eine Seite, die andere nehmen; Luft/Cross-Modelle tauchen
               als Oberflächenblock ohnehin nicht auf. */
            this.topLayers[id] = top >= 0 ? top : Math.max(side, 0);
            this.sideLayers[id] = side >= 0 ? side : this.topLayers[id];
        }
    }

    public int topLayer(int stateId) {
        return this.topLayers[stateId];
    }

    public int sideLayer(int stateId) {
        return this.sideLayers[stateId];
    }

    /** Gepackter Multiplikations-Tint 0xRRGGBB (WHITE = neutral). */
    public int tint(int stateId) {
        return this.tints[stateId];
    }
}
