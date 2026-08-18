package de.skyengine.core.resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/** Eine einzelne Ebene im Ressourcen-Stack. */
public interface ResourceSource {
    String name();
    boolean contains(ResourceId id);
    InputStream open(ResourceId id) throws IOException;
    Set<ResourceId> list(String pathPrefix) throws IOException;
}
