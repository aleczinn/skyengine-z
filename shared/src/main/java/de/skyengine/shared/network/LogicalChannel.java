package de.skyengine.shared.network;

public enum LogicalChannel {
    CONTROL,
    MOVEMENT,
    GAMEPLAY,
    ENTITY,
    CHAT,
    CHUNK_DATA;

    public static LogicalChannel fromId(int id) throws ProtocolException {
        LogicalChannel[] values = values();
        if (id < 0 || id >= values.length) throw new ProtocolException("Unknown channel " + id);
        return values[id];
    }
}
