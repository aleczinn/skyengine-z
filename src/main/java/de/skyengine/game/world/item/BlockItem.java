package de.skyengine.game.world.item;

import de.skyengine.game.world.block.Block;

import java.util.Map;

/** Item-Repräsentation eines Blocks (zum Halten, Lagern und später Platzieren). */
public final class BlockItem extends Item {

    private final Block block;

    public BlockItem(Block block) {
        super(block.getIdentifier(), block.getItemMaxStackSize());
        this.block = block;
    }

    public Block getBlock() {
        return block;
    }

    @Override
    public Block getPlacedBlock() {
        return block;
    }

    @Override
    public String translationKey() {
        return "block." + this.getId().namespace() + "." + this.getId().path();
    }

    @Override
    protected void appendTooltipVariables(ItemStack stack, TooltipContext context,
                                          Map<String, String> variables) {
        this.block.appendTooltipVariables(stack, context, variables);
    }
}
