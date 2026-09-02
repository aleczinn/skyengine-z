package de.skyengine.shared;

/** Values shared by the headless server and the graphical client. */
public final class EngineInfo {
    public static final String GAME_NAME = "Voxel Stories";
    public static final String CONTENT_NAMESPACE = "voxelstories";
    public static final String GAME_DATA_DIRECTORY_NAME = "." + CONTENT_NAMESPACE;
    public static final String ENGINE_VERSION = "0.0.16-alpha";
    public static final int PROTOCOL_VERSION = 5;
    public static final int TICKS_PER_SECOND = 20;

    private EngineInfo() {}
}
