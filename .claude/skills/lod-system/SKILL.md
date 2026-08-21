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

**Himmelslicht im LOD:** Freie Terrain- und Wasseroberflächen schreiben **15** (voller Himmel) in
den Licht-Int. Sichtbarer Boden und Wände unter LOD-Wasser werden analytisch um eine Stufe pro
vollem Block Wassertiefe abgedunkelt (`clamp(15 - ceil(waterTop - vertexY), 0, 15)`); Wände tragen
oben/unten getrennte Werte und interpolieren dazwischen. Das nutzt ausschließlich die ohnehin
vorhandenen Oberflächen-/Bodenhöhen und bleibt deterministisch. Es gibt weiterhin keine echten
Lichtdaten im LOD: `LodDataSource` liefert nur Höhen + Block-ID, Blocklicht bleibt 0, und ein
Sampling echter Lichtwerte wäre teuer und determinismus-kritisch. Bewusste Folge: trockene
beschattete Fernregionen (Schluchtwände, Nordseiten) sind heller als echtes Terrain — das liegt
≥16 Chunks entfernt im Fog-Übergang und ist akzeptiert.

- `LodConfig` (pure Formel): `maxLevel = clamp(ceil(log2(lodMax/RD)), 1, 5)`;
  `levelAt(dist) = clamp(floor(log2(dist/(RD*32))) + 1, 1, maxLevel)`; Zellgröße `2^L` (max 32 —
  Formatgrenze des UV-Fixed-Point). RD/lodMax ändern ⇒ Levelzahl ergibt sich automatisch.
