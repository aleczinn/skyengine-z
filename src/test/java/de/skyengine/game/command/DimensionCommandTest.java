package de.skyengine.game.command;

import de.skyengine.core.i18n.I18n;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.entity.SimpleItemStorage;
import de.skyengine.game.world.dimension.WorldgenRegistries;
import de.skyengine.game.world.dimension.DimensionDefinition;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DimensionCommandTest {

    @BeforeAll
    static void bootstrap() {
        I18n.load("en_us");
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void queuesRegisteredTargetAndSuggestsItsId() {
        AtomicReference<Identifier> requested = new AtomicReference<>();
        CommandContext.DimensionAccess access = new CommandContext.DimensionAccess() {
            @Override public Identifier current() { return WorldgenRegistries.OVERWORLD; }
            @Override public List<Identifier> available() {
                return List.of(WorldgenRegistries.OVERWORLD, WorldgenRegistries.MINING);
            }
            @Override public boolean request(Identifier target) {
                requested.set(target);
                return true;
            }
        };
        CommandContext context = new CommandContext(new SimpleItemStorage(1), access);
        CommandDispatcher dispatcher = new CommandDispatcher();
        dispatcher.register(new DimensionCommand());

        CommandResult result = dispatcher.execute(context, "/dimension voxelstories:mining");

        assertTrue(result.success());
        assertTrue(result.message().contains("Mining Dimension"));
        assertEquals(WorldgenRegistries.MINING, requested.get());
        assertEquals(List.of("/dimension voxelstories:mining"),
                dispatcher.suggest(context, "/dimension voxelstories:mi"));
        assertEquals("Mining Portal", I18n.tr("block.voxelstories.mining_portal"));
        assertEquals("Mining Dimension", DimensionDefinition.displayName(WorldgenRegistries.MINING));
        assertEquals("Entering Mining Dimension...", I18n.tr("world.loading",
                DimensionDefinition.displayName(WorldgenRegistries.MINING)));

        I18n.load("de_de");
        assertEquals("Bergbauportal", I18n.tr("block.voxelstories.mining_portal"));
        assertEquals("Bergbau-Dimension", DimensionDefinition.displayName(WorldgenRegistries.MINING));
        assertEquals("Betrete Bergbau-Dimension...", I18n.tr("world.loading",
                DimensionDefinition.displayName(WorldgenRegistries.MINING)));
        I18n.load("en_us");
    }
}
