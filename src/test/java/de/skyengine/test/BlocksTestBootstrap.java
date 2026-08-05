package de.skyengine.test;

import de.skyengine.core.file.Files;
import de.skyengine.game.world.block.Blocks;

import java.io.File;

/** Initialisiert die globalen Content-Registries pro Test-JVM genau einmal. */
public final class BlocksTestBootstrap {

    private static boolean bootstrapped;

    public static synchronized void ensureBootstrapped() {
        if (bootstrapped) return;
        Blocks.bootstrap(new File(Files.RESOURCES_PATH, "game/blocks"));
        bootstrapped = true;
    }

    private BlocksTestBootstrap() {}
}
