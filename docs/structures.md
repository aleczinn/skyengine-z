# Structure system

`.structure` means **Voxel Structure** and is the canonical, versioned structure format of
Voxel Stories. It is deliberately independent of Minecraft and WorldEdit. Files begin with
the `VSTR` magic, a format version and a compression marker; version 1 stores a gzip-compressed
NBT payload with a block-state palette and sparse cells.

The runtime pipeline is:

```text
Sponge .schem import ─┐
                      ├─> StructureTemplate -> StructureSerializer (.structure)
In-engine selection ──┘              │
                                     ├─> StructurePlacement (debug / Structure Block)
                                     └─> StructurePlacement (world generation / LOD)
```

Sponge `.schem` and legacy WorldEdit `.schematic` are import formats only. They are never required
by the game runtime or regular world generation. The standalone Gradle task `schematicConvert`
provides the offline content pipeline; there is deliberately no in-game `/schematics` command.

## Cell semantics

- A missing sparse cell, or an imported `minecraft:structure_void`, is `IGNORE`: the existing
  world is not modified.
- An explicit `skyengine:air` cell is `AIR`: the existing world block is removed.
- Every other cell is a block state transformed and placed according to the placement rule.

Natural features are imported/saved without air by default, preventing neighboring trees from
cutting holes into each other. Buildings can opt into explicit air using `air=include`.

## In-engine authoring

```text
/structure load trees/spruce/spruce_large_01
/structure save houses/test air=include overwrite=true anchor=player
/structure anchor
/structure anchor 120 64 -30
/structure anchor reset
/structure list 2

//wand
//rotate 90
//flip
//preview
//preview 120 64 -30 replace=keep
//preview clear
//paste
//paste 120 64 -30 replace=all
//undo
//redo 2
```

The command-only Debug Stick sets selection position 1 with left click and position 2 with right
click. It is intentionally absent from creative tabs and `/give`. `/structure load` only changes
the editor clipboard; it does not create a preview or place blocks. Rotation is limited to multiples
of 90 degrees because voxel block states and integer template cells cannot represent arbitrary
angles such as 40 degrees without resampling and invalid stair, door or log orientations. `//flip`
chooses its mirror plane from the player's horizontal look direction.

`//preview` renders transformed blocks as depth-tested translucent ghosts, outlines the complete
template and highlights its anchor separately. `//paste`, `//undo` and `//redo` use atomic placement
transactions. Undo history is per player and dimension, remains in memory only and is bounded by
both transaction count and changed-cell count.

Player-authored and imported files are shared by all saves and stored below
`%APPDATA%/.voxelstories/bin/structures/<path>.structure` (or
`~/.voxelstories/bin/structures` outside Windows). This visible directory is the only canonical
runtime source for debug placement and world generation. Bundled starter templates are installed
there once without overwriting existing files. The namespace remains embedded as an internal
format ID but is hidden by the file-based chat interface. Old namespaced arguments remain
compatible.

Offline conversion examples:

```text
./gradlew schematicConvert --args='convert "C:/schematics/oak.schem" --id=skyengine:trees/oak/oak_1'
./gradlew schematicConvert --args='convert "C:/schematics/old_oak.schematic" --id=skyengine:trees/oak/old_oak_1'
./gradlew schematicConvert --args='batch "C:/schematics/trees" --namespace=skyengine --prefix=trees'
```

The defaults are `air=ignore`, unknown Minecraft block states are errors and existing targets
require `--overwrite`. `--output=<folder>` can target a staging directory instead of the global
catalog.

Selection and anchor boxes are visible while the Debug Stick is held. World generation receives a
stable snapshot of `bin/structures` when a save is opened; external changes therefore affect debug
loading immediately and worldgen after re-entering a save.

Tree templates are selected through `%APPDATA%/.voxelstories/bin/worldgen/tree_templates.json`.
The versioned catalog maps stable tree types such as `oak`, `birch`, `spruce`, `acacia`, `jungle`,
`redwood` and `palm` to folders below `bin/structures/trees/` and assigns separate template and
procedural fallback weights. Missing or empty folders therefore degrade safely to the existing
procedural generator instead of suppressing trees.
`StructureAuthoringService`, `StructureSelection`, `StructureTemplateManager` and
`StructurePlacement` are intentionally GUI-independent so a later Structure Block can invoke
the same SAVE/LOAD/CORNER/DATA operations rather than duplicating command behavior.
