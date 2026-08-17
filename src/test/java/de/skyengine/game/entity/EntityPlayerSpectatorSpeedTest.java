package de.skyengine.game.entity;

import de.skyengine.game.Gamemode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class EntityPlayerSpectatorSpeedTest {

    @Test
    void scrollChangesSpectatorSpeedInTenPercentStepsAndClampsIt() {
        EntityPlayer player = new EntityPlayer();
        player.setGamemode(Gamemode.SPECTATOR);

        player.adjustSpectatorFlySpeed(1);
        assertEquals(1.1F, player.getSpectatorFlySpeed(), 0.0001F);
        player.adjustSpectatorFlySpeed(-2);
        assertEquals(0.9F, player.getSpectatorFlySpeed(), 0.0001F);
        player.adjustSpectatorFlySpeed(100);
        assertEquals(10F, player.getSpectatorFlySpeed(), 0.0001F);
        player.adjustSpectatorFlySpeed(-100);
        assertEquals(0F, player.getSpectatorFlySpeed(), 0.0001F);
    }

    @Test
    void reenteringSpectatorResetsSpeedAndOtherModesIgnoreScroll() {
        EntityPlayer player = new EntityPlayer();
        player.adjustSpectatorFlySpeed(10);
        assertEquals(1F, player.getSpectatorFlySpeed(), 0.0001F);

        player.setGamemode(Gamemode.SPECTATOR);
        player.adjustSpectatorFlySpeed(5);
        player.setGamemode(Gamemode.CREATIVE);
        player.setGamemode(Gamemode.SPECTATOR);

        assertEquals(1F, player.getSpectatorFlySpeed(), 0.0001F);
    }
}
