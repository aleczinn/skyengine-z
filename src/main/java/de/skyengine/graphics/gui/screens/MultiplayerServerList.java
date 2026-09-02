package de.skyengine.graphics.gui.screens;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.skyengine.core.file.GameDirectory;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Persistent ordered multiplayer favorites stored below the client game directory. */
public final class MultiplayerServerList {
    public record Entry(String name, String address) {
        public Entry {
            name = normalize(name, "Server", 128);
            address = normalize(address, "localhost", 512);
        }

        private static String normalize(String value, String fallback, int maximum) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.isEmpty()) normalized = fallback;
            return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
        }
    }

    private record Data(List<Entry> servers) {}

    private static final Logger LOGGER = LogManager.getLogger(MultiplayerServerList.class.getName());
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_SERVERS = 1024;

    private final Path file;
    private final List<Entry> entries = new ArrayList<>();

    public MultiplayerServerList() {
        this(GameDirectory.resolve("config/servers.json").toPath());
    }

    MultiplayerServerList(Path file) {
        this.file = file.toAbsolutePath().normalize();
        load();
    }

    public List<Entry> entries() { return List.copyOf(this.entries); }

    public void add(Entry entry) {
        if (this.entries.size() >= MAX_SERVERS) throw new IllegalStateException("Too many saved servers");
        this.entries.add(entry);
        save();
    }

    public void set(int index, Entry entry) {
        this.entries.set(index, entry);
        save();
    }

    public void remove(int index) {
        this.entries.remove(index);
        save();
    }

    private void load() {
        this.entries.clear();
        if (!Files.isRegularFile(this.file)) return;
        try {
            Data data = GSON.fromJson(Files.readString(this.file, StandardCharsets.UTF_8), Data.class);
            if (data == null || data.servers == null) return;
            for (Entry entry : data.servers) {
                if (entry != null && this.entries.size() < MAX_SERVERS) {
                    this.entries.add(new Entry(entry.name, entry.address));
                }
            }
        } catch (RuntimeException | IOException error) {
            LOGGER.warning("Serverliste konnte nicht geladen werden: " + this.file, error);
        }
    }

    private void save() {
        Path parent = this.file.getParent();
        Path temporary = this.file.resolveSibling(this.file.getFileName() + ".tmp");
        try {
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(temporary, GSON.toJson(new Data(this.entries)), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, this.file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            LOGGER.warning("Serverliste konnte nicht gespeichert werden: " + this.file, error);
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }
}
