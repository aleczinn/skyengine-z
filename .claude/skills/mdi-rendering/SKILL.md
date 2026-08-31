---
name: mdi-rendering
description: ChunkRenderer mit MultiDrawIndirect — VertexArena (deferred frees!), MappedRing-Frame-Slots mit Fences, geteilter Quad-EBO, CPU-Frustum-Hierarchie, Render-Pass-Reihenfolge und Translucent-Sortierung. Lesen vor Änderungen an ChunkRenderer, VertexArena, SectionMesh, MappedRing oder der Draw-Reihenfolge.
---

# MDI-Rendering (ChunkRenderer & Co.)

## Architektur

Ein Draw-Call pro Section×Layer skaliert nicht. Deshalb liegen die Section-Meshes in drei
`VertexArena`s, je eine pro `RenderLayer`. Sichtbare Sections werden auf der CPU über einen
Chunk-Spalten-Index gecullt und anschließend als Indirect-Commands plus kamerarelative Offsets
in persistente `MappedRing`s geschrieben. Pro Segment folgt ein
`glMultiDrawElementsIndirect`-Call. Voraussetzung sind GL 4.3 (MDI) und GL 4.4
(`BufferStorage`); `ChunkRenderer.init()` bricht ohne diese Fähigkeiten ab.

Der frühere optionale GPU-Frustum-/Hi-Z-Pfad wurde entfernt: gemessen waren Compute,
Synchronisation und Hi-Z-Pyramide teurer als die eingesparte Rasterarbeit. Es gibt nur noch den
einfacheren CPU-Frustum-Pfad; MDI und GPU-lokale Vertex-Arenen bleiben unverändert erhalten.

Die Arena-Startgröße basiert auf dem `(renderDistance + 6)`-Kreis und gemessenen Bytes pro
Chunk, mit Floors von 96/64/8 MB. Die Vorab-Reservierung ist auf 768 MB je Arena begrenzt.
`grow()` bleibt möglich und verdoppelt die Arena, ist aber teuer, weil dabei der komplette
GPU-Puffer kopiert wird. Regionen werden per Best-Fit vergeben und niemals verschoben.

## Lebenszyklus und Synchronisation

- Drei Frame-Slots schützen Command-/Offset-Ringe mit GL-Fences.
- `beginFrame()` wartet auf den drei Frames alten Slot und sammelt anschließend per
  `VertexArena.collect(completed)` freigegebene Regionen ein.
- Freigaben sind immer deferred: `mesh.dispose(arena, frameId)` darf Speicher nicht sofort
  wiederverwenden, solange die GPU noch aus dem alten Frame lesen kann.
- Die Vertex-Arena bleibt device-local. Uploads laufen über einen Orphaning-Staging-Buffer und
  `glCopyBufferSubData`; Mapping oder `glBufferSubData` der Arena verursachte auf NVIDIA einen
  deutlichen Leistungseinbruch.
- `beginFrame()` läuft nur in `renderSolid()`, `endFrame()` nur am Ende von
  `renderTranslucent()`. Beide Methoden müssen pro Frame paarweise aufgerufen werden.

## Geteilter Quad-EBO

Der EBO enthält `0,1,2 / 2,3,0` je Quad und wächst auf die größte beobachtete Quad-Anzahl.
Beim Wachstum zuerst den neuen Buffer erzeugen und erst danach den alten löschen. Andernfalls
kann OpenGL denselben Namen recyceln und `ensureVaoBindings()` übersieht die notwendige
Neubindung. Beim EBO-Upload darf kein VAO gebunden sein.

Das gepackte Chunk-Vertexformat besitzt zwei Attribute:

- Attribut 0: `uvec4` mit den ersten vier Ints.
- Attribut 1: Licht-Int bei Byte-Offset 16.

Beide verwenden `ChunkMesher.VERTEX_SIZE * 4` als Stride. Nach Arena- oder EBO-Wachstum bindet
`ensureVaoBindings()` alle Layer-VAOs neu.

## CPU-Frustum-Hierarchie

`cullColumns` gruppiert Section-Meshes nach Chunk-X/Z. Pro Frame wird zuerst die AABB einer
ganzen Spalte gegen das Frustum getestet; nur bei Schnitt folgen die einzelnen Section-AABBs.
`registerSectionMesh()` und `unregisterSectionMesh()` müssen diesen Parallelindex an jeder
Mutation synchron zu `meshes` pflegen. Aus `visible` werden zugleich die Teilmengen für
Translucent und Kleinvegetation gebildet.

## Render-Reihenfolge

`World.render`: `processRemeshes()` → `renderSolid()` (OPAQUE + CUTOUT) → BlockEntities →
Entities → `renderTranslucent()`. Wasser muss über Entities blenden.

- OPAQUE: Alpha-Cutoff 0,5.
- CUTOUT: gleicher Cutoff, aber die „or-equal“-Depth-Funktion, damit koplanare
  Gras-Seiten-Overlays gewinnen.
- Detailvegetation: eigenes CUTOUT-Segment mit deterministischer Distanz-Ausdünnung.
- TRANSLUCENT: Blending, Sections back-to-front; Quads innerhalb der Section werden
  budgetiert sortiert (maximal acht Sorts pro Frame).
- Fog- und Licht-Uniforms gelten für alle Segmente desselben Shaderprogramms.

## Upload-Budgets

Priority-Uploads (Edits) sind auf 24 Batches pro Frame gedeckelt, Initial-Uploads auf acht.
Die Mesh-Sequenzprüfung verhindert, dass ein älteres Ergebnis ein neueres überholt.

## Verifikation

- Fenstertitel: `Sections sichtbar/gesamt` zur Kontrolle der CPU-Frustum-Selektion.
- Flackernde oder falsche Geometrie deutet meist auf Fence-/Deferred-Free-Fehler.
- Kaputte Dreiecke nach Arena-/EBO-Wachstum deuten auf die Buffer-Namens-Recycling-Falle.
- Nach Änderungen an Arena, Ring oder EBO den vollständigen Besitz- und Fence-Lebenszyklus
  nachvollziehen und die Test-Suite ausführen.
