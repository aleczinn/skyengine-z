package de.skyengine.client.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClientInputIntentBufferTest {
    @Test void flankeBleibtBisZumTickErhalten() {
        ClientInputIntentBuffer input = new ClientInputIntentBuffer();
        input.pressAttack();
        input.captureMovement(0, 0, false, false, false, false, false);
        assertTrue(input.takeAttackPress());
        assertFalse(input.takeAttackPress());
    }

    @Test void doppelsprungUndHotbarWerdenBestaetigt() {
        ClientInputIntentBuffer input = new ClientInputIntentBuffer();
        input.pressJump(1_000_000_000L);
        input.pressJump(1_200_000_000L);
        assertTrue(input.takeFlyToggle());
        assertFalse(input.takeFlyToggle());

        input.selectHotbarSlot(4, 9);
        assertEquals(4, input.visibleHotbarSlot(2));
        input.confirmHotbarSlot(8, 2);
        assertEquals(4, input.visibleHotbarSlot(2));
        input.confirmHotbarSlot(9, 4);
        assertEquals(4, input.visibleHotbarSlot(4));
    }
}
