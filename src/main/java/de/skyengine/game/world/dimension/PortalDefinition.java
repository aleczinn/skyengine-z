package de.skyengine.game.world.dimension;

import de.skyengine.game.world.block.Identifier;

import java.util.Map;

/** Portalblock mit dimensionsabhaengiger Zielroute und eigenem Aktivierungsmodus. */
public record PortalDefinition(Identifier id, Identifier block, Map<Identifier, Identifier> routes,
                               Activation activation) {
    public enum Activation { CONTACT, USE }

    public PortalDefinition {
        routes = Map.copyOf(routes);
    }

    public PortalDefinition(Identifier id, Identifier block, Map<Identifier, Identifier> routes) {
        this(id, block, routes, Activation.CONTACT);
    }

    public Identifier targetFrom(Identifier source) {
        return this.routes.get(source);
    }
}
