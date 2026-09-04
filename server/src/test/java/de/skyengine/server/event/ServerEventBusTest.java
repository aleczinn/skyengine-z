package de.skyengine.server.event;

import de.skyengine.server.player.PlayerIdentity;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerEventBusTest {
    @Test
    void registrationCanModifyAndCancelTickOwnedGameplayEvent() throws Exception {
        ServerEventBus bus = new ServerEventBus();
        AtomicInteger calls = new AtomicInteger();
        AutoCloseable registration = bus.register(ChatEvent.class, event -> {
            calls.incrementAndGet();
            event.message(event.message().toUpperCase(java.util.Locale.ROOT));
            event.cancel("filtered");
        });
        ChatEvent event = bus.post(new ChatEvent(new PlayerIdentity(UUID.randomUUID(), "Player"), "hello"));
        assertEquals("HELLO", event.message());
        assertTrue(event.cancelled());
        assertEquals("filtered", event.cancellationMessage());
        registration.close();
        bus.post(new ChatEvent(new PlayerIdentity(UUID.randomUUID(), "Player"), "again"));
        assertEquals(1, calls.get());
    }
}
