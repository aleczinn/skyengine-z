---
name: licht-system
description: Licht — Himmelslicht UND Blocklicht (LightStorage/LightEngine, Heightmap, Emitter, Flood-Fill, LIGHTING/LIT in der Chunk-Pipeline, Smooth Lighting im Mesher, Lichtkurve und Helligkeits-Regler im Shader). Lesen vor JEDER Änderung an Licht, Licht-Opazität, Leuchtblöcken, ChunkStatus, dem 5. Vertex-Int oder dem Chunk-Shader.
---

# Licht

Es gibt **zwei Ebenen**, beide monochrom 0..15 und beide über dieselbe Maschinerie:

- **Himmelslicht** (`chunk.light`) — Heightmap + Flood, verlustfreie Direkt-Säule nach unten.
- **Blocklicht** (`chunk.blockLight`) — Quellen sind Blöcke mit `light_level` (Fackel 14,
  Verzauberungstisch 7, Lava 15, brauner Pilz 1). Kein Tag-Nacht-Zyklus, keine Schatten.

**Farbiges Licht gibt es noch nicht.** `light_color` wird zwar aus der Block-JSON gelesen,
validiert und in `BlockConfig` abgelegt, wirkt aber **nirgends aufs Bild** — das ist die
vorbereitete Datenhälfte der RGB-Phase, kein vergessener Draht. Wer RGB nachrüstet, legt weitere
`LightStorage`-Ebenen daneben und nutzt die freien Bits 8-31 des Licht-Ints; er baut sie **nicht**
in die vorhandenen Nibbles hinein.

**Entities emittieren nicht** (kein Dynamic Lights) — sie empfangen nur.

## Kette in einem Absatz

Block-JSON `light_opacity`/`light_level` → `StateFlags` Bits 10-13 bzw. 14-17 →
`BlockState.getLightOpacity()`/`getLuminance()` → `LightEngine` (Heightmap + Emitter + BFS) →
`chunk.light`/`chunk.blockLight` (je ein `LightStorage`, Nibble pro Zelle) →
`ChunkMesher.computeCornerLight` (Smooth Lighting, packt beide Kanäle) → int4 des Vertex →
Vertex-Attribut 1 → `v_light` (`vec2`) → Fragment-Shader
(`max` → `lightCurve` → Ambient-Boden → Regler-Kurve).

## Datenhaltung (`game/world/light/LightStorage.java`)

Ein Nibble-Array **pro Section** (32³/2 = 16 KB), lazy materialisiert, Referenzen in einer
`AtomicReferenceArray` (sichere Publikation). Nicht materialisierte Sections tragen einen
**uniformen** Wert: 15 über dem Terrain, 0 darunter — beide kosten **0 Byte**. Das ist der Grund,
warum ~3 statt 16 Sections pro Chunk Speicher brauchen (≈50 MB statt 256 MB bei rd 16).

Die Klasse weiß **nichts von Ebenen** — es gibt schlicht zwei Instanzen pro Chunk (`light`,
`blockLight`). Fürs Blocklicht ist der Uniform-Default 0 goldrichtig: eine Section ohne
Leuchtblock materialisiert nie. Deshalb kostet die zweite Ebene in normalem Terrain **nichts**.

**Bewusst lock-frei** (wie `WorldLodDataSource`): Byte-Zugriffe reißen nicht, nebenläufige Reader
(Mesher) sehen höchstens transient veraltete Werte, die Dirty-Masken sorgen für Konvergenz. Hier
nachträglich Locks einzuziehen bricht die Architektur, statt sie abzusichern.

## Ausbreitung (`game/world/light/LightEngine.java`)

Arbeitsraum ist das **3×3-Chunk-Umfeld** um ein Zentrum, Koordinaten x/z ∈ [−32, 63]. Lichtradius
15 < Chunkbreite 32 ⇒ ein BFS erreicht höchstens die direkten Nachbarn, deshalb ist **kein
transitives Gating** nötig. Eine Instanz ist **nicht threadsicher** — ThreadLocal im
`ChunkManager`, eine eigene in `World` für den Render-Thread.

