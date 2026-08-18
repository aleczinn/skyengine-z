# SkyEngine resource packs

SkyEngine resource packs work as an overlay above the built-in presentation assets. Packs may be
directories or `.zip` files in `%APPDATA%/.skyengine/resourcepacks` (on other systems:
`~/.skyengine/resourcepacks`). They are enabled under **Options > Resource Packs**.

The selected list is ordered from highest to lowest priority. Resolution happens per file: if the
top pack only contains a sand texture, an ore texture from the pack below it remains active. Any
file not supplied by a selected pack falls back to the built-in SkyEngine resource.

Applying the list reloads resources without restarting the game. If validation or loading fails,
the complete previous stack is restored and the error is shown in the resource-pack screen.

## Pack layout

`pack.json` must be located at the root of the directory or ZIP. Assets use a namespaced layout:

```text
my-pack/
|- pack.json
|- pack.png                         # optional pack artwork
`- assets/
   `- skyengine/
      |- textures/
      |  |- block/
      |  |- item/
      |  |- entity/
      |  |- gui/
      |  `- menu/
      |- models/block/
      |- blockstates/
      |- sounds/
      |  `- music/
      |- lang/
      `- fonts/
```

Current manifest format:

```json
{
  "pack": {
    "format": 1,
    "name": "My Resource Pack",
    "description": "Sharper ores and warmer sand"
  }
}
```

`name` and `description` are optional. Unknown or malformed formats are listed as invalid and
cannot be enabled.

## Supported resources

- PNG textures for blocks, block items/icons, entities, GUIs and menu artwork
- Minecraft-style block models and blockstates
- OGG sound effects and OGG/WAV music
- JSON language files; their keys are merged from low to high priority
- TTF fonts using the engine's names (`monocraft.ttf`, `monocraft-bold.ttf`, and optional styles)
- PNG animation strips with the existing `<texture>.png.mcmeta` metadata

Resource packs are intentionally visual/audio only. They cannot replace shaders, block behavior,
collision shapes, recipes, loot or other gameplay data. A replacement block model changes world,
held-item, dropped-item, icon and moving-piston rendering, while collision stays derived from the
built-in model.

The engine accepts block texture resolutions from 16x16 through 256x256 in powers of two. Mixed
resolutions are scaled with nearest-neighbor sampling to the largest active block texture size.
Animation strips may contain square frames vertically or in a frame grid.

## Replacing a texture

Use the same relative asset path as the built-in file. For example:

```text
assets/skyengine/textures/block/iron_ore.png
assets/skyengine/textures/block/sand.png
```

The `.png.mcmeta` sidecar, when present, follows the existing animation format. A higher pack can
replace the metadata independently from the image because every resource is resolved separately.

## Normal and material maps

For any block-atlas texture, add sidecars next to the color texture:

```text
iron_ore.png       # color/albedo
iron_ore_n.png     # optional tangent-space normal
iron_ore_s.png     # optional material channels
```

Normal maps use the OpenGL tangent convention (`+Y`, usually called the green-channel-up format).
The material map channels are:

| Channel | Meaning | 0 | 255 |
|---|---|---:|---:|
| R | Roughness | smooth | rough |
| G | Metallic | dielectric | metal |
| B | Emission | none | full |

The alpha channel is not authored data; SkyEngine uses it internally to distinguish a supplied map
from a fallback. Missing normal or material sidecars preserve the classic rendering for that layer.

## Replacing a block model

Models support `parent`, texture references, elements/faces, display transforms and the existing
SkyEngine blockstate variant/multipart format. Both legacy paths and namespaced IDs are accepted.

`assets/skyengine/models/block/iron_ore.json`:

```json
{
  "parent": "skyengine:block/cube_all",
  "textures": {
    "all": "skyengine:block/iron_ore"
  }
}
```

`assets/skyengine/blockstates/iron_ore.json`:

```json
{
  "variants": {
    "": { "model": "skyengine:block/iron_ore" }
  },
  "inventory_model": "skyengine:block/iron_ore"
}
```

A blockstate file replaces the visual blockstate document with the same name; model parents and
texture resources are still resolved independently through the full pack stack.

## Priority example

With this GUI order:

```text
1. Warm Sand        (contains only textures/block/sand.png)
2. Better Ores      (contains ore textures)
3. SkyEngine Default
```

the resulting sand comes from `Warm Sand`, ores come from `Better Ores`, and every other asset comes
from the built-in default. Moving either selected pack with **Up**/**Down** immediately changes which
file wins after pressing **Done**.
