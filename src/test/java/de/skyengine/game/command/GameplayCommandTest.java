package de.skyengine.game.command;

import de.skyengine.core.i18n.I18n;
import de.skyengine.game.Gamemode;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.entity.SimpleItemStorage;
import de.skyengine.game.world.dimension.WorldgenRegistries;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class GameplayCommandTest {

    @BeforeAll
    static void language() {
        I18n.load("en_us");
    }

    @Test
    void playerCommandsUseTheCentralPlayerAccess() {
        FakePlayer player = new FakePlayer();
        CommandContext context = context(player, new FakeWorld());

        assertTrue(new GamemodeCommand().execute(context, List.of("spectator")).success());
        assertEquals(Gamemode.SPECTATOR, player.gamemode);
        assertTrue(new TeleportCommand().execute(context, List.of("1.5", "70", "-2")).success());
        assertEquals(1.5, player.position.x());
        assertEquals(70, player.position.y());
        assertEquals(-2, player.position.z());
        assertFalse(new TeleportCommand().execute(context, List.of("NaN", "2", "3")).success());
        assertTrue(new KillCommand().execute(context, List.of()).success());
        assertTrue(player.killed);
    }

    @Test
    void homeSpawnAndBiomeCommandsDelegateWithoutOwningWorldState() {
        FakePlayer player = new FakePlayer();
        FakeWorld world = new FakeWorld();
        CommandContext context = context(player, world);

        assertFalse(new HomeCommand().execute(context, List.of()).success());
        player.homeResult = CommandContext.HomeResult.TELEPORTED;
        assertTrue(new HomeCommand().execute(context, List.of()).success());
        assertTrue(new SetHomeCommand().execute(context, List.of()).success());
        assertTrue(new SetSpawnPointCommand().execute(context, List.of()).success());
        assertTrue(new BiomeCommand().execute(context, List.of("plains")).success());
        assertEquals("plains", world.locatedBiome);
        assertFalse(new BiomeCommand().execute(context, List.of("missing")).success());
    }

    private static CommandContext context(FakePlayer player, FakeWorld world) {
        return new CommandContext(new SimpleItemStorage(1), null, null, player, world);
    }

    private static final class FakePlayer implements CommandContext.PlayerAccess {
        private CommandContext.Position position = new CommandContext.Position(
                WorldgenRegistries.OVERWORLD, 0, 64, 0);
        private Gamemode gamemode = Gamemode.SURVIVAL;
        private CommandContext.HomeResult homeResult = CommandContext.HomeResult.NOT_SET;
        private boolean killed;

        @Override public CommandContext.Position position() { return this.position; }
        @Override public void kill() { this.killed = true; }
        @Override public Gamemode gamemode() { return this.gamemode; }
        @Override public void gamemode(Gamemode gamemode) { this.gamemode = gamemode; }
        @Override public boolean teleport(double x, double y, double z) {
            this.position = new CommandContext.Position(this.position.dimension(), x, y, z);
            return true;
        }
        @Override public CommandContext.Position setHome() { return this.position; }
        @Override public CommandContext.HomeResult home() { return this.homeResult; }
    }

    private static final class FakeWorld implements CommandContext.WorldAccess {
        private String locatedBiome;

        @Override public CommandContext.Position setSpawnPoint() {
            return new CommandContext.Position(WorldgenRegistries.OVERWORLD, 4, 65, 8);
        }
        @Override public List<String> biomeNames() { return List.of("desert", "plains"); }
        @Override public boolean locateBiome(String name) {
            this.locatedBiome = name;
            return true;
        }
    }
}
