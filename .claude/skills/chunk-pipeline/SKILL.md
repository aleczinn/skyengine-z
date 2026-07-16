---
name: chunk-pipeline
description: Chunk-Lebenszyklus NEW→GENERATING→GENERATED→DECORATING→DECORATED→MESHING→READY, Nachbar-Gating, Worker-Prioritäten, Upload-Queues, Dirty-Masken und Locking. Lesen vor Änderungen an ChunkManager, ChunkStatus, World.setBlock, Remesh-Logik oder dem Feature-/Dekorations-Pass.
---

# Chunk-Pipeline

## Grunddaten (weichen von Minecraft ab!)

- Chunk = **32×512×32** Blöcke (`ChunkSection.SIZE = 32`, `SHIFT = 5`, `Chunk.HEIGHT = 512`,
  16 Sections à 32³). Wer `>> 4` oder `& 15` aus Minecraft-Gewohnheit schreibt, erzeugt subtile
  Koordinaten-Bugs — immer `ChunkSection.SHIFT`/`MASK` verwenden.
- Block-Speicher: `PalettedContainer` + `BitStorage` pro Section, lazy (leere Section = null).
  State-IDs sind `int` ohne 65536-Limit.

## Status-Lattice und Nachbar-Gating (`ChunkManager.update`, 1×/Tick)

`ChunkStatus`: NEW → GENERATING → GENERATED → DECORATING → DECORATED → MESHING → READY.
Checks laufen über `isAtLeast` (ordinal-basiert). Die Übergänge sind **nachbar-gegated**:

- GENERATED → DECORATING erst, wenn **alle 8 Nachbarn** (4 kardinal + 4 diagonal) mindestens
  GENERATED sind (Scheiben-Modell der Features braucht das 3×3-Umfeld).
- DECORATED → MESHING erst, wenn alle 8 Nachbarn mindestens DECORATED sind (Feature-Scheiben an
  Rändern + Fluid-Eckhöhen an Chunk-Ecken).

**Der Manager wartet, nie der Job.** `ChunkDecorator` hält bewusst keine ChunkManager/World-Referenz —
ein Dekorations-Job darf konstruktionsbedingt keine Nachbar-Generierung anstoßen (sonst
Kaskaden/Deadlocks im Worker-Pool). Diese Invariante bei neuen Feature-Typen NICHT aufweichen.

## Worker-Prioritäten (häufige Falle)

`PriorityBlockingQueue` mit `PrioTask(prio, seq)`:
PRIO_REMESH(0) < PRIO_LOD_CLIP(1) < PRIO_LOAD(2) < PRIO_LOD(3).
LOD_CLIP = LOD-Masken-Remeshes (Chunk sichtbar geworden / pendingUnload) — die überholen die
Lade-Queue bewusst, sonst steht beim Schnellflug die alte LOD-Geometrie sekundenlang über frisch
erschienenen L0-Chunks (Clip-Job hinter bis zu LOAD_QUEUE_LIMIT Lade-Jobs).
Zwei Fallen:
1. **`workers.execute(...)`, niemals `submit(...)`** — submit wrappt in ein nicht-vergleichbares
   FutureTask → ClassCastException in der Priority-Queue.
2. Der `seq`-Zähler ist der Tiebreaker: PriorityBlockingQueue ist nicht stabil, ohne seq würde die
   Blickrichtungs-Sortierung der Lade-Jobs verwürfelt.

**Ladereihenfolge — Sichtfeld in zwei Stufen:** Offsets im Kreis, Score =
`(imSichtkegel ? 0 : renderDistance+1) + dist`. Stufe 1 = `cos(Winkel zur Blickrichtung) >=
VIEW_CONE_COS` (cos 75° = FOV/2 **plus ~30° Rand**) ODER `dist <= NEAR_ALWAYS` (2 Chunks);
Stufe 2 = Rest. Der Bias ist größer als jede Stufe-1-Distanz → nichts hinter dem Spieler überholt
je einen sichtbaren Chunk. **Der Rand des Kegels ist tragend:** die Pipeline ist nachbar-gegated —
ein exakter Frustum-Schnitt würde genau die Gating-Nachbarn am Kegelrand nach hinten schieben und
die sichtbaren Chunks auf sie warten lassen. Neu sortiert nur bei Chunk-Wechsel oder >20° Drehung.

**`LOAD_QUEUE_LIMIT` (128 wartende Lade-Jobs) — ohne das wirkt die Sortierung nicht:** `PrioTask`
trägt nur `(prio, seq)`, die Reihenfolge **eingereihter** Jobs ist also eingefroren; der 64er-Deckel
gilt nur für die Generierung (Dekoration/Erst-Mesh waren ungedeckelt). Ohne Deckel staut sich beim
Start ein Rückstau von tausenden Jobs und ein 180°-Dreh sortiert faktisch nichts mehr um. Gezählt
werden die WARTENDEN Jobs (`pendingLoadTasks`, dekrementiert beim Job-START); `update()` bricht den
Offset-Durchlauf ab, sobald der Deckel erreicht ist — der Rest kommt im nächsten Tick, dann ggf.
neu bewertet.

