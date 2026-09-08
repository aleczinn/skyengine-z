package de.skyengine.shared.entity;

import de.skyengine.shared.gameplay.NetworkItemStack;
import de.skyengine.shared.player.PlayerGameMode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;

/** Single strict codec for the opaque player metadata carried by entity snapshots. */
public final class NetworkPlayerMetadataCodec {
    private static final int FIXED_BYTES = 2 + Integer.BYTES + 1 + Integer.BYTES * 3;

    private NetworkPlayerMetadataCodec() {}

    public static byte[] encode(NetworkPlayerMetadata metadata) {
        try {
            NetworkItemStack stack = metadata.heldItem();
            byte[] components = stack.components();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(FIXED_BYTES + components.length);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeByte(metadata.gameMode().ordinal());
                output.writeByte(metadata.selectedSlot());
                output.writeInt(metadata.movementState());
                output.writeBoolean(metadata.grounded());
                output.writeInt(stack.itemId());
                output.writeInt(stack.count());
                output.writeInt(components.length);
                output.write(components);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not encode player metadata", impossible);
        }
    }

    public static NetworkPlayerMetadata decode(byte[] encoded) {
        if (encoded == null || encoded.length < FIXED_BYTES
                || encoded.length > FIXED_BYTES + NetworkItemStack.MAX_COMPONENT_BYTES) {
            throw new IllegalArgumentException("Invalid player metadata length");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            int gameMode = input.readUnsignedByte();
            int selectedSlot = input.readUnsignedByte();
            int movementState = input.readInt();
            boolean grounded = input.readBoolean();
            int itemId = input.readInt();
            int count = input.readInt();
            int componentLength = input.readInt();
            if (gameMode >= PlayerGameMode.values().length || componentLength < 0
                    || componentLength > NetworkItemStack.MAX_COMPONENT_BYTES
                    || componentLength != input.available()) {
                throw new IllegalArgumentException("Invalid player metadata payload");
            }
            byte[] components = input.readNBytes(componentLength);
            if (components.length != componentLength) throw new EOFException("Truncated item components");
            return new NetworkPlayerMetadata(PlayerGameMode.values()[gameMode], selectedSlot,
                    movementState, grounded, new NetworkItemStack(itemId, count, components));
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid player metadata", failure);
        }
    }
}
