package de.skyengine.shared.network.pack;

import java.util.List;
import java.util.Objects;
import java.util.HashSet;

public record RegistryMapping(String registry, List<String> identifiers) {
    public RegistryMapping {
        Objects.requireNonNull(registry);
        if (registry.isBlank()) throw new IllegalArgumentException("Blank registry name");
        identifiers = List.copyOf(identifiers);
        HashSet<String> unique = new HashSet<>();
        for (String identifier : identifiers) {
            if (identifier.isBlank() || !unique.add(identifier)) {
                throw new IllegalArgumentException("Invalid or duplicate registry identifier: " + identifier);
            }
        }
    }
}
