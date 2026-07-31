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

**Eine `y`-Drehung ersetzt ein Vanilla-`_alt`-Modell nur, wenn die Textur unter dieser Drehung
symmetrisch ist.** Vanilla hat für Panes und Gitter je zwei Modelle pro Achse
(`template_bars_side`/`_side_alt`, `_cap`/`_cap_alt`) — geometrisch sind sie tatsächlich
deckungsgleich mit 180°, die **UVs** aber nicht: die Drehung nimmt sie mit, das `_alt`-Modell greift
dagegen auf die gespiegelte Texturhälfte. Bei `iron_bars.png` ist das sichtbar, weil Spalte 7 hell
und Spalte 8 dunkel ist: mit der Drehung lag am Blockmittelpunkt beidseitig die dunkle Spalte, wo MC
auf einer Seite die helle zeigt. `glass_pane.json` hatte die `_alt`-Modelle von Anfang an,
`iron_bars.json` nahm die Abkürzung — das war der Fehler. **Vor jeder solchen „das ist doch nur eine
Drehung"-Vereinfachung die Textur prüfen**, nicht die Geometrie.

## RenderLayer & Sichtbarkeitsregeln

`layer` in der Block-JSON: opaque (Default) / cutout (Alpha-Test, Blätter, Cross) / translucent
(Blending, Glas — wird zuletzt und sortiert gerendert). `opaque`-Default folgt dem Layer;
`cull_same` cullt Faces zwischen zwei identischen Blöcken (Glas an Glas).

**Der Layer gilt auch außerhalb des Chunk-Renderers.** Ein Block wird an vier Stellen gezeichnet,
und jede braucht ihn: `ChunkRenderer` (drei Passes), `ItemIconRenderer` (Icon), `HeldItemMeshes`
(Hand + 3rd Person + Inventar-Puppe) und `EntityRenderer` (Drops, fallende Blöcke, TNT). Die
letzten beiden hatten den Layer lange gar nicht und zeichneten alles mit einem festen Cutout bei
0,5 — transluzente Blöcke kamen dort **deckend** heraus, weil ihre Textur Alpha ≈ 0,7 hat und den
Test überlebt (`slime_block.png` 180/255, `ice.png` 190, `honey_block_side.png` 189). Der Fix ist
in beiden Renderern derselbe wie im Welt-Pass: `u_AlphaCutoff` als Uniform (0,5 Cutout / 0,001
transluzent) plus `glEnable(GL_BLEND)` um den Draw. **Wer einen neuen Renderer für Block-Geometrie
baut, muss das mitbringen** — der Shader schreibt das Alpha ohnehin korrekt, ohne Blending wertet
es nur niemand aus. Merke: Blend-Zustand des Aufrufers retten (`glIsEnabled`), nicht hart
abschalten; die Inventar-Puppe zeichnet mitten in der GUI, die Blending braucht.

`cull_same` gehört an **jeden** Block, dessen Modell Faces mit `cullface` an der Blockgrenze hat und
der neben seinesgleichen stehen darf — nicht nur an Vollwürfel. Es ist unser Gegenstück zu MCs
`Block.skipRendering` und derselbe Fehler ist hier schon **zweimal** passiert: bei `glass_pane` die
End-Flächen der Verbindungsarme (`glass_pane_side` → `north`, `_alt` → `south`), bei `iron_bars` die
Endkappe in `bars_side.json`. Beide liegen exakt in der Blockgrenze, beide Nachbarn haben dort eine
— sichtbar als koplanares Paar an jeder Naht, das es in MC nicht gibt.

Faustregel: Sobald ein Modell einen Arm zur Blockgrenze schickt und der Block sich mit seinesgleichen
verbindet, braucht er `cull_same`. Faces **ohne** `cullface` (Mittelpfosten, Kantenplatten) bleiben
davon unberührt — sie sollen ja stehen bleiben.

## `ambientocclusion` im Modell-JSON

MC-Feld, Default `true`, erbt über die `parent`-Kette (erstes Vorkommen gewinnt,
`ModelLoader.collectAmbientOcclusion`). Bei `false` läuft `stripDirection`: die Quads verlieren ihre
Richtung (`BakedQuad.face = NO_DIRECTION`), womit das AO-Gate im Mesher nicht mehr greift —
`cullFace` und alles andere bleiben erhalten. Das DTO-Feld ist bewusst `Boolean` und nicht `boolean`:
bei einem primitiven Typ liefert GSON für ein fehlendes Feld `false` und schaltet AO überall ab.

