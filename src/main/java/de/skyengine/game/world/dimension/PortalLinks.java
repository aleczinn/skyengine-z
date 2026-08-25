package de.skyengine.game.world.dimension;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Weltweite, bidirektionale 1:1-Verbindungen zwischen dimensionsgebundenen Portal-UUIDs. */
public final class PortalLinks {

    private static final Logger LOGGER = LogManager.getLogger(PortalLinks.class.getName());
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public record Endpoint(String dimension, String portalId) {}
    public record Link(String type, Endpoint first, Endpoint second) {}

    private static final class Data {
        int version = 1;
        List<Link> links = new ArrayList<>();
    }

    private final File file;
    private final List<Link> links = new ArrayList<>();

    public PortalLinks(File saveRoot) {
        this.file = new File(saveRoot, "portal_links.json");
        this.load();
    }

    public Endpoint linked(Identifier type, Identifier dimension, String portalId) {
        Endpoint source = new Endpoint(dimension.toString(), portalId);
        for (Link link : this.links) {
            if (!link.type.equals(type.toString())) continue;
            if (link.first.equals(source)) return link.second;
            if (link.second.equals(source)) return link.first;
        }
        return null;
    }

    public boolean isLinked(Identifier type, Identifier dimension, String portalId) {
        return this.linked(type, dimension, portalId) != null;
    }

    /** Ersetzt etwaige Altbindungen beider Endpunkte und speichert genau ein reziprokes Paar. */
    public void pair(Identifier type, Identifier firstDimension, String firstPortal,
                     Identifier secondDimension, String secondPortal) {
        Endpoint first = new Endpoint(firstDimension.toString(), firstPortal);
        Endpoint second = new Endpoint(secondDimension.toString(), secondPortal);
        if (second.equals(this.linked(type, firstDimension, firstPortal))) return;
        Link wanted = new Link(type.toString(), first, second);
        this.links.removeIf(link -> link.type.equals(type.toString())
                && (link.first.equals(first) || link.second.equals(first)
                || link.first.equals(second) || link.second.equals(second)));
        this.links.add(wanted);
        this.save();
    }

    public void unlink(Identifier type, Identifier dimension, String portalId) {
        Endpoint endpoint = new Endpoint(dimension.toString(), portalId);
        if (this.links.removeIf(link -> link.type.equals(type.toString())
                && (link.first.equals(endpoint) || link.second.equals(endpoint)))) this.save();
    }

    private void load() {
        if (!this.file.isFile()) return;
        try {
            Data data = GSON.fromJson(Files.readString(this.file.toPath()), Data.class);
            if (data != null && data.version == 1 && data.links != null) {
                for (Link link : data.links) {
                    if (link != null && link.type != null && link.first != null && link.second != null) {
                        this.links.add(link);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warning("Portalverbindungen konnten nicht geladen werden: " + this.file
                    + " (" + e.getMessage() + ")");
        }
    }

    private void save() {
        try {
            Files.createDirectories(this.file.toPath().getParent());
            Data data = new Data();
            data.links = new ArrayList<>(this.links);
            File temp = new File(this.file.getParentFile(), this.file.getName() + ".tmp");
            Files.writeString(temp.toPath(), GSON.toJson(data));
            try {
                Files.move(temp.toPath(), this.file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp.toPath(), this.file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            LOGGER.warning("Portalverbindungen konnten nicht gespeichert werden: " + this.file
                    + " (" + e.getMessage() + ")");
        }
    }
}
