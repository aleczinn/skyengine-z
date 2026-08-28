package de.skyengine.core.resource;

import de.skyengine.core.file.Files;
import de.skyengine.core.SkyEngine;
import de.skyengine.core.settings.GameSettings;

import java.nio.file.Path;
import java.util.List;

/** Globaler, frueh verfuegbarer Zugang zum aktiven Ressourcen-Stack. */
public final class Resources {
    private static ResourcePackRepository repository;
    private static ResourceManager manager;

    public static synchronized void initialize() {
        if (manager != null) return;
        ResourceSource defaults = new DirectoryResourceSource(SkyEngine.GAME_PREFIX + "-default",
                Path.of(Files.RESOURCES_PATH, "game"), true);
        repository = new ResourcePackRepository();
        repository.refresh();
        manager = new ResourceManager(defaults);
        manager.setPacks(repository.selected(GameSettings.get().resourcePacks));
    }

    public static ResourceManager get() {
        if (manager == null) initialize();
        return manager;
    }

    public static ResourcePackRepository repository() {
        if (repository == null) initialize();
        return repository;
    }

    public static synchronized void activate(List<String> sourceNames) {
        repository.refresh();
        manager.setPacks(repository.selected(sourceNames));
    }

    /** Test-Hook fuer isolierte Resolver-Tests. */
    public static synchronized void install(ResourceManager replacement, ResourcePackRepository replacementRepository) {
        manager = replacement;
        repository = replacementRepository;
    }

    private Resources() {}
}
