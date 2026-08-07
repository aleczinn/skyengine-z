package de.skyengine.game.world.loot;

import de.skyengine.game.world.ExplosionDropCollector;
import de.skyengine.game.world.block.Blocks;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.BlockStateCodec;
import de.skyengine.game.world.item.Enchantments;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.Items;
import de.skyengine.test.BlocksTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class LootTablesTest {

    @BeforeAll
    static void bootstrap() {
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void grassStoneUndGlassBeachtenSilkTouch() {
        ItemStack pickaxe = new ItemStack(item("iron_pickaxe"), 1);
        assertDrop("grass_block", pickaxe, "dirt", 1);
        assertDrop("stone", pickaxe, "cobblestone", 1);
        assertTrue(drops("glass", pickaxe, LootContext.Cause.PLAYER, 0, 1).isEmpty());

        pickaxe.setEnchantment(Enchantments.SILK_TOUCH, 1);
        assertDrop("grass_block", pickaxe, "grass_block", 1);
        assertDrop("stone", pickaxe, "stone", 1);
        assertDrop("glass", pickaxe, "glass", 1);
    }

    @Test
    void erzUndDoppelSlabNutzenFunctionsUndBlockState() {
        ItemStack pickaxe = new ItemStack(item("iron_pickaxe"), 1);
        assertDrop("iron_ore", pickaxe, "raw_iron", 1);
        pickaxe.setEnchantment(Enchantments.SILK_TOUCH, 1);
        assertDrop("iron_ore", pickaxe, "iron_ore", 1);

        List<ItemStack> slab = drops("stone_slab[type=double]", ItemStack.EMPTY,
                LootContext.Cause.PLAYER, 0, 2);
        assertEquals(1, slab.size());
        assertEquals(2, slab.getFirst().getCount());
    }

    @Test
    void explosionHatStandardmaessigKeinenDecay() {
        int full = 0;
        int decayed = 0;
        for (int i = 0; i < 1000; i++) {
            long seed = i * 0x9E3779B97F4A7C15L;
            full += drops("dirt", ItemStack.EMPTY, LootContext.Cause.EXPLOSION, 0, seed).size();
            decayed += drops("dirt", ItemStack.EMPTY, LootContext.Cause.EXPLOSION, 4, seed).size();
        }
        assertEquals(1000, full);
        assertTrue(decayed > 180 && decayed < 320, "Decay außerhalb des erwarteten Bereichs: " + decayed);
    }

    @Test
    void explosionsCollectorErzeugtHoechstensSechzehnerStacks() {
        ExplosionDropCollector collector = new ExplosionDropCollector();
        Item dirt = item("dirt");
        for (int i = 0; i < 4096; i++) collector.accept(new ItemStack(dirt, 1), i, 0, 0);
        assertEquals(256, collector.entityCount());
    }

    @Test
    void verzauberungenUeberlebenPersistenzUndTrennenStacks() {
        ItemStack enchanted = new ItemStack(item("diamond_pickaxe"), 1);
        enchanted.setEnchantment(Enchantments.FORTUNE, 3);
        ItemStack loaded = ItemStack.load(enchanted.save());
        assertEquals(3, loaded.getEnchantmentLevel(Enchantments.FORTUNE));
        assertEquals(3, loaded.copy().getEnchantmentLevel(Enchantments.FORTUNE));
        assertFalse(loaded.canStackWith(new ItemStack(item("diamond_pickaxe"), 1)));
    }

    @Test
    void parserLehntUnbekannteKonstrukteStriktAb() {
        String json = """
                {"type":"skyengine:block","pools":[{"rolls":1,"entries":[
                  {"type":"skyengine:item","name":"skyengine:dirt","conditions":[
                    {"condition":"skyengine:nicht_vorhanden"}
                  ]}
                ]}]}
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LootTables.compileForTest(json));
        assertTrue(error.getMessage().contains("unbekannte Condition"));
    }

    @Test
    void gewichteteEntriesWerdenBeideErreicht() {
        String json = """
                {"type":"skyengine:block","pools":[{"rolls":1,"entries":[
                  {"type":"skyengine:item","name":"skyengine:dirt","weight":1},
                  {"type":"skyengine:item","name":"skyengine:stone","weight":3}
                ]}]}
                """;
        LootTable table = LootTables.compileForTest(json);
        BlockState state = BlockStateCodec.decode("skyengine:dirt");
        Random random = new Random(72);
        int[] counts = new int[2];
        for (int i = 0; i < 1000; i++) {
            LootContext context = new LootContext(null, 0, 0, 0, state, ItemStack.EMPTY,
                    LootContext.Cause.PLAYER, 0, random);
            table.generate(context, (stack, x, y, z) -> counts[stack.getItem() == item("dirt") ? 0 : 1]++);
        }
        assertTrue(counts[0] > 180 && counts[0] < 320);
        assertEquals(1000, counts[0] + counts[1]);
    }

    @Test
    void fehlendeTabelleFaelltAufBlockItemZurueck() {
        BlockState dirt = BlockStateCodec.decode("skyengine:dirt");
        List<ItemStack> result = new ArrayList<>();
        LootContext context = new LootContext(null, 0, 64, 0, dirt, ItemStack.EMPTY,
                LootContext.Cause.PLAYER, 0, new Random(1));
        LootTables.selfDropFallback(dirt.getBlock()).generate(context,
                (stack, x, y, z) -> result.add(stack));
        assertEquals(1, result.size());
        assertSame(item("dirt"), result.getFirst().getItem());
    }

    private static void assertDrop(String block, ItemStack tool, String item, int count) {
        List<ItemStack> drops = drops(block, tool, LootContext.Cause.PLAYER, 0, 42);
        assertEquals(1, drops.size(), block);
        assertSame(item(item), drops.getFirst().getItem(), block);
        assertEquals(count, drops.getFirst().getCount(), block);
    }

    private static List<ItemStack> drops(String encoded, ItemStack tool, LootContext.Cause cause,
                                         float radius, long seed) {
        String full = encoded.contains(":") ? encoded : "skyengine:" + encoded;
        BlockState state = BlockStateCodec.decode(full);
        assertNotNull(state, full);
        List<ItemStack> result = new ArrayList<>();
        LootContext context = new LootContext(null, 0, 64, 0, state, tool, cause, radius, new Random(seed));
        state.getBlock().appendDrops(context, (stack, x, y, z) -> result.add(stack));
        return result;
    }

    private static Item item(String path) {
        Item item = Items.get(Identifier.of("skyengine:" + path));
        assertNotNull(item, path);
        return item;
    }
}
