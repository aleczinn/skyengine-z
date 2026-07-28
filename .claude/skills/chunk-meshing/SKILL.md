---
name: chunk-meshing
description: ChunkMesher — Greedy-Meshing-Pass, Ambient Occlusion (inkl. Flip und Shader-Clamp), gepacktes 20-Byte-Vertex-Format, Cull-Regeln, Nachbar-Sampling über Chunk-Grenzen. Lesen vor Änderungen an ChunkMesher, Vertex-Format, AO oder allem, was Quads emittiert.
---

# Chunk-Meshing (ChunkMesher)

## Architektur: zwei Pässe, ein ThreadLocal-Mesher

`ChunkMesher.mesh(chunk, section, north, south, west, east, diagonals)` läuft auf Chunk-Workern —
**reine Daten, kein GL**. Eine Instanz pro Worker-Thread (`ThreadLocal` im ChunkManager), alle
Puffer werden wiederverwendet; der Chunk-Kontext wird am Ende genullt (kein Leak).

- **Pass 1 (Greedy):** nur opake Full-Cube-Faces im OPAQUE-Layer. Slice-weise pro Face-Richtung;
  Merge-Schlüssel = `stateId << 2 | AO-Quantisierung` (+1, damit 0 = leer bleibt). Zellen mit
  **uneinheitlichem AO** (Kanten/Ecken) werden einzeln mit per-Vertex-AO emittiert — Greedy merged
  nur uniform helle Flächen. Texturen kacheln über UV > 1 (GL_REPEAT im TextureArray).
- **Pass 1.5 (Greedy-Wasser-Tops):** flach-stille Fluid-Quell-Tops (Meeresoberfläche) werden pro
  y-Slice greedy zu großen TRANSLUCENT-Quads gemergt (Kriterium `FluidGeometry.
  isMergeableFlatStillTop`); die Zellen landen in `mergedWaterTop`, damit `FluidGeometry.build`
  in Pass 2 ihr Top auslässt (Rest der Fluid-Geometrie bleibt dynamisch).
- **Pass 2 (klassisch):** alles andere — Fluids (dynamische `FluidGeometry`), Cross, Slabs, Stairs,
  Cubes mit nicht-greedy-fähigen Modellen, plus Seiten-Overlays nicht-greedy-fähiger Blöcke
  (Sicherheitsnetz — der Grasblock-Normalfall läuft in Pass 1 als Einzel-Emission,
  siehe vegetation-tint).

## Greedy-Fähigkeit ist streng (buildGreedyFaces)

Ein State ist nur greedy-fähig, wenn: opaker Full-Cube, OPAQUE-Layer, kein Fluid, kein
Random-Offset, Modell = **exakt 6 Full-Face-Quads** (eines je Face, Ecken-Koordinaten UND -UVs auf
0/1). Alles andere → `GreedyFaces.NONE` → Pass 2. Wer neue Modell-Features baut (z.B. gedrehte UVs
auf Würfeln), muss prüfen, ob der Greedy-Check sie korrekt ablehnt oder korrekt merged —
`uAlongT1[face]` entscheidet, ob u mit Breite oder Höhe des gemergten Quads skaliert
(erhält Spiegelung/Rotation des Mappings über die Periodizität).

## AO — die nicht offensichtlichen Details

1. **Minecraft-Schema** (`computeAo`): pro Ecke 2 Kanten-Nachbarn + Eck-Nachbar; 4 Stufen
   `0.4 + level*0.2`; beide Kanten opak → dunkelste Stufe, Ecke egal.
2. **Einheits-Face + Bilinear (Teilblöcke).** Die 4 AO-Werte werden immer für das volle
   1×1-Quadrat berechnet und danach **bilinear** auf die echten Quad-Ecken interpoliert. Ohne das
   bekäme ein Viertel-Face (oberes Treppen-Element) viermal denselben Wert und eine Slab-Seite den
   Verlauf der vollen Blockhöhe gestaucht. **Die Interpolation muss in Multiplikationsform
   `a*(1-u) + b*u` stehen, nicht als `lerp` (`a + (b-a)*t`)** — nur so ist das Ergebnis bei
   u,v ∈ {0,1} bit-exakt der Eckwert, und genau darauf beruht der `==`-Uniformitätsvergleich des
   Greedy-Passes. Ein ULP Abweichung schickt flächig alle Zellen in die Einzel-Emission: der
   Vertexcount explodiert, sichtbar ist nichts.
