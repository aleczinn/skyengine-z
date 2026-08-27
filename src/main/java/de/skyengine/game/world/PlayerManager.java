package de.skyengine.game.world;

import de.skyengine.game.Gamemode;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.entity.DataTag;
import de.skyengine.game.world.dimension.WorldgenRegistries;
import de.skyengine.game.world.save.LevelData;
import de.skyengine.game.world.save.PlayerIO;
import de.skyengine.game.world.save.WorldSaves;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Weltweite Spieleridentitaeten und UUID-basierte Persistenz. */
public final class PlayerManager {

    private final WorldSaves.WorldSave save;
    private final File root;
    private final Map<UUID, EntityPlayer> players = new LinkedHashMap<>();
    private final EntityPlayer localPlayer;
    private final boolean localPlayerHasPosition;

    PlayerManager(WorldSaves.WorldSave save, File root) {
        this(save, root, () -> { });
    }

    PlayerManager(WorldSaves.WorldSave save, File root, Runnable levelSaver) {
        this.save = save;
        this.root = root;
        LevelData level = save.level();
        UUID uuid = parseUuid(level.localPlayerUuid);
        DataTag tag = uuid == null ? null : PlayerIO.read(PlayerIO.playerFile(root, uuid));
        if (uuid == null) uuid = UUID.randomUUID();

        this.localPlayer = new EntityPlayer(uuid);
        this.localPlayerHasPosition = this.load(this.localPlayer, tag, level);
        this.players.put(uuid, this.localPlayer);
        level.localPlayerUuid = uuid.toString();
        level.formatVersion = WorldSaves.CURRENT_FORMAT_VERSION;
        levelSaver.run();
    }

    public EntityPlayer localPlayer() {
        return this.localPlayer;
    }

    public boolean localPlayerHasPosition() {
        return this.localPlayerHasPosition;
    }

    public EntityPlayer get(UUID uuid) {
        return this.players.get(uuid);
    }

    public void saveAll() {
        for (EntityPlayer player : this.players.values()) this.save(player);
    }

    private boolean load(EntityPlayer player, DataTag tag, LevelData level) {
        Identifier dimension = WorldgenRegistries.OVERWORLD;
        boolean hasPosition = false;
        if (tag != null) {
            player.setPosition(tag.getDouble("x", 0.5), tag.getDouble("y", 80),
                    tag.getDouble("z", 0.5));
            player.yaw = (float) tag.getDouble("yaw", 0);
            player.pitch = (float) tag.getDouble("pitch", 0);
            applyState(player, tag.getString("gamemode", ""),
                    tag.getBoolean("flying", false),
                    (float) tag.getDouble("health", player.getHealth()),
                    tag.getInt("foodLevel", player.getFoodLevel()),
                    (float) tag.getDouble("saturation", player.getSaturation()));
            Identifier savedDimension = Identifier.of(tag.getString(
                    "dimension", WorldgenRegistries.OVERWORLD.toString()));
            if (WorldgenRegistries.DIMENSIONS.get(savedDimension) != null) dimension = savedDimension;
            DataTag inventory = tag.getTag("inventory");
            if (inventory != null) player.getInventory().load(inventory);
            player.setSelectedSlot(tag.getInt("selectedSlot", 0));
            hasPosition = true;
        } else {
            Identifier spawnDimension = Identifier.of(level.spawnDimension == null
                    ? WorldgenRegistries.OVERWORLD.toString() : level.spawnDimension);
            if (WorldgenRegistries.DIMENSIONS.get(spawnDimension) != null) dimension = spawnDimension;
        }
        DataTag home = tag == null ? null : tag.getTag("home");
        if (home != null) {
            Identifier homeDimension = Identifier.of(home.getString("dimension", ""));
            if (WorldgenRegistries.DIMENSIONS.get(homeDimension) != null) {
                try {
                    player.setHome(new PlayerLocation(homeDimension,
                            home.getDouble("x", 0), home.getDouble("y", 0), home.getDouble("z", 0),
                            (float) home.getDouble("yaw", 0), (float) home.getDouble("pitch", 0)));
                } catch (IllegalArgumentException ignored) { }
            }
        }
        player.setDimensionId(dimension);
        return hasPosition;
    }

    private static void applyState(EntityPlayer player, String gamemode, boolean flying,
                                   float health, int foodLevel, float saturation) {
        try {
            player.setGamemode(Gamemode.valueOf(gamemode));
        } catch (Exception ignored) { }
        player.setFlying(flying);
        player.setHealth(health);
        player.setFoodLevel(foodLevel);
        player.setSaturation(saturation);
    }

    public void save(EntityPlayer player) {
        DataTag tag = new DataTag();
        UUID uuid = player.getUuid();
        tag.putLong("uuidMost", uuid.getMostSignificantBits());
        tag.putLong("uuidLeast", uuid.getLeastSignificantBits());
        tag.putDouble("x", player.x);
        tag.putDouble("y", player.y);
        tag.putDouble("z", player.z);
        Identifier dimension = player.getDimensionId() == null
                ? WorldgenRegistries.OVERWORLD : player.getDimensionId();
        tag.putString("dimension", dimension.toString());
        tag.putDouble("yaw", player.yaw);
        tag.putDouble("pitch", player.pitch);
        tag.putString("gamemode", player.getGamemode().name());
        tag.putBoolean("flying", player.isFlying());
        tag.putDouble("health", player.getHealth());
        tag.putInt("foodLevel", player.getFoodLevel());
        tag.putDouble("saturation", player.getSaturation());
        tag.putInt("selectedSlot", player.getSelectedSlot());
        PlayerLocation home = player.getHome();
        if (home != null) {
            tag.putTag("home", new DataTag()
                    .putString("dimension", home.dimension().toString())
                    .putDouble("x", home.x()).putDouble("y", home.y()).putDouble("z", home.z())
                    .putDouble("yaw", home.yaw()).putDouble("pitch", home.pitch()));
        }
        DataTag inventory = new DataTag();
        player.getInventory().save(inventory);
        tag.putTag("inventory", inventory);
        PlayerIO.write(PlayerIO.playerFile(this.root, uuid), tag);
    }

    private static UUID parseUuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
