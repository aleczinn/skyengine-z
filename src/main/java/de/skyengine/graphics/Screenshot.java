package de.skyengine.graphics;

import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.stb.STBImageWrite;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Speichert den aktuell präsentierten Frame als PNG. Muss im Render-Thread (aktiver GL-Kontext)
 * und NACH {@code blitToScreen()} aufgerufen werden: Der Offscreen-FBO ist multisampled und damit
 * für {@code glReadPixels} ungeeignet — gelesen wird der bereits aufgelöste Default-Framebuffer (0).
 */
public final class Screenshot {

    private static final Logger LOGGER = LogManager.getLogger(Screenshot.class.getName());
    /* Liegt im Spiel-Root (%APPDATA%\.skyengine), nicht im Arbeitsverzeichnis. */
    private static final File DIRECTORY = de.skyengine.core.file.GameDirectory.resolve("screenshots");
    private static final DateTimeFormatter NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private Screenshot() {}

    public static void capture(int width, int height) {
        if (width <= 0 || height <= 0) return;

        /* RGB-Zeilen ohne 4-Byte-Padding lesen, sonst sind die Zeilen bei ungerader Breite verschoben. */
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
        GL11.glReadBuffer(GL11.GL_BACK);

        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 3);
        try {
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, pixels);

            if (!DIRECTORY.exists()) DIRECTORY.mkdirs();
            File out = uniqueFile();

            /* OpenGL liefert die Pixel von unten nach oben; PNG erwartet oben nach unten. */
            STBImageWrite.stbi_flip_vertically_on_write(true);
            boolean ok = STBImageWrite.stbi_write_png(out.getPath(), width, height, 3, pixels, width * 3);

            if (ok) {
                LOGGER.info("Screenshot gespeichert: " + out.getAbsolutePath());
            } else {
                LOGGER.error("Screenshot konnte nicht gespeichert werden: " + out.getPath());
            }
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    /** Zeitstempel-Name; bei Kollision in derselben Sekunde mit _1, _2, … erweitert. */
    private static File uniqueFile() {
        String base = "screenshot_" + LocalDateTime.now().format(NAME_FORMAT);
        File file = new File(DIRECTORY, base + ".png");
        int counter = 1;
        while (file.exists()) {
            file = new File(DIRECTORY, base + "_" + counter + ".png");
            counter++;
        }
        return file;
    }
}
