package de.skyengine.game.world.structure;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Weltbezogener Manager fuer voneinander isolierte Spieler-Editor-Sitzungen. */
public final class StructureAuthoringService {
    private final StructureTemplateManager templates;
    private final Map<UUID, StructureEditorSession> sessions = new ConcurrentHashMap<>();

    public StructureAuthoringService(StructureTemplateManager templates) {
        this.templates = templates;
    }

    public StructureEditorSession session(UUID player) {
        return sessions.computeIfAbsent(player, ignored -> new StructureEditorSession(templates));
    }

    public StructureTemplateManager templates() { return templates; }
}
