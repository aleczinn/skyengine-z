---
name: lod-system
description: Heightmap-LOD (Clipmap-Ringe um den Spieler) — LodConfig-Level-Formel, Anker/Hysterese, Settings-Epoche, 16-Bit-Chunk-Masken, Determinismus-Regeln des LodMesher, yBase-Packung, Skirts. Lesen vor Änderungen an LodManager, LodMesher, LodConfig, Lod-Datenquellen oder der LOD-Anbindung im ChunkRenderer.
---

# LOD-System (Heightmap-Clipmap)

## Grundidee & Zuständigkeiten

Jenseits der Render-Distanz zeigt die Engine blockbasierte Heightmap-Regionen
(`LodMesher.REGION_BLOCKS = 128` = 4×4 Chunks, fix über alle Level): pro Zelle (Stride 2^Level,
global ausgerichtetes Raster) ein Top-Quad + Wände zu niedrigeren Nachbarn — Voxel-Optik, gleicher
Shader wie echtes Terrain, aber **zwei eigene Arenen** (LOD_OPAQUE, LOD_TRANSLUCENT) mit eigenen
Indirect-Draw-Segmenten (baseVertex gilt nur im selben Vertex-Buffer). AO ist ins LOD-Mesh
gebacken (AO-Toggle bumpt die Epoche); LOD-Wasser rendert transluzent im LOD-TRANSLUCENT-Pass
(unsortiert — großflächige Top-Quads ohne Höhlenwasser-Komplexität), Wände an Fluid-Zellen
ebenfalls. Gras-Wände tragen ein koplanares getöntes Overlay-Quad (s.u.).

- `LodConfig` (pure Formel): `maxLevel = clamp(ceil(log2(lodMax/RD)), 1, 5)`;
  `levelAt(dist) = clamp(floor(log2(dist/(RD*32))) + 1, 1, maxLevel)`; Zellgröße `2^L` (max 32 —
  Formatgrenze des UV-Fixed-Point). RD/lodMax ändern ⇒ Levelzahl ergibt sich automatisch.
- `LodManager` (Tick-Thread, aus `World.update`): Soll-Zustand `desired` (regionKey → Level),
  Ist-Zustand `current`, `inflight`-Set gegen Doppel-Submits; Jobs laufen mit **niedrigster**
  Priorität auf den Chunk-Workern (`submitLodTask`), max. 32 Submits/Tick, nah-zuerst.
- `ChunkRenderer` übernimmt Ergebnisse (Budget 4/Frame), schreibt eigene LOD-Segmente hinter
  die Terrain-Segmente und räumt Meshes ab, sobald `isDesiredKey` false ist. Die
  LOD-OPAQUE-Arena startet mit `LodMesher.estimateOpaqueArenaBytes` (Schätzung spiegelt die
  `recomputeDesired`-Geometrie; ~250 MB bei rd16/lodMax128) und wird bei Settings-Wechsel zur
  Laufzeit per `ensureCapacity` EINMALIG vorvergrößert — sonst wächst sie vom 8-MB-Floor
  treppenweise mit GPU-Vollkopien (real beobachtet 8→196 MB beim LOD-Einschalten).

## Die vier Mechanismen, die man leicht kaputtmacht

1. **Anker + Hysterese:** Die Level-Zuordnung hängt am Anker (Zentrum der Spieler-Region beim
   letzten Recompute), NICHT an der Live-Position. Recompute erst, wenn der Spieler
   `RECOMPUTE_DISTANCE = 64+24` Blöcke vom Anker weg ist — sonst pendeln Level-Pops an
   Regionsgrenzen. Der Anker (`jobAx/jobAz`) wird jedem Mesh-Job mitgegeben.
2. **Settings-Epoche:** Jede Änderung an lodEnabled/renderDistance/lodMaxDistance erhöht `epoch`
   und entwertet ALLE gebauten Meshes (`acceptResult` prüft epoch + Level). Ergebnisse, die nicht
   mehr passen, MÜSSEN verworfen werden — sonst Arena-Leak (Upload↔Unload-Race).
