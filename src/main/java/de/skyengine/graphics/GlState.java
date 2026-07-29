package de.skyengine.graphics;

import org.lwjgl.opengl.GL11;

/**
 * CPU-Spiegel ausgewählter GL-Zustände (nur Render-Thread). {@code glIsEnabled}/{@code glGetInteger}
 * auf Pipeline-State sind synchrone Treiber-Roundtrips (siehe {@code EngineProperties.baseDepthFunc})
 * — der Spiegel ersetzt diese Abfragen im Save/Restore-Muster der Renderer.
 *
 * <p><b>ALLE</b> Schaltstellen von {@code GL_CULL_FACE} müssen über diese Klasse laufen,
 * sonst läuft der Spiegel auseinander. {@link #forceCullFaceEnabled()} am Frame-Anfang
 * (SkyEngine.onRender) heilt jede Drift einmal pro Frame.
 */
public final class GlState {

    private static boolean cullFace;

    private GlState() {
    }

    /** Setzt den GL-Zustand UNBEDINGT (Frame-Anfang) — Synchronisationspunkt gegen Drift. */
    public static void forceCullFaceEnabled() {
        GL11.glEnable(GL11.GL_CULL_FACE);
        cullFace = true;
    }

    public static void enableCullFace() {
        if (!cullFace) {
            GL11.glEnable(GL11.GL_CULL_FACE);
            cullFace = true;
        }
    }

    public static void disableCullFace() {
        if (cullFace) {
            GL11.glDisable(GL11.GL_CULL_FACE);
            cullFace = false;
        }
    }

    /** Ersatz für {@code glIsEnabled(GL_CULL_FACE)} im Save/Restore-Muster. */
    public static boolean isCullFaceEnabled() {
        return cullFace;
    }
}
