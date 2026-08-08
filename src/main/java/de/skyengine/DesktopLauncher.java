package de.skyengine;

import de.skyengine.core.EngineConfig;
import de.skyengine.core.SkyEngine;
import de.skyengine.graphics.color.Color4;

public class DesktopLauncher {

    public static void main(String[] args) {
        EngineConfig config = new EngineConfig();

        /* Fenstergröße optional per -Dskyengine.window=BREITExHOEHE (Messläufe brauchen eine
           feste, vergleichbare Auflösung — z.B. 5120x1440). Ohne die Property: 1280x720. */
        int breite = 1280, hoehe = 720;
        String fenster = System.getProperty("skyengine.window");
        if (fenster != null && fenster.matches("\\d+x\\d+")) {
            String[] teile = fenster.split("x");
            breite = Integer.parseInt(teile[0]);
            hoehe = Integer.parseInt(teile[1]);
        }
        config.setWindowSize(breite, hoehe);
        config.setWindowMinSizeLimit(640, 360);
        config.setWindowIcon(
                "./src/main/resources/engine/logo/skyengine-logo-big-128.png",
                "./src/main/resources/engine/logo/skyengine-logo-big-64.png",
                "./src/main/resources/engine/logo/skyengine-logo-big-32.png"
        );
        config.setWindowMode(EngineConfig.WindowMode.WINDOWED);
        config.setResizeable(true);
        config.setMaximized(false);
        config.setVSync(false);
        config.setDebugMode(EngineConfig.DebugMode.FULL);
        config.setWindowClearColor(new Color4(0.5059F, 0.6431F, 1.0F, 1.0F));

        SkyEngine engine = new SkyEngine(config);
        engine.launch();
    }
}