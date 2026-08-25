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

Sponge `.schem` is an import format only. It is never required by the game runtime or regular
world generation. The standalone Gradle task `schematicConvert` provides the offline content
pipeline; there is deliberately no in-game `/schematics` command.

## Cell semantics

- A missing sparse cell, or an imported `minecraft:structure_void`, is `IGNORE`: the existing
  world is not modified.
- An explicit `skyengine:air` cell is `AIR`: the existing world block is removed.
- Every other cell is a block state transformed and placed according to the placement rule.

Natural features are imported/saved without air by default, preventing neighboring trees from
cutting holes into each other. Buildings can opt into explicit air using `air=include`.

## In-engine authoring

```text
/structure pos1
/structure pos2
/structure anchor
/structure anchor 120 64 -30
/structure anchor reset
/structure save houses/test.structure
/structure save houses/test.structure air=include overwrite=true anchor=player
/structure load houses/test.structure
/structure paste rotation=90 mirror=front_back
/structure paste houses/test.structure 120 64 -30 rotation=270
/structure list 2
```

Player-authored and imported files are shared by all saves and stored below
`%APPDATA%/.voxelstories/bin/structures/<path>.structure` (or
`~/.voxelstories/bin/structures` outside Windows). This visible directory is the only canonical
runtime source for debug placement and world generation. Bundled starter templates are installed
there once without overwriting existing files. The namespace remains embedded as an internal
format ID but is hidden by the file-based chat interface. Old namespaced arguments remain
compatible.

Offline conversion examples:

```text
./gradlew schematicConvert --args="convert C:/schematics/oak.schem --id=skyengine:trees/oak/oak_1"
./gradlew schematicConvert --args="batch C:/schematics/trees --namespace=skyengine --prefix=trees"
```

The defaults are `air=ignore`, unknown Minecraft block states are errors and existing targets
require `--overwrite`. `--output=<folder>` can target a staging directory instead of the global
catalog.

The green debug box visualizes the current selection, yellow its effective anchor and violet the
last paste bounds. World generation receives a stable snapshot of `bin/structures` when a save is
opened; external changes therefore affect debug loading immediately and worldgen after re-entering
a save. Spruce templates below `trees/spruce/` are discovered dynamically; without one, spruce
generation falls back to its procedural tree shape.
`StructureAuthoringService`, `StructureSelection`, `StructureTemplateManager` and
`StructurePlacement` are intentionally GUI-independent so a later Structure Block can invoke
the same SAVE/LOAD/CORNER/DATA operations rather than duplicating command behavior.
