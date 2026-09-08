package de.skyengine.game.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PlayerPresentationStateTest {

    @Test
    void networkSnapshotsDoNotResetSmoothCrouchTransition() {
        EntityPlayer player = new EntityPlayer();

        player.restoreNetworkMovementState(false, false, false, true);
        assertEquals(0F, player.getCrouchProgress(1F), 0.0001F);

        player.tickPresentationState();
        float firstTick = player.getCrouchProgress(1F);
        assertEquals(0.5F, firstTick, 0.0001F);

        player.restoreNetworkMovementState(false, false, false, true);
        player.tickPresentationState();
        assertEquals(0.75F, player.getCrouchProgress(1F), 0.0001F);

        player.restoreNetworkMovementState(false, false, false, false);
        player.tickPresentationState();
        assertTrue(player.getCrouchProgress(1F) < 0.75F);
        assertTrue(player.getCrouchProgress(1F) > 0F);
    }

    @Test
    void movementSampleDrivesLimbAnimationAndViewBobbing() {
        EntityPlayer player = new EntityPlayer();
        PlayerAnimationState animation = new PlayerAnimationState();
        player.lastX = 0;
        player.lastZ = 0;
        player.x = 0.25;
        player.z = 0;
        player.onGround = true;

        animation.tick(player);

        assertTrue(animation.getLimbSwingAmount(1F) > 0F);
        assertTrue(animation.getLimbSwing(1F) > 0F);
        assertTrue(animation.getBob(1F) > 0F);
        assertTrue(animation.getWalkDistExtrapolated(1F) > 0F);
    }
}
