package de.skyengine.graphics.blockentity;

import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.graphics.camera.Camera;
import org.joml.Matrix4f;

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

    /**
     * Ob dieser Renderer ein Inventar-Icon liefern kann (BlockEntity-Blöcke wie die Truhe haben ein
     * leeres statisches Modell und würden sonst kein Icon bekommen).
     */
    default boolean hasIcon() { return false; }

    /**
     * Zeichnet das Block-Modell als Inventar-Icon. {@code mvp} ist die fertige (Ortho × Iso) Matrix
     * des Icon-Renderers; das Modell liegt in 0..1-Blockeinheiten. Implementierungen verwalten ihren
     * GL-State (Shader/Textur/Depth) selbst.
     */
    default void renderIcon(Matrix4f mvp) {}

    default void dispose() {}
}
