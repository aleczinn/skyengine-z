package de.skyengine.graphics.gui;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class MekanismEnergyGuiAssetTest {
    @Test
    void energyCubeUsesExactMekanismGuiAssets() throws Exception {
        assertAsset("/game/textures/gui/mekanism/button.png",
                200, 60, "26daafa6ff2077e2f25e325c45fbf8852ed3af781c1d415b91652722336217ba");
        assertAsset("/game/textures/gui/mekanism/inner_screen.png",
                256, 256, "0e0e2aeb4512785d15b144258746f7a1c681f2b20dc55724dba35dcf3cc35fd6");
        assertAsset("/game/textures/gui/mekanism/gauge/normal.png",
                5, 5, "a1834639bc02aaa70a58d6b8dc8ae818fe9957f6060f777eb43226e38a242e48");
        assertAsset("/game/textures/liquid/mekanism/energy.png",
                16, 512, "270777c43278c85b8279b2752b7e6e777e5a258e8e725c938066e2a4127553b0");
    }

    private static void assertAsset(String path, int width, int height, String sha256) throws Exception {
        byte[] bytes;
        try (InputStream input = MekanismEnergyGuiAssetTest.class.getResourceAsStream(path)) {
            assertNotNull(input, path);
            bytes = input.readAllBytes();
        }
        assertEquals(sha256, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        try (InputStream input = MekanismEnergyGuiAssetTest.class.getResourceAsStream(path)) {
            var image = ImageIO.read(input);
            assertNotNull(image, path);
            assertEquals(width, image.getWidth(), path);
            assertEquals(height, image.getHeight(), path);
        }
    }
}