Welche Ebene gerade läuft, sagt das Feld **`skyLayer`**; `getLight`/`setLight`/`seedBorder` routen
über `storageOf(chunk)`. Zurückgesetzt wird es in **`setContext()`**, nicht bloß in einem `finally`
— die Instanz lebt im ThreadLocal weiter, und eine geworfene Exception würde die Ebene sonst in den
nächsten Job lecken (Blocklicht landete im Himmelslicht-Array).

`lightInitial` macht **beide** Ebenen in einem Aufruf — deshalb musste der `ChunkManager` gar nicht
angefasst werden, es gibt keinen zweiten Job und keinen neuen ChunkStatus:
1. Himmel: `computeHeightmap` → `initColumns` (15 über der Heightmap, Voll-Sections als Uniform) →
   `seedColumnEdges` → `seedWaterColumns` → `runIncrease`.
2. Block: `skyLayer = false` → `seedEmitters` → `runIncrease`. Kein Gegenstück zu `initColumns` —
   Blocklicht startet überall bei 0, Quellen sind allein die Emitter.

`exchangeBorders` läuft ebenfalls zweimal (`exchangeLayer` je Ebene), `applyDirty()` **einmal** am
Ende: die `dirtyMasks` sammeln über beide.

**`seedEmitters` hat einen Paletten-Vorfilter.** Ein Zell-Scan wäre 32768 Zellen je Section; kommt
in `section.container().paletteEntries()` keine leuchtende State-ID vor, kann keine Zelle leuchten.
In normalem Terrain fallen so alle 16 Sections raus, ohne dass eine Zelle gelesen wird. Die Palette
schrumpft nie — eine gesetzte und wieder abgebaute Fackel hinterlässt einen Treffer, der keiner
mehr ist. Kostet einen überflüssigen Scan, sonst nichts.

### Regeln, die nicht wegoptimiert werden dürfen

- **`seedWaterColumns`.** `seedColumnEdges` seedet nur an Höhen*differenzen*. Eine flache
  Ozeanfläche hat keine — ohne diesen Schritt hätte sie NULL BFS-Quellen und der ganze Meeresboden
  bliebe schwarz. Wasser braucht dafür `light_opacity: 1`.
- **Verlustfreie Direkt-Säule:** `skyLayer && d == DIR_DOWN && level == 15 && opacity == 0 →
  next = 15`. Und ihr Gegenstück im Decrease: `neighborLevel == 15` unter `level == 15` gilt als
  „von uns gespeist". **Beide Hälften gehören zusammen** — eine allein erzeugt Geister-Licht bzw.
  schwarze Säulen. Beide sind **skylight-only**: eine Fackel leuchtet nach unten so weit wie zur
  Seite.
- **Aufgeschobener Emitter-Re-Seed im Decrease.** Eine gelöschte Zelle, die selbst leuchtet, muss
  zurückkommen — sonst löschen zwei benachbarte Fackeln einander aus. Sie landet dafür in einer
  **eigenen `reseed`-Queue**, die erst **nach** der Unlight-Welle angewandt wird.
  *Inline wäre falsch:* die Welle kann dieselbe Zelle ein zweites Mal aus einer stärkeren Richtung
  erreichen (Fackel 7 neben Lava 15), sie erneut auf 0 setzen, und der bereits eingereihte
  Increase-Eintrag scheitert dann an `getLight != level` — die Fackel bliebe **dauerhaft aus**.
  *„Emitter nie löschen" wäre ebenso falsch:* eine Fackel neben Lava trägt 14, nach dem Abbau der
  Lava bliebe die veraltete 14 hängen. Regressionstest: `testTwoTorches` im `LightProbe`.
- **`getLight` über der Welt:** `y >= HEIGHT` liefert 15 fürs Himmelslicht, aber **0** fürs
  Blocklicht — sonst strahlte von oberhalb der Welt Fackellicht herein.
- **Uniform-Kurzschluss** in `initColumns`/`seedBorder`: Speicher UND Laufzeit. Fürs Blocklicht
  greift er noch häufiger (beide Seiten uniform 0), der Block-Randdurchgang kostet dann nichts.
