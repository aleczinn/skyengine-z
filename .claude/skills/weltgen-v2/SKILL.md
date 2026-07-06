---
name: weltgen-v2
description: AlphaWorldGeneratorV2 — klimabasierte Höhe/Biome aus denselben Feldern, Seed-Offset-Vergabe, Worley-Seen (Cache-Rekursions-Falle!), RiverNetwork (Quelle→Mündung, monotones Profil), 3D-Dichte/Höhlen, Feature-Pass mit Scheiben-Modell. Lesen vor JEDER Änderung an Weltgenerierung, Biomen, Seen, Flüssen oder Features.
---

# Weltgenerierung V2

## Kernprinzip: alles sind pure Funktionen von (Seed, Position)

Chunks werden parallel und in beliebiger Reihenfolge generiert. JEDE Generator-Funktion muss
deterministisch und chunk-unabhängig sein — Nachbarchunks berechnen dieselben Randwerte erneut
und müssen exakt dasselbe sehen. Caches (`lakeCache`, `RiverNetwork.cache`) sind reine
Memoization pro Zelle (ConcurrentHashMap), nie Zustand. Wer „nur kurz" einen chunk-lokalen
Seiteneffekt einbaut, erzeugt Nähte, die erst Kilometer weiter auffallen.

## Klima → Höhe UND Biome (deshalb keine Blend-Logik nötig)

`ClimateSampler` (seed+0..+8 reserviert): 4 niederfrequente Felder (Temperatur, Feuchte,
Kontinentalität inkl. Küstendetail, Erosion). Zwei Sample-Pfade: `sample()` MIT Grenz-Dither
(Biome-Lookup, Materialien — probabilistische Mischung an Grenzen) und `sampleSmooth()` OHNE
(Höhenmodell, Tint-Grids). `Biomes.lookup` ist reiner Schwellwert-Lookup über die stetigen Felder
→ Übergänge sind automatisch glatt; `Biomes.mountainWeight` skaliert ZUGLEICH den Berg-Aufschlag
im Höhenmodell → Extreme-Hills-Biom und Berg-Terrain sind per Konstruktion deckungsgleich.
**Init-Falle:** `Biomes` fängt `Blocks.*`-IDs beim Klassen-Init ein — nie vor `Blocks.bootstrap`
berühren (nicht aus Generator-Konstruktoren!).

## Seed-Offset-Buchführung (bei jedem neuen Noise prüfen!)

ClimateSampler: seed+0..+8. Generator-Noises: seed+10..+23 (Zuordnung siehe Konstruktor).
RiverNetwork: seed+24 (Mäander), seed+25 (Breite). Neue Noises bekommen den nächsten freien
Offset — Doppelbelegung korreliert Felder sichtbar (Ausnahme bewusst: detailBase2/mountain teilen
seed+11 bei anderer Frequenz/Nutzung).

## Spaltenberechnung (`columnFor`) — Reihenfolge und withWater-Flag

rawHeight (Kontinent-Spline + Uplift + Erosions-Detail + Ridged-Berge mit Plateau-Kappung +
Wüsten-Auffüllung) → Fluss-Carving → See-Carving → Wasserspiegel (Meer 64 / Fluss / See).
Fluss- und See-Carving dämpfen die 3D-Shape-Amplitude auf 0 (`damp`) — sonst hebt das Shape-Noise
die Sohle über den Spiegel (trockene Flussbett-Flicken).

**`withWater=false` (heightBeforeLakes) ist die Anti-Rekursions-Sperre:** Seespiegel werden aus
Ringpunkt-Höhen OHNE See- und Fluss-Carving berechnet. Seen dürfen NICHT von Flüssen abhängen,
sonst: See-Ring → Fluss-Trace → lakeNear → See-Ring… (Cache-Rekursion/Deadlock). Diese Kante nie
„vereinfachen".

## Worley-Seen

