---
name: vegetation-tint
description: Biome-abhängige Gras-/Laubfärbung — tintType-Pipeline vom Block-JSON bis in den Mesher, 33×33-Eck-Grids mit bilinearer Interpolation, koplanare Grasblock-Overlays mit or-equal-Depth. Lesen vor Änderungen an Tints, Grasblock-Rendering, Biomfarben oder getönten Blättern.
---

# Vegetations-Tint (Biome-Färbung)

## Pipeline in drei Stufen

1. **Block-JSON** deklariert `"tint": "grass"|"foliage"` (+ optional `tint_faces`,
   `textures.overlay` für den Grasblock-Seitenrand). `Tints.byName` liefert die
   Platzhalter-Farbe (Fallback), `Tints.typeByName` den `BakedQuad.tintType`
   (TINT_GRASS/TINT_FOLIAGE). Festfarben wie `foliage_birch`/`foliage_spruce` bleiben
   TINT_NONE — biome-unabhängig wie in Vanilla. **Laub-JSONs brauchen `"tint": "foliage"`,
   sonst bleiben sie grau.**
2. **Generator** (`AlphaWorldGeneratorV2.buildTintGrids`): pro Chunk zwei 33×33-Eck-Grids
   (`chunk.grassTintCorners`/`foliageTintCorners`, Index `cx*33+cz`, 0xRRGGBB). Berechnung:
   Biomfarbe am groben 4-Block-Raster (UNgedithertes `sampleSmooth`-Klima — glatte Grenzen!) →
   3×3-Box-Glättung → bilinear auf die Block-Ecken. Die Werte sind pure Funktionswerte → an
   gemeinsamen Rändern chunk-übergreifend identisch, keine Nähte. `null` = Generator ohne
   Biome-Tints (V1) → Mesher fällt auf den gebackenen Platzhalter-Tint zurück.
3. **Mesher** (`ChunkMesher.biomeTint`): bei tintType ≠ NONE wird der Tint zur Mesh-Zeit ersetzt —
   bilinear zwischen den vier umliegenden Eckwerten. Einzeln emittierte Quads (Cross, Blätter,
   Overlay) sampeln am Blockzentrum (+0.5); **gemergte Greedy-Quads sampeln pro VERTEX** →
   glatter Farbverlauf über die große Fläche. Der Tint bleibt bewusst aus dem Greedy-
   Merge-Schlüssel heraus (Merging bleibt maximal, der Verlauf kommt aus der Interpolation).

## Grasblock-Overlay: koplanar, NICHT versetzt

Die Grasrand-Overlays über den Dirt-Seiten werden mit **identischen Vertices** wie die
OPAQUE-Basis-Seite emittiert (`BlockModels.overlaySides` nutzt direkt `FACE_VERTICES` —
koplanar, kein Offset). Ein früherer 1/64-Offset-Ansatz wurde bewusst durch die
Koplanar+or-equal-Lösung ersetzt — keinen Offset wieder einführen.
Identische Vertices in derselben Section ⇒ identische Tiefenwerte (GL-Invarianz); der CUTOUT-Pass
zeichnet mit „or-equal"-Depth-Func (Reversed-Z: GEQUAL), damit das Overlay exakt gewinnt.
Konsequenzen:
- Im Greedy-Pass wird eine Zelle mit Overlay **einzeln** emittiert (Basis-Face nicht mergen!) +
  Overlay in den CUTOUT-Layer (`GreedyFaces.overlays`).
- Wer die Emissions-/Vertex-Reihenfolge von Basis oder Overlay ändert, bricht die Invarianz →
  Z-Fighting. Wer die or-equal-Logik im ChunkRenderer entfernt, macht Overlays unsichtbar.

## LOD & Icons

- LOD: `LodMesher.tintFor` fragt die Datenquelle (`grassTintAt`/`foliageTintAt` = pures
  Biom-Sample, ohne 16-Block-Glättung — auf LOD-Distanz unsichtbar) am Quad-Zentrum.
- Item-Icons backen frisch aus den Modell-JSONs und wenden den festen Tint über
  `Block.applyTint` selbst an (kein Biome-Kontext im Inventar).

## Fallstricke

- Grids mit **gedithertem** Klima bauen → körnige Farbfläche statt glattem Verlauf
  (deshalb `sampleSmooth`).
- Falsche Interpolations-Indizierung: Grid ist `[cx * 33 + cz]`, 33 = SIZE+1 — wer 32 nimmt
  oder x/z vertauscht, bekommt diagonale Farbstreifen an Chunk-Rändern.
- Neue Biomfarbe nur in `Biome.grassTint/foliageTint` (Biomes.java) eintragen — `Tints.GRASS/
  FOLIAGE` sind nur noch Fallbacks (Icons, V1-Generator).

## Verifikation

Nur visuell: An eine Biomgrenze laufen (z.B. Plains↔Jungle) und prüfen, dass der Übergang über
~16 Blöcke weich verläuft — auf großen Grasflächen (Greedy-Quads!) UND auf Cross-Gras/Blättern.
Grasblock von der Seite: Rand gefärbt, kein Z-Fighting, auch schräg von unten. LOD-Distanz:
Farbverlauf setzt sich ohne harten Sprung am L0-Rand fort. `GeneratorMapExporter` (debug-maps/)
zeigt die Biome-Verteilung ohne Engine-Start.
