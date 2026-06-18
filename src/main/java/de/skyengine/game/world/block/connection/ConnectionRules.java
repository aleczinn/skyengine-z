package de.skyengine.game.world.block.connection;

import de.skyengine.game.world.block.entity.BlockEntity;
import de.skyengine.game.world.block.entity.Capability;

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

    /**
     * Netzwerk-Regel (Pipes/Cables): verbindet mit Nachbarn derselben Gruppe (gleiche
     * Pipe-/Kabel-Art) <b>oder</b> mit einem Nachbarn, dessen BlockEntity die angeforderte
     * Capability auf der zugewandten Seite bereitstellt (Maschine/Tank/Energiespeicher).
     */
    public static ConnectionRule networkOrCapability(Capability<?> capability) {
        return (world, x, y, z, dir, self, neighbor) -> {
            String group = self.getBlock().getConnectionGroup();
            if (group != null && group.equals(neighbor.getBlock().getConnectionGroup())) return true;

            BlockEntity be = world.getBlockEntity(x + dir.offsetX(), y + dir.offsetY(), z + dir.offsetZ());
            return be != null && be.getCapability(capability, dir.opposite()).isPresent();
        };
    }

    private ConnectionRules() {}
}
