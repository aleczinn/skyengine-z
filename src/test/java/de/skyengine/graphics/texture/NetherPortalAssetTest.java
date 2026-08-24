package de.skyengine.graphics.texture;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class NetherPortalAssetTest {

    @Test
    void textureIsTheExactMinecraft262ThirtyTwoFrameStrip() throws Exception {
        byte[] bytes;
        try (InputStream input = getClass().getResourceAsStream(
                "/game/textures/block/nether_portal.png")) {
            assertNotNull(input);
            bytes = input.readAllBytes();
        }
        assertEquals("83f7f02814cf0dcf2b7ddd7be5c4ef03ab50e279bbca4acdcab5cd1d11a694f7",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        try (InputStream input = getClass().getResourceAsStream(
                "/game/textures/block/nether_portal.png")) {
            var image = ImageIO.read(input);
            assertEquals(16, image.getWidth());
            assertEquals(512, image.getHeight());
        }
    }

    @Test
    void ambientSoundIsTheExactMinecraft262PortalSound() throws Exception {
        byte[] bytes;
        try (InputStream input = getClass().getResourceAsStream(
                "/game/sounds/portal/ambient.ogg")) {
            assertNotNull(input);
            bytes = input.readAllBytes();
        }
        assertEquals("cab476273992068550356ba489629a3b570c5cc9208d7a397f65a6afd212fdda",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }
}
