---
name: mdi-rendering
description: ChunkRenderer mit MultiDrawIndirect — VertexArena (deferred frees!), MappedRing-Frame-Slots mit Fences, geteilter Quad-EBO, Render-Pass-Reihenfolge, Translucent-Sortierung, GPU-Cull-Pfad (Two-Phase-Hi-Z-Occlusion, Default AN). Lesen vor Änderungen an ChunkRenderer, VertexArena, SectionMesh, LodMesh, MappedRing, GpuCull oder der Draw-Reihenfolge.
---

# MDI-Rendering (ChunkRenderer & Co.)

## Warum diese Architektur

Ein Draw-Call pro Section×Layer skaliert nicht. Stattdessen: **5 `VertexArena`s** — pro
`RenderLayer` eine (Startgröße aus dem **(rd+6)-Kreis** × Bytes/Chunk-Erfahrungswert, Floors
96/64/8 MB — rd+6 ist Absicht: `pendingUnload`-Chunks halten ihre Meshes bis zum Notventil,
beim Flug hält die Arena also weit mehr als den Steady-State; real beobachtet wuchs OPAQUE bei
rd=16 sonst von 96 auf 324 MB) plus zwei dedizierte LOD-Arenen (LOD-OPAQUE: Startgröße aus
`LodMesher.estimateOpaqueArenaBytes`, plus `ensureCapacity`-Vorabvergrößerung bei
Settings-Wechsel; LOD-TRANSLUCENT 2 MB). Sections/Regionen mieten darin Bereiche
(First-Fit-Free-List). **Jeder `grow` ist eine GPU-Vollkopie der ganzen Arena im Frame** —
das war der gemessene Flug-Ruckler (friert auf dem Single-Thread auch Input/Tick ein). Deshalb:
Startgrößen so, dass es im Normalbetrieb NIE wächst, Grow-Faktor 2 (statt 1,5) und eine
Debug-Logzeile pro Grow (jede davon heißt: Startgrößen passen nicht mehr). Pro Frame wird nur das
Indirect-Command-Array + ein Offset-SSBO gebaut → **ein `glMultiDrawElementsIndirect` pro
Segment**; die LOD-Draws sind EIGENE Segmente direkt nach dem jeweiligen Terrain-Segment
(gleicher Shader, aber baseVertex gilt nur im selben Vertex-Buffer → eigene Arena = eigener
Draw-Call). Braucht GL 4.3 (MDI) + 4.4 (BufferStorage) — `init()` wirft sonst.

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

**Zwei Vertex-Attribute, nicht eines:** Attribut 0 ist das `uvec4` (int0..int3) bei Offset 0,
Attribut 1 der Licht-Int bei Offset 16 (`glVertexAttribIPointer(1, 1, GL_UNSIGNED_INT, stride, 16)`).
Ein fünfkomponentiges Attribut gibt es nicht. Die Schleife läuft über **alle** Arena-Slots, LOD
eingeschlossen — wer einen Slot vergisst, bekommt dort undefiniertes Licht. Der Stride
(`VERTEX_SIZE * 4`) gilt für beide.

## Render-Pass-Reihenfolge (Vanilla-Konvention, nicht umsortieren)

`World.render`: `processRemeshes()` → `renderSolid` (OPAQUE + CUTOUT) → BlockEntities → Entities →
`renderTranslucent`. Wasser blendet ÜBER Entities — Entities nach dem Translucent-Pass wären
hinter Wasser unsichtbar. `renderSolid` und `renderTranslucent` teilen sich Frame-Slot und
visible-Listen desselben Frames (Cursor `cmdCursor`/`offCursor` verbindet die Segmente);
`beginFrame()` läuft in `renderSolid`, `endFrame()` NUR am Ende von `renderTranslucent` —
beide Methoden müssen daher immer paarweise pro Frame aufgerufen werden.

- OPAQUE + LOD-OPAQUE: AlphaCutoff 0.5 (Cutout-Discard funktioniert damit auch im LOD-Segment —
  darauf bauen die koplanaren LOD-Gras-Overlay-Wände). CUTOUT: gleicher Cutoff, aber
  **"or-equal"-Depth-Func** (Reversed-Z: GREATER→GEQUAL), damit koplanare Gras-Seiten-Overlays
  exakt gewinnen.
- TRANSLUCENT: Blending an, Cutoff 0.001; Sections back-to-front (Command-Reihenfolge = Zeichen-
  Reihenfolge im MDI), Quads innerhalb einer Section per `SectionMesh.sortTranslucent` —
  **budgetiert** (max. 8 Sorts/Frame, nahe Sections zuerst), sonst Upload-Spike bei Kamerabewegung
  über einem Ozean. Sortierte Daten wandern in frische Arena-Regionen → danach `ensureVaoBindings`.
  LOD-TRANSLUCENT (Wasser-Tops/Fluid-Wände) bewusst unsortiert.