3. **Sample-Ebene hängt an „bündig"**, nicht am cullFace: liegt die Quad-Ebene auf der Blockgrenze
   (Toleranz `FLUSH_EPS`), wird der Layer VOR der Face gesampelt, sonst die Schicht des Blocks
   SELBST. Letzteres erzeugt das dunkle Band am hinteren Rand einer Treppenstufe und die
   abgedunkelte Slab-Oberseite — beides wie in Minecraft.
4. **Anisotropie-Flip** (`EMIT_FLIPPED`): Ist `ao[1]+ao[3] > ao[0]+ao[2]`, wird die Emissions-
   Reihenfolge rotiert, damit die Triangulierungs-Diagonale durch das hellere Eckpaar läuft —
   sonst kippt der Interpolations-Gradient sichtbar. Der Index-Buffer trianguliert IMMER
   0,1,2/2,3,0 über die emittierte Reihenfolge; geflippt wird die Reihenfolge, nicht der Index.
5. **Shader-Clamp gegen Funkel-Striche:** Kantenparallel gesehene Faces rastern als degenerierte
   Sliver-Dreiecke, deren Interpolation die AO-Farben ÜBER 1.0 extrapoliert → helle Striche auf
   Augenhöhe. Fix ist `clamp(v_color, 0.0, 1.0)` im Fragment-Shader des ChunkRenderers.
   **Nicht entfernen** — der Bug ist gelöst, war aber teuer zu finden.

AO bekommt jedes Quad mit achsenparalleler Richtung — maßgeblich ist `BakedQuad.face()`, **nicht**
`cullFace()`. `face` ist die geometrische Normalenrichtung und auch dann gesetzt, wenn das Quad
kein cullFace hat (Slab-Oberseite, Treppen-Trittfläche liegen bei y=0.5 im Blockinneren). Vergeben
wird sie in `BlockModels.box` aus dem tex/cull-Slot-Index — der ist nach `BoxElement.rotateY/
rotateX/mirrorY` weiterhin die echte Richtung. `NO_DIRECTION` (= −1) und damit AO-frei bleiben
Cross-Pflanzen und die nicht-planare Fluid-Geometrie. **Der deklarative Weg dorthin ist
`"ambientocclusion": false` im Modell-JSON** (`ModelLoader.stripDirection` entfernt die Richtung,
`cullFace` bleibt) — der Schalter für „dieser Block soll kein AO" liegt also in den Daten, nicht
hier im Mesher. Genutzt von Türen, Glasscheiben und Eisengittern, wie in Vanilla; Details im Skill
`block-modelle-und-texturen`. **`cullFace()` bleibt zuständig für Culling,
`buildGreedyFaces` und die Tint-Maske** — dort nicht auf `face()` umstellen: der `face < 0`-Check
in `buildGreedyFaces` ist der Filter „jedes Face eines greedy-fähigen Blocks braucht ein cullface",
sonst entstehen Löcher.

Das AO-Setting wird 1× pro mesh()-Aufruf gelesen (konsistent pro Section).

## Vertex-Format (20 Bytes — Grenzen kennen!)

5 Ints pro Vertex (`VERTEX_SIZE = 5`), entpackt im Vertex-Shader des ChunkRenderers:
```
int0: posX | posY << 16   (u16 fixed 6.10, POS_SCALE=1024, Bias +1 Block, section-lokal)
int1: posZ | u    << 16   (UV fixed 6.10, UV_SCALE=1024, Bias +1)
int2: v    | layer << 16  (layer = TextureArray-Layer)
int3: r | g<<8 | b<<16    (Farbe = Helligkeit × AO × Tint)
int4: reserviert für farbiges Licht (RGB8 + 8 Bit frei) — noch ungenutzt, der Vertex-Shader
      liest weiterhin nur ein uvec4 (int0..int3); Stride wächst automatisch mit VERTEX_SIZE
```
Konsequenzen: Positionen tragen nur ~−1..+62 Blöcke, UVs max. ~63 (deshalb Merge-Deckel im LOD;
Section-Greedy bleibt ≤ 32 durch die Section-Größe).