- **`encode`** packt x/z ∈ [−32,63] (7 Bit), y ∈ [0,511] (9 Bit), Level (4 Bit) in einen int —
  ausgelegt auf `Chunk.HEIGHT = 512`. Wer die Welthöhe ändert, muss hier mitziehen.

### Der Edit-Pfad (`onBlockChanged`) — die teuerste Falle

Der Early-Out prüft **Opazität ODER Luminanz**. Eine gesetzte Fackel ändert die Opazität nämlich
**nicht** (0 → 0); prüfte man nur sie, löste kein einziger Leuchtblock je ein Update aus und
Fackeln leuchteten erst nach einem Weltneustart. Danach:
`updateSkyAt` nur bei Opazitätsänderung, `updateBlockAt` bei **beidem** — eine neu gesetzte Wand
blockt auch Fackellicht. `updateBlockAt` braucht kein `darker`/`heller`-Zweigpaar (es gibt keine
Heightmap zu pflegen): `removeLight` → neue Luminanz seeden → sechs Nachbarn als Quellen anbieten →
`runIncrease`. Das deckt alle vier Fälle ab.

### Licht-Opazität

Automatik: `isOpaqueCube(state) ? 15 : 0`, per State (Doppel-Slab 15, Halbstufe 0). Explizit in der
Block-JSON nur da, wo Licht **dämpfen** statt durchfallen oder hart enden soll — heute `water` und
die **acht** Laub-Blöcke, je `light_opacity: 1`. Die acht Laub-Dateien einzeln anfassen, **nicht**
`preset/cube.json` (CLAUDE.md-Falle „Preset-Felder gelten für ALLE Kinder").

Bewusst auf auto (= 0) und damit von Vanilla abweichend: Glas, Schnee, Lava sowie Slabs/Treppen —
eine Halbstufen-Decke dunkelt nicht ab. Per JSON nachziehbar.

### Eigenleuchten (`light_level`, `light_color`)

`light_level` 0..15 geht denselben Weg wie `light_opacity`: `BlockDefinition` →
`ArchetypeBlockFactory` (geclampt) → `BlockConfig.lightLevel()` → `Block.getLuminance(state)` →
`BlockRegistry.computeFlags` → `StateFlags` **Bits 14-17** → `BlockState.getLuminance()`.
Heute gesetzt: `torch` 14, `enchanting_table` 7, `lava` 15, `brown_mushroom` 1.

> **Lava war der Perf-Verdacht — und ist entkräftet:** der Weltgenerator platziert **überhaupt
> keine** Lava (nachgemessen, `grep` über `generator/` ist leer). Sie entsteht nur per Eimer oder
> aus einem Minecraft-Import. Nur dort kann ein See mit Opazität 0 einen 15er-Radius fluten und
> Blocklicht-Sections zu je 16 KB materialisieren.

`light_color` (`"#RRGGBB"`, `#` optional) wird geparst, validiert und in `BlockConfig` abgelegt —
und **wirkt nirgends**. Es ist die Datenhälfte der RGB-Phase, kein toter Draht. Bewusst **nicht**
in `StateFlags`: nach der Luminanz sind dort 13 Bit frei, eine Farbe braucht 24. Eine kaputte
Farbangabe ist kein Abbruch (Fallback Weiß), aber eine `LOGGER.warning` — und `saveTest` liest
Warnungen mit.

## Pipeline (`ChunkStatus`, `ChunkManager`)

DECORATED → **LIGHTING** (Gate: 8× DECORATED, der Job liest Nachbar-BLÖCKE) → **LIT** →
MESHING (Gate: 8× LIT, der Mesher liest Nachbar-LICHT).

**`status = LIT` steht VOR `exchangeBorders` und INNERHALB des try:**
- *Warum davor:* `exchangeBorders` seedet nur zu Nachbarn, die schon LIT sind. Wer von zwei
  benachbarten Jobs später fertig wird, sieht den anderen und tauscht **beide** Richtungen aus —
  damit ist die Job-Reihenfolge egal. Danach gesetzt könnten sich zwei gleichzeitig fertige Jobs
  verpassen: **dauerhaft dunkle Naht**.
- *Der Preis:* das Erst-Mesh darf schon starten und sieht am Rand transient altes Licht. Das
  fangen `markCell`/`applyDirty` auf — die Dirty-Bits überleben bis nach READY, weil der
  Erst-Mesh-Job `consumeDirtySections()` nicht aufruft.
- *Warum im try:* `exchangeBorders` liest Nachbar-Blöcke, das braucht die Read-Locks.

**`submitLoadTask`, nicht `submitTask`** — sonst zählt der Licht-Job nicht in `pendingLoadTasks`,
der `initialLoadComplete`-Latch feuert zu früh und das LOD startet auf halb belichtetem Terrain.

`remeshAll()` fällt auf **LIT** zurück (nicht DECORATED): sonst flutet jeder AO-Toggle das
unveränderte Licht komplett neu.

**Edits:** `World.setBlockRaw` liest den alten Block VOR dem Write und ruft danach `updateLight`.
Läuft synchron auf dem Render-Thread — dem einzigen Block-Schreiber. Die Dirty-Markierung von
`setBlockRaw` reicht dafür **nicht** (1-Block-Ring gegen bis zu 15 Blöcke Lichtreichweite über
Chunk-Grenzen hinweg); die Engine markiert selbst, inklusive ±1-Ring fürs Corner-Smoothing.

## Mesher

`computeCornerLight` liefert **gepacktes** Licht: Himmel in Bits 0-3, Block in Bits 4-7. Je Kanal
das Mittel der vier Zellen im Layer VOR der Face, die die Ecke berühren, je Kanal kaufmännisch
gerundet; opake Zellen zählen nicht. Gegated über `quad.face() >= 0` (dieselbe Bedingung wie das
AO), Quads ohne Richtung (Cross, Fluid-Geometrie) bekommen flach das Licht der eigenen Zelle.

> **Dass hier schon gepackt wird, ist der Grund, warum der Blocklicht-Umbau so klein blieb:**
> `putVertex`, `emitGreedyQuad` und `emitWaterTop` reichen den einen int unverändert durch und
> mussten **gar nicht** angefasst werden.

`NeighborSampler.samplePackedLight` (früher `sampleLight`) liefert denselben gepackten Wert. Die
Randkonstanten stimmen gepackt unverändert weiter: `15` heißt „Himmel 15, Blocklicht 0" (fehlender
Nachbar-Chunk darf hell sein, aber nicht glühen), `0` heißt „beides aus". Deshalb braucht es dort
**keinen** zweiten Fallback-Wert.

