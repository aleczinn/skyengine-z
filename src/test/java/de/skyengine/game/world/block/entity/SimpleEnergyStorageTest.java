package de.skyengine.game.world.block.entity;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SimpleEnergyStorageTest {
    @Test void usesLongsAndHonorsIndependentLimits() {
        SimpleEnergyStorage storage = new SimpleEnergyStorage(5_000_000_000L, 2_000L, 750L);
        assertEquals(2_000L, storage.receive(Long.MAX_VALUE, false));
        assertEquals(750L, storage.extract(Long.MAX_VALUE, false));
        assertEquals(1_250L, storage.getEnergy());
        assertEquals(2_000L, storage.getMaxReceive());
        assertEquals(750L, storage.getMaxExtract());
    }

    @Test void simulationAndInvalidAmountsNeverMutate() {
        AtomicInteger changes = new AtomicInteger();
        SimpleEnergyStorage storage = new SimpleEnergyStorage(100, 100, 100, changes::incrementAndGet);
        assertEquals(100, storage.receive(Long.MAX_VALUE, true));
        assertEquals(0, storage.getEnergy());
        assertEquals(0, storage.receive(-1, false));
        assertEquals(0, storage.extract(-1, false));
        assertEquals(0, changes.get());
    }

    @Test void clampsLoadedEnergyAndRejectsNegativeConfiguration() {
        SimpleEnergyStorage storage = new SimpleEnergyStorage(100, 100);
        storage.setEnergy(Long.MAX_VALUE);
        assertEquals(100, storage.getEnergy());
        storage.setEnergy(Long.MIN_VALUE);
        assertEquals(0, storage.getEnergy());
        assertThrows(IllegalArgumentException.class, () -> new SimpleEnergyStorage(-1, 0));
    }
}
