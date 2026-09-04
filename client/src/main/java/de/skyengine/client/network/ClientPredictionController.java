package de.skyengine.client.network;

import de.skyengine.shared.player.PlayerInputFrame;
import de.skyengine.shared.player.PlayerStateSnapshot;

import java.util.ArrayDeque;
import java.util.Objects;

/** Transport-independent prediction/replay state. The simulation adapter is shared with gameplay physics. */
public final class ClientPredictionController {
    public interface Simulation {
        PlayerStateSnapshot simulate(PlayerStateSnapshot state, PlayerInputFrame input);
    }

    public record Reconciliation(double correctionX, double correctionY, double correctionZ,
                                 boolean hardCorrection, int replayedInputs) {}

    private static final int MAX_PENDING_INPUTS = 256;
    private static final double HARD_CORRECTION_DISTANCE_SQUARED = 4.0;
    private final Simulation simulation;
    private final ArrayDeque<PlayerInputFrame> pending = new ArrayDeque<>();
    private PlayerStateSnapshot predicted;
    private long lastSubmittedSequence;

    public ClientPredictionController(PlayerStateSnapshot initial, Simulation simulation) {
        this.predicted = Objects.requireNonNull(initial);
        this.simulation = Objects.requireNonNull(simulation);
        this.lastSubmittedSequence = initial.lastProcessedInputSequence();
    }

    public PlayerStateSnapshot submit(PlayerInputFrame input) {
        if (input.sequence() <= this.lastSubmittedSequence) throw new IllegalArgumentException("Input sequence is not monotonic");
        if (this.pending.size() >= MAX_PENDING_INPUTS) throw new IllegalStateException("Prediction input backlog exceeded");
        this.lastSubmittedSequence = input.sequence();
        this.pending.addLast(input);
        this.predicted = Objects.requireNonNull(this.simulation.simulate(this.predicted, input));
        return this.predicted;
    }

    public Reconciliation reconcile(PlayerStateSnapshot authoritative) {
        Objects.requireNonNull(authoritative);
        double oldX = this.predicted.x(), oldY = this.predicted.y(), oldZ = this.predicted.z();
        while (!this.pending.isEmpty()
                && this.pending.getFirst().sequence() <= authoritative.lastProcessedInputSequence()) {
            this.pending.removeFirst();
        }
        this.predicted = authoritative;
        int replayed = 0;
        for (PlayerInputFrame input : this.pending) {
            this.predicted = Objects.requireNonNull(this.simulation.simulate(this.predicted, input));
            replayed++;
        }
        double dx = this.predicted.x() - oldX, dy = this.predicted.y() - oldY, dz = this.predicted.z() - oldZ;
        boolean hard = dx * dx + dy * dy + dz * dz >= HARD_CORRECTION_DISTANCE_SQUARED
                || !this.predicted.dimension().equals(authoritative.dimension());
        return new Reconciliation(dx, dy, dz, hard, replayed);
    }

    public PlayerStateSnapshot predicted() { return this.predicted; }
    public int pendingInputs() { return this.pending.size(); }
}