**Position: 6.10, nicht 8.8** — eine Section braucht nur −1..33, die übrigen Bits gehen in die
Nachkommastellen (1/1024 Block). Der Grund ist Modellgeometrie: MC-Modelle trennen koplanare Flächen
mit winzigen Offsets, und bei 1/256 war der kleinste darstellbare Versatz (1/16 px) selbst schon
sichtbar. Vorrechnen muss man diese Offsets im Modell-JSON nicht: `ModelElements.pxEdge` hebt beim
Laden jeden Wert, der auf eine Blockgrenze rundet ohne exakt darauf zu liegen, auf einen
Quantisierungsschritt an — Vanilla-Werte wie `0.001` funktionieren dadurch wörtlich. **Das LOD teilt diese Konstante NICHT** (`LodMesher.posScaleFor` führt eigene 256/64) —
dort zählt Reichweite statt Auflösung, deshalb packt es zusätzlich relativ zu `yBase`.
Die Skala steht **pro Draw** im Offset-SSBO (`.w`), der Shader hat sie nicht hartkodiert; wer
`POS_SCALE` ändert, muss nur die Java-Schreiber mitziehen (`ChunkRenderer.writeSegment`, `GpuCull`).
Sie muss eine **Zweierpotenz** bleiben: nur dann ist `raw * 2^-n` im Shader eine reine
Exponenten-Verschiebung und zwei Vertices mit gleichem Rohwert landen bitidentisch — worauf die
koplanaren Gras-Overlays angewiesen sind. Ein Quad = 4 Vertices (BakedQuad liefert 6 Modell-Vertices A,B,C,C,D,A —
der Mesher emittiert daraus die 4 eindeutigen Ecken via `UNIQUE_VERTS`), Triangulierung über den
**geteilten Index-Buffer** des Renderers — niemals eigene Indizes pro Section erfinden.

## Cull- und Sampling-Regeln

`shouldRenderFace`: Nachbar opaker Full-Cube → unsichtbar; Nachbar = derselbe Block und
`cullsSameBlock()` (Glas an Glas) → unsichtbar. `sample(x,y,z)` erlaubt x/z in −1..32 und löst
über die 4 Kardinal- + 4 **Diagonal**-Chunks auf — die Auflösung (inkl. Diagonalen-Reihenfolge
NW,NE,SW,SE, wie der ChunkManager sie liefert) liegt zentral in `chunk/NeighborSampler` und wird
von ChunkMesher UND FluidGeometry geteilt; Änderungen nur dort. Außerhalb geladener Chunks: Luft.

Cross-Blöcke mit `hasRandomOffset()` bekommen einen deterministischen XZ-Versatz aus `posSeed`
(entspricht Minecrafts `Mth.getSeed` mit y=0) — stabil über Chunk-Grenzen und Remeshes.

## Verifikation

- `./gradlew compileJava` reicht nie: Meshing-Fehler sind **nur visuell** erkennbar (Löcher,
  Z-Fighting, falsche UV-Kachelung auf großen Flächen, dunkle/flackernde Ecken).
- Nach AO-/Greedy-Änderungen gezielt prüfen: große ebene Flächen (Merge korrekt?), Kanten/Ecken
  von Klippen (per-Vertex-AO + Flip?), Blick flach über eine Ebene (Funkel-Striche?).
- Nach Format-Änderungen: Pack- und Unpack-Seite synchron ändern — es gibt zwei Schreiber
  (`ChunkMesher.putVertex`, `LodMesher.putVertex`) und einen Leser (Vertex-Shader-String in
  `ChunkRenderer`).
