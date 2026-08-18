package de.skyengine.graphics;

import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.stb.STBImageWrite;
import org.lwjgl.system.MemoryUtil;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Speichert den aktuell präsentierten Frame als PNG. Muss im Render-Thread (aktiver GL-Kontext)
 * und NACH Resolve + Post-Kette + GUI aufgerufen werden (s. SkyEngine.onRender): Der Offscreen-FBO
 * kann multisampled sein und ist für {@code glReadPixels} ungeeignet — gelesen wird der bereits
 * fertige Default-Framebuffer (0).
 */
public final class Screenshot {

    private static final Logger LOGGER = LogManager.getLogger(Screenshot.class.getName());
    /* Liegt im Spiel-Root (%APPDATA%\.skyengine), nicht im Arbeitsverzeichnis. */
    private static final File DIRECTORY = de.skyengine.core.file.GameDirectory.resolve("screenshots");
    private static final DateTimeFormatter NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private Screenshot() {}

    public static File capture(int width, int height) {
        if (width <= 0 || height <= 0) return null;

        /* RGB-Zeilen ohne 4-Byte-Padding lesen, sonst sind die Zeilen bei ungerader Breite verschoben. */
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
        GL11.glReadBuffer(GL11.GL_BACK);

        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 3);
        try {
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, pixels);

            if (!DIRECTORY.exists() && !DIRECTORY.mkdirs()) {
                LOGGER.error("Screenshot-Ordner konnte nicht erstellt werden: " + DIRECTORY.getPath());
                return null;
            }
            File out = uniqueFile();

            /* OpenGL liefert die Pixel von unten nach oben; PNG erwartet oben nach unten. */
            STBImageWrite.stbi_flip_vertically_on_write(true);
            boolean ok = STBImageWrite.stbi_write_png(out.getPath(), width, height, 3, pixels, width * 3);

            if (ok) {
                LOGGER.info("Screenshot gespeichert: " + out.getAbsolutePath());
                return out;
            } else {
                LOGGER.error("Screenshot konnte nicht gespeichert werden: " + out.getPath());
                return null;
            }
        } catch (RuntimeException e) {
            LOGGER.error("Screenshot konnte nicht aufgenommen werden", e);
            return null;
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    /** Öffnet den Screenshot selbst im Standard-Bildbetrachter. */
    public static void open(File screenshot) {
        try {
            if (screenshot == null || !screenshot.isFile()) {
                throw new IOException("Screenshot-Datei existiert nicht");
            }
            if (!Desktop.isDesktopSupported()) {
                throw new UnsupportedOperationException("Desktop-API wird nicht unterstützt");
            }
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.OPEN)) {
                throw new UnsupportedOperationException("Dateien können nicht geöffnet werden");
            }
            desktop.open(screenshot);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Screenshot konnte nicht im Bildbetrachter geöffnet werden: "
                    + (screenshot == null ? "<null>" : screenshot.getAbsolutePath()), e);
            throw new IllegalStateException(e);
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
