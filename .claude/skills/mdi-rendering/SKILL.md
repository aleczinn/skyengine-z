---
name: mdi-rendering
description: ChunkRenderer mit MultiDrawIndirect — VertexArena (deferred frees!), MappedRing-Frame-Slots mit Fences, geteilter Quad-EBO, Render-Pass-Reihenfolge, Translucent-Sortierung, GPU-Cull-Pfad (Two-Phase-Hi-Z-Occlusion, Default AUS). Lesen vor Änderungen an ChunkRenderer, VertexArena, SectionMesh, LodMesh, MappedRing, GpuCull oder der Draw-Reihenfolge.
---

# MDI-Rendering (ChunkRenderer & Co.)

## Warum diese Architektur

Ein Draw-Call pro Section×Layer skaliert nicht. Stattdessen: **5 `VertexArena`s** — pro
`RenderLayer` eine (Startgröße aus dem **(rd+6)-Kreis** × Bytes/Chunk-Erfahrungswert, Floors
96/64/8 MB — rd+6 ist Absicht: `pendingUnload`-Chunks halten ihre Meshes bis zum Notventil,
beim Flug hält die Arena also weit mehr als den Steady-State; real beobachtet wuchs OPAQUE bei
rd=16 sonst von 96 auf 324 MB) plus zwei dedizierte LOD-Arenen (LOD-OPAQUE: Startgröße aus
`LodMesher.estimateOpaqueArenaBytes`, plus `ensureCapacity`-Vorabvergrößerung bei
Settings-Wechsel; LOD-TRANSLUCENT 8 MB — 2 MB wuchsen bei Ozean im Ring sofort).
Die Vorab-Reservierung ist auf **768 MB je Arena gedeckelt** (`cappedArenaBytes`, Warnlog beim
Klemmen — bei rd=32 rechnete die Formel sonst ~1,13 GB je für OPAQUE/CUTOUT); `grow`/
`ensureCapacity` bleiben ungedeckelt, `createBuffer` wirft seit dem Deckel bei
GL_OUT_OF_MEMORY (Fail-Fast statt stiller Leer-Geometrie). Sections/Regionen mieten Bereiche
per **Best-Fit** (Größen-Index `freeBySize` parallel zur Offset-Free-List; beide nur über
addFree/removeFree mutieren, sonst divergieren sie — Regionen werden dabei NIE verschoben,
GpuCull-baseVertex hängt an stabilen Offsets).
**Jeder `grow` ist eine GPU-Vollkopie der ganzen Arena im Frame** —
das war der gemessene Flug-Ruckler (friert auf dem Single-Thread auch Input/Tick ein). Deshalb:
Startgrößen so, dass es unterhalb des Deckels im Normalbetrieb NIE wächst, Grow-Faktor 2
(statt 1,5) und eine
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
  `GameSettings.fog` + `lodEnabled`); der Offscreen-Framebuffer ist HDR (RGBA16F) und
  multisampelt nur im AA-Modus MSAA (`PostProcessingSettings`, s. graphics/post/).

## GPU-Cull-Pfad (GpuCull, Two-Phase-Hi-Z — Default AUS)

`GpuCull.ENABLED` (Default **AUS** — s. Kosten-Realität unten; Live-A/B über den
GuiDebugScreen, der CPU-Pfad bleibt vollständig erhalten): Frustum-Cull für Sections
OPAQUE/CUTOUT + Sicht-Gate + LOD-Opaque (**alle Level in EINEM Segment** — die früheren
per-Level-Segmente besuchten jeden LOD-Slot K-mal pro Phase und submitteten K×mirrorCount
Commands; nach dem Merge wird jeder Slot pro Phase genau einmal besucht)
läuft als Compute-Pass, der pro Descriptor-Slot Indirect-Commands in GPU-Puffer schreibt
(plain MDI mit **Null-Commands**, KEIN `glMultiDrawElementsIndirectCount` — dessen
Count-Lesung stallte die Submission 76–114 ms). Translucent, LOD-Wasser und Kleinvegetation
bleiben bewusst CPU (Sortierung/Ausdünnung braucht CPU-Reihenfolge).

