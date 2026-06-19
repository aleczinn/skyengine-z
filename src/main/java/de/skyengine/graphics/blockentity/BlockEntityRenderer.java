package de.skyengine.graphics.blockentity;

import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.graphics.camera.Camera;

/**
 * Zeichnet eine {@link BlockEntity} mit eigener (oft animierter) Geometrie - außerhalb des
 * statischen Chunk-Mesh. Erster Anwendungsfall: aufgehende Truhe; später Zaubertisch-Buch,
 * drehende Säge, Bett. Camera-relativ wie die Chunks (Offset = Weltpos - Kamerapos).
 */
public interface BlockEntityRenderer {

    /** Einmalig mit GL-Kontext: Shader/Textur/Mesh anlegen. */
    default void init() {}

    /** Pro Frame: zeichnet die BlockEntity. {@code partialTick} für flüssige Interpolation. */
    void render(BlockEntity be, Camera camera, float partialTick);

    default void dispose() {}
}
