package de.skyengine.game.world.item;

import de.skyengine.game.world.block.Block;

/** Item-Repräsentation eines Blocks (zum Halten, Lagern und später Platzieren). */
public final class BlockItem extends Item {

    private final Block block;

    public BlockItem(Block block) {
        super(block.getIdentifier());
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
}
