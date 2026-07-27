---
name: block-modelle-und-texturen
description: Modell-Pipeline (ModelLoader mit parent/#ref, BlockStateModels variants/multipart, BakedQuad) und Textur-Layer-Vergabe (BlockTextures/TextureArray, layerOf-Falle, animierte Sprites). Lesen bevor Block-Modelle, Blockstate-JSONs, Texturen, Icons oder RenderLayer angefasst werden.
---

# Block-Modelle & Texturen

## Pipeline (eine Quelle der Wahrheit für Geometrie UND Kollision)

1. `ModelLoader.load` liest alle `game/models/**/*.json` (Minecraft-Stil: `parent`-Vererbung,
   `#ref`-Texturvariablen, `elements` mit from/to/faces). `bake(name, xDeg, yDeg)` liefert
   gebackene `BakedQuad[]` **und** Kollisions-/Outline-AABBs aus denselben Boxen — gecacht je
   (name, x, y). Rotation nur in 90°-Schritten.
2. `BlockStateModels.load` liest die **Block-JSONs erneut** (die variants/multipart-Sektion steckt
   in derselben Datei unter `game/blocks/`). `bake(block, state)` wählt per `variants`
   (State-Key `prop=wert,prop2=wert2`, alphabetisch sortiert, Enum-Werte lowercase) oder
   `multipart` (`when`-Bedingungen). **Ohne beides**: Auto-Default auf das gleichnamige Modell
   `block/<id>` ohne Rotation.
3. `BlockRegistry.bake()` ruft `block.bakeModel(state)` pro State — Ergebnis landet als
   `BakedQuad[]` am State (`state.getModel()`), Overlays separat (`state.getOverlay()`).

## Die layerOf-Falle (wichtigste Regel hier)

`BlockTextures.layerOf(path)` ist `computeIfAbsent`: **jeder Aufruf mit unbekanntem Pfad legt einen
neuen Layer an.** Das TextureArray wird EINMAL in `ChunkRenderer.init()` aus `getOrderedPaths()`
gebaut — jede Layer-Registrierung danach ergibt einen Index außerhalb des Arrays (unsichtbar/
falsche Textur, kein Crash). Konsequenzen:
- Alle Texturen (auch Item-Icons, Eimer, Crack-Stages) werden **zur Bake-/Bootstrap-Zeit**
  registriert — siehe `Items.bootstrap` und `Blocks.bootstrap` (destroy_stage_0..9).
- **Layer nie raten oder hartkodieren** — immer über den real gebackenen Block/`layerOf` beziehen.
- Tippfehler im Pfad fallen nicht sofort auf: es entsteht einfach ein leerer Layer.

## BakedQuad-Konventionen

6 Vertices pro Quad (A,B,C,C,D,A), 5 Floats (x,y,z,u,v) in lokalen 0..1-Koordinaten.
`cullFace`: 0=top, 1=bottom, 2=north(−z), 3=south(+z), 4=west(−x), 5=east(+x) oder `NO_CULL`
(immer sichtbar, Cross/Fluids). `NO_FACE` als Textur-Sentinel in `BlockModels.box` lässt ein Face
ganz weg (verdeckte Innenflächen). `tint` = multiplikatives 0xRRGGBB (WHITE = neutral);
`tintType` TINT_GRASS/TINT_FOLIAGE lässt den Mesher den Tint zur Mesh-Zeit durch die Biomfarbe
ersetzen (tint bleibt Fallback für Icons/Chunks ohne Grid).

Per-Face-`uv` in Modell-JSONs wird unterstützt (MC-Format `[u0,v0,u1,v1]`, Pixel 0..16) und
**rotiert bei rotateY mit**; ohne `uv` gilt der Fallback aus der Box-Ausdehnung.

**`uvlock` (Variante/`apply`, MC-Feld):** dreht die Geometrie, lässt die Textur aber
weltachsenfest — `ModelLoader.bake(name, x, y, uvlock)` wirft nach der Drehung die Face-UVs weg
(`BoxElement.withoutFaceUv`), sodass `BlockModels.box` sie aus der **gedrehten** Box neu ableitet.
Die Regel ist kein Geschmack, sondern steht so in Vanilla:
- **`uvlock: true`** bei Treppen und Zäunen (auch Mauern/Knöpfe/Zauntore) — sonst dreht sich die
  Maserung der Trittfläche mit der Blickrichtung mit.
- **nie** bei Säulen/Stämmen (`preset/pillar`), Türen, Panes, `carved_pumpkin`, Fackel — dort
  **soll** die Textur mitdrehen; das war der Grund für Commit `2be8820`.

Zwei Fallen: der Cache-Key ist `name|x|y|uvlock` (ohne das Flag gewinnt der erste Bake für beide
Varianten), und `uvlock` ohne Drehung ist ein bewusstes No-op. Explizite `uv`-Rechtecke gehen bei
uvlock verloren (Warnung „uvlock verwirft die expliziten Face-UVs"); im schrägen Element-Pfad
(`rotateQuads`, Fackel) wird `uvlock` bewusst ignoriert — für ein gekipptes Quad gibt es keine
achsenparallele Box.

## RenderLayer & Sichtbarkeitsregeln

`layer` in der Block-JSON: opaque (Default) / cutout (Alpha-Test, Blätter, Cross) / translucent
(Blending, Glas — wird zuletzt und sortiert gerendert). `opaque`-Default folgt dem Layer;
`cull_same` cullt Faces zwischen zwei identischen Blöcken (Glas an Glas).

**Wo Texturen hingehören (2026-07-26 umgebaut — frühere Fassungen dieses Abschnitts sagten das
Gegenteil):** Die `textures`-Map der **Block-JSON** ist die Texturquelle. Die Block-JSON nennt
mit `model` (bzw. `models` als Suffix→Rumpf-Map) nur noch den Geometrie-Rumpf; daraus baut
`ModelLoader.registerBlockModels` ein **virtuelles** Modell `block/<id><suffix>` mit
`parent = Rumpf` und den Block-Texturen. `game/models/` enthält deshalb nur noch Geometrie
(`elements`) plus die geteilten Rümpfe `block/block`, `block/cube_all`, `block/cube_bottom_top`,
`block/cross`.

Daraus folgen drei Regeln:
- Ein Block braucht **entweder** `model`/`models` **oder** eine gleichnamige Datei
  `models/block/<id>.json`. Fehlt beides, ist er **unsichtbar** (Warnung „Modell fehlt").
- Existiert eine Datei UND deklariert der Block `model`, **gewinnt die Datei** — Warnung
  „Modell-Datei ueberdeckt die Block-Definition", und Textur-Änderungen im Block wirken nicht.
- `registerBlockModels` läuft in `Blocks.bootstrap` zwingend NACH `ModelLoader.load` (das leert
  MODELS *und* CACHE) und VOR dem ersten `bake`.

Ausnahme: `tall_cross` (tall_grass, lilac) ist bewusst nicht migriert — `_bottom`/`_top`
brauchen zwei verschiedene Texturen, ein virtuelles Modell hat aber nur eine `textures`-Map.

**Vererbung:** Block- und Item-JSONs kennen `parent` (Deep-Merge, Arrays werden ersetzt) und
`${var}`-Platzhalter (eingebaut `${id}`/`${ns}`, dazu ein `vars`-Objekt; nur EINE Ebene).
Presets liegen in `blocks/preset/` bzw. `items/preset/` und werden nicht registriert.
**Falle:** ein Feld ins Preset zu ziehen, das nur ein Teil der Kinder hatte, ändert die
anderen still mit.

**Element-Rotation:** MC-Format `rotation: {origin, axis, angle, rescale}` wird unterstützt
(Wandfackel −22,5°). Solche Elemente laufen an `BoxElement` vorbei — die fertigen Quads werden
affin gedreht und verlieren `face` UND `cullFace`, landen also im selben Regime wie Cross-Quads.
`from`/`to` sind deshalb `float` und dürfen außerhalb 0..16 liegen.

## Icons & animierte Texturen

- `inventory_model`/`inventory_x/y` im Blockstate-Teil überschreibt das Icon-Modell (Zaun mit
  Armen, Mini-Tür); `icon_flat`/`icon_item` liefern flache Sprites. Der Icon-Pfad backt frisch aus
  den Modell-JSONs und muss Tints selbst anwenden (`Block.applyTint` ist deshalb public).
- Animierte Texturen laufen über `<textur>.png.mcmeta` (`SpriteAnimations`): animierte Layer werden
  beim TextureArray-Bau übersprungen und pro Frame per `replaceLayer` getauscht (kein Re-Mesh).
  Nach dem Initial-Upload werden **Mipmaps regeneriert** — sonst transparente Mips in der Ferne.

## Verifikation

- `./gradlew saveTest` genügt für alles rund ums Laden — bootstrappt die Registry ohne GL.
  Log prüfen: „N Modelle geladen", „N Modelle aus Block-Definitionen erzeugt", „N Blockstates
  geladen"; die Warnungen „Modell fehlt"/„Textur fehlt"/„Variante fehlt"/„Modell-Datei
  ueberdeckt" müssen **null** Treffer haben.
- Optik (UV-Rotation, Tint, Layer) ist nur visuell prüfbar — Block ins Startinventar legen
  (`GameContainer.fillStartInventory`), platzieren, aus mehreren Richtungen ansehen.
- Nach Textur-/Icon-Ergänzungen: Wird der Pfad VOR `ChunkRenderer.init()` registriert?
  (Suche nach dem `layerOf`-Aufruf im Bootstrap-Pfad.)
