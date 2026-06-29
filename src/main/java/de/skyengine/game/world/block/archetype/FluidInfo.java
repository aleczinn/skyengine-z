package de.skyengine.game.world.block.archetype;

/**
 * Fluid-Metadaten eines Blocks (Wasser/Lava): Texturpfade für Still-/Flow-Sprite,
 * Ausbreitungsreichweite und Tick-Takt des Fluss-Algorithmus sowie das Lava-Flag.
 *
 * <p>{@link #stillLayer}/{@link #flowLayer} werden erst beim Registry-Bake aufgelöst
 * (über {@code BlockTextures.layerOf}), wenn die Texturen einen Atlas-Layer bekommen –
 * vorher sind sie {@code -1}. Der {@code ChunkMesher} liest die Layer im Hot-Path.
 */
public final class FluidInfo {

    public final String stillTexture;
    public final String flowTexture;
    public final int spread;      // max. horizontale Ausbreitung (Wasser 7, Lava 3)
    public final int tickDelay;   // Ticks zwischen Fluss-Updates
    public final boolean lava;

    public int stillLayer = -1;   // beim Bake aufgelöst
    public int flowLayer = -1;

    public FluidInfo(String stillTexture, String flowTexture, int spread, int tickDelay, boolean lava) {
        this.stillTexture = stillTexture;
        this.flowTexture = flowTexture;
        this.spread = spread;
        this.tickDelay = tickDelay;
        this.lava = lava;
    }
}
