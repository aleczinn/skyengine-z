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

## RenderLayer & Sichtbarkeitsregeln

`layer` in der Block-JSON: opaque (Default) / cutout (Alpha-Test, Blätter, Cross) / translucent
(Blending, Glas — wird zuletzt und sortiert gerendert). `opaque`-Default folgt dem Layer;
`cull_same` cullt Faces zwischen zwei identischen Blöcken (Glas an Glas).

**Häufigste Verwirrung:** Ein Archetyp-Block (cube etc.) liest die `textures`-Map der Block-JSON
NICHT — nur der `JsonBlock`-Fallback tut das. Textur-Änderungen gehören in
`game/models/block/<id>.json`. Fehlt die Modell-Datei komplett, ist der Block **unsichtbar**
(Auto-Default findet nichts, Warnung „Modell fehlt" im Log).

## Icons & animierte Texturen

- `inventory_model`/`inventory_x/y` im Blockstate-Teil überschreibt das Icon-Modell (Zaun mit
  Armen, Mini-Tür); `icon_flat`/`icon_item` liefern flache Sprites. Der Icon-Pfad backt frisch aus
  den Modell-JSONs und muss Tints selbst anwenden (`Block.applyTint` ist deshalb public).
- Animierte Texturen laufen über `<textur>.png.mcmeta` (`SpriteAnimations`): animierte Layer werden
  beim TextureArray-Bau übersprungen und pro Frame per `replaceLayer` getauscht (kein Re-Mesh).
  Nach dem Initial-Upload werden **Mipmaps regeneriert** — sonst transparente Mips in der Ferne.

## Verifikation

- `./gradlew run` und Log prüfen: „N Modelle geladen", „N Blockstates geladen", Warnungen
  „Modell fehlt"/„Textur fehlt"/„Variante fehlt" sind konkrete Fehlerhinweise.
- Optik (UV-Rotation, Tint, Layer) ist nur visuell prüfbar — Block ins Startinventar legen
  (`GameContainer.fillStartInventory`), platzieren, aus mehreren Richtungen ansehen.
- Nach Textur-/Icon-Ergänzungen: Wird der Pfad VOR `ChunkRenderer.init()` registriert?
  (Suche nach dem `layerOf`-Aufruf im Bootstrap-Pfad.)
