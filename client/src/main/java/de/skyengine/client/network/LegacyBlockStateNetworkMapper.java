package de.skyengine.client.network;

import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.BlockStateCodec;
import de.skyengine.shared.network.pack.RegistryMapping;

import java.util.Objects;
import java.util.function.IntUnaryOperator;

/** Resolves stable protocol identifiers to this client's baked, process-local state IDs. */
public final class LegacyBlockStateNetworkMapper {
    public record Mapping(IntUnaryOperator remoteToLocal, IntUnaryOperator localToRemote) { }
    private LegacyBlockStateNetworkMapper() {
    }

    public static IntUnaryOperator create(RegistryMapping mapping) {
        return createBidirectional(mapping).remoteToLocal();
    }

    public static Mapping createBidirectional(RegistryMapping mapping) {
        Objects.requireNonNull(mapping, "mapping");
        if (!mapping.registry().equals("block_state")) {
            throw new IllegalArgumentException("Expected block_state registry, got " + mapping.registry());
        }
        int[] remoteToLocal = new int[mapping.identifiers().size()];
        int maximumLocal = 0;
        for (int i = 0; i < remoteToLocal.length; i++) {
            String identifier = mapping.identifiers().get(i);
            BlockState local = BlockStateCodec.decode(identifier);
            if (local == null) {
                throw new IllegalArgumentException("Server requires unknown block state " + identifier);
            }
            remoteToLocal[i] = local.getId();
            maximumLocal = Math.max(maximumLocal, local.getId());
        }
        int[] localToRemote = new int[maximumLocal + 1];
        java.util.Arrays.fill(localToRemote, -1);
        for (int remote = 0; remote < remoteToLocal.length; remote++) {
            localToRemote[remoteToLocal[remote]] = remote;
        }
        IntUnaryOperator decode = remoteId -> {
            if (remoteId < 0 || remoteId >= remoteToLocal.length) {
                throw new IllegalArgumentException("Server block-state ID outside negotiated registry: " + remoteId);
            }
            return remoteToLocal[remoteId];
        };
        IntUnaryOperator encode = localId -> {
            if (localId < 0 || localId >= localToRemote.length || localToRemote[localId] < 0) {
                throw new IllegalArgumentException("Local block state is absent from negotiated registry: " + localId);
            }
            return localToRemote[localId];
        };
        return new Mapping(decode, encode);
    }
}
