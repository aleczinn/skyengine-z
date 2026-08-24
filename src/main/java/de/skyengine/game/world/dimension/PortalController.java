package de.skyengine.game.world.dimension;

import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.physics.AABB;
import de.skyengine.game.world.World;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Identifier;

/** Zaehlt Portalberuehrung und liefert einen sicheren Wechselauftrag. */
public final class PortalController {

    public static final int TRAVEL_TICKS = 20;

    public record Travel(Identifier targetDimension, Identifier portalType, int x, int z) {}

    private Identifier contactPortal;
    private int contactTicks;
    private boolean locked;

    public Travel tick(World world, EntityPlayer player) {
        PortalContact contact = contact(world, player.getBoundingBox());
        return this.advance(world.getDimensionId(), contact);
    }

    /** Aktiviert ein USE-Portal durch einen gezielten Rechtsklick. */
    public Travel use(World world, int x, int y, int z) {
        int state = world.getBlock(x, y, z);
        if (state == Blocks.AIR) return null;
        Identifier block = Blocks.getState(state).getBlock().getIdentifier();
        return this.useBlock(world.getDimensionId(), block, x, z);
    }

    Travel useBlock(Identifier source, Identifier block, int x, int z) {
        for (PortalDefinition definition : WorldgenRegistries.PORTALS.values()) {
            if (definition.activation() != PortalDefinition.Activation.USE
                    || !definition.block().equals(block)) continue;
            Identifier target = definition.targetFrom(source);
            return target == null ? null : new Travel(target, definition.id(), x, z);
        }
        return null;
    }

    /** Paketintern getrennt von der Kollisionserkennung, damit die Tick-Semantik testbar bleibt. */
    Travel tickContact(Identifier source, PortalDefinition definition, int x, int z) {
        return this.advance(source, definition == null
                || definition.activation() != PortalDefinition.Activation.CONTACT
                ? null : new PortalContact(definition, x, z));
    }

    private Travel advance(Identifier source, PortalContact contact) {
        if (contact == null) {
            this.contactPortal = null;
            this.contactTicks = 0;
            this.locked = false;
            return null;
        }
        if (this.locked) return null;
        if (!contact.definition.id().equals(this.contactPortal)) {
            this.contactPortal = contact.definition.id();
            this.contactTicks = 0;
        }
        if (++this.contactTicks < TRAVEL_TICKS) return null;
        Identifier target = contact.definition.targetFrom(source);
        this.contactTicks = 0;
        if (target == null) return null;
        this.locked = true;
        return new Travel(target, contact.definition.id(), contact.x, contact.z);
    }

    /** Nach einem Wechsel bleibt das Zielportal gesperrt, bis es verlassen wurde. */
    public void lockUntilExit() {
        this.locked = true;
        this.contactTicks = 0;
        this.contactPortal = null;
    }

    public void reset() {
        this.locked = false;
        this.contactTicks = 0;
        this.contactPortal = null;
    }

    private static PortalContact contact(World world, AABB box) {
        int minX = (int) Math.floor(box.minX), maxX = (int) Math.floor(Math.nextDown(box.maxX));
        int minY = (int) Math.floor(box.minY), maxY = (int) Math.floor(Math.nextDown(box.maxY));
        int minZ = (int) Math.floor(box.minZ), maxZ = (int) Math.floor(Math.nextDown(box.maxZ));
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    int state = world.getBlock(x, y, z);
                    if (state == Blocks.AIR) continue;
                    Identifier block = Blocks.getState(state).getBlock().getIdentifier();
                    for (PortalDefinition definition : WorldgenRegistries.PORTALS.values()) {
                        if (definition.activation() == PortalDefinition.Activation.CONTACT
                                && definition.block().equals(block)) {
                            return new PortalContact(definition, x, z);
                        }
                    }
                }
            }
        }
        return null;
    }

    private record PortalContact(PortalDefinition definition, int x, int z) {}
}
