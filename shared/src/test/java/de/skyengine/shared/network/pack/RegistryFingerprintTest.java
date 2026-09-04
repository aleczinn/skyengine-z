package de.skyengine.shared.network.pack;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RegistryFingerprintTest {
    @Test void registryOrderIsCanonicalButNetworkIdOrderIsSignificant() {
        RegistryMapping blocks = new RegistryMapping("blocks", List.of("sky:air", "sky:stone"));
        RegistryMapping items = new RegistryMapping("items", List.of("sky:stone"));
        assertArrayEquals(RegistryFingerprint.compute(List.of(blocks, items)),
                RegistryFingerprint.compute(List.of(items, blocks)));
        assertFalse(java.util.Arrays.equals(RegistryFingerprint.compute(List.of(blocks)),
                RegistryFingerprint.compute(List.of(new RegistryMapping("blocks",
                        List.of("sky:stone", "sky:air"))))));
    }
}
