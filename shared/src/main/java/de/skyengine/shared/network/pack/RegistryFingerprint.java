package de.skyengine.shared.network.pack;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Canonical SHA-256 over registry names and their ordered network-ID mappings. */
public final class RegistryFingerprint {
    private RegistryFingerprint() {
    }

    public static byte[] compute(List<RegistryMapping> mappings) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<RegistryMapping> ordered = new ArrayList<>(mappings);
            ordered.sort(Comparator.comparing(RegistryMapping::registry));
            putInt(digest, ordered.size());
            for (RegistryMapping mapping : ordered) {
                putString(digest, mapping.registry());
                putInt(digest, mapping.identifiers().size());
                for (String identifier : mapping.identifiers()) putString(digest, identifier);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static void putString(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        putInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void putInt(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }
}
