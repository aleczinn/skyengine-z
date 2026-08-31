package de.skyengine.game.world.generator.feature;

import de.skyengine.game.world.generator.biome.Biome;
import de.skyengine.game.world.structure.StructureTemplate;

import java.util.Random;

/** Gemeinsamer, deterministischer Schreibkontext für Worldgen-Features. */
public interface FeatureContext {

    Random random();
    int sourceMinX();
    int sourceMinZ();
    int surfaceHeight(int wx, int wz);
    int surfaceBlock(int wx, int wz);
    Biome biome(int wx, int wz);
    void set(int wx, int wy, int wz, int block);
    void setIfAir(int wx, int wy, int wz, int block);

    /**
     * Atomare Structure-Zelle. Echte Chunks können zusätzlich die BlockEntity nach dem
     * vollständigen Feature-Pass materialisieren.
     */
    default void setStructureCell(int wx, int wy, int wz, int block, boolean ifAir,
                                  StructureTemplate.BlockEntitySnapshot blockEntity) {
        if (ifAir) setIfAir(wx, wy, wz, block); else set(wx, wy, wz, block);
    }
}
