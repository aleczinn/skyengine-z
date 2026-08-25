package de.skyengine.game.world.save;

import de.skyengine.game.world.block.entity.DataTag;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * Liest/schreibt UUID-basierte {@code players/<uuid>.dat} sowie die alte
 * {@code player/player.dat} während der Migration. Den Tag-Inhalt baut der PlayerManager.
 */
public final class PlayerIO {

    private static final Logger LOGGER = LogManager.getLogger(PlayerIO.class.getName());

    /** null, wenn die Datei fehlt oder nicht lesbar ist (Aufrufer fällt auf Migration/Spawn zurück). */
    public static DataTag read(File file) {
        if (!file.isFile()) return null;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            return DataTagIO.read(in);
        } catch (IOException e) {
            LOGGER.error("player.dat nicht lesbar: " + file.getPath(), e);
            return null;
        }
    }

    public static void write(File file, DataTag tag) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            LOGGER.error("player-Ordner konnte nicht angelegt werden: " + parent.getPath());
            return;
        }
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            DataTagIO.write(tag, out);
        } catch (IOException e) {
            LOGGER.error("player.dat konnte nicht geschrieben werden: " + file.getPath(), e);
        }
    }

    public static File playerFile(File worldRoot, UUID uuid) {
        return new File(new File(worldRoot, "players"), uuid + ".dat");
    }

    public static File legacyPlayerFile(File worldRoot) {
        return new File(worldRoot, "player/player.dat");
    }

    private PlayerIO() {}
}
