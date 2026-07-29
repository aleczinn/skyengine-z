---
name: licht-system
description: Himmelslicht (Skylight) — LightStorage/LightEngine, Heightmap, Flood-Fill, LIGHTING/LIT in der Chunk-Pipeline, Smooth Lighting im Mesher, Lichtkurve und Helligkeits-Regler im Shader. Lesen vor JEDER Änderung an Licht, Licht-Opazität, ChunkStatus, dem 5. Vertex-Int oder dem Chunk-Shader.
---

# Himmelslicht

**Es gibt NUR Himmelslicht.** Kein Blocklicht, keine Leuchtblöcke, keine Fackel-Emission, kein
Tag-Nacht-Zyklus. Fackeln sind Deko. Wer Blocklicht nachrüstet, erweitert `LightStorage` um
weitere Ebenen und nutzt die freien Bits 4-31 des Licht-Ints — er baut sie **nicht** ins
Skylight-Nibble hinein.

## Kette in einem Absatz

Block-JSON `light_opacity` → `StateFlags` Bits 10-13 → `BlockState.getLightOpacity()` →
`LightEngine` (Heightmap + BFS) → `Chunk.light` (`LightStorage`, Nibble je Zelle) →
`ChunkMesher.computeCornerLight` (Smooth Lighting) → int4 des Vertex → Vertex-Attribut 1 →
`v_light` → Fragment-Shader (`lightCurve` → Ambient-Boden → Regler-Kurve).

## Datenhaltung (`game/world/light/LightStorage.java`)

Ein Nibble-Array **pro Section** (32³/2 = 16 KB), lazy materialisiert, Referenzen in einer
`AtomicReferenceArray` (sichere Publikation). Nicht materialisierte Sections tragen einen
**uniformen** Wert: 15 über dem Terrain, 0 darunter — beide kosten **0 Byte**. Das ist der Grund,
warum ~3 statt 16 Sections pro Chunk Speicher brauchen (≈50 MB statt 256 MB bei rd 16).

**Bewusst lock-frei** (wie `WorldLodDataSource`): Byte-Zugriffe reißen nicht, nebenläufige Reader
(Mesher) sehen höchstens transient veraltete Werte, die Dirty-Masken sorgen für Konvergenz. Hier
nachträglich Locks einzuziehen bricht die Architektur, statt sie abzusichern.

## Ausbreitung (`game/world/light/LightEngine.java`)

Arbeitsraum ist das **3×3-Chunk-Umfeld** um ein Zentrum, Koordinaten x/z ∈ [−32, 63]. Lichtradius
15 < Chunkbreite 32 ⇒ ein BFS erreicht höchstens die direkten Nachbarn, deshalb ist **kein
transitives Gating** nötig. Eine Instanz ist **nicht threadsicher** — ThreadLocal im
`ChunkManager`, eine eigene in `World` für den Render-Thread.

`lightInitial`: `computeHeightmap` → `initColumns` (15 über der Heightmap, Voll-Sections als
Uniform) → `seedColumnEdges` → `seedWaterColumns` → `runIncrease`.

### Regeln, die nicht wegoptimiert werden dürfen

- **`seedWaterColumns`.** `seedColumnEdges` seedet nur an Höhen*differenzen*. Eine flache
  Ozeanfläche hat keine — ohne diesen Schritt hätte sie NULL BFS-Quellen und der ganze Meeresboden
  bliebe schwarz. Wasser braucht dafür `light_opacity: 1`.
- **Verlustfreie Direkt-Säule:** `d == DIR_DOWN && level == 15 && opacity == 0 → next = 15`. Und
  ihr Gegenstück im Decrease: `neighborLevel == 15` unter `level == 15` gilt als „von uns gespeist".
  **Beide Hälften gehören zusammen** — eine allein erzeugt Geister-Licht bzw. schwarze Säulen.
- **Uniform-Kurzschluss** in `initColumns`/`seedBorder`: Speicher UND Laufzeit.
- **`encode`** packt x/z ∈ [−32,63] (7 Bit), y ∈ [0,511] (9 Bit), Level (4 Bit) in einen int —
  ausgelegt auf `Chunk.HEIGHT = 512`. Wer die Welthöhe ändert, muss hier mitziehen.

