package de.skyengine.game.world.generator.feature;

import de.skyengine.game.world.generator.biome.Biome;
import de.skyengine.game.world.structure.StructureTemplate;

import java.util.Random;

/** Gemeinsamer, deterministischer Schreibkontext fuer echte Chunks und kompakte LOD-Features. */
public interface FeatureContext {

    Random random();
    int sourceMinX();
    int sourceMinZ();
    int surfaceHeight(int wx, int wz);
    int surfaceBlock(int wx, int wz);
    Biome biome(int wx, int wz);
    /**
     * Markiert den tragenden Fuss eines Features fuer die verlustbehaftete LOD-Reduktion.
     * Echte Chunk-Platzierung braucht die Metadaten nicht und darf deshalb ein No-Op bleiben.
     */
    default void markLodSupport(int wx, int wy, int wz) {}
    void set(int wx, int wy, int wz, int block);
    void setIfAir(int wx, int wy, int wz, int block);

    /**
     * Atomare Structure-Zelle. LOD-Kontexte uebernehmen nur den BlockState; echte Chunks
     * koennen zusaetzlich die BlockEntity nach dem vollstaendigen Feature-Pass materialisieren.
     */
    default void setStructureCell(int wx, int wy, int wz, int block, boolean ifAir,
                                  StructureTemplate.BlockEntitySnapshot blockEntity) {
        if (ifAir) setIfAir(wx, wy, wz, block); else set(wx, wy, wz, block);
    }
}
