package de.skyengine.game.world.structure;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Weltbezogener Manager fuer voneinander isolierte Spieler-WorldEdit-Sitzungen. */
public final class WorldEditService {
    private final StructureTemplateManager templates;
    private final Map<UUID, WorldEditSession> sessions = new ConcurrentHashMap<>();

    public WorldEditService(StructureTemplateManager templates) {
        this.templates = templates;
    }

    public WorldEditSession session(UUID player) {
        return sessions.computeIfAbsent(player, ignored -> new WorldEditSession(templates));
    }

    public StructureTemplateManager templates() { return templates; }
}
