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

`PriorityBlockingQueue` mit `PrioTask(prio, seq)`: PRIO_REMESH(0) < PRIO_LOAD(1) < PRIO_LOD(2).
Zwei Fallen:
1. **`workers.execute(...)`, niemals `submit(...)`** — submit wrappt in ein nicht-vergleichbares
   FutureTask → ClassCastException in der Priority-Queue.
2. Der `seq`-Zähler ist der Tiebreaker: PriorityBlockingQueue ist nicht stabil, ohne seq würde die
   Blickrichtungs-Sortierung der Lade-Jobs verwürfelt.

Ladereihenfolge: Offsets im Kreis, Score = `1.5*dist − 0.5*(Blickrichtungs-Skalarprodukt)`;
neu sortiert nur bei Chunk-Wechsel oder >20° Drehung. Max. 64 Generierungs-Submits pro Tick.

## Upload-Pfad (Worker → Render-Thread)

Mesh-Ergebnisse laufen als `MeshBatch` über zwei Queues, die der `ChunkRenderer` leert:
- `priorityUploadQueue` (Edit-/Fluid-Remeshes): **vollständig und zuerst** pro Frame.
- `uploadQueue` (Initial-Load): gedeckelt auf 8 Batches/Frame (Initial-Mesh reiht jede Section als
  eigenen Batch ein → Uploads verteilen sich über Frames).

READY heißt nur „Batches eingereiht". Wirklich sichtbar ist der Chunk erst, wenn der Renderer alle
16 Sections angewendet hat (`chunk.markSectionUploaded()` / `isFullyUploaded()`) — darauf wartet
die **LOD-Maske**, sonst reißt das LOD Löcher auf, bevor der echte Mesh da ist.

## Edits & Remeshing

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

## Verifikation

- Neue Status-Übergänge: Wer setzt den Status, auf welchem Thread? (Worker setzen nur „ihr"
  Endstatus; `chunk.status` ist volatile.)
- Bei jedem neuen Worker-Job: `execute` mit `PrioTask`? Keine World/Manager-Rückgriffe aus dem Job?
- Bei Änderungen an setBlock/Meshing: Sind alle 9 Chunks gelockt? Werden Ecken diagonal dirty markiert?
- Läuft die Engine (`./gradlew run`): F8 lädt alle Chunks neu, Taste P friert Laden/Unload ein
  (Remeshes von Edits laufen weiter) — gut, um Pipeline-Zustände zu inspizieren. Sichtbare
  Löcher/falsche Faces an Chunk-Grenzen sind das typische Symptom verletzter Gating-Regeln.