**Single-Phase-Fallback:** ist kein Hi-Z möglich (AA-Modus MSAA = keine sample-bare
Depth-Textur, erster Frame ohne Szene-FBO), läuft EIN Durchlauf (u_Phase=2), der alle
Frustum-Sichtbaren zeichnet und die Vis-Bits fail-open setzt — Phase 2 samt Barrier/Draws
entfällt. Der Fallback ist zulässig, weil dabei GAR keine Occlusion stattfindet (die
Ein-Phasen-Falle unten betrifft nur Hi-Z-Culling).

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
- **Depth-Sichtbarkeit vor dem Pyramiden-Bau herstellen.** Historisch per Abbinden des
  Szene-FBO (das Depth-Attachment eines GEBUNDENEN FBO zu sampeln ist Feedback-UB — das war
  das Küsten-Flackern). Seit 2026-07-30 stattdessen **`glTextureBarrier()`** bei gebundenem
  FBO: der Compute rendert nicht und schreibt das Attachment nicht, die Feedback-Regel greift
  also nicht — gemessen −28 µs/Frame, Draw-Count-Telemetrie und Pixelvergleich unverändert.
  Was NICHT geht: die Ordnung ganz weglassen (als Sonde geprüft, bringt ohnehin ~0).
- **Null-Command-Hygiene:** JEDE Compute-Invocation beider Phasen schreibt Command ODER
  Null-Command in ihre Phasen-Range — sonst zeichnen stale Ring-Slot-Inhalte Geister.
- **Vis-Write nur aus ZULÄSSIGEN Invocations** — die per-Level-LOD-Dispatches besuchen jeden
  LOD-Slot 5×; ein level-fremder Write überschriebe das echte Verdikt.
- Snapshot-Copy + Count-Clear macht NUR `dispatchPhase1`; `dispatchPhase2` bindet denselben
  Slot (erneuter Snapshot zerrisse den noch ungelesenen Phase-1-Stand). Descriptor-Zahlen
  sind zwischen den Phasen eingefroren.
- Phase-2-Draws OHNE FrameProfiler-GPU-Queries (1 Query-Objekt pro Slot/Section — ein
  zweites Begin überschriebe die Phase-1-Messung).

**Kosten-Realität — GEMESSEN 2026-07-30 (RTX 4080, 2560×1440, rd16/lodMax128, feste Pose,
Messstand `CullBench`):** die frühere These „Fixkosten des GPU-Pfads" war zu grob. Die Kosten
liegen fast vollständig in der **Hi-Z-Occlusion**, nicht im Compute-Substrat:

| Konfiguration | frame | Δ zum CPU-Pfad |
|---|---|---|
| CPU-Cull | 1231 µs | – |
| GPU-Cull, nur Compute-Frustum (`FRUSTUM_ONLY`) | 1266 µs | **+35 µs** |
| GPU-Cull + Hi-Z (Two-Phase) | 1400 µs | **+169 µs** |

Der Hi-Z-Aufschlag steckt vollständig in den gemessenen Sektionen (`hiz` 59, `cull2` 19,
`solid2` 7 µs; die unvermessene GPU-Zeit wächst nur um ~31 µs) — es sind KEINE geheimen
Pipeline-Blasen. **Die entscheidende Zahl:** `solid`+`cut` (die Rasterarbeit) beträgt in ALLEN
drei Konfigurationen 156 µs. Die Occlusion spart also nichts, weil Early-Z verdeckte Fragmente
ohnehin verwirft. Und selbst im besten Fall ist der Nutzen durch diese 156 µs **gedeckelt**,
während Hi-Z 85 µs kostet — es müsste über 55 % der Rasterarbeit wegcullen, um sich zu tragen.
Deshalb **`FRUSTUM_ONLY` Default AN** (Hi-Z aus), im GuiDebugScreen getrennt schaltbar. Mit
Licht/Schatten steigen die Fragmentkosten und damit die Decke — dann neu messen.

