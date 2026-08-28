package de.skyengine.game.world.save;

import java.util.LinkedHashMap;
import java.util.Map;

/** Aktuelle Welt-Metadaten fuer {@code saves/<ordner>/level.json}. */
public final class LevelData {

    public String name;
    public int seed;
    public long created;
    public long lastPlayed;

    /** Version des Save-Layouts, getrennt von der Chunk-Payload-Version. */
    public Integer formatVersion;
    /** UUID des lokalen Singleplayer-Profils. */
    public String localPlayerUuid;

    public Map<String, DimensionData> dimensions = new LinkedHashMap<>();
    public Map<String, Long> lootRandomStates = new LinkedHashMap<>();
    public Boolean tntExplosionDropDecay;

    public String spawnDimension;
    public Integer spawnX;
    public Integer spawnY;
    public Integer spawnZ;
    public Float spawnYaw;
    public Float spawnPitch;

    public static final class DimensionData {
        public int seed;
        public String generator;
        public Integer generatorVersion;
        public Map<String, Long> lootRandomStates = new LinkedHashMap<>();
    }
}
