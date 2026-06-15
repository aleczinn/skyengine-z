package de.skyengine.game.world.block.connection;

import de.skyengine.game.world.block.Direction;

/**
 * Konfiguration eines Verbindungssystems: die zu berechnenden Achsen (4 horizontal für
 * Zäune/Panes, alle 6 für Pipes/Cables) und die {@link ConnectionRule}. Datengetrieben
 * und wiederverwendbar — Pipes/Cables sind damit kein eigener Archetyp.
 */
public final class ConnectionComponent {

    private final Direction[] axes;
    private final ConnectionRule rule;

    public ConnectionComponent(Direction[] axes, ConnectionRule rule) {
        this.axes = axes;
        this.rule = rule;
    }

    public Direction[] axes() { return axes; }
    public ConnectionRule rule() { return rule; }
}
