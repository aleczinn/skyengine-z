---
name: chunk-meshing
description: ChunkMesher — Greedy-Meshing-Pass, Ambient Occlusion (inkl. Flip und Shader-Clamp), gepacktes 16-Byte-Vertex-Format, Cull-Regeln, Nachbar-Sampling über Chunk-Grenzen. Lesen vor Änderungen an ChunkMesher, Vertex-Format, AO oder allem, was Quads emittiert.
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

## AO — die drei nicht offensichtlichen Details

1. **Minecraft-Schema** (`computeAo`): pro Ecke 2 Kanten-Nachbarn + Eck-Nachbar im Layer VOR der
   Face; 4 Stufen `0.4 + level*0.2`; beide Kanten opak → dunkelste Stufe, Ecke egal.
2. **Anisotropie-Flip** (`EMIT_FLIPPED`): Ist `ao[1]+ao[3] > ao[0]+ao[2]`, wird die Emissions-
   Reihenfolge rotiert, damit die Triangulierungs-Diagonale durch das hellere Eckpaar läuft —
   sonst kippt der Interpolations-Gradient sichtbar. Der Index-Buffer trianguliert IMMER
   0,1,2/2,3,0 über die emittierte Reihenfolge; geflippt wird die Reihenfolge, nicht der Index.
3. **Shader-Clamp gegen Funkel-Striche:** Kantenparallel gesehene Faces rastern als degenerierte
   Sliver-Dreiecke, deren Interpolation die AO-Farben ÜBER 1.0 extrapoliert → helle Striche auf
   Augenhöhe. Fix ist `clamp(v_color, 0.0, 1.0)` im Fragment-Shader des ChunkRenderers.
   **Nicht entfernen** — der Bug ist gelöst, war aber teuer zu finden.

AO nur für Quads mit gesetztem `cullFace`; NO_CULL-Quads (Cross, Fluids) bleiben voll hell.
Das AO-Setting wird 1× pro mesh()-Aufruf gelesen (konsistent pro Section).

## Vertex-Format (16 Bytes — Grenzen kennen!)

4 Ints pro Vertex (`VERTEX_SIZE = 4`), entpackt im Vertex-Shader des ChunkRenderers:
```
int0: posX | posY << 16   (u16 fixed 8.8, POS_SCALE=256, Bias +1 Block, section-lokal)
int1: posZ | u    << 16   (UV fixed 6.10, UV_SCALE=1024, Bias +1)
int2: v    | layer << 16  (layer = TextureArray-Layer)
int3: r | g<<8 | b<<16    (Farbe = Helligkeit × AO × Tint)
```
Konsequenzen: Positionen tragen nur ~−1..+254 Blöcke (deshalb packt das LOD relativ zu `yBase`);
UVs tragen max. ~63 (deshalb Merge-Deckel im LOD; Section-Greedy bleibt ≤ 32 durch die
Section-Größe). Ein Quad = 4 Vertices (BakedQuad liefert 6 Modell-Vertices A,B,C,C,D,A —
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