Der **Diagonal-Flip-Tiebreak** in `emitQuad` vergleicht `effectiveLight()` (= `max` beider Kanäle,
dieselbe Regel wie im Shader), nicht den gepackten int — sonst dominierte das Blocklicht in den
oberen Bits jeden Vergleich.

> **Die Falle beim „Vereinheitlichen mit `computeAo`":** `computeAo` verschiebt die Basiszelle nur
> bei bündigem Quad (`flush`) — das erzeugt bewusst das dunkle Band an der Treppenstufe. Fürs Licht
> muss **immer** verschoben werden. Sonst wäre bei einer Slab-Oberseite (y = 0,5, nicht bündig) die
> Basiszelle der Slab selbst, alle vier Zellen okkludiert, `count == 0` → **schwarze Slab-Oberseiten**.

Der **Greedy-Merge-Schlüssel** trägt die 8 Bit gepacktes Licht zwischen State-ID und AO
(`stateId << 10 | packedLight << 2 | aoIdx`). Nur Flächen mit gleichem Licht UND gleichem AO werden
zusammengefasst. Licht ist `int` — der Uniformitätsvergleich ist hier, anders als beim AO, nicht
ULP-empfindlich. Der gemergte Wasser-Top-Pass bleibt bewusst **lichtfrei** im Schlüssel (sonst
zerreißen die großen Ozean-Quads); er nimmt flach das Licht der Zelle über der Oberfläche.

