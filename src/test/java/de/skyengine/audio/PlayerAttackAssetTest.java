package de.skyengine.audio;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class PlayerAttackAssetTest {

    private static final String[] SHA256 = {
            "c3a2cdb84c239ba00d504d82a1a7933b7dcfe536248a0dd7c1cf6ce94b560a57",
            "4b10eab0de89174ddea982d360a1d207382d3a4e0b6caeedba368b9097905fe8",
            "4611d672d17e1b82b2516db0118e2381003cd3767baeffa5382c0acb82e7e2f1",
            "954f1849040fd9476561e939f433a1f237d71b35e5665d6e276cbafdb227c201"
    };

    @Test
    void weakAttackSoundsAreTheExactMinecraft262Assets() throws Exception {
        for (int i = 0; i < SHA256.length; i++) {
            String path = "/game/sounds/player_attack/weak" + (i + 1) + ".ogg";
            try (InputStream input = getClass().getResourceAsStream(path)) {
                assertNotNull(input, path);
                String actual = HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
                assertEquals(SHA256[i], actual, path);
            }
        }
    }

    @Test
    void weakAttackUsesMinecraft262SoundDefinitionVolume() {
        assertEquals(0.7F, SoundManager.WEAK_ATTACK_VOLUME);
    }
}
