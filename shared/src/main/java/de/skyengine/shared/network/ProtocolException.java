package de.skyengine.shared.network;

import java.io.IOException;

public final class ProtocolException extends IOException {
    public ProtocolException(String message) { super(message); }
    public ProtocolException(String message, Throwable cause) { super(message, cause); }
}