### Licht-Opazität

Automatik: `isOpaqueCube(state) ? 15 : 0`, per State (Doppel-Slab 15, Halbstufe 0). Explizit in der
Block-JSON nur da, wo Licht **dämpfen** statt durchfallen oder hart enden soll — heute `water` und
die **acht** Laub-Blöcke, je `light_opacity: 1`. Die acht Laub-Dateien einzeln anfassen, **nicht**
`preset/cube.json` (CLAUDE.md-Falle „Preset-Felder gelten für ALLE Kinder").

Bewusst auf auto (= 0) und damit von Vanilla abweichend: Glas, Schnee, Lava sowie Slabs/Treppen —
eine Halbstufen-Decke dunkelt nicht ab. Per JSON nachziehbar.

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

`computeCornerLight` — Mittel der vier Zellen im Layer VOR der Face, die die Ecke berühren; opake
Zellen zählen nicht. Gegated über `quad.face() >= 0` (dieselbe Bedingung wie das AO), Quads ohne
Richtung (Cross, Fluid-Geometrie) bekommen flach das Licht der eigenen Zelle.

> **Die Falle beim „Vereinheitlichen mit `computeAo`":** `computeAo` verschiebt die Basiszelle nur
> bei bündigem Quad (`flush`) — das erzeugt bewusst das dunkle Band an der Treppenstufe. Fürs Licht
> muss **immer** verschoben werden. Sonst wäre bei einer Slab-Oberseite (y = 0,5, nicht bündig) die
> Basiszelle der Slab selbst, alle vier Zellen okkludiert, `count == 0` → **schwarze Slab-Oberseiten**.

Der **Greedy-Merge-Schlüssel** trägt 4 Bit Skylight zwischen State-ID und AO
(`stateId << 6 | sky << 2 | aoIdx`). Nur Flächen mit gleichem Licht UND gleichem AO werden
zusammengefasst. Skylight ist `int` — der Uniformitätsvergleich ist hier, anders als beim AO, nicht
ULP-empfindlich. Der gemergte Wasser-Top-Pass bleibt bewusst **lichtfrei** im Schlüssel (sonst
zerreißen die großen Ozean-Quads); er nimmt flach das Licht der Zelle über der Oberfläche.

## Shader

```glsl
float lightCurve(float f) { return f / (4.0 - 3.0 * f); }   // MC-Helligkeitskurve
float light = lightCurve(clamp(v_light, 0.0, 1.0));
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

## Was NICHT beleuchtet wird

BlockEntities (Truhe, Verzauberungstisch), Spielermodell, Item-Drops, First-Person-Hand und
Item-Icons haben **eigene Shader-Programme** und bleiben unverändert hell. Bewusste Auslassung,
kein Versehen. LOD-Terrain bekommt pauschal Himmel 15 (s. Skill `lod-system`).

## Persistenz

Licht wird **nicht** gespeichert. Von Disk geladene Chunks landen auf DECORATED und durchlaufen den
Licht-Job automatisch — `ChunkSerializer`/`RegionFile`/Format-Version bleiben unangetastet.

## Verifikation

`./gradlew lightTest` (`game/world/light/debug/LightProbe`) prüft GL-frei: Licht-Opazitäten,
Heightmap-Regel, verlustfreie Direkt-Säule, Tunnel-Gradient (−1/Block, Reichweite 15),
Versiegeln/Aufbrechen einer Höhle über `onBlockChanged`, Wassersäulen-Abstufung, Chunk-Naht nach
`exchangeBorders`, echtes Generator-Terrain (alle 1024 Säulen müssen an der Oberfläche 15 haben)
und das Licht **im gepackten Vertex** des Meshers. Exit 0 = alles korrekt.

Nur im Fenster prüfbar bleibt der Rest des Renderpfads: VAO-Attribut 1, Vertex-/Fragment-Shader,
Smooth-Lighting-Optik, Nähte im Bild, der Helligkeits-Regler.
