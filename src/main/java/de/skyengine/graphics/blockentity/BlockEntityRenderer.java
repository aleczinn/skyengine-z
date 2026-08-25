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

    /**
     * Pro Frame: zeichnet die BlockEntity. {@code partialTick} für flüssige Interpolation,
     * {@code light} ist der fertige Licht-Faktor der Zelle (Himmel + Block)
     * ({@code ChunkRenderer.lightFactor}, 1.0 = voll hell bzw. Fullbright) — ohne ihn säße die
     * Truhe in einer finsteren Höhle als leuchtender Fremdkörper in ihrer Wand.
     */
    void render(BlockEntity be, Camera camera, float partialTick, float light);

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
    default void renderIcon(Matrix4f mvp, Matrix4f iconModel) {}

    /**
     * Zeichnet das Block-Modell als gehaltenes Item (First-/Third-Person-Hand, Inventar-Vorschau).
     * {@code mvp} ist die fertige Matrix (ProjView × Hand-/Display-Transform); das Modell liegt in
     * 0..1-Blockeinheiten. WICHTIG: kein Depth-/Cull-State anfassen (Reversed-Z-Funcs sind global
     * gesetzt, der Hand-Pass hat Tiefentest an und Culling aus) — nur eigenen Shader/Textur binden
     * und am Ende unbinden; den Aufrufer-State stellt {@code HeldItemMeshes} wieder her.
     *
     * <p>{@code light} wie bei {@link #render} — in der Inventar-Vorschau reicht der Aufrufer
     * hier <b>1.0</b> durch, sonst würde eine GUI mit der Weltbeleuchtung abdunkeln.
     */
    default void renderHeld(Matrix4f mvp, float light) {}

    default void dispose() {}
}
