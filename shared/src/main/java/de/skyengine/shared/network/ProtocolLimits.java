package de.skyengine.shared.network;

public final class ProtocolLimits {
    public static final int MAX_FRAME_BYTES = 2 * 1024 * 1024;
    public static final int MAX_DECOMPRESSED_BYTES = 8 * 1024 * 1024;
    public static final int MAX_USERNAME_BYTES = 64;
    public static final int MAX_IDENTIFIER_BYTES = 256;
    public static final int MAX_MESSAGE_BYTES = 4096;
    public static final int MAX_CHAT_BYTES = 1024;
    public static final int MAX_COMMAND_BYTES = 8192;
    public static final int MAX_PACKS = 128;
    public static final int MAX_REGISTRY_ENTRIES = 1_000_000;
    public static final int MAX_COLLECTION_SIZE = 1_000_000;

    private ProtocolLimits() {}
}
