package de.skyengine.game.world.block.entity;

/** Selected block-entity state that may travel with its block item. */
public interface PortableBlockEntity {
    void savePortable(DataTag tag);
    void loadPortable(DataTag tag);
}
