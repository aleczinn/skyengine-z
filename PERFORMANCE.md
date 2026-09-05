# SkyEngine Performance Guide

Diese Datei beschreibt die aktuelle Performance-Architektur, die reproduzierbaren
Messverfahren und die bekannten Baselines. Sie soll verhindern, dass Optimierungen nur
anhand einzelner FPS-Werte oder nicht vergleichbarer Läufe bewertet werden.

## Grundregeln für belastbare Messungen

- Vor und nach einer Änderung denselben Commit-Zustand, Seed, dieselben Einstellungen und
  dieselbe JVM-Konfiguration verwenden.
- CPU-Meshing, Streaming/Upload und GPU-Rendering getrennt messen. FPS allein kann nicht
  zeigen, welcher Teil schneller oder langsamer geworden ist.
- Für Mesher-Vergleiche zuerst uninstrumentierte `BASELINE`-Läufe verwenden. `DETAIL`,
  `OPERATIONS` und der Ingame-Profiler verändern den gemessenen Pfad und dienen nur der
  Diagnose.
- Mindestens fünf getrennte JVM-Prozesse vergleichen und den Median der Prozess-Mediane
  verwenden. Einzelne Läufe können durch JIT, Betriebssystem und Hintergrundlast ausreißen.
- Während einer Messreihe keine anderen CPU-/GPU-intensiven Programme starten. Energieprofil,
  Taktraten, Fenstergröße, VSync und Grafikoptionen konstant halten.
- Eine Optimierung nur übernehmen, wenn Korrektheit erhalten bleibt und der Gewinn größer als
  die normale Streuung ist.

## Aktuelle L0-Mesh-Architektur

Eine Chunk-Section umfasst `32 x 32 x 32` Blöcke. Der Mesher trennt drei wesentliche Pfade:

1. Achsenparallele Full-Cubes werden über vorberechnete primitive State-Tabellen
   klassifiziert. Row-/Word-Masken leiten die sechs Sichtbarkeitsrichtungen ab, bevor das
   Greedy Meshing Material-, State- und exakte Shading-Kompatibilität prüft.
2. Wasser besitzt einen eigenen Greedy-Pfad.
3. Spezialmodelle, nicht kompakt darstellbare Geometrie und sonstige Fallbacks verwenden das
   generische Vertexformat.

Der Full-Cube-Pfad verwendet Vertex Pulling und ein kompaktes Quadformat:

| Variante | Geometrie | Shading | Gesamt pro Quad | Verwendung |
|---|---:|---:|---:|---|
| Standard | 8 Byte | 0 Byte | 8 Byte | Shading vollständig aus Standardwerten ableitbar |
| Uniform | 8 Byte | 4 Byte | 12 Byte | identisches Sky-/RGB-Licht und AO über das Quad |
| Corner | 8 Byte | 16 Byte | 24 Byte | vier getrennte Corner-Shading-Werte |
| Legacy | 4 x 20 Byte | enthalten | 80 Byte | generischer Modell-/Fallback-Pfad |

Die 8-Byte-Geometrie enthält lokale Position, Achse, Seite, Breite/Höhe, UV-Transformation,
Diagonale, eine 16-Bit-Material-ID, Tintindex und Flags. Uniform- und Corner-Shading liegen in
getrennten Streams, sodass einfache Quads keine unbenutzte Shading-Nutzlast tragen.

Materialien werden über eine zentrale Materialtabelle auf Textur-, PBR-, Alpha- und
Tintinformationen abgebildet. Grass-Seiten verwenden ein allgemeines Composite-Material:
Basistextur und biomegetöntes Overlay werden in einem Fragment kombiniert. Dadurch werden
keine zwei koplanaren Legacy-Quads mehr benötigt. Der Biome-Tint wird räumlich aus dem
`33 x 33`-Tintgrid rekonstruiert und ist nicht Bestandteil des Greedy-Merge-Keys.

