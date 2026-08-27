package de.skyengine.game.world.item;

import de.skyengine.core.i18n.I18n;
import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.behavior.ExplosionBehavior;
import de.skyengine.game.world.block.registry.Registries;
import de.skyengine.graphics.gui.text.RichText;
import de.skyengine.graphics.gui.text.Span;
import de.skyengine.graphics.gui.text.TextColors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import de.skyengine.test.BlocksTestBootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class ItemTooltipTest {

    @BeforeAll
    static void loadLanguage() {
        I18n.load("en_us");
        BlocksTestBootstrap.ensureBootstrapped();
    }

    @Test
    void blockItemLoadsItsOptionalLocalizedDescription() {
        Block tnt = Registries.BLOCK.get(Identifier.of("voxelstories:tnt"));
        BlockItem item = new BlockItem(tnt);
        List<RichText> lines = new ArrayList<>();

        item.appendTooltip(new ItemStack(item, 1), new TooltipContext(null, null), lines);

        assertEquals(List.of("Can be ignited by fire or redstone. "
                + "Explodes after 80 ticks (4 seconds)."), visible(lines));
        assertSame(TextColors.GRAY, lines.getFirst().spans().getFirst().color());
        assertEquals("80 ticks", lines.getFirst().spans().get(1).text());
        assertSame(TextColors.parse("gold"), lines.getFirst().spans().get(1).color());
    }

    @Test
    void missingDescriptionDoesNotLeakItsTranslationKey() {
        Item item = new Item(Identifier.of("voxelstories:no_description"));
        List<RichText> lines = new ArrayList<>();

        item.appendTooltip(new ItemStack(item, 1), new TooltipContext(null, null), lines);

        assertEquals(List.of(), lines);
    }

    @Test
    void explosionTooltipUsesTheConfiguredFuse() {
        ExplosionBehavior behavior = new ExplosionBehavior(4.0F, 60);
        Map<String, String> variables = new LinkedHashMap<>();

        behavior.appendTooltipVariables(ItemStack.EMPTY, new TooltipContext(null, null), variables);

        assertEquals("60", variables.get("fuse_ticks"));
        assertEquals("3", variables.get("fuse_seconds"));
    }

    @Test
    void runtimeProviderIsEvaluatedAgainForChangedValues() {
        MutableTooltipItem item = new MutableTooltipItem();
        ItemStack stack = new ItemStack(item, 1);

        assertEquals(List.of("Energy: 10 / 100"), tooltipOf(item, stack));
        item.energy = 65;
        assertEquals(List.of("Energy: 65 / 100"), tooltipOf(item, stack));
    }

    @Test
    void namedTemplateReplacesKnownValuesAndLeavesUnknownOnesVisible() {
        assertEquals("Energy: 25 / %max_energy% ($5)", TooltipTemplate.resolve(
                "Energy: %energy% / %max_energy% (%price%)",
                Map.of("energy", "25", "price", "$5")));
    }

    private static List<String> tooltipOf(Item item, ItemStack stack) {
        List<RichText> lines = new ArrayList<>();
        item.appendTooltip(stack, new TooltipContext(null, null), lines);
        return visible(lines);
    }

    private static List<String> visible(List<RichText> lines) {
        return lines.stream().map(line -> {
            StringBuilder result = new StringBuilder();
            for (Span span : line.spans()) result.append(span.text());
            return result.toString();
        }).toList();
    }

    private static final class MutableTooltipItem extends Item {
        private int energy = 10;

        private MutableTooltipItem() {
            super(Identifier.of("voxelstories:energy_cube"));
        }

        @Override
        public void appendTooltip(ItemStack stack, TooltipContext context, List<RichText> lines) {
            lines.add(RichText.plain("Energy: " + this.energy + " / 100", TextColors.GRAY));
        }
    }
}
