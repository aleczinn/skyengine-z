package de.skyengine.core.resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Aktiver Ressourcen-Stack. Packs stehen in UI-Reihenfolge (hoechste Prioritaet zuerst),
 * die eingebaute Default-Quelle immer ganz unten.
 */
public final class ResourceManager {
    public record Match(ResourceSource source, ResourceId id) {
        public InputStream open() throws IOException { return this.source.open(this.id); }
    }

    private final ResourceSource defaults;
    private volatile List<ResourceSource> packs = List.of();

    public ResourceManager(ResourceSource defaults) {
        this.defaults = defaults;
    }

    public void setPacks(List<ResourcePack> selected) {
        List<ResourceSource> sources = new ArrayList<>();
        if (selected != null) {
            for (ResourcePack pack : selected) if (pack != null && pack.valid()) sources.add(pack.source());
        }
        this.packs = List.copyOf(sources);
    }

    public List<String> activePackNames() {
        return this.packs.stream().map(ResourceSource::name).toList();
    }

    public Optional<Match> find(ResourceId id) {
        for (ResourceSource source : this.packs) {
            if (source.contains(id)) return Optional.of(new Match(source, id));
        }
        return this.defaults.contains(id) ? Optional.of(new Match(this.defaults, id)) : Optional.empty();
    }

    public Optional<Match> find(String path) {
        return this.find(ResourceId.of(path));
    }

    /** Niedrigste bis hoechste Prioritaet, geeignet fuer Key-Merges. */
    public List<Match> findStack(ResourceId id) {
        List<Match> result = new ArrayList<>();
        if (this.defaults.contains(id)) result.add(new Match(this.defaults, id));
        for (int i = this.packs.size() - 1; i >= 0; i--) {
            ResourceSource source = this.packs.get(i);
            if (source.contains(id)) result.add(new Match(source, id));
        }
        return result;
    }

    /** Effektive Vereinigung: gleiche IDs erscheinen genau einmal aus der hoechsten Quelle. */
    public Map<ResourceId, Match> listResolved(String pathPrefix) throws IOException {
        Map<ResourceId, Match> result = new LinkedHashMap<>();
        for (ResourceSource source : this.packs) {
            for (ResourceId id : source.list(pathPrefix)) result.putIfAbsent(id, new Match(source, id));
        }
        for (ResourceId id : this.defaults.list(pathPrefix)) result.putIfAbsent(id, new Match(this.defaults, id));
        return result;
    }

    public Set<ResourceId> listIds(String pathPrefix) throws IOException {
        return new LinkedHashSet<>(this.listResolved(pathPrefix).keySet());
    }

    public InputStream open(ResourceId id) throws IOException {
        Match match = this.find(id).orElseThrow(() -> new IOException("Ressource nicht gefunden: " + id));
        return match.open();
    }

    public InputStream open(String path) throws IOException {
        return this.open(ResourceId.of(path));
    }

    public boolean exists(String path) {
        try {
            return this.find(path).isPresent();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
