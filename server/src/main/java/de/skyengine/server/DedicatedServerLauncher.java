package de.skyengine.server;

import java.nio.file.Path;

public final class DedicatedServerLauncher {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(".");
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--directory") && i + 1 < args.length) directory = Path.of(args[++i]);
            else if (args[i].equals("--help")) {
                System.out.println("Usage: java -jar skyengine-server-all.jar [--directory <server-dir>]");
                return;
            } else throw new IllegalArgumentException("Unknown argument: " + args[i]);
        }

        ServerConfig config = ServerConfig.load(directory);
        ServerApplication server = ServerApplication.dedicated(config);
        ServerConsole console = new ServerConsole(server);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            console.close();
            server.close();
        }, "Server Shutdown"));
        server.startDedicated();
        console.start();
    }

    private DedicatedServerLauncher() {}
}