**`initialLoadComplete` (Latch):** true, sobald die Lade-Pipeline einmal ihren **Fixpunkt** erreicht
hat — `loadSubmitsThisTick == 0` UND `pendingLoadTasks == 0`. Reset in `clearAllChunks()` (F8) und
`setRenderDistance`. Der `LodManager` submittet erst danach (s. Skill `lod-system`).
**Nicht „alle Chunks READY" als Kriterium nehmen** (real gebaut, LOD blieb für immer aus): die
äußersten Ringe des Lade-Kreises finden ihre Gating-Nachbarn außerhalb des Kreises nicht und bleiben
dauerhaft auf GENERATED/DECORATED stehen — „alle READY" tritt nie ein. Bewusst ein EINMALIGER Latch —
ein Dauer-Gate „solange etwas lädt" würde LOD im Betrieb permanent blockieren (an der Ladefront ist
immer etwas offen).

## Upload-Pfad (Worker → Render-Thread)

Mesh-Ergebnisse laufen als `MeshBatch` über zwei Queues, die der `ChunkRenderer` leert:
- `priorityUploadQueue` (Edit-/Fluid-Remeshes): **vollständig und zuerst** pro Frame.
- `uploadQueue` (Initial-Load): gedeckelt auf 8 Batches/Frame (Initial-Mesh reiht jede Section als
  eigenen Batch ein → Uploads verteilen sich über Frames).

READY heißt nur „Batches eingereiht". Wirklich sichtbar ist der Chunk erst, wenn der Renderer alle
16 Sections angewendet hat (`chunk.markSectionUploaded()` / `isFullyUploaded()`) — darauf wartet
die **LOD-Maske**, sonst reißt das LOD Löcher auf, bevor der echte Mesh da ist.

## Edits & Remeshing

**Lese-Grenze DECORATED:** `World.getBlock` und die Kollisionsabfragen behandeln Chunks unter
DECORATED wie ungeladen (AIR bzw. FULL_CUBE), und `processRemeshes` verlangt Nachbarn ≥
DECORATED — denn GENERATING/DECORATING-Chunks werden von Workern **lock-frei** beschrieben
(Generator/FeaturePlacer), und `PalettedContainer`/`BitStorage` sind nicht threadsicher
(torn reads). Diese Schwellen nie auf GENERATED absenken. Einzige bewusste Ausnahme:
`WorldLodDataSource` liest ab GENERATED lock-frei (transiente Fehler remeshen sich weg —
siehe lod-system-Skill); diese Ausnahme weder auf andere Leser übertragen noch dort „reparieren".

`World.setBlockRaw` schreibt nur in READY-Chunks (verhindert Races mit laufenden Mesh-Jobs), nimmt den
**Write-Lock** des Chunks; Mesh-Jobs nehmen Read-Locks auf alle 9 beteiligten Chunks
(`ChunkManager.lockRead`). Dirty-Markierung: eigene Section, vertikal angrenzende Section bei y an
der Section-Grenze, Nachbar-Chunks bei lx/lz an 0/31, **Diagonal-Chunks an Ecken** (Fluid-Eckhöhen!).
`processRemeshes()` läuft 1×/FRAME (aus `World.render`), nicht pro Tick — Edits erscheinen sofort.
Fehlen Nachbarn (Weltrand), wird die Dirty-Maske NICHT konsumiert (bleibt für später).

`remeshAll()` (z.B. AO-Toggle) setzt READY-Chunks auf **DECORATED zurück — nicht GENERATED**, sonst
würden Features doppelt platziert. Alte Meshes bleiben sichtbar, bis der Ersatz hochgeladen ist.

## Unload

Radius `renderDistance + 2`; nur NEW/GENERATED/DECORATED/READY werden entfernt — Chunks mit
laufenden Jobs (GENERATING/DECORATING/MESHING) bleiben, bis der Job fertig ist. Die GL-Meshes
entsorgt der Renderer selbst, wenn er den Chunk nicht mehr in der Map findet.

**LOD-Unload-Gate:** Sichtbare Chunks (`isFullyUploaded()` — bewusst nicht status==READY,
s. remeshAll) warten zusätzlich auf `lodManager.coversChunk(...)`: solange das hochgeladene
LOD-Mesh ihre Zelle noch clippt, bleiben sie mit `pendingUnload = true` in der Map (die
LOD-Maske zählt sie ab da als abwesend → Region remesht die Zelle un-geclippt, erst dann
Unload — sonst Pop-in-Loch). Zurück im Radius wird das Flag zurückgesetzt. Notventil: jenseits
`renderDistance + 6` wird bedingungslos entladen. Details: Skill `lod-system`, Mechanismus 3.

## Verifikation

- Neue Status-Übergänge: Wer setzt den Status, auf welchem Thread? (Worker setzen nur „ihr"
  Endstatus; `chunk.status` ist volatile.)
- Bei jedem neuen Worker-Job: `execute` mit `PrioTask`? Keine World/Manager-Rückgriffe aus dem Job?
- Bei Änderungen an setBlock/Meshing: Sind alle 9 Chunks gelockt? Werden Ecken diagonal dirty markiert?
- Läuft die Engine (`./gradlew run`): F8 lädt alle Chunks neu, Taste P friert Laden/Unload ein
  (Remeshes von Edits laufen weiter) — gut, um Pipeline-Zustände zu inspizieren. Sichtbare
  Löcher/falsche Faces an Chunk-Grenzen sind das typische Symptom verletzter Gating-Regeln.
