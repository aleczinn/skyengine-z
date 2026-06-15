package de.skyengine.game.world.block.connection;

/** Mitgelieferte {@link ConnectionRule}-Implementierungen. */
public final class ConnectionRules {

    /**
     * Zaun-/Pane-Regel: verbindet mit Nachbarn derselben Connection-Gruppe
     * (z.B. alle Zäune) oder mit einem opaken Vollwürfel. Gruppen kommen aus
     * {@link de.skyengine.game.world.block.Block#getConnectionGroup()}.
     */
    public static final ConnectionRule SAME_GROUP_OR_SOLID = (world, x, y, z, dir, self, neighbor) -> {
        if (neighbor.isOpaqueCube()) return true;
        String group = self.getBlock().getConnectionGroup();
        return group != null && group.equals(neighbor.getBlock().getConnectionGroup());
    };

    private ConnectionRules() {}
}
