package de.skyengine.game.world.environment;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DayNightCycleTest {

    @Test
    void convertsBetweenClockAndMinecraftTicks() {
        assertEquals(0.0, DayNightCycle.clockToTicks(6, 0));
        assertEquals(3_000.0, DayNightCycle.clockToTicks(9, 0));
        assertEquals(6_000.0, DayNightCycle.clockToTicks(12, 0));
        assertEquals(18_000.0, DayNightCycle.clockToTicks(0, 0));
        assertEquals("09:00", DayNightCycle.formatClock(3_000));
        assertEquals("00:00", DayNightCycle.formatClock(18_000));
    }

    @Test
    void wrapsNegativeAndMultiDayTimes() {
        assertEquals(23_999.0, DayNightCycle.wrappedTicks(-1.0));
        assertEquals(1_000.0, DayNightCycle.wrappedTicks(49_000.0));
    }

    @Test
    void sunAndMoonRemainOppositeAndLightTransitionsSmoothly() {
        Vector3f sun = DayNightCycle.sunDirection(6_000, new Vector3f());
        Vector3f moon = DayNightCycle.moonDirection(6_000, new Vector3f());
        assertEquals((float) Math.cos(Math.toRadians(35.0)), sun.y, 0.00001F);
        assertEquals((float) -Math.sin(Math.toRadians(35.0)), sun.z, 0.00001F);
        assertEquals(-1F, sun.dot(moon), 0.00001F);
        assertEquals(1F, DayNightCycle.skyIntensity(6_000), 0.00001F);
        assertEquals(0.16F, DayNightCycle.skyIntensity(18_000), 0.00001F);
        float before = DayNightCycle.skyIntensity(-500);
        float sunrise = DayNightCycle.skyIntensity(0);
        float after = DayNightCycle.skyIntensity(500);
        assertTrue(before < sunrise && sunrise < after);
    }

    @Test
    void environmentCarriesDimensionAndBiomeParameters() {
        EnvironmentState state = new EnvironmentState();
        BiomeEnvironmentModifier modifier = new BiomeEnvironmentModifier(
                0.9F, 1F, 1F, 1F, 0.8F, 0.7F, 2F);
        state.update(EnvironmentProfile.OVERWORLD, modifier, 6_000);
        assertEquals(EnvironmentProfile.OVERWORLD.fogDensity() * 2F, state.fogDensity, 0.000001F);
        assertEquals(0.9F, state.skyTint.x, 0.000001F);
        assertEquals(1F, state.skyIntensity, 0.000001F);
    }
}