**Vier falsifizierte Hebel dieser Runde — nicht wiederholen:**
1. **Command-Kompaktierung**: Sonde mit 8000 zusätzlichen toten Descriptoren ergab ~8,5 ns pro
   Null-Command. Die ~3.700 echten Null-Commands sind ~30 µs, nicht der Posten.
2. **Phase-2-Draws hinter den Entity-Pass** (damit die Barrier-Drain hinter echter Arbeit
   liegt): kostete 22 µs MEHR. BE-/Entity-Pass sind hier <10 µs — nichts zu verstecken.
3. **`textureGather` im Copy-Pass** statt 25–30 `texelFetch` je Thread: `hiz` blieb bei 59–60 µs.
   Der Pass ist nicht fetch-instruktionsgebunden.
4. **Copy + erste Reduce-Stufe verschmelzen** (Mips 0–4 in einem Dispatch, ein Dispatch und eine
   Barrier weniger): ~1 µs. Die Dispatch-ZAHL ist auf dieser Hardware nicht der Kostentreiber.

Was der Copy-Pass tatsächlich kostet (Sonde mit Stride 4, also 1/16 der Lesungen): `hiz`
59 → 36 µs. Die Depth-Lesungen sind also ~24 µs, die restlichen ~35 µs sind Fixkosten der
Pyramidenkette, die sich mit keinem der oben genannten Mittel senken ließen. Die Lesungen
selbst sind nicht reduzierbar: ein konservatives MIN braucht JEDEN Texel des Footprints —
eine Teilmenge liefert ein zu NAHES Minimum und cullt dann sichtbare Geometrie weg.

**Messlehre (teuer gelernt):** ohne FIXIERTE Spielerpose sind Läufe wertlos — der Spieler
fällt beim Laden, der Autosave schreibt die Landeposition zurück, und derselbe CPU-Pfad maß
in zwei Läufen 795 gegen 909 FPS. Ebenso muss man auf den fertigen LOD-Ring warten
(Chunks fertig ≠ LOD fertig: sonst misst man 375 statt 3346 Regionen). Beides erledigt
`CullBench` (`-Dskyengine.cullbench=<Weltordner>`, dazu `-Dskyengine.window=BxH`).
FPS-Vergleiche IMMER in Frame-Zeiten rechnen, nie in FPS-Differenzen.

**Verifikation:** Telemetrie „GpuCull-Draws" (DebugMode FULL) zeigt pro Segment
`Σmin..max (P1+P2)` — bei statischer Kamera muss die **SUMME** konstant sein (`Σn..n`);
die Phasen-Spannen alleine können Löcher verstecken (Runde-1-Lehre). Der Occlusion-Debug-Tint
(GuiDebugScreen) zeichnet Verdeckt-Verdikte rot statt zu cullen (Löcher rot ⇒ Verdikt-Bug,
Löcher leer ⇒ Mechanik-Bug).

## Upload-Budgets pro Frame

Priority-Queue (Edits) max. 24 Batches (weicher Deckel — Einzel-Edits bleiben sofort sichtbar,
Explosions-Wellen verteilen sich über wenige Frames; Überholen deckt die meshSeq-Prüfung ab);
Initial-Load max. 8 Batches; LOD max. 4 Ergebnisse.
LOD-Ergebnisse, die nicht mehr gewünscht sind, MÜSSEN verworfen werden (`lodManager.acceptResult`)
— sonst Arena-Leak beim Upload↔Unload-Race.

## Verifikation

- Nur visuell verifizierbar (`./gradlew run`): flackernde/falsche Geometrie deutet auf
  Fence-/Free-Fehler; komplett kaputte Dreiecke nach langem Spielen auf die EBO-Falle;
  FPS-Einbruch auf Arena-Mapping/SubData.
- Nach jeder Änderung an Arena/Ring/EBO: Wer erzeugt zuerst, wer löscht wann, welcher Frame
  schützt die Region? Den Fence-Pfad (`beginFrame`/`endFrame`/`collect`) im Diff nachvollziehen.
- Fenstertitel zeigt „Sections: sichtbar/total" — Frustum-Culling-Regressionen fallen dort auf.