Die Ausgabe wird nach Renderklasse und Shadingklasse in GPU-Arenen hochgeladen. Compact-Quads
werden per Vertex Pulling gelesen; der Renderer bündelt sichtbare Section-Geometrie über seine
Indirect-/Batch-Strukturen. Das aktuelle Dokument beschreibt ausschließlich L0. Ein Fern-LOD
ist in diesem Stand nicht Teil der Baseline.

## Verifikation

Voraussetzung ist die im Build konfigurierte Java-25-Toolchain.

```powershell
.\gradlew check
```

`check` führt neben den Unit-Tests auch `saveTest`, `lightTest` und `meshTest` aus. Der
deterministische Mesher-Zensus kann separat gestartet werden:

```powershell
.\gradlew meshTest
```

Aktuelle erwartete Referenz:

```text
MESH b100fe64b6cb9abd
```

Der Hash umfasst Legacy- und Compact-Streams einer deterministisch generierten und
beleuchteten `3 x 3`-Chunk-Fixture mit Seed `123`. Er darf nur zusammen mit einer bewusst
geprüften Meshänderung angepasst werden. Bei absichtlich anderer, aber semantisch gleicher
Geometrie müssen zusätzlich Differentialtests und eine visuelle Prüfung erfolgen.

## L0-Meshing-Benchmark

Der Benchmark ist GL-frei und misst den Produktionsmesher ohne Worldgen und Lighting im
Messfenster. Standardkonfiguration:

```powershell
.\gradlew meshBench -PmeshBenchMode=BASELINE -PmeshBenchWarmups=10 -PmeshBenchIterations=30 -PmeshBenchLabel=baseline
```

Das Ergebnis wird nach
`build/reports/meshing/mesh-benchmark-baseline.json` geschrieben. Der Lauf enthält:

- Generated Terrain mit AO
- Generated Terrain ohne AO
- Full-Cube Best Case
- Mixed Models

Verfügbare Modi:

| Modus | Zweck |
|---|---|
| `BASELINE` | uninstrumentierte, verbindliche Laufzeitmessung |
| `DETAIL` | isolierte Phasentimer zur Hotspot-Diagnose |
| `OPERATIONS` | Operations- und Algorithmuszähler |
| `ALL` | Baseline plus beide Diagnoserunden |

Diagnosebeispiele:

```powershell
.\gradlew meshBench -PmeshBenchMode=DETAIL -PmeshBenchDetailIterations=16 -PmeshBenchFullCubeSampleStride=64 -PmeshBenchLabel=detail
.\gradlew meshBench -PmeshBenchMode=OPERATIONS -PmeshBenchLabel=operations
.\gradlew meshBenchJfr
```

Der JFR-Lauf schreibt:

- `build/reports/meshing/mesh-benchmark-jfr.json`
- `build/reports/meshing/mesh-benchmark.jfr`

Die Detailphasen sind diagnostisch. Ihr zusätzlicher Timer-Overhead kann die absolute
Full-Cube-Zeit sichtbar verzerren; ihre Prozentwerte dürfen daher nicht anstelle der
uninstrumentierten Baseline verwendet werden.

## A/B-Vergleiche

Der normale Spielpfad verwendet `ROW_MASK` und `COMPOSITE`. Referenzpfade existieren nur für
Tests und Benchmarks.

Grass-Composite gegen den alten Overlay-Fallback:

```powershell
.\gradlew meshBench -PmeshBenchMode=BASELINE -PmeshBenchOverlayPath=COMPOSITE -PmeshBenchLabel=composite
.\gradlew meshBench -PmeshBenchMode=BASELINE -PmeshBenchOverlayPath=LEGACY_REFERENCE -PmeshBenchLabel=legacy-overlay
```

Row-Mask gegen den alten skalaren Visibility-Pfad:

