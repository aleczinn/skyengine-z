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
    /* Seiten-Overlay (z.B. getönter Grasrand über der Dirt-Seite): Layer/Tint des separat
       gebackenen Overlay-Quads (state.getOverlay(), nicht im Modell!); Layer -1 = keins. */
    private final int[] sideOverlayLayers;
    private final int[] sideOverlayTints;
    private final int[] sideOverlayTintTypes;
    private final boolean[] fluids;
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
        this.sideOverlayLayers = new int[count];
        this.sideOverlayTints = new int[count];
        this.sideOverlayTintTypes = new int[count];
        this.fluids = new boolean[count];
        this.skyLightAttenuatingFluids = new boolean[count];

        for (int id = 0; id < count; id++) {
            BlockState state = BlockRegistry.getState(id);
            this.topTints[id] = BakedQuad.WHITE;
            this.sideTints[id] = BakedQuad.WHITE;
            this.sideOverlayLayers[id] = -1;
            this.fluids[id] = state.isFluid();

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

            /* Alle Seiten-Overlays sind identisch gebacken (BlockModels.overlaySides) —
               das erste mit Seiten-Cullface reicht. */
            for (BakedQuad quad : state.getOverlay()) {
                if (quad.cullFace() >= 2) {
                    this.sideOverlayLayers[id] = quad.textureLayer();
                    this.sideOverlayTints[id] = quad.tint();
                    this.sideOverlayTintTypes[id] = quad.tintType();
                    break;
                }
            }
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

    /** Textur-Layer des Seiten-Overlays (getönter Grasrand); -1 = Block hat kein Overlay. */
    public int sideOverlayLayer(int stateId) {
        return this.sideOverlayLayers[stateId];
    }

    /** Gepackter Multiplikations-Tint 0xRRGGBB des Seiten-Overlays. */
    public int sideOverlayTint(int stateId) {
        return this.sideOverlayTints[stateId];
    }

    /** Biome-Tint-Typ des Seiten-Overlays ({@code BakedQuad.TINT_*}); NONE = fester Tint. */
    public int sideOverlayTintType(int stateId) {
        return this.sideOverlayTintTypes[stateId];
    }

    /** true für Fluide — deren Zell-Top liegt auf der Quellhöhe (8/9) statt auf Höhe+1. */
    public boolean isFluid(int stateId) {
        return this.fluids[stateId];
    }

    /** true für Wasser-States, deren LOD-Säule Himmelslicht um eine Stufe pro Block dämpft. */
    public boolean attenuatesSkyLight(int stateId) {
        return this.skyLightAttenuatingFluids[stateId];
    }
}
