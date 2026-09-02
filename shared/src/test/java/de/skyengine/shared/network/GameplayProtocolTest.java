package de.skyengine.shared.network;

import de.skyengine.shared.gameplay.BlockActionRequest;
import de.skyengine.shared.gameplay.InventoryActionRequest;
import de.skyengine.shared.gameplay.NetworkItemStack;
import de.skyengine.shared.gameplay.ContainerKind;
import de.skyengine.shared.gameplay.EntityActionRequest;
import de.skyengine.shared.gameplay.WorldSoundType;
import de.skyengine.shared.gameplay.BlockActionEffectType;
import de.skyengine.shared.gameplay.AuthoritativeBlockCorrection;
import de.skyengine.shared.network.packets.CorePackets;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GameplayProtocolTest {
    @Test
    void authoritativeRequestsAndInventoryContentRoundTrip() throws Exception {
        PacketRegistry registry = CoreProtocol.createRegistry();
        var block = new CorePackets.BlockAction(new BlockActionRequest(5,
                BlockActionRequest.Action.PLACE, "skyengine:overworld", -100, 70, 200, 1, 0, 42));
        byte[] encoded = registry.encode(PacketDirection.CLIENT_TO_SERVER, ConnectionState.PLAY,
                new PacketEnvelope(block));
        var decoded = (CorePackets.BlockAction) registry.decode(PacketDirection.CLIENT_TO_SERVER,
                ConnectionState.PLAY, encoded).packet();
        assertEquals(block, decoded);

        var inventory = new CorePackets.InventoryContent(2, 7,
                List.of(new NetworkItemStack(9, 3, new byte[] {1, 2}), NetworkItemStack.empty()),
                NetworkItemStack.empty());
        encoded = registry.encode(PacketDirection.SERVER_TO_CLIENT, ConnectionState.PLAY,
                new PacketEnvelope(inventory));
        var decodedInventory = (CorePackets.InventoryContent) registry.decode(PacketDirection.SERVER_TO_CLIENT,
                ConnectionState.PLAY, encoded).packet();
        assertEquals(2, decodedInventory.stacks().size());
        assertEquals(9, decodedInventory.stacks().getFirst().itemId());
        assertEquals(3, decodedInventory.stacks().getFirst().count());
    }

    @Test
    void entityActionsAndContainerLifecycleRoundTrip() throws Exception {
        PacketRegistry registry = CoreProtocol.createRegistry();
        CorePackets.EntityAction entity = new CorePackets.EntityAction(
                new EntityActionRequest(91, EntityActionRequest.Action.INTERACT, 1_000_004));
        byte[] encoded = registry.encode(PacketDirection.CLIENT_TO_SERVER, ConnectionState.PLAY,
                new PacketEnvelope(entity));
        assertEquals(entity, registry.decode(PacketDirection.CLIENT_TO_SERVER,
                ConnectionState.PLAY, encoded).packet());

        CorePackets.ContainerOpen opened = new CorePackets.ContainerOpen(7, ContainerKind.CHEST,
                54, 6, "skyengine:overworld", -32, 64, 96);
        encoded = registry.encode(PacketDirection.SERVER_TO_CLIENT, ConnectionState.PLAY,
                new PacketEnvelope(opened));
        assertEquals(opened, registry.decode(PacketDirection.SERVER_TO_CLIENT,
                ConnectionState.PLAY, encoded).packet());

        CorePackets.ContainerClose closed = new CorePackets.ContainerClose(7);
        encoded = registry.encode(PacketDirection.CLIENT_TO_SERVER, ConnectionState.PLAY,
                new PacketEnvelope(closed));
        assertEquals(closed, registry.decode(PacketDirection.CLIENT_TO_SERVER,
                ConnectionState.PLAY, encoded).packet());
        CorePackets.ContainerClosed serverClosed = new CorePackets.ContainerClosed(7);
        encoded = registry.encode(PacketDirection.SERVER_TO_CLIENT, ConnectionState.PLAY,
                new PacketEnvelope(serverClosed));
        assertEquals(serverClosed, registry.decode(PacketDirection.SERVER_TO_CLIENT,
                ConnectionState.PLAY, encoded).packet());

        CorePackets.ContainerData containerData = new CorePackets.ContainerData(7,
                new int[] {20, 200, 40, 100});
        encoded = registry.encode(PacketDirection.SERVER_TO_CLIENT, ConnectionState.PLAY,
                new PacketEnvelope(containerData));
        CorePackets.ContainerData decodedData = (CorePackets.ContainerData) registry.decode(
                PacketDirection.SERVER_TO_CLIENT, ConnectionState.PLAY, encoded).packet();
        assertEquals(40, decodedData.values()[2]);

        CorePackets.RespawnRequest respawn = new CorePackets.RespawnRequest();
        encoded = registry.encode(PacketDirection.CLIENT_TO_SERVER, ConnectionState.PLAY,
                new PacketEnvelope(respawn));
        assertEquals(respawn, registry.decode(PacketDirection.CLIENT_TO_SERVER,
                ConnectionState.PLAY, encoded).packet());

        CorePackets.WorldSound sound = new CorePackets.WorldSound("skyengine:overworld",
                WorldSoundType.PISTON_EXTEND, 0, -12.5, 65.0, 4.25);
        encoded = registry.encode(PacketDirection.SERVER_TO_CLIENT, ConnectionState.PLAY,
                new PacketEnvelope(sound));
        assertEquals(sound, registry.decode(PacketDirection.SERVER_TO_CLIENT,
                ConnectionState.PLAY, encoded).packet());
    }

    @Test
    void invalidClientClaimsAreRejectedAtConstructionBoundary() {
        assertThrows(IllegalArgumentException.class, () -> new BlockActionRequest(1,
                BlockActionRequest.Action.PLACE, "skyengine:overworld", 0, 512, 0, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new InventoryActionRequest(1, 0,
                5000, 0, InventoryActionRequest.Action.PICKUP, 0));
        assertThrows(IllegalArgumentException.class, () -> new NetworkItemStack(1, 0, null));
    }

    @Test
    void swingIntentAndAuthoritativeBlockEffectRoundTrip() throws Exception {
        PacketRegistry registry = CoreProtocol.createRegistry();
        CorePackets.PlayerSwing swing = new CorePackets.PlayerSwing(44);
        byte[] encoded = registry.encode(PacketDirection.CLIENT_TO_SERVER, ConnectionState.PLAY,
                new PacketEnvelope(swing));
        assertEquals(swing, registry.decode(PacketDirection.CLIENT_TO_SERVER,
                ConnectionState.PLAY, encoded).packet());

        CorePackets.BlockActionEffect effect = new CorePackets.BlockActionEffect(44, 9,
                BlockActionEffectType.BREAK, "skyengine:overworld", 17,
                -33, 70, 65, 4, 128, 64, 255);
        encoded = registry.encode(PacketDirection.SERVER_TO_CLIENT, ConnectionState.PLAY,
                new PacketEnvelope(effect));
        assertEquals(effect, registry.decode(PacketDirection.SERVER_TO_CLIENT,
                ConnectionState.PLAY, encoded).packet());
    }

    @Test
    void placementTargetAndTargetedCorrectionsRoundTrip() throws Exception {
        PacketRegistry registry = CoreProtocol.createRegistry();
        var request = new CorePackets.BlockAction(new BlockActionRequest(77,
                BlockActionRequest.Action.PLACE, "skyengine:overworld", -33, 71, 65,
                1, 0, 42, 99, 0, 12, 128, 244, false));
        byte[] encoded = registry.encode(PacketDirection.CLIENT_TO_SERVER, ConnectionState.PLAY,
                new PacketEnvelope(request));
        assertEquals(request, registry.decode(PacketDirection.CLIENT_TO_SERVER,
                ConnectionState.PLAY, encoded).packet());

        var result = new CorePackets.BlockActionResult(77, false, "Placement target changed", List.of(
                new AuthoritativeBlockCorrection("skyengine:overworld", -33, 71, 65, 42),
                new AuthoritativeBlockCorrection("skyengine:overworld", -33, 72, 65, 0)));
        encoded = registry.encode(PacketDirection.SERVER_TO_CLIENT, ConnectionState.PLAY,
                new PacketEnvelope(result));
        assertEquals(result, registry.decode(PacketDirection.SERVER_TO_CLIENT,
                ConnectionState.PLAY, encoded).packet());
    }
}