```powershell
.\gradlew meshBench -PmeshBenchMode=BASELINE -PmeshBenchVisibilityPath=ROW_MASK -PmeshBenchLabel=row-mask
.\gradlew meshBench -PmeshBenchMode=BASELINE -PmeshBenchVisibilityPath=SCALAR_REFERENCE -PmeshBenchLabel=scalar-visibility
```

Jede Variante sollte in einem eigenen Gradle-/JVM-Prozess mehrfach ausgeführt werden. Für
einen Vergleich mindestens dokumentieren:

- Section Median, p95 und Maximum
- Full-Cube Median, p95 und Maximum
- `PREPARE_AND_HALO`
- Quads und Meshbytes pro nichtleerer Section
- Allocation pro Section sowie GC-Anzahl/-Zeit
- vollständige Gradle-Parameter und Commit-ID

## Ingame-Profiling

In einer Welt schaltet `F3+P` den sitzungsbezogenen Performance-Profiler ein und aus. Das
normale Debug-Overlay wird dabei automatisch eingeblendet. Der deaktivierte Worker-Pfad prüft
nur ein Flag und liest keine Uhr.

Der Profiler unterscheidet unter anderem:

- CPU: Culling, Command Build, Submission, Upload, Sortierung, Entities, Partikel,
  Post-Processing, GUI, Swap und gesamter Frame
- GPU: L0 Opaque, Cutout und Translucent, Entities, Partikel, Resolve, Post-Processing, GUI
  und Frame Span
- Worker: Queue Wait, Disk Load, Terrain, Features, Initial Lighting, Light Updates,
  Initial Mesh, Remesh, Mesher-Unterphasen, Upload Wait und Upload
- Meshzähler: Full-Cube-Faces, Compact-Quads, 8/12/24-Byte-Verteilung,
  Merge-Rejections, Legacy-Quads/-Bytes und Grass-Composite-Quads/-Bytes

GPU-Zeiten werden mit OpenGL-Time-Elapsed-Queries aufgenommen. Deshalb GPU- und CPU-Werte
nicht addieren: CPU und GPU arbeiten überlappend. Der Profiler selbst verursacht zusätzliche
Arbeit und dient nicht zur Ermittlung der maximalen FPS.

### Empfohlener Runtime-Test

1. Spiel mit fester Fenstergröße starten, beispielsweise:

   ```powershell
   .\gradlew run -Dskyengine.window=1920x1080
   ```

2. Dieselbe Welt, Position, Blickrichtung, Renderdistanz und Grafikoptionen verwenden.
3. Welt vollständig einpendeln lassen und erst danach `F3+P` aktivieren.
4. Mindestens 30 Sekunden Stillstand messen.
5. Anschließend eine festgelegte Flugroute mit gleicher Geschwindigkeit messen.
6. Mittelwert, p95, Maximum, sichtbare Sections, Mesh-/Uploadzähler und Screenshots notieren.
7. Den Lauf ohne Profiler wiederholen, um die tatsächliche FPS-Regression zu prüfen.

Für Streamingtests müssen Cold- und Warm-Läufe getrennt werden:

- **Cold:** Prozess neu starten und einen noch nicht im Betriebssystem-Cache erwärmten Pfad
  verwenden.
- **Warm:** denselben Pfad nach abgeschlossener Generierung, Beleuchtung und Upload erneut
  abfliegen.

## Aktuelle Referenz-Baseline

Stand: Commit `3cb0e0b` (`Add compact grass composite materials`), 2026-09-01.

Testsystem:

| Komponente | Wert |
|---|---|
| CPU | Intel Core i9-13900K, 24 Kerne / 32 Threads |
| GPU | NVIDIA GeForce RTX 4080, 16 GB |
| GPU-Treiber | 596.49 |
| Betriebssystem | Windows 11 Pro 64-Bit, Build 26200 |
| Projekt-Toolchain | Java 25 |
| Benchmark-Threads | 1 |
| Warmups / Iterationen | 10 / 30 |

Repräsentative uninstrumentierte Prozess-Mediane nach Visibility-, Compatibility- und
Grass-Composite-Umbau:

| Szenario | Section-Median |
|---|---:|
| Generated Terrain mit AO | ca. 0,86 ms |
| Generated Terrain ohne AO | ca. 0,65 ms |
| Full-Cube Best Case | ca. 1,26 ms vor dem Composite-Schritt; neu messen, falls relevant |
| Mixed Models | ca. 1,54 ms vor dem Composite-Schritt; neu messen, falls relevant |

Die Neuprofilierung direkt vor dem Composite-Umbau ergab für Generated Terrain mit AO etwa:

- Section Median: `0,858 ms`
- Section p95: `1,317 ms`
- Full-Cube Median: `0,646 ms`
- `PREPARE_AND_HALO`: `0,099 ms`
- `973` Quads pro nichtleerer Section
- `46,4 KiB` Mesh-Payload pro nichtleerer Section

Der Grass-Composite-A/B-Vergleich ergab:

| Messgröße | Legacy Overlay | Composite | Änderung |
|---|---:|---:|---:|
| Overlay-Fallback-Faces | 1.462 | 0 | -100 % |
| Overlay-Geometrie | 2.924 Quads | 937 Quads | -68,0 % |
| Overlay-Payload | 233.920 Byte | 21.896 Byte | -90,6 % |
| gesamte Terrain-Quads | 26.274 | 24.287 | -7,6 % |
| gesamte Terrain-Payload | 1.283.356 Byte | 1.071.332 Byte | -16,5 % |
| Payload je nichtleerer Section | 47.531,7 Byte | 39.679,0 Byte | -16,5 % |

Über fünf getrennte Prozesse lag der Median der Prozess-Mediane bei ungefähr:

| Szenario | Legacy Overlay | Composite |
|---|---:|---:|
| Generated Terrain mit AO | 0,868 ms | 0,861 ms |
| Generated Terrain ohne AO | 0,679 ms | 0,647 ms |

Der Composite-Umbau ist daher primär ein Geometrie-, Upload- und Speichergewinn; ein großer
CPU-Meshing-Gewinn wird daraus nicht abgeleitet.

Für GPU-Framezeiten existiert an diesem Commit noch keine versionierte, kontrollierte
Runtime-Baseline. FPS-Angaben aus manuellen Spielsitzungen werden hier bewusst nicht als
Referenz eingetragen. Diese Lücke sollte mit der oben beschriebenen festen Szene geschlossen
werden.

## Multiplayer-L0-Streaming messen

Integrated und Dedicated benutzen dieselbe logische Pipeline aus Interest, Revision,
`ImmutableChunkColumnData`, Snapshotcache, Installation und Client-Meshing. LocalTransport gibt
das immutable Snapshotobjekt ohne Wire-Encoding, Kompression oder Remote-Validierung weiter.
TCP encodiert dieselbe Revision genau einmal, komprimiert sie optional einmal und decodiert sie
direkt in die finale gepackte Clientrepräsentation. Mehrere Spieler teilen Snapshot-, Encoding-
und Kompressionsarbeit.

Die Streaming-Footprints sind dabei phasenspezifisch. Sichtbare Chunks und der eine für
Nachbarzugriffe benötigte Client-Halo werden bis `LIT` vorbereitet. Der folgende reine
Server-Dependency-Ring endet bei `DECORATED`, der äußere Ring bei `GENERATED`. Reine
Serverdependencies werden weder repliziert noch clientseitig installiert oder gemesht. Die
Footprints sind geometrisch exakt um die kreisförmige Sichtweite erweitert; an diagonalen
Grenzen fehlen daher keine Mesher-Nachbarn.

Der Dedicated Server verwendet standardmäßig `availableProcessors - 2` gemeinsame CPU-Worker.
Worldgen, Features, Lighting, Snapshot-Freeze, Encoding und Kompression werden in gewichteten,
alternden Fairness-Lanes ausgeführt. Server-Tick, Netty-Eventloops und Region-IO bleiben separate
Owner-Threads. Beim Integrated Server teilen sich Worldgen, Snapshot-Aufbau, Client-Decode und
Meshing denselben arbeitskonservierenden Pool gleicher Größe; es gibt keine statisch reservierten
Client-/Server-Workergruppen. `worker-threads` kann den Dedicated-Wert explizit überschreiben.

Für einen reproduzierbaren manuellen Cold-Streaming-Lauf:

1. Server und Client mit derselben Welt, Renderdistanz und Blickrichtung starten.
2. Vor dem Lauf `profile start` und danach `profile`, `net` und `perf` in der Serverkonsole
   ausführen: unmittelbar nach `PLAY`, nach vollständig sichtbarem Radius und nach einer festen
   Spectator-Flugroute.
3. `pending/in-flight/ready/ack/applied`, World-Worker-Auslastung, Queues, TX-Bytes und
   Chunk-Encoding-Zeit zusammen mit der Zeit bis zum geschlossenen sichtbaren Radius notieren.
4. Zusätzlich Cachebytes, Hits/Creates/Requests, Leases, allokierte/kopierte/Wire-Bytes und die
   Queue-Wartezeiten jeder Scheduler-Lane erfassen. Das gemeinsame Replikations-Cachebudget ist
   über `-Dskyengine.replication-cache-bytes=<Bytes>` konfigurierbar.

Bei schneller Bewegung werden nur Jobs abgebrochen, deren gemeinsames Interest-/Dependency-
Ticket wirklich entfallen ist. Eine Richtungsänderung bewertet wartende Arbeit neu, startet sie
aber nicht neu. Ein `ChunkViewEpoch` entfernt Trails ohne tausende einzelne Unload-Pakete; ein
älterer Batch bleibt dennoch gültig, wenn seine Koordinate auch in der neuen View benötigt wird.
Admission erfolgt vor dem Freeze über Byte-/Arbeit-Credits; nach der atomaren Clientinstallation
gibt der ACK die Lease sofort frei. Transiente Snapshotfehler werden mit begrenztem Backoff
wiederholt. Fehlende Installations-ACKs lösen nach dem konfigurierten Connection-Timeout die
Lease und die Verbindung, statt alte Revisionen unbegrenzt zu pinnen. `/net` weist dafür
Retry-/Timeout-/Resync-Zähler sowie Ready-/ACK-Bytes separat aus. Development-Läufe können mit
`-Dskyengine.debug.replicationLeaseAssertions=true` beim Serverabbau hängende Lease-Owner melden.

Der automatisierte Protokoll-/Session-Lasttest bleibt:

```powershell
.\gradlew.bat multiplayerLoadTest -Pplayers=8 -Pseconds=10
```

Für den produktiven autoritativen Chunkpfad einschließlich Worldgen, Lighting, Snapshot-Freeze,
Interest, LocalTransport, Leases und ACKs dient:

```powershell
.\gradlew.bat multiplayerPipelineLoadTest -Pplayers=8 -Pseconds=10 -PviewDistance=16 -Proute=fast
```

Nach der Flugphase wartet der Harness standardmäßig bis zu zehn Sekunden auf die aktuelle View.
Das ist über `-PcatchUpSeconds=<Sekunden>` konfigurierbar. Er meldet dabei die tatsächliche
Flugdistanz, residente Serverchunks sowie sichtbare, residente und präsentierte Clientchunks;
damit ist ein am Ende der Messzeit noch laufender Stream von einem dauerhaft steckengebliebenen
Chunk unterscheidbar.

Dieser Lauf schreibt `tools/build/reports/multiplayer/multiplayer-pipeline-load.json`. Der Bericht
enthält getrennte Snapshot-/Encoded-/Compressed-Cachebytes, Requests/Hits/Creates, Evictions,
Pins und Lease-Alter sowie allokierte, kopierte, direkte und erzeugte Wire-Bytes. Damit ist bei
überlappenden Spielern prüfbar, dass teure Arbeit mit eindeutigen Chunkrevisionen statt mit
`Spieler × Chunks` skaliert.

Der äquivalente Dedicated-Lauf verwendet echte TCP-Frames, Chunkfragmentierung, optionales
Zstd, validierendes Decode und dieselbe finale `ReplicatedChunkCache`-Installation:

```powershell
.\gradlew.bat multiplayerDedicatedLoadTest -Pplayers=8 -Pseconds=10 -PviewDistance=16 -Proute=fast
```

Für die von Cold-Worldgen getrennte Delta-/COW-Messung warten zwei weitere Aufgaben zunächst
auf eine vollständig präsentierte, arbeitsseitig ruhige Welt. Erst danach wechseln die Bots
Blöcke mit einer globalen, konfigurierbaren Rate und der Tick-/Chunkprofiler wird an einer
sauberen Tickgrenze zurückgesetzt:

```powershell
.\gradlew.bat multiplayerWarmWorldLoadTest -Pplayers=32 -Pseconds=10 `
  -PviewDistance=8 -PmutationRate=100
.\gradlew.bat multiplayerDedicatedWarmWorldLoadTest -Pplayers=32 -Pseconds=10 `
  -PviewDistance=8 -PmutationRate=100
```

Der JSON-Bericht trennt dabei Requests, bestätigte/abgelehnte Aktionen, beobachtete
Clientzustandswechsel sowie ausschließlich während der Warm-Phase erzeugte Pakete, Wire-Bytes,
Snapshots, Allokationen und Kopien vom vorausgehenden Cold-Load. `L0_CLIENT_DELTA_COW` und
`clientDeltaCowBytes` weisen zusätzlich Zeit und neu angelegte immutable Sectiondaten für
bestätigte Blockänderungen aus.

`ChunkTransportConvergenceTest` speist dieselbe Serverrevision einmal als direkte immutable
LocalTransport-Nachricht und einmal über das kanonische Wire-Encoding samt validierendem Decode
in zwei Client-Caches. Auch wenn neuere Blockdeltas die Basis überholen, müssen beide Pfade
bitgenau auf derselben Revision konvergieren; die geteilte Serverrevision darf dabei nicht
mutiert werden.

Beide Modi nacheinander mit identischem Seed, Workerlimit, maximalem Bytebudget und derselben
Route startet der Dreiervergleich einschließlich der nicht produktiven direkten Referenz:

```powershell
.\gradlew.bat multiplayerComparisonLoadTest -Pplayers=8 -Pseconds=10 `
  -PviewDistance=16 -Proute=fast -Pseed=123456789 -PbandwidthMiB=128 -Pworkers=30
```

`cleanupReferenceLoadTest` misst ausschließlich den ehemaligen direkten
Terrain→Features→Light→Mesh-Datenfluss. Er ist von keinem Menü und keiner produktiven Session
erreichbar. Für einen fairen Vergleich lädt er fünf explizit ausgewiesene Dependency-Ringe,
damit exakt alle Chunks innerhalb der angeforderten sichtbaren Distanz meshingfähig sind:

```powershell
.\gradlew.bat cleanupReferenceLoadTest -PviewDistance=32 -Pseed=123456789 -Pworkers=30
```

Die Integrated-/Dedicated-Harnesses akzeptieren denselben `-Pworkers`-Parameter. Der Vergleich
ist nur gültig, wenn `presentedVisibleChunks` beziehungsweise `presentedClientChunks` jeweils
der identischen erwarteten sichtbaren Chunkanzahl entspricht; die zusätzlichen direkten,
serverseitigen und clientseitigen Resident-Chunks werden getrennt ausgewiesen.

Die Bots verwenden die produktive `ClientNetworkSession`, `ChunkManager`,
`ReplicatedChunkWorldAdapter` und den produktiven `ChunkMesher`. Ein simulierter GPU-Upload
markiert exakt die fertig gemeshten Sections als präsentiert. Ein Batch wird erst nach
Fragment-Reassembly, Decode, Revisionsprüfung und atomarer Chunkinstallation bestätigt. Der Report enthält
zusätzlich installierte, residente und entladene Clientchunks, Zeit bis zum ersten Chunk, zur
lokalen Kollisionsnachbarschaft und zur vollständigen sichtbaren View sowie RX/TX-Bytes,
Worker-Lane-Wartezeiten und Cache-/Lease-Zustände. Damit werden auch stehengebliebene Streams
und Chunktrails sichtbar; ein bloß empfangenes `ChunkBatchEnd` zählt nicht als Erfolg.

Der Harness verarbeitet und bestätigt alle empfangenen Chunkbatches und schreibt standardmäßig
`tools/build/reports/multiplayer/multiplayer-load.json`. Ein JFR-Lauf ist verfügbar mit:

```powershell
.\gradlew.bat multiplayerLoadJfr -Pplayers=32 -Pseconds=30
```

Er ersetzt keinen visuellen Chunkstreaming-Lauf, prüft aber Sessions, Tickzeiten, Backpressure,
Leases und begrenzte Queues ohne Renderer. Ein Streamingvergleich ist nur gültig, wenn Seed,
frische Welt, JVM, sichtbare Chunkanzahl, Dependency-Halo, Flugroute, Kompression und Workerzahl
identisch dokumentiert sind. Verbindliche Produktionsszenarien sind RD16, RD32, schneller Flug,
zwei überlappende Spieler und 1/8/32 Spieler. Neben Chunks/s zählen insbesondere Zeit bis zur
spielbaren Welt, Zeit bis zur vollen View sowie Interest→CollisionReady/RenderReady/Presented
(Median/p95). Cleanup bleibt ausschließlich Performance- und Verhaltensreferenz.

## Neue Baseline eintragen

Bei einer relevanten Änderung einen neuen Abschnitt oder eine neue Tabellenzeile ergänzen:

```text
Datum:
Commit:
Branch:
Änderung:
CPU / GPU / Treiber / OS / Java:
Gradle-Befehl und Parameter:
Benchmark-JSON:
Mesh-Hash:
Section Median / p95 / Max:
Full-Cube Median / p95 / Max:
Quads / Meshbytes / Uploadbytes:
Allocation / GC:
Ingame-Szene und Einstellungen:
CPU Frame / Upload / Command Build:
GPU L0 / Post-Processing / Frame Span:
Cold-/Warm-Streaming-Ergebnis:
Bewertung und bekannte Ausreißer:
```

Rohreports unter `build/reports/meshing/` sind Build-Artefakte und werden nicht als dauerhafte
Dokumentation vorausgesetzt. Wenn ein Report als langfristige Referenz dienen soll, sollte er
gezielt und mit Commit-/Hardware-Metadaten unter `docs/performance/baselines/` archiviert
werden.

## Noch offene Messbereiche

- kontrollierte GPU-Baseline für L0 Opaque/Cutout/Translucent
- CPU-Kosten von Streaming, Upload, Arena-Verwaltung und Command Build in einer festen Route
- Cold-/Warm-Verhalten bei hoher Fluggeschwindigkeit
- tatsächliche GPU-Speicherbelegung der Arenen
- Skalierung über Renderdistanz und sichtbare Sectionanzahl
- getrennte Kosten von MSAA/TAA, Resolve und Post-Processing

Erst wenn diese Werte vorliegen, sollte der nächste größere Renderer- oder Streaming-Umbau
ausgewählt werden.