> **Der Schlüssel wird an DREI Stellen gebaut/gelesen** (AO-aus-Zweig, AO-an-Zweig, Auflösung im
> Merge-Loop). Wer das Licht-Feld verbreitert und eine davon vergisst, bekommt Key-Kollisionen:
> Flächen werden mit falscher Helligkeit gemergt, und zwar nur an Kanten — sichtbar als
> rechteckige Helligkeitsflecken.

Rund um eine Fackel zerfallen große Greedy-Quads in kleinere (der Gradient macht das Licht
uneinheitlich). Denselben Effekt gibt es beim Skylight an Höhleneingängen längst; er ist auf 15
Blöcke Radius begrenzt.

## Shader

`v_light` ist ein **`vec2`**: x = Himmel, y = Block, je 0..1. Der Vertex-Shader dekodiert beide
Nibbles aus `a_light` — weiterhin **vor** dem Detail-Ausdünnungsblock, der mit `return` aussteigt.

```glsl
float lightCurve(float f) { return f / (4.0 - 3.0 * f); }   // MC-Helligkeitskurve
float light = lightCurve(clamp(max(v_light.x, v_light.y), 0.0, 1.0));  // monochrom: max gewinnt
light = u_MinLight + (1.0 - u_MinLight) * light;            // Ambient-Boden ZUERST
float inv = 1.0 - light, inv2 = inv * inv;
light = mix(light, 1.0 - inv2 * inv2, u_Brightness);        // dann die Regler-Kurve
vec3 lit = color.rgb * clamp(v_color, 0.0, 1.0) * light;
```

> **Die Reihenfolge ist der ganze Trick — und war einmal falsch herum.** Stünde die Kurve vor dem
> Boden, bekäme sie bei Lichtlevel 0 eine Null herein und gäbe eine Null heraus (`1 − 1⁴ = 0`).
> Der Regler wäre dann in der dunkelsten Höhle **mathematisch wirkungslos**, also genau dort, wo
> man ihn braucht — gemessen an zwei Screenshots derselben Stelle bei 10 % und 100 %: Mittelwert
> 2,12 in beiden, bit-identisch. Nie tauschen.

`GameSettings.brightness`: 0 = AUS = Fullbright (`u_MinLight = 1.0`, `u_Brightness = 0`), sonst
5..100 % → `u_MinLight = AMBIENT_LIGHT` (0,04) und `u_Brightness = brightness/100`. Damit gilt
`light(0) = mix(0.04, 0.1507, Regler)`, der Regler hebt den Boden also mit an — bei Regler 50 %
läuft die Skala 0 → 0,095 · 4 → 0,260 · 8 → 0,471 · 12 → 0,733 · 15 → 1,000.

**Beide Fixpunkte sind bit-exakt:** bei Skylight 15 liefert `lightCurve` exakt 1.0, der Boden
rundet `0.04f + 0.96f` auf exakt 1.0 und die Kurve lässt 1.0 stehen — die Oberfläche sieht also
aus wie ohne Lichtsystem. `u_MinLight = 1.0` ⇒ `light == 1.0` für jedes `v_light`, das ist
Fullbright, ohne Shader-Zweig, ohne zweite Programmvariante, ohne Remesh. Es gibt nur **ein**
Chunk-Shader-Programm (Opaque/Cutout/Detail/Translucent/LOD/GPU-Cull-Phase-2 teilen es sich),
deshalb reicht ein Upload pro Frame in `renderSolid`.

**Kein Remesh** bei Reglerwechsel — genau deshalb liegt Licht in int4 und wird nicht wie AO/Tint
in die Vertexfarbe multipliziert.

### Dunkle Szenen brauchen 16-Bit-Zwischenpuffer

`PostContext.createPingTargets` legt die Post-Ping-Ziele als **RGBA16F** an, nicht RGBA8. In einer
Höhle liegen die Werte bei 0,001–0,005 — die 8-Bit-Stufe (1/255 = 0,0039) ist dort *gröber als das
Signal*, alles kollabiert auf 0/1/2. TAA klemmt die Historie danach auf ein ~1-LSB-Fenster und
friert das Muster ein statt es zu mitteln, und CAS-Sharpen verstärkt genau diese Sprünge um Faktor
1,9–2,7 (`amp = sqrt(min/max)` geht bei kleinem Absolut- und großem Relativkontrast gegen 1 — CAS
schärft am stärksten dort, wo das Signal am kaputtesten ist). Ergebnis waren gesprenkelte
Tunnelenden. **Nicht auf RGBA8 zurückdrehen.**