- `LodManager` (Tick-Thread, aus `World.update`): Soll-Zustand `desired` (regionKey → Level),
  Ist-Zustand `current`, `inflight`-Set gegen Doppel-Submits; Jobs laufen mit **niedrigster**
  Priorität auf den Chunk-Workern (`submitLodTask`), max. 32 Submits/Tick. **Ausnahme:** reine
  Masken-Remeshes (Level/Epoche stimmen, nur `mask != c.mask`) submitten mit `clip=true` →
  `PRIO_LOD_CLIP` **vor** der Lade-Queue — sonst verhungert der Clip hinter bis zu
  LOAD_QUEUE_LIMIT Lade-Jobs: beim Schnellflug steht die alte LOD-Geometrie sekundenlang über
  frisch erschienenen L0-Chunks (Lade-Richtung) bzw. pendingUnload-Chunks halten ihre Meshes
  fest (Arena-Druck, Unload-Richtung). Neubauten/Level-/Epoche-Wechsel bleiben `PRIO_LOD`. Submit-Reihenfolge =
  Zwei-Stufen-Score wie die Chunk-Ladereihenfolge (Sichtkegel cos 75° zuerst, dann Distanz) —
  rein distanzsortiert war sie blickrichtungs-blind, ein 180°-Dreh änderte nichts am sichtbaren
  LOD-Fortschritt. Der Score ändert NUR die Reihenfolge, nie Level/Anker (Determinismus).
  **`submitPass` ist zusätzlich auf `chunkManager.isInitialLoadComplete()` gegated** — echtes
  Terrain zuerst. `PRIO_LOD` allein reicht dafür NICHT: die Priorität verhindert nur das
  Verdrängen in der Queue; läuft sie kurz leer, greift ein freier Worker sofort einen LOD-Job und
  zieht ihn ohne Präemption durch — und beim Start sind diese Jobs teuer (ohne Chunks fällt die
  Datenquelle pro Zelle auf den Generator-Noise zurück).
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
   **Keinen Innen-Radius einbauen** (einmal gebaut, sofort wieder ausgebaut): Er misst vom **Anker**
   (hinkt per Hysterese bis `RECOMPUTE_DISTANCE` hinterher), der Chunk-Unload dagegen vom
   **Spieler** (`(rd+2)*32`) — Zellen ausgeschlossener Regionen landen dadurch jenseits des
   Unload-Radius: Chunk weg, LOD nie da ⇒ **Ring aus Löchern beim Fliegen**. Zusätzlich schaltet er
   still das Unload-Gate ab (`coversChunk` liefert für nicht gewünschte Regionen `true`). Er bringt
   auch nichts: sind die 16 Chunks geladen, ist die Maske `0xFFFF` und `LodMesher.mesh` steigt mit
   einem LEEREN Mesh aus — LOD unter dem Spieler kostet nichts. Gegen LOD-Flackern beim Weltstart
   wirkt das Lade-Gate (`ChunkManager.isInitialLoadComplete`, s.o.), nicht die Ring-Geometrie.
   **Unload-Gate (Gegenrichtung):** Der ChunkManager entfernt sichtbare Chunks
   (`isFullyUploaded()`, bewusst NICHT status==READY — `remeshAll()` setzt READY→LIT
   zurück, während die Meshes sichtbar bleiben) jenseits rd+2 erst, wenn
   `LodManager.coversChunk` bestätigt, dass das HOCHGELADENE Mesh (Bit in `current.mask`
   ungesetzt) die Zelle deckt; bis dahin `chunk.pendingUnload = true` → `computeMask` zählt ihn
   als abwesend → Region un-clippt, BEVOR der Chunk verschwindet. Notventil: jenseits rd+6 wird
   bedingungslos entladen. Verdrahtung: `chunkManager.setLodManager(...)` in `World.init`.
   Kein Loch-Frame, weil `applyLodResults` im Renderer VOR der Section-Mesh-Disposal läuft.
   **Sicht-Gate (atomarer Swap, beide Richtungen):** Der Cull-Loop des ChunkRenderers (und
   gespiegelt der BlockEntityRenderDispatcher) überspringt Section-Meshes, solange
   `LodManager.lodShowsCell(cx,cz)` true ist (hochgeladenes LOD-Mesh zeigt die Zelle noch,
   Bit ungeclippt). Da `applyLodResults` im selben Frame VOR dem Cull läuft, erscheinen
   geclipptes LOD und Chunk im SELBEN Frame — kein Doppelbild an der Ladefront, kein
   progressiver Teil-Pop teil-hochgeladener Chunks. Fürs VERSTECKEN nie `coversChunk`
   verwenden: dessen „!desired → true"-Zweig würde Chunks ohne LOD verstecken (Loch);
   `lodShowsCell` prüft nur `current` (auf desired gepruned).
4. **Determinismus des Meshers:** Jede Zelle wird rein am Zellmittel gesampelt — identisch aus
   Sicht ALLER Regionen. Zellen fremder Regionen werden auf DEREN Zellraster gesampelt
   (`neighborLevel` nutzt dieselbe pure `levelAt`-Formel mit demselben Anker), aber im EIGENEN
   Raster quantisiert (`quantizeHeight(..., s)`): Ring-Höhen sind reine VERGLEICHSwerte für
   Wand-Bedingung/Ecken-AO — mit Nachbar-Quantisierung ergäbe flaches Terrain an Level-Grenzen
   Phantom-AO-Streifen und Phantom-Stufenwände; den realen Restversatz der Meshes
   (< MAX_QUANT_STRIDE) decken die Regionsrand-Skirts. Wer an der Levelzuordnung/Sample-Position
   einen Sonderpfad einbaut, erzeugt Nähte/Löcher an Regionsgrenzen, die nur aus bestimmten
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
- Das 20-Byte-Vertexformat trägt nur ~254 Blöcke Y-Spanne → Vertices werden relativ zu `yBase`
  (tiefste Geometrie − Skirt − 2) gepackt; der Renderer schiebt per Draw-Offset zurück.
  Der Greedy-Merge ist auf `MAX_MERGE_BLOCKS = 32` je Achse gedeckelt (UV-Fixed-Point 6.10 trägt
  max ~63). Tops mergen **2D** (Breite entlang x, dann Höhe entlang z — wie im ChunkMesher),
  Wände 1D entlang ihrer Kante (die zweite Quad-Dimension ist dort schon die Höhe).

