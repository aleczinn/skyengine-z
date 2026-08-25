package de.skyengine.game;

import de.skyengine.game.entity.EntityPlayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class GameContainerSessionlessGuiTest {

    @Test
    void mainMenuUsesSafeDefaultSlotWithoutPlayer() {
        assertEquals(0, GameContainer.selectedSlotForGui(null));
    }

    @Test
    void joinedWorldUsesPlayersSelectedSlot() {
        EntityPlayer player = new EntityPlayer();
        player.setSelectedSlot(6);
        assertEquals(6, GameContainer.selectedSlotForGui(player));
    }
}