## Objekte ohne Vertex-Licht

BlockEntities (Truhe, Verzauberungstisch), Spielermodell, Item-Drops, First-Person-Hand und das
Item in der Hand haben **eigene Shader-Programme**. Für sie ist das Licht pro Draw konstant, also
rechnet die CPU den fertigen Faktor einmal — `ChunkRenderer.lightFactor(sky, block)` — und die
Renderer bekommen nur ein skalares `uniform float u_Light`, statt dieselbe GLSL-Kurve in vier
Shader zu kopieren.

> **`lightFactor` ist eine Handkopie des Fragment-Shaders und muss mit ihm zusammen geändert
> werden.** Laufen beide auseinander, sitzt eine Truhe sichtbar heller oder dunkler in ihrer Wand
> als das Terrain daneben. Dass der Shader das `max` erst nach der Interpolation bildet und die CPU
> schon davor, ist kein Unterschied: dort sind beide Werte pro Draw konstant.

Wer den Wert woher nimmt: Hand/Arm aus der **Augen**-Zelle, Third-Person-Spieler aus der
Spielerzelle (beide über `World.getSkyLight` + `getBlockLight`); Item-Drops und BlockEntities
lesen `chunk.light`/`chunk.blockLight` direkt, weil der Chunk in der Render-Schleife ohnehin
vorliegt. `getBlockLight` hat dasselbe LIT-Gate wie `getSkyLight`, aber Fallback **0** statt 15 —
„unbekannt" heißt beim Himmel „vermutlich hell", beim Blocklicht „vermutlich keine Fackel".

**Die GUI-Pfade setzen hart `1.0F`** (Inventar-Vorschau, Item/Truhe in der Vorschauhand,
Slot-Icons): sie teilen sich die Shader mit der Welt und würden sonst mit der Höhle abdunkeln.

Item-Icons und LOD-Terrain bleiben außen vor; LOD bekommt pauschal Himmel 15 / Blocklicht 0
(s. Skill `lod-system`) — jenseits der Renderdistanz zeigt es nur Oberfläche, wo der Himmel
ohnehin dominiert.

## Persistenz

**Keine** der beiden Ebenen wird gespeichert. Von Disk geladene Chunks landen auf DECORATED und
durchlaufen den Licht-Job automatisch — `ChunkSerializer`/`RegionFile`/Format-Version bleiben
unangetastet. Das gilt für Blocklicht genauso: die Emitter stehen ja in den Blockdaten.

## Verifikation

`./gradlew lightTest` (`game/world/light/debug/LightProbe`) prüft GL-frei: Licht-Opazitäten **und
Luminanzen**, Heightmap-Regel, verlustfreie Direkt-Säule, Tunnel-Gradient (−1/Block, Reichweite 15),
Versiegeln/Aufbrechen einer Höhle über `onBlockChanged`, Wassersäulen-Abstufung, Chunk-Naht nach
`exchangeBorders` (für **beide** Ebenen), echtes Generator-Terrain (alle 1024 Säulen müssen an der
Oberfläche 15 haben), Fackel-Gradient, Fackel setzen/abbauen, den Zwei-Fackel-Re-Seed und beide
Licht-Kanäle **im gepackten Vertex** des Meshers. Exit 0 = alles korrekt.

`./gradlew saveTest` deckt zusätzlich das JSON-Parsing inklusive `light_color` ab (kaputte Farben
erzeugen Warnungen).

Nur im Fenster prüfbar bleibt der Rest des Renderpfads: VAO-Attribut 1, Vertex-/Fragment-Shader,
Smooth-Lighting-Optik um eine Fackel, Merge-Artefakte, Nähte im Bild, der Helligkeits-Regler
(immer am Extremwert prüfen!) und das Objektlicht in einem fackelbeleuchteten Keller.
