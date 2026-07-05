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
    private final int[] topTints;
    private final int[] sideTints;
    private final int[] topTintTypes;
    private final int[] sideTintTypes;
    private final boolean[] fluids;

    /** Erst nach BlockRegistry.bake() erzeugen (World.init). */
    public LodBlockAppearance() {
        int count = BlockRegistry.getStateCount();
        this.topLayers = new int[count];
        this.sideLayers = new int[count];
        this.topTints = new int[count];
        this.sideTints = new int[count];
        this.topTintTypes = new int[count];
        this.sideTintTypes = new int[count];
        this.fluids = new boolean[count];

        for (int id = 0; id < count; id++) {
            BlockState state = BlockRegistry.getState(id);
            this.topTints[id] = BakedQuad.WHITE;
            this.sideTints[id] = BakedQuad.WHITE;
            this.fluids[id] = state.isFluid();

            FluidInfo fluid = state.getBlock().getFluidInfo();
            if (fluid != null) {
                this.topLayers[id] = fluid.stillLayer;
                this.sideLayers[id] = fluid.stillLayer;
                if (!fluid.lava) {
                    this.topTints[id] = FluidGeometry.WATER_TINT;
                    this.sideTints[id] = FluidGeometry.WATER_TINT;
                }
                continue;
            }

            int top = -1, side = -1;
            for (BakedQuad quad : state.getModel()) {
                if (top < 0 && quad.cullFace() == 0) {
                    top = quad.textureLayer();
                    this.topTints[id] = quad.tint(); // Vegetations-Tint kommt generisch mit (Gras-Top)
                    this.topTintTypes[id] = quad.tintType();
                }
                if (side < 0 && quad.cullFace() >= 2) {
                    side = quad.textureLayer();
                    this.sideTints[id] = quad.tint();
                    this.sideTintTypes[id] = quad.tintType();
                }
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

    /** Gepackter Multiplikations-Tint 0xRRGGBB der Oberseite (WHITE = neutral). */
    public int topTint(int stateId) {
        return this.topTints[stateId];
    }

    /** Gepackter Multiplikations-Tint 0xRRGGBB der Seiten (WHITE = neutral). */
    public int sideTint(int stateId) {
        return this.sideTints[stateId];
    }

    /** Biome-Tint-Typ der Oberseite ({@code BakedQuad.TINT_*}); NONE = fester Tint. */
    public int topTintType(int stateId) {
        return this.topTintTypes[stateId];
    }

    /** Biome-Tint-Typ der Seiten ({@code BakedQuad.TINT_*}); NONE = fester Tint. */
    public int sideTintType(int stateId) {
        return this.sideTintTypes[stateId];
    }

    /** true für Fluide — deren Zell-Top liegt auf der Quellhöhe (8/9) statt auf Höhe+1. */
    public boolean isFluid(int stateId) {
        return this.fluids[stateId];
    }
}
