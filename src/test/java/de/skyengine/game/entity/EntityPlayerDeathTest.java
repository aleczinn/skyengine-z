package de.skyengine.game.entity;

import de.skyengine.game.Gamemode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EntityPlayerDeathTest {

    @Test
    void forcedKillUsesTheNormalHurtEdgeInEveryGamemode() {
        for (Gamemode gamemode : Gamemode.values()) {
            EntityPlayer player = new EntityPlayer();
            player.setGamemode(gamemode);

            player.kill();

            assertTrue(player.isDead(), gamemode.name());
            assertEquals(0F, player.getHealth(), gamemode.name());
            assertTrue(player.consumeHurt(), gamemode.name());
            assertFalse(player.consumeHurt(), gamemode.name());
            assertEquals(0F, player.consumeFallDamage(), gamemode.name());
        }
    }
}
