package de.skyengine.game.physics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ChunkMovementLimiterTest {
    private static final double EPSILON = 1.0E-6;

    @Test void clipsOnlyMovementIntoAnUnavailableChunk() {
        AABB player = new AABB(31.1, 64, 4, 31.7, 65.8, 4.6);
        ChunkMovementLimiter.Availability loaded = (x, z) -> x <= 0;

        ChunkMovementLimiter.Movement outward = ChunkMovementLimiter.limit(player, 2, 0, 0, loaded);
        assertEquals(0.3, outward.x(), EPSILON);

        ChunkMovementLimiter.Movement back = ChunkMovementLimiter.limit(player, -2, 0, 0, loaded);
        assertEquals(-2, back.x(), EPSILON);
    }

    @Test void highSpeedMovementCannotSkipAcrossAnUnavailableColumn() {
        AABB player = new AABB(1.2, 64, 1.2, 1.8, 65.8, 1.8);
        ChunkMovementLimiter.Availability loaded = (x, z) -> x <= 1;

        ChunkMovementLimiter.Movement movement = ChunkMovementLimiter.limit(player, 200, 0, 0, loaded);
        assertEquals(64 - player.maxX, movement.x(), EPSILON);
    }

    @Test void diagonalMovementSlidesAlongTheLoadedFrontier() {
        AABB player = new AABB(31.1, 64, 5, 31.7, 65.8, 5.6);
        ChunkMovementLimiter.Availability loaded = (x, z) -> x <= 0;

        ChunkMovementLimiter.Movement movement = ChunkMovementLimiter.limit(player, 2, 0, 3, loaded);
        assertEquals(0.3, movement.x(), EPSILON);
        assertEquals(3, movement.z(), EPSILON);
    }

    @Test void negativeChunkBoundaryUsesFloorCoordinates() {
        AABB player = new AABB(-31.0, 64, 0, -30.4, 65.8, 0.6);
        ChunkMovementLimiter.Availability loaded = (x, z) -> x >= -1;

        ChunkMovementLimiter.Movement movement = ChunkMovementLimiter.limit(player, -5, 0, 0, loaded);
        assertEquals(-1.0, movement.x(), EPSILON);
    }

    @Test void playerStraddlingAnUnavailableFrontierCanRetreatButNotContinueOutward() {
        AABB player = new AABB(31.8, 64, 4, 32.4, 65.8, 4.6);
        ChunkMovementLimiter.Availability loaded = (x, z) -> x <= 0;

        ChunkMovementLimiter.Movement retreat = ChunkMovementLimiter.limit(player, -1, 0, 0, loaded);
        ChunkMovementLimiter.Movement outward = ChunkMovementLimiter.limit(player, 1, 0, 0, loaded);

        assertEquals(-1, retreat.x(), EPSILON);
        assertEquals(0, outward.x(), EPSILON);
    }
}
