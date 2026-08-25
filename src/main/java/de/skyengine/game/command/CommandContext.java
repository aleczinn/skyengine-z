package de.skyengine.game.command;

import de.skyengine.game.world.block.entity.SimpleItemStorage;
import de.skyengine.game.world.block.Identifier;

import java.util.List;

/** Die fuer Singleplayer-Befehle verfuegbare Spielumgebung. */
public record CommandContext(SimpleItemStorage inventory, DimensionAccess dimensions) {

    public CommandContext(SimpleItemStorage inventory) {
        this(inventory, null);
    }

    public interface DimensionAccess {
        Identifier current();
        List<Identifier> available();
        boolean request(Identifier target);
    }
}
