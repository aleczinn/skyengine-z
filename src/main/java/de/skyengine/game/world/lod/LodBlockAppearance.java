package de.skyengine.game.world.lod;

import de.skyengine.game.world.block.BlockRegistry;
import de.skyengine.game.world.block.RenderLayer;
import de.skyengine.game.world.block.archetype.FluidInfo;
import de.skyengine.game.world.block.model.BakedQuad;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.chunk.FluidGeometry;

/**
 * LOD-Darstellung pro BlockState-ID: Top-/Seiten-Layer, Tint und AO-Okklusion — einmalig nach
 * dem Registry-Bake aus den real gebackenen Modellen aufgelöst (nie Layer raten!). Fluide haben
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
    private final boolean[] aoOccluders;
    private final boolean[] fluids;
    private final boolean[] translucent;
    private final boolean[] skyLightAttenuatingFluids;

    /** Erst nach BlockRegistry.bake() erzeugen (World.init). */
    public LodBlockAppearance() {
        int count = BlockRegistry.getStateCount();
        this.topLayers = new int[count];
        this.sideLayers = new int[count];
        this.topTints = new int[count];
        this.sideTints = new int[count];
        this.topTintTypes = new int[count];
        this.sideTintTypes = new int[count];
        this.aoOccluders = new boolean[count];
        this.fluids = new boolean[count];
        this.translucent = new boolean[count];
        this.skyLightAttenuatingFluids = new boolean[count];

        for (int id = 0; id < count; id++) {
            BlockState state = BlockRegistry.getState(id);
            this.topTints[id] = BakedQuad.WHITE;
            this.sideTints[id] = BakedQuad.WHITE;
            this.aoOccluders[id] = state.occludesAo();
            this.fluids[id] = state.isFluid();
            this.translucent[id] = state.getRenderLayer() == RenderLayer.TRANSLUCENT;

            FluidInfo fluid = state.getBlock().getFluidInfo();
            if (fluid != null) {
                this.skyLightAttenuatingFluids[id] = !fluid.lava;
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
            /* Fallbacks: fehlt eine Seite, die andere nehmen. Blöcke ganz ohne gebackenes
               Quad (Luft, Cross-Modelle) bleiben -1 — der LodMesher überspringt sie; die
               frühere 0-Substitution zeichnete Void-Flächen mit dem zufällig ersten
               Textur-Layer (orange Akazien-Ebene bei importierten Welten). */
            this.topLayers[id] = top >= 0 ? top : side;
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

    /** Package-intern: dieselbe AO-Okkludierer-Regel wie der normale ChunkMesher. */
    boolean occludesAo(int stateId) {
        return this.aoOccluders[stateId];
    }

    /** true für Fluide — deren Zell-Top liegt auf der Quellhöhe (8/9) statt auf Höhe+1. */
    public boolean isFluid(int stateId) {
        return this.fluids[stateId];
    }

    public boolean isTranslucent(int stateId) {
        return this.translucent[stateId];
    }

    /** true für Wasser-States, deren LOD-Säule Himmelslicht um eine Stufe pro Block dämpft. */
    public boolean attenuatesSkyLight(int stateId) {
        return this.skyLightAttenuatingFluids[stateId];
    }
}
