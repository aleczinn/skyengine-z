package de.skyengine.mcimport;

import de.skyengine.mcimport.mca.McWorldPaths;
import de.skyengine.mcimport.nbt.NbtCompound;
import de.skyengine.mcimport.nbt.NbtReader;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Findet die Minecraft-Welten des Spielers für die Import-Auswahl (GuiImportWorld).
 * Aufgelöst wird {@code %APPDATA%\.minecraft\saves} — dieselbe Logik, mit der
 * {@code GameDirectory} den eigenen Spiel-Ordner {@code .skyengine} findet.
 */
public final class McSaves {

    /** Eine importierbare MC-Welt: Ordner, Anzeigename (aus level.dat) und Spielzeitpunkt. */
    public record McSave(File dir, String name, long lastPlayed) {}

    /** {@code %APPDATA%\.minecraft\saves} (Fallback: {@code <user.home>/.minecraft/saves}). */
    public static File defaultSavesDir() {
        String appData = System.getenv("APPDATA");
        File base = appData != null && !appData.isBlank()
                ? new File(appData) : new File(System.getProperty("user.home"));
        return new File(new File(base, ".minecraft"), "saves");
    }

    /**
     * Alle Unterordner mit Block-Region-Dateien, zuletzt gespielte zuerst. Ordner ohne
     * {@code region/*.mca} (z.B. leere Welten) fallen raus — sie wären nicht importierbar.
     */
    public static List<McSave> list(File savesDir) {
        List<McSave> result = new ArrayList<>();
        File[] dirs = savesDir != null ? savesDir.listFiles(File::isDirectory) : null;
        if (dirs == null) return result;
        for (File dir : dirs) {
            if (McWorldPaths.overworldRegionDir(dir) == null) continue;
            result.add(readSave(dir));
        }
        result.sort(Comparator.comparingLong(McSave::lastPlayed).reversed());
        return result;
    }

    /** Name/Zeitstempel aus der level.dat; bei jedem Fehler Ordnername + Datei-Datum. */
    private static McSave readSave(File dir) {
        try (FileInputStream in = new FileInputStream(new File(dir, "level.dat"))) {
            NbtCompound data = NbtReader.readAuto(in).requireCompound("Data");
            String name = data.getString("LevelName", dir.getName());
            long lastPlayed = data.getLong("LastPlayed", dir.lastModified());
            return new McSave(dir, name.isBlank() ? dir.getName() : name, lastPlayed);
        } catch (Exception e) {
            return new McSave(dir, dir.getName(), dir.lastModified());
        }
    }

    private McSaves() {}
}