Zellraster 384, Jitter+Radius so gewählt, dass ein See seine Zelle NIE verlässt → pro Spalte
genügt die eigene Zelle (`lakeAt`), nur `lakeNear` (Mündungserkennung) prüft 3×3. Spiegel =
min(Zentrum, 16 Ringpunkte) − 1; Ring-Spanne > 12 = Hanglage = kein See. Ufer-Noise verzerrt die
Distanz nur nach INNEN (Buchten) — nie über den Radius hinaus, sonst läuft Wasser jenseits der
Ringpunkte aus.

## RiverNetwork (Quelle→Mündung)

4096er-Zellen (`CELL`); `SOURCE_TRIES = 6` Quell-Kandidaten pro Zelle, durch Gates +
250-Block-Abstandsregel (`nearAny`) bleiben praktisch 0–2 Läufe übrig (keine harte Kappe im
Code). Ein Lauf verlässt den 3×3-Ring seiner Quellzelle nie → `sampleAt` fragt nur 3×3 Zellen.
Trace läuft auf `riverGuide` bergab (glattes Leitfeld MIT Gebirgs-Penalty, OHNE hochfrequente
Oktaven); der Spiegel folgt `riverCarrier` (Terrain bis auf Detail-Oktaven 3+4) als
**laufendes Minimum − DOWNCUT 3** → monoton fallend, Wasserwände konstruktionsbedingt
ausgeschlossen. Enden: Meer, See (nur wenn Seespiegel ≤ Profil+2, sonst Becken DAVOR), Endbecken
(abflusslose Senke/Maxlänge, aufgeweitet), Zusammenfluss (JOIN_DIST 64 auf früheren Lauf derselben
Zelle). Schutzmechanismen, alle gegen konkrete beobachtete Artefakte: harte Drehklammer TURN_MAX
28° (Knicke), Selbstschnitt-Abbruch (Spiral-Knäuel), Segment-Spiegel-Blend in `sampleAt`
(Haarnadel-/Parallel-Wasserwände), Rückwärts-Glättung max. +3/Segment (stehende Wasserwand an
Kaskaden). Der Uferdamm in `columnFor` (Anhebung bis Spiegel+1, Deckel 4) ist die EINZIGE
Terrain-Anhebung des Fluss-Systems.

## generate() & Feature-Pass

3D-Dichte nur an Gitterpunkten (4×4 horizontal, 8 vertikal) + trilineare Interpolation;
`surfaceSolidHeight` reproduziert exakt dieselbe Mathematik (globale Raster!) — Features stehen
damit auf dem realen Boden. Höhlen (Cheese + 2×Spaghetti) stechen nie Oberflächen-/Bodenkruste
unter Wasser an. Materialien: `surfaceTop`/`fillerFor` werden von generate() UND LOD
(`sampleSurface`) geteilt — geänderte Deckmaterialien MÜSSEN durch beide Pfade, sonst Nähte am
LOD-Übergang. Bodenpflanzen: Dichtefeld × Pro-Block-Hash (`hash01`), kein Feature-Pass nötig.

Bäume laufen im Feature-Pass (`ChunkDecorator`, **Scheiben-Modell**): der Ziel-Chunk wertet die
Features aller 9 Chunks seines 3×3-Umfelds deterministisch aus (`featureSeed` pro Quell-Chunk ×
Feature-Index), schreibt aber nur eigene Blöcke (`FeaturePlacer.set/setIfAir` filtern). Feste
Reihenfolge (row-major, dann Feature-Index) — nie umsortieren, `setIfAir` braucht deterministische
Vorzustände. Die Feature-Listen-Reihenfolge in `World` geht in den Seed ein.

## Verifikation

- **`GeneratorMapExporter` (eigene main, kein GL!)**: schreibt Falschfarben-PNGs nach
  `debug-maps/` — Klima, Biome, Höhen in Sekunden prüfen, args `<step> <centerX> <centerZ>`.
  Das ist der schnellste Weg, Weltgen-Änderungen zu verifizieren.
- Determinismus-Check: F8 (Chunks neu laden) muss exakt dieselbe Welt ergeben; Nähte an
  Chunk-Grenzen = verletzte Purity.
- Performance: Log „Generierung: X ms/Chunk" alle 256 Chunks (Richtwert ~2,5 ms/Chunk).
- In-Game-Optik (Flussufer, Seeränder, Schneegrenze) nur visuell.
