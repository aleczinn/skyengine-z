package de.skyengine.shared.player;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Produces a short, stable display name from an offline-session UUID. */
public final class OfflinePlayerName {
    private static final String[] ADJECTIVES = {
            "Amber", "Azure", "Brave", "Bright", "Calm", "Clever", "Copper", "Coral",
            "Cosmic", "Ruby", "Daring", "Green", "Frost", "Gentle", "Golden", "Happy",
            "Ivory", "Jolly", "Lucky", "Lunar", "Misty", "Nimble", "Nova", "Quiet",
            "Rapid", "Royal", "Silver", "Solar", "Swift", "Velvet", "Vivid", "Wild"
    };
    private static final String[] NOUNS = {
            "Badger", "Bear", "Bee", "Bison", "Cat", "Crab", "Crow", "Deer",
            "Dove", "Eagle", "Finch", "Fox", "Frog", "Gecko", "Hare", "Hawk",
            "Heron", "Koala", "Lynx", "Mole", "Mouse", "Otter", "Owl", "Panda",
            "Raven", "Robin", "Seal", "Shark", "Tiger", "Viper", "Wolf", "Wren"
    };

    private OfflinePlayerName() {}

    public static String fromUuid(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        long mixed = mix(uuid.getMostSignificantBits()
                ^ Long.rotateLeft(uuid.getLeastSignificantBits(), 23));
        String adjective = ADJECTIVES[(int) (mixed & (ADJECTIVES.length - 1))];
        String noun = NOUNS[(int) ((mixed >>> 5) & (NOUNS.length - 1))];
        int suffix = (int) Math.floorMod(mixed >>> 10, 10_000L);
        return adjective + noun + String.format(Locale.ROOT, "%04d", suffix);
    }

    private static long mix(long value) {
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        return value ^ value >>> 31;
    }
}
