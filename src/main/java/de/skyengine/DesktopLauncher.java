package de.skyengine;

import de.skyengine.core.EngineConfig;
import de.skyengine.core.SkyEngine;
import de.skyengine.graphics.color.Color4;

public class DesktopLauncher {

    public static void main(String[] args) {
        EngineConfig config = new EngineConfig();

        config.setWindowSize(1280, 720);
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
        config.setWindowClearColor(new Color4(0.5F, 0.8F, 1.0F, 1.0F));

        SkyEngine engine = new SkyEngine(config);
        engine.launch();
    }
}