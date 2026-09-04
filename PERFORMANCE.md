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

Der Dedicated Server verwendet standardmäßig `availableProcessors * 5 / 8` World-Worker; der
Remote-Client bis zu acht eigene Decode-/Mesh-Worker. Beim Integrated Server gibt es dagegen
keine feste Aufteilung: Server-Worldgen, Snapshot-Aufbau, Client-Decode und Meshing teilen sich
`availableProcessors - 4` gewichtete, starvation-freie Worker. Die Werte werden beim Start
ausgegeben und lassen sich für Dedicated Server über `worker-threads` fest einstellen.

Für einen reproduzierbaren manuellen Cold-Streaming-Lauf:

1. Server und Client mit derselben Welt, Renderdistanz und Blickrichtung starten.
2. Unmittelbar nach `PLAY`, nach vollständig sichtbarem Radius und nach einer festen
   Spectator-Flugroute jeweils `net` und `perf` in der Serverkonsole ausführen.
3. `pending/in-flight/ready/ack/applied`, World-Worker-Auslastung, Queues, TX-Bytes und
   Chunk-Encoding-Zeit zusammen mit der Zeit bis zum geschlossenen sichtbaren Radius notieren.
4. Für Loopback beachten: TCP-Chunkdaten sind unkomprimiert und erhalten 128 MiB/s; das
   Bytebudget wird von Netty unabhängig vom 20-TPS-Takt abgearbeitet. Echte Remote-Verbindungen
   verwenden `chunk-bytes-per-second` und optional Zstd.

Bei schneller Bewegung werden wartende Worldgen-Stufen außerhalb des aktuellen gemeinsamen
Spielerinteresses abgebrochen und verbleibende Aufgaben neu nach Nähe und Bewegungsrichtung
eingereiht. Der Load-Vorlauf ist auf ungefähr zwei Aufgaben pro World-Worker begrenzt. Auf der
Netzwerkseite dürfen pro Spieler höchstens 16 vollständige Chunkbatches auf eine Clientbestätigung
warten; veraltete, noch nicht kodierte Batches sind abbrechbar. Diese Grenzen sollen bei einem
Flugtest verhindern, dass alte Koordinaten minutenlang vor der aktuellen Sichtfront liegen.

Der automatisierte Protokoll-/Session-Lasttest bleibt:

```powershell
.\gradlew.bat multiplayerLoadTest -Pplayers=8 -Pseconds=10
```

Er ersetzt keinen visuellen Chunkstreaming-Lauf, prüft aber Sessions, Tickzeiten und begrenzte
Queues ohne Renderer. Ein Streamingvergleich ist nur gültig, wenn sichtbare Renderdistanz,
Meshing-Halo, Weltzustand, Kompression und Workerzahl identisch dokumentiert sind.

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
