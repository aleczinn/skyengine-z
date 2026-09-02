package de.skyengine.server.event;

public abstract class CancellableServerEvent {
    private boolean cancelled;
    private String cancellationMessage = "Action cancelled";
    public boolean cancelled() { return this.cancelled; }
    public String cancellationMessage() { return this.cancellationMessage; }
    public void cancel(String message) {
        this.cancelled = true;
        this.cancellationMessage = message == null || message.isBlank() ? "Action cancelled" : message;
    }
}
