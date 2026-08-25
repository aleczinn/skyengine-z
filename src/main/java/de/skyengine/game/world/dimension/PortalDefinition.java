package de.skyengine.game.world.dimension;

import de.skyengine.game.world.block.Identifier;

import java.util.Map;

/** Portalblock mit dimensionsabhaengiger Zielroute und eigenem Aktivierungsmodus. */
public record PortalDefinition(Identifier id, Identifier block, Map<Identifier, Identifier> routes,
                               Activation activation, int survivalDelayTicks, int creativeDelayTicks,
                               LinkPolicy linkPolicy) {
    public enum Activation { CONTACT, USE }
    public enum LinkPolicy { SIMPLE, NETHER }

    public PortalDefinition {
        routes = Map.copyOf(routes);
        if (survivalDelayTicks < 1 || creativeDelayTicks < 1) {
            throw new IllegalArgumentException("Portalwartezeit muss positiv sein: " + id);
        }
    }

    public PortalDefinition(Identifier id, Identifier block, Map<Identifier, Identifier> routes) {
        this(id, block, routes, Activation.CONTACT, 20, 20, LinkPolicy.SIMPLE);
    }

    public PortalDefinition(Identifier id, Identifier block, Map<Identifier, Identifier> routes,
                            Activation activation) {
        this(id, block, routes, activation, 20, 20, LinkPolicy.SIMPLE);
    }

    public Identifier targetFrom(Identifier source) {
        return this.routes.get(source);
    }
}
