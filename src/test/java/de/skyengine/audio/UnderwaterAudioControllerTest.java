package de.skyengine.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class UnderwaterAudioControllerTest {

    @Test
    void emitsTransitionsAndFadesLoopLikeMinecraft() {
        FakeSink sink = new FakeSink();
        UnderwaterAudioController controller = new UnderwaterAudioController(sink, new FixedRandom(0.5F));

        for (int i = 0; i < 40; i++) controller.tick(true, false, true, 0, 0, 0);
        assertEquals(1, sink.enters);
        assertEquals(1F, sink.loopGains.getLast(), 0.000001F);

        for (int i = 0; i < 20; i++) controller.tick(false, false, true, 0, 0, 0);
        assertEquals(1, sink.exits);
        assertEquals(0F, sink.loopGains.getLast(), 0.000001F);
    }

    @Test
    void selectsAllThreeAdditionBands() {
        FakeSink sink = new FakeSink();
        UnderwaterAudioController controller = new UnderwaterAudioController(sink,
                new FixedRandom(0.005F, 0.0005F, 0.00005F));

        controller.tick(true, false, true, 0, 0, 0);
        controller.tick(true, false, true, 0, 0, 0);
        controller.tick(true, false, true, 0, 0, 0);

        assertEquals(List.of(UnderwaterAudioController.ADDITION_NORMAL,
                UnderwaterAudioController.ADDITION_RARE,
                UnderwaterAudioController.ADDITION_ULTRA_RARE), sink.additions);
    }

    @Test
    void splashesOnContactAndSwimsAfterTravel() {
        FakeSink sink = new FakeSink();
        UnderwaterAudioController controller = new UnderwaterAudioController(sink, new FixedRandom(0.5F));

        controller.tick(false, false, true, 0, 0, 0);
        controller.tick(false, true, true, 0.4, 0, 0);
        controller.tick(false, true, true, 0.7, 0, 0);
        controller.tick(false, true, true, 0.7, 0, 0);

        assertEquals(1, sink.splashes);
        assertEquals(1, sink.swims);
    }

    @Test
    void creativeFlightSuppressesRepeatedSwimSounds() {
        FakeSink sink = new FakeSink();
        UnderwaterAudioController controller = new UnderwaterAudioController(sink, new FixedRandom(0.5F));

        controller.tick(true, true, false, 0, 0, 0);
        for (int i = 0; i < 20; i++) controller.tick(true, true, false, 2, 0, 0);

        assertEquals(0, sink.swims);
        assertEquals(1, sink.enters);
    }

    private static final class FixedRandom extends Random {
        private final float[] values;
        private int index;

        FixedRandom(float... values) {
            this.values = values;
        }

        @Override
        public float nextFloat() {
            float value = this.values[Math.min(this.index, this.values.length - 1)];
            this.index++;
            return value;
        }
    }

    private static final class FakeSink implements UnderwaterAudioSink {
        int enters, exits, swims, splashes;
        final List<Float> loopGains = new ArrayList<>();
        final List<Integer> additions = new ArrayList<>();

        @Override public void playUnderwaterEnter() { this.enters++; }
        @Override public void playUnderwaterExit() { this.exits++; }
        @Override public void setUnderwaterLoopGain(float gain) { this.loopGains.add(gain); }
        @Override public void playUnderwaterAddition(int rarity) { this.additions.add(rarity); }
        @Override public void playSwim(float speed) { this.swims++; }
        @Override public void playSplash(float speed) { this.splashes++; }
    }
}