- **Fog:** beide Pässe setzen `u_FogColor/u_FogStart/u_FogEnd` (`setFogUniforms`, hängt an
  `GameSettings.fog` + `lodEnabled`); der Offscreen-Framebuffer rendert mit MSAA
  (`GameSettings.msaaSamples`).

## GPU-Cull-Pfad (GpuCull, Two-Phase-Hi-Z — Default AN)

`GpuCull.ENABLED` (Default **AN**, Hotkey **K** = Live-A/B gegen den CPU-Pfad, der vollständig
erhalten bleibt): Frustum-Cull für Sections OPAQUE/CUTOUT + Sicht-Gate + LOD-Opaque L1–L5
läuft als Compute-Pass, der pro Descriptor-Slot Indirect-Commands in GPU-Puffer schreibt
(plain MDI mit **Null-Commands**, KEIN `glMultiDrawElementsIndirectCount` — dessen
Count-Lesung stallte die Submission 76–114 ms). Translucent, LOD-Wasser und Kleinvegetation
bleiben bewusst CPU (Sortierung/Ausdünnung braucht CPU-Reihenfolge).

**Two-Phase-Occlusion** (die EINZIGE flackerfreie Struktur — Ein-Phasen-Hi-Z hatte einen
bewiesenen Selbst-Feedback-Loop): Phase 1 zeichnet die Letzte-Frame-Sichtbaren (Vis-Bit
`(gen<<24)|1` pro Slot), daraus wird die Pyramide DESSELBEN Frames gebaut, Phase 2 testet
alle mit der AKTUELLEN Matrix und zeichnet Nachzügler sofort. **Das Vis-Bit entscheidet nur
die Phasen-Zuordnung, nie die Sichtbarkeit** — stale Bits kosten höchstens einen Frame
Phase-2-Umweg, nie ein Loch.

Die teuer gefundenen Regeln (NICHT „vereinfachen"):
- **Pyramide ist Pow2 bei ~Viertel-Auflösung** (Basis per Footprint-MIN direkt aus dem
  Szenen-Depth): Pow2 ⇒ alle Halbierungen exakt. Der frühere Odd-Size-Verlust der letzten
  Mip-Spalte/Zeile ließ Himmel aus der MIN-Reduktion fallen ⇒ falsche Culls an
  Silhouetten/Horizont (die LOD-Loch-Linien!).
- **Szene-FBO wird während des Pyramiden-Baus ABGEBUNDEN** — das Depth-Attachment eines
  GEBUNDENEN FBO zu sampeln ist Feedback-UB (war das Küsten-Flackern). Nie „den Unbind
  sparen".
- **Null-Command-Hygiene:** JEDE Compute-Invocation beider Phasen schreibt Command ODER
  Null-Command in ihre Phasen-Range — sonst zeichnen stale Ring-Slot-Inhalte Geister.
- **Vis-Write nur aus ZULÄSSIGEN Invocations** — die per-Level-LOD-Dispatches besuchen jeden
  LOD-Slot 5×; ein level-fremder Write überschriebe das echte Verdikt.
- Snapshot-Copy + Count-Clear macht NUR `dispatchPhase1`; `dispatchPhase2` bindet denselben
  Slot (erneuter Snapshot zerrisse den noch ungelesenen Phase-1-Stand). Descriptor-Zahlen
  sind zwischen den Phasen eingefroren.
- Phase-2-Draws OHNE FrameProfiler-GPU-Queries (1 Query-Objekt pro Slot/Section — ein
  zweites Begin überschriebe die Phase-1-Messung).

**Kosten-Realität (2026-07-19, RTX 4080):** der GPU-Pfad kostet **~0,15–0,2 ms Fixkosten
pro Frame, auflösungsunabhängig** (Sync-Struktur: Phase-1-Draws → Pyramide → Phase 2 +
zweiter Draw-Satz) — bei heutigem unbeleuchtetem Content ist er damit LANGSAMER als der
CPU-Pfad (z.B. 880→750 FPS bei 5120×1440). Default trotzdem AN (User-Entscheidung): der
Pfad wird ständig mitgetestet und zahlt sich aus, sobald Licht-Merge/Schatten-Pass die
Frames real verteuern. FPS-Vergleiche IMMER in Frame-Zeiten rechnen, nie in FPS-Differenzen.

**Verifikation:** Telemetrie „GpuCull-Draws" (DebugMode FULL) zeigt pro Segment
`Σmin..max (P1+P2)` — bei statischer Kamera muss die **SUMME** konstant sein (`Σn..n`);
die Phasen-Spannen alleine können Löcher verstecken (Runde-1-Lehre). Hotkey **J** zeichnet
Verdeckt-Verdikte rot statt zu cullen (Löcher rot ⇒ Verdikt-Bug, Löcher leer ⇒ Mechanik-Bug).

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
