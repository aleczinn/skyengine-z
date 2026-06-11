package de.skyengine.core;

import de.skyengine.graphics.color.Color4;

public class EngineConfig {

    private String title = "SkyEngine";
    private String version = "1.0.0";

    private int windowWidth = 1280;
    private int windowHeight = 720;

    private int windowMinWidth = -1, windowMaxWidth = -1;
    private int windowMinHeight = -1, windowMaxHeight = -1;

    private String[] windowIconPaths;

    private WindowMode windowMode = WindowMode.WINDOWED;
    private boolean windowResizeable = true;
    private boolean windowMaximized = false;
    private boolean windowMinimized = false;
    private boolean useVSync = false;

    private int backgroundFPS = -1;

    private Color4 windowClearColor = Color4.BLACK;
    private DebugMode debugMode = DebugMode.NONE;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public int getWindowWidth() {
        return windowWidth;
    }

    public void setWindowWidth(int width) {
        this.windowWidth = Math.max(width, 10);
    }

    public int getWindowHeight() {
        return windowHeight;
    }

    public void setWindowHeight(int height) {
        this.windowHeight = Math.max(height, 10);
    }

    public void setWindowSize(int width, int height) {
        this.windowWidth = Math.max(width, 10);
        this.windowHeight = Math.max(height, 10);
    }

    public double getAspectRatio() {
        return ((double) this.windowHeight / this.windowHeight);
    }

    public int getWindowMaxWidth() {
        return windowMaxWidth;
    }

    public void setWindowMaxWidth(int value) {
        this.windowMaxWidth = value;
    }

    public int getWindowMinWidth() {
        return windowMinWidth;
    }

    public void setWindowMinWidth(int value) {
        this.windowMinWidth = value;
    }

    public int getWindowMaxHeight() {
        return windowMaxHeight;
    }

    public void setWindowMaxHeight(int value) {
        this.windowMaxHeight = value;
    }

    public int getWindowMinHeight() {
        return windowMinHeight;
    }

    public void setWindowMinHeight(int value) {
        this.windowMinHeight = value;
    }

    public void setWindowSizeLimit(int minWidth, int minHeight, int maxWidth, int maxHeight) {
        this.windowMinWidth = Math.max(minWidth, 0);
        this.windowMinHeight = Math.max(minHeight, 0);
        this.windowMaxWidth = maxWidth;
        this.windowMaxHeight = maxHeight;
    }

    public void setWindowMinSizeLimit(int minWidth, int minHeight) {
        this.windowMinWidth = minWidth;
        this.windowMinHeight = minHeight;
        this.windowMaxWidth = -1;
        this.windowMaxHeight = -1;
    }

    public WindowMode getWindowMode() {
        return windowMode;
    }

    public void setWindowMode(WindowMode windowMode) {
        this.windowMode = windowMode;
    }

    public boolean isWindowed() {
        return this.windowMode.equals(WindowMode.WINDOWED);
    }

    public boolean isFullscreen() {
        return this.windowMode.equals(WindowMode.FULLSCREEN);
    }

    public boolean isBorderlessFullscreen() {
        return this.windowMode.equals(WindowMode.BORDERLESS_FULLSCREEN);
    }

    public boolean isResizeable() {
        return windowResizeable;
    }

    public void setResizeable(boolean resizeable) {
        this.windowResizeable = resizeable;
    }

    public boolean isMaximized() {
        return windowMaximized;
    }

    public void setMaximized(boolean maximized) {
        this.windowMaximized = maximized;
    }

    public boolean isMinimized() {
        return windowMinimized;
    }

    public void setMinimized(boolean minimized) {
        this.windowMinimized = minimized;
    }

    public boolean isVSync() {
        return useVSync;
    }

    public void setVSync(boolean vSync) {
        this.useVSync = vSync;
    }

    public String[] getWindowIconPaths() {
        return windowIconPaths;
    }

    public void setWindowIcon(String... paths) {
        this.windowIconPaths = paths;
    }

    public Color4 getWindowClearColor() {
        return windowClearColor;
    }

    public void setWindowClearColor(Color4 color) {
        this.windowClearColor = color;
    }

    public DebugMode getDebugMode() {
        return debugMode;
    }

    public void setDebugMode(DebugMode debugMode) {
        this.debugMode = debugMode;
    }

    public int getBackgroundFPS() {
        return backgroundFPS;
    }

    public void setBackgroundFPS(int backgroundFPS) {
        this.backgroundFPS = backgroundFPS;
    }

    public enum WindowMode {
        WINDOWED, FULLSCREEN, BORDERLESS_FULLSCREEN;
    }

    public enum DebugMode {
        NONE, LOW, FULL;
    }
}
