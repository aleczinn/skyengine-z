---
name: mdi-rendering
description: ChunkRenderer mit MultiDrawIndirect — VertexArena (deferred frees!), MappedRing-Frame-Slots mit Fences, geteilter Quad-EBO, Render-Pass-Reihenfolge, Translucent-Sortierung. Lesen vor Änderungen an ChunkRenderer, VertexArena, SectionMesh, LodMesh, MappedRing oder der Draw-Reihenfolge.
---

# MDI-Rendering (ChunkRenderer & Co.)

## Warum diese Architektur

Ein Draw-Call pro Section×Layer skaliert nicht. Stattdessen: pro `RenderLayer` (OPAQUE 96 MB /
CUTOUT 8 MB / TRANSLUCENT 8 MB) EINE `VertexArena`, in der Sections Regionen mieten
(First-Fit-Free-List). Pro Frame wird nur das Indirect-Command-Array + ein Offset-SSBO gebaut →
**ein `glMultiDrawElementsIndirect` pro Layer**. LOD-Draws hängen sich als weitere Commands ans
OPAQUE-Segment (gleiche Arena, gleicher Shader). Braucht GL 4.3 (MDI) + 4.4 (BufferStorage) —
`init()` wirft sonst.

## Lebenszyklus & Synchronisation (die gefährlichen Teile)

- **3 Frame-Slots** (Command-/Offset-`MappedRing`) mit GL-Fences: `beginFrame()` wartet auf den
  3 Frames alten Fence, `endFrame()` setzt den neuen. Erst nach dem Fence-Signal sammelt
  `VertexArena.collect(completed)` freigegebene Regionen ein.
- **Frees sind IMMER deferred:** `mesh.dispose(arena, frameId)` taggt die Region mit der aktuellen
  Frame-Nummer; zurück in die Free-List geht sie erst, wenn dieser Frame GPU-seitig durch ist.
  Wer eine Region sofort freigibt/wiederverwendet, produziert Geometrie-Müll, der nur sporadisch
  sichtbar ist (GPU liest noch).
- Die Arena ist bewusst **device-local, nicht gemappt, kein glBufferSubData** — beides bewegt den
  Buffer im NVIDIA-Treiber ins Host-RAM (gemessen bis ~4× FPS-Einbruch). Uploads laufen über einen
  Orphaning-Staging-Buffer + `glCopyBufferSubData`. Dieses Muster nicht „vereinfachen" —
  ein Staging-Ring-Experiment wurde bereits falsifiziert und revertiert.
- Die NVIDIA-Debug-Meldung **0x20072** einmal pro Arena-Buffer-Erzeugung ist bekannt und harmlos;
  sie wird bewusst nicht gefiltert.

## Geteilter Quad-EBO — die Namens-Recycling-Falle

Der EBO (0,1,2/2,3,0 je Quad) wächst auf die größte je gesehene Quad-Zahl (`maxSeenQuads`).
Beim Neubau: **neuen Buffer erzeugen, BEVOR der alte gelöscht wird** — solange der alte Name lebt,
ist der neue garantiert verschieden. Sonst recycelt der Treiber den Namen, `ensureVaoBindings`
hält die Bindung für aktuell und die VAOs zeigen aufs alte, zu kleine EBO-Objekt (Garbage-Indizes).
Gleiches Muster in `VertexArena.grow`. Beim EBO-Upload darf **kein VAO gebunden** sein.
Nach Arena-Wachstum oder EBO-Neubau bindet `ensureVaoBindings()` neu (vergleicht gecachte Namen).

## Render-Pass-Reihenfolge (Vanilla-Konvention, nicht umsortieren)

`World.render`: `processRemeshes()` → `renderSolid` (OPAQUE + CUTOUT) → BlockEntities → Entities →
`renderTranslucent`. Wasser blendet ÜBER Entities — Entities nach dem Translucent-Pass wären
hinter Wasser unsichtbar. `renderSolid` und `renderTranslucent` teilen sich Frame-Slot und
visible-Listen desselben Frames (Cursor `cmdCursor`/`offCursor` verbindet die Segmente);
`beginFrame()` läuft in `renderSolid`, `endFrame()` NUR am Ende von `renderTranslucent` —
beide Methoden müssen daher immer paarweise pro Frame aufgerufen werden.

- OPAQUE: AlphaCutoff 0.5. CUTOUT: gleicher Cutoff, aber **"or-equal"-Depth-Func**
  (Reversed-Z: GREATER→GEQUAL), damit koplanare Gras-Seiten-Overlays exakt gewinnen.
- TRANSLUCENT: Blending an, Cutoff 0.001; Sections back-to-front (Command-Reihenfolge = Zeichen-
  Reihenfolge im MDI), Quads innerhalb einer Section per `SectionMesh.sortTranslucent` —
  **budgetiert** (max. 8 Sorts/Frame, nahe Sections zuerst), sonst Upload-Spike bei Kamerabewegung
  über einem Ozean. Sortierte Daten wandern in frische Arena-Regionen → danach `ensureVaoBindings`.

## Upload-Budgets pro Frame

Priority-Queue (Edits) komplett; Initial-Load max. 8 Batches; LOD max. 4 Ergebnisse.
LOD-Ergebnisse, die nicht mehr gewünscht sind, MÜSSEN verworfen werden (`lodManager.acceptResult`)
— sonst Arena-Leak beim Upload↔Unload-Race.

## Verifikation

- Nur visuell verifizierbar (`./gradlew run`): flackernde/falsche Geometrie deutet auf
  Fence-/Free-Fehler; komplett kaputte Dreiecke nach langem Spielen auf die EBO-Falle;
  FPS-Einbruch auf Arena-Mapping/SubData.
- Nach jeder Änderung an Arena/Ring/EBO: Wer erzeugt zuerst, wer löscht wann, welcher Frame
  schützt die Region? Den Fence-Pfad (`beginFrame`/`endFrame`/`collect`) im Diff nachvollziehen.
- Fenstertitel zeigt „Sections: sichtbar/total" — Frustum-Culling-Regressionen fallen dort auf.