## Datenquellen

`LodDataSource` ist die EINZIGE Datenquelle des Meshers (LOD kann Spieleränderungen weder
überschreiben noch verzögern). Verdrahtet ist **ausschließlich `PersistentLodDataSource`**
(`World.init`); `WorldLodDataSource` und `StorageLodDataSource` haben keinen Aufrufer mehr.
Sie entscheidet **je Quellchunk, nicht je Level**, in dieser Reihenfolge:

1. **RAM-Cache** — Treffer nur, wenn dort ein Level **≤** dem angeforderten liegt
   (`materializeLevel` leitet nur zu GRÖBEREN Leveln ab, nie zurück zu feineren).
2. **LOD-Disk-Cache** (`saves/<welt>/lod/`) — gelesen **nur**, wenn *überhaupt kein* RAM-Eintrag
   existiert **und** der Chunk nicht invalidiert ist. Er folgt also **nicht** auf einen
   unbrauchbaren RAM-Treffer: liegt im RAM nur ein grobes Level und wird ein feineres verlangt,
   wird er **übersprungen** und direkt über 3./4./5. neu gebaut. Invalidiert wird beim Speichern
   eines Chunks, also bei jeder Spieleränderung.
3. **residenter Chunk** (≥ DECORATED, bewusst ohne Lock — transiente Fehler remeshen sich weg)
4. **Savegame-Snapshot**
5. **Generator** + Feature-Pass

**Einen „generatorreinen" Fernring gibt es also nicht** — auch L3/L4 lesen residente Chunkdaten,
wenn welche in Reichweite liegen; und jeder Quellchunk wird immer bei voller 32×32-Auflösung
gebaut und erst danach auf das angeforderte Level hochreduziert (kanonische L0-Projektion,
`ChunkLodColumns`). Der Spaltenscan
akzeptiert nur **opake Vollblöcke ohne
`no_lod_surface`-Flag** (`isOpaqueCube && !isExcludedFromLodSurface`) oder Fluid — LOD ist
bewusst baum-/vegetationsfrei: Leaves sind `solid=true` und wurden mit dem alten
`isSolid`-Prädikat zur LOD-Oberfläche (Baumkronen-Klötze in L1/L2), danach die Logs darunter
(kahle Stamm-Säulen). Logs sind per Struktur-Flags nicht von Stein unterscheidbar (Archetyp
wird beim Bake verworfen, oak_log ist sogar `cube`) → Block-JSON-Flag `"no_lod_surface": true`
auf den 5 Weltgen-Logs (oak/birch/spruce/acacia/jungle; Redwood=spruce, Palm=jungle). Der
Generator-Pfad war schon immer baumfrei — Features entstehen erst im Dekorations-Pass. Implementierungen MÜSSEN threadsicher und deterministisch
sein — Nachbarregionen sampeln dieselben Randzellen erneut.

## Verifikation

- Nur visuell (`./gradlew run`): „Loading einfrieren" (GuiDebugScreen) pausiert Chunk-Loading → in LOD-Gebiete fliegen und
  Nähte prüfen (Regionsgrenzen, L0-Übergang, Level-Wechsel). LOD-Toggle-Taste (KeyBindings.LOD)
  und Render-Distanz −/= triggern die Epoche.
- Nach Mesher-Änderungen gezielt ansehen: Regionsgrenzen im flachen UND steilen Terrain,
  Fluid-Flächen (koplanar mit echtem Wasser? SOURCE_HEIGHT!), frisch geladene Chunks am L0-Rand
  (Schlitze?).
- Debug-Log: „LOD: N Regionen, M Quads, X MB Arena" (alle 2048 Frames) und „LOD-Regionen: L1=…"
  nach jedem Recompute.
