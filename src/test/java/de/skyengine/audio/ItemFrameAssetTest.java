package de.skyengine.audio;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class ItemFrameAssetTest {

    private static final String[] BREAK_SHA256 = {
            "9e708acd89ab036d2f81e4b52549b110210af8a46adc32485813b5a51ba556ca",
            "67da621d67510619867028a0623c29291e5e685b3f6c6d8afa920a30c85f7fb8",
            "a1c305b0aedaa646378f99bcd966202711356a347d54f6d5caa92df2544d4865"
    };
    private static final String[] REMOVE_SHA256 = {
            "46e190a696aafbe55b92de8ebe8b99cb527639710474778c71b4e49fb1712446",
            "91345a73644602635d912ae17df954fca22ec51be822ae6e7c1d40ce26913766",
            "39edb30f2abdcbbe995d9833c0d576554d27677b8bfc2f3ddeba3d70a533dcde",
            "7b6712b1d53dc92b982e0981c05f86589be9c4e799a2fe943034d6047ccb1eb6"
    };

    @Test
    void breakAndRemoveSoundsAreTheExactMinecraft262Assets() throws Exception {
        assertAssets("break", BREAK_SHA256);
        assertAssets("remove_item", REMOVE_SHA256);
    }

    private void assertAssets(String base, String[] expected) throws Exception {
        for (int i = 0; i < expected.length; i++) {
            String path = "/game/sounds/item_frame/" + base + (i + 1) + ".ogg";
            try (InputStream input = getClass().getResourceAsStream(path)) {
                assertNotNull(input, path);
                String actual = HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
                assertEquals(expected[i], actual, path);
            }
        }
    }
}
