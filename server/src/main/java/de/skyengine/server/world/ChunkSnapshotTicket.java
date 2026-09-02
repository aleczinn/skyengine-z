package de.skyengine.server.world;

import de.skyengine.shared.world.ChunkColumnSnapshot;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/** A subscriber to a deduplicated snapshot job. Cancellation never interrupts shared work. */
public final class ChunkSnapshotTicket {
    private final CompletableFuture<Optional<ChunkColumnSnapshot>> result;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    ChunkSnapshotTicket() {
        this(new CompletableFuture<>());
    }

    private ChunkSnapshotTicket(CompletableFuture<Optional<ChunkColumnSnapshot>> result) {
        this.result = result;
    }

    public static ChunkSnapshotTicket completed(Optional<ChunkColumnSnapshot> result) {
        return new ChunkSnapshotTicket(CompletableFuture.completedFuture(result));
    }

    public CompletionStage<Optional<ChunkColumnSnapshot>> result() { return this.result; }
    /** Convenience kept for tools/tests that previously consumed the returned CompletionStage directly. */
    public CompletableFuture<Optional<ChunkColumnSnapshot>> toCompletableFuture() { return this.result; }
    public boolean cancelled() { return this.cancelled.get(); }

    public void cancel() {
        if (this.cancelled.compareAndSet(false, true)) this.result.cancel(false);
    }

    void complete(Optional<ChunkColumnSnapshot> value) {
        if (!cancelled()) this.result.complete(value);
    }

    void completeExceptionally(Throwable failure) {
        if (!cancelled()) this.result.completeExceptionally(failure);
    }
}
