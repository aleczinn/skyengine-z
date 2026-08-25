package de.skyengine.game.world;

import de.skyengine.game.Gamemode;
import de.skyengine.game.entity.EntityPlayer;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.entity.DataTag;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;
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
        boolean legacy = false;
        if (tag == null) {
            tag = PlayerIO.read(PlayerIO.legacyPlayerFile(root));
            legacy = tag != null;
            if (uuid == null && tag != null) uuid = uuidFrom(tag);
        }
        if (uuid == null) uuid = UUID.randomUUID();

        this.localPlayer = new EntityPlayer(uuid);
        this.localPlayerHasPosition = this.load(this.localPlayer, tag, level);
        this.players.put(uuid, this.localPlayer);
        level.localPlayerUuid = uuid.toString();
        level.formatVersion = 3;
        levelSaver.run();
        if (legacy) this.save(this.localPlayer);
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
        } else if (level.player != null) {
            LevelData.PlayerData old = level.player;
            player.setPosition(old.x, old.y, old.z);
            player.yaw = old.yaw;
            player.pitch = old.pitch;
            applyState(player, old.gamemode, old.flying,
                    old.health == null ? player.getHealth() : old.health,
                    old.foodLevel == null ? player.getFoodLevel() : old.foodLevel,
                    old.saturation == null ? player.getSaturation() : old.saturation);
            loadLegacyInventory(player, level);
            hasPosition = true;
        } else {
            loadLegacyInventory(player, level);
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

    private static void loadLegacyInventory(EntityPlayer player, LevelData level) {
        if (level.inventory == null) return;
        for (LevelData.ItemEntry entry : level.inventory) {
            if (entry.slot < 0 || entry.slot >= player.getInventory().size()) continue;
            Item item = Items.get(Identifier.of(entry.id));
            if (item == null) continue;
            ItemStack stack = new ItemStack(item, entry.count);
            stack.setDamage(entry.damage);
            player.getInventory().set(entry.slot, stack);
        }
    }

    private void save(EntityPlayer player) {
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
        DataTag inventory = new DataTag();
        player.getInventory().save(inventory);
        tag.putTag("inventory", inventory);
        PlayerIO.write(PlayerIO.playerFile(this.root, uuid), tag);
    }

    private static UUID uuidFrom(DataTag tag) {
        long most = tag.getLong("uuidMost", 0);
        long least = tag.getLong("uuidLeast", 0);
        return most == 0 && least == 0 ? null : new UUID(most, least);
    }

    private static UUID parseUuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