3. **16-Bit-Chunk-Maske (beide Richtungen gated!):** Bit gesetzt = dieser Chunk zeigt JETZT
   echtes Terrain — d.h. Status READY **und** `chunk.isFullyUploaded()` (alle 16 Section-Uploads
   angewendet) **und nicht** `chunk.pendingUnload`. READY allein reicht nicht → Löcher flackern,
   bevor der echte Mesh sichtbar ist. Masken-Diff (Chunk fertig geladen/entladen) triggert
   Region-Remesh. Innen wird NICHT per Radius ausgeschlossen — die Maske clippt zellgenau und
   füllt Lücken, solange Chunks laden.
   **Unload-Gate (Gegenrichtung):** Der ChunkManager entfernt sichtbare Chunks
   (`isFullyUploaded()`, bewusst NICHT status==READY — `remeshAll()` setzt READY→DECORATED
   zurück, während die Meshes sichtbar bleiben) jenseits rd+2 erst, wenn
   `LodManager.coversChunk` bestätigt, dass das HOCHGELADENE Mesh (Bit in `current.mask`
   ungesetzt) die Zelle deckt; bis dahin `chunk.pendingUnload = true` → `computeMask` zählt ihn
   als abwesend → Region un-clippt, BEVOR der Chunk verschwindet. Notventil: jenseits rd+6 wird
   bedingungslos entladen. Verdrahtung: `chunkManager.setLodManager(...)` in `World.init`.
   Kein Loch-Frame, weil `applyLodResults` im Renderer VOR der Section-Mesh-Disposal läuft.
4. **Determinismus des Meshers:** Jede Zelle wird rein am Zellmittel gesampelt — identisch aus
   Sicht ALLER Regionen. Zellen fremder Regionen werden auf DEREN Zellraster gesampelt
   (`neighborLevel` nutzt dieselbe pure `levelAt`-Formel mit demselben Anker). Wer hier einen
   Sonderpfad einbaut, erzeugt Nähte/Löcher an Regionsgrenzen, die nur aus bestimmten
   Blickwinkeln sichtbar sind.

## Optik-Details

- **Gras-Overlay-Wände:** `LodBlockAppearance` löst neben Top-/Seiten-Layer auch das separat
  gebackene Seiten-Overlay auf (`state.getOverlay()`, liegt NICHT im Modell). `emitWall`
  emittiert es als koplanares Quad mit IDENTISCHEN Vertices **vor** der Basis-Wand im selben
  Opaque-Puffer: identische Vertexdaten im selben Draw ⇒ identische Tiefe (GL-Invarianz), die
  Basis verliert den strikten Tiefentest genau dort, wo das Overlay nicht discarded wurde
  (`u_AlphaCutoff 0.5` gilt auch im LOD-OPAQUE-Segment). Reihenfolge Overlay-vor-Basis ist
  tragend; KEINEN Offset und keinen eigenen Pass einführen.
- **Fog:** Distanz-Fog (`GameSettings.fog`) kaschiert LOD-Übergänge und die Far-Kante; die
  Fog-Spanne hängt von `lodEnabled` ab (`ChunkRenderer.setFogUniforms`).

## Skirts & yBase (Format-Grenzen)

- An Regionsrand-Kanten wird IMMER eine Wand mit tiefem Skirt emittiert (`BASE_SKIRT << level`,
  Deckel 48) — verdeckt Level-Wechsel und Remesh-Latenz. **Masken-Kanten (geclippter Nachbar =
  L0-Naht) brauchen dieselben Skirts**, sonst blitzen ~1 Block hohe Schlitze durch.
- Das 16-Byte-Vertexformat trägt nur ~254 Blöcke Y-Spanne → Vertices werden relativ zu `yBase`
  (tiefste Geometrie − Skirt − 2) gepackt; der Renderer schiebt per Draw-Offset zurück.
  1D-Greedy-Merge ist auf `MAX_MERGE_BLOCKS = 32` gedeckelt (UV-Fixed-Point 6.10 trägt max ~63).

## Datenquellen

`LodDataSource` ist die EINZIGE Datenquelle des Meshers (LOD kann Spieleränderungen weder
überschreiben noch verzögern). `WorldLodDataSource`: Stride ≤ 4 (L1/L2) sampelt echte Chunkdaten
(Spaltenscan, bewusst ohne Lock — transiente Fehler remeshen sich weg), sonst pure
Generator-Funktion (`sampleSurface`). Implementierungen MÜSSEN threadsicher und deterministisch
sein — Nachbarregionen sampeln dieselben Randzellen erneut.

## Verifikation

- Nur visuell (`./gradlew run`): Taste P pausiert Chunk-Loading → in LOD-Gebiete fliegen und
  Nähte prüfen (Regionsgrenzen, L0-Übergang, Level-Wechsel). LOD-Toggle-Taste (KeyBindings.LOD)
  und Render-Distanz −/= triggern die Epoche.
- Nach Mesher-Änderungen gezielt ansehen: Regionsgrenzen im flachen UND steilen Terrain,
  Fluid-Flächen (koplanar mit echtem Wasser? SOURCE_HEIGHT!), frisch geladene Chunks am L0-Rand
  (Schlitze?).
- Debug-Log: „LOD: N Regionen, M Quads, X MB Arena" (alle 2048 Frames) und „LOD-Regionen: L1=…"
  nach jedem Recompute.
