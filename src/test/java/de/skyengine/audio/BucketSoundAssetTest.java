package de.skyengine.audio;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class BucketSoundAssetTest {
    @Test
    void allWaterAndLavaBucketVariantsAreBundledOggFiles() throws Exception {
        for (String base : new String[]{"empty", "empty_lava", "fill", "fill_lava"}) {
            for (int variant = 1; variant <= 3; variant++) {
                try (InputStream input = getClass().getResourceAsStream(
                        "/game/sounds/bucket/" + base + variant + ".ogg")) {
                    assertNotNull(input, base + variant);
                    assertArrayEquals(new byte[]{'O', 'g', 'g', 'S'}, input.readNBytes(4));
                }
            }
        }
    }
}
