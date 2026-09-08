package de.skyengine.game.command;

import java.util.List;

/** Canonical gameplay command registry shared by local UI tooling and server authority. */
public final class GameplayCommands {
    private GameplayCommands() { }

    public static CommandDispatcher createDispatcher() {
        CommandDispatcher dispatcher = new CommandDispatcher();
        dispatcher.register(new GiveCommand());
        dispatcher.register(new DimensionCommand());
        dispatcher.register(new KillCommand());
        dispatcher.register(new GamemodeCommand());
        dispatcher.register(new TeleportCommand());
        dispatcher.register(new SetSpawnPointCommand());
        dispatcher.register(new SetHomeCommand());
        dispatcher.register(new HomeCommand());
        dispatcher.register(new BiomeCommand());
        dispatcher.register(new StructureCommand());
        for (String name : List.of("wand", "pos1", "pos2", "hpos1", "hpos2", "copy", "cut",
                "set", "replace", "expand", "contract", "stack", "move", "regen",
                "rotate", "flip", "preview", "paste", "undo", "redo")) {
            dispatcher.register(new WorldEditCommand(name));
        }
        return dispatcher;
    }
}