**Faustregel (deckt sich mit Vanilla):** Dünne Deko-Geometrie, die im Blockinneren liegt, bekommt
kein AO — Türen, Glasscheiben, Eisengitter. Grund ist der Nicht-bündig-Pfad der AO-Berechnung: er
samplet die Nachbarn der **eigenen** Zelle, also Boden, Sims und Mauer ringsum, und dunkelt die
Scheibe damit sichtbar ab. Vanilla setzt das Feld genau dort (`template_glass_pane_*`,
`template_bars_*`, `door_*` — in der Client-Jar nachprüfbar). Für ein Modell aus der `models`-Map
eines Blocks genügt das Feld im **Rumpf**: das virtuelle Modell erbt es über `parent` (so hängen
die Eisengitter an `bars_*`). Achtung, damit gilt es für ALLE Kinder des Rumpfs.

## Null-dicke Elemente (Ebenen statt Boxen)

`from == to` auf einer Achse ist erlaubt — `RawElement.from/to` sind `float[]`, es gibt keine
Normalisierung und keinen Degeneriert-Check. Damit baut man MC-Geometrie, die gar keine Box ist:
Eisengitter sind Ebenen in der Blockmitte, nicht 2 px dicke Balken (Glasscheiben dagegen **sind**
2-px-Boxen, auch in Vanilla — nicht verwechseln).

Zwei Regeln dabei:

1. **Nur die beiden nicht-degenerierten Faces deklarieren.** `BlockModels.box` überspringt
   ausschließlich Faces mit `NO_FACE`, prüft aber keine Fläche — eine deklarierte degenerierte Face
   landet als Nullflächen-Quad im Vertexbuffer und kostet stumm Speicher.
2. **Beide Seiten deklarieren** (`west` **und** `east`), denn GL-Backface-Culling ist global an
   (`SkyEngine.onRender`) und der ChunkRenderer schaltet es nie ab. Zwei koplanare, entgegengesetzt
   gewickelte Quads sind deshalb kein Z-Fighting-Risiko — es rastert immer nur eines. Dasselbe
   Muster nutzt `BlockModels.cross`.

**Epsilon-Offsets aus MC-Modellen dürfen unverändert übernommen werden.** Vanilla trennt koplanare
Flächen gern um 0,001 px (`template_bars_*`). Das sind 1/16000 Block und liegt weit unter der
Auflösung von `ChunkMesher.fixedPos` (1/1024 Block) — der Wert allein würde also auf die Blockgrenze
zurückfallen. **`ModelElements.pxEdge` fängt das ab:** ein Wert, der auf eine Blockgrenze rundet,
ohne exakt darauf zu liegen, wird um genau einen Quantisierungsschritt weggeschoben
(0.001 → 1/1024, 15.999 → 1023/1024); alles andere bleibt unangetastet. Angewandt wird das nur auf
`from`/`to` der Elemente (`ModelLoader.toBox`), nicht auf die Rotations-Origin. Muster:
`bars_post_ends.json`, `bars_side.json`. Selbst vorrechnen muss man den Offset also nicht mehr —
aber er muss **im JSON stehen**: koplanar geht auch mit Anhebung nicht (siehe nächster Absatz).

**Koplanar an einer Blockgrenze ist nie in Ordnung** — auch nicht im CUTOUT-Pass. Der läuft zwar
nach Opaque mit or-equal-Depth, aber `GEQUAL` löst nur **bit-identische** Tiefen auf. Die
Grasblock-Overlays funktionieren, weil Basis-Face und Overlay aus derselben Greedy-Zelle mit
denselben Eckpunkten stammen (der Mesher emittiert das Basis-Face dafür bewusst ungemergt). Ein
2×2-px-Quad auf einer gemergten 16×16-Blöcke-Fläche hat dagegen völlig andere Dreiecke: die Tiefe
wird pro Pixel anders gerundet, das Vorzeichen des Fehlers wechselt über die Fläche → wandernde
Sprenkel. Gleiche Ebene ≠ gleiche Tiefe.

Für zwei Quads **desselben** Modells gilt das nicht: gleiche Textur, gleiches UV, gleiche
Flächenhelligkeit heißt pixelidentisch, da ist nichts zu sehen (z. B. die Platten zweier gestapelter
Gitter). Der Versatz ist also nur gegen **fremde** Nachbargeometrie nötig.

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
