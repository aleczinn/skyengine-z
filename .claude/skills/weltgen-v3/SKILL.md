---
name: weltgen-v3
description: AlphaWorldGeneratorV3 (aktiver Default-Generator) — Biome-Parameter-Blending im 5D-Klimaraum (BiomeWeights/BiomeTerrainProfile), Label-Pfad (pick, gewarpt, minShare-Gate) vs. Blend-Pfad (blend, smooth), Intra-Biom-Shaper (Fjord-Klippe, Canyon-Terrassen/Strata/mesaness), geteilte Noise-Basis, Fluss-Hooks. Lesen vor JEDER Änderung an V3, Biom-Profilen, Klassifikation oder neuen Biomen.
---

# Weltgenerierung V3 (Biome-Parameter-Blending)

`AlphaWorldGeneratorV3` ist der **aktive Default** (`World` erzeugt ihn mit Seed 123). Er ist
strukturell eine V2-Kopie: Purity-Regeln, Worley-Seen, RiverNetwork, 3D-Dichte/Höhlen,
Feature-Pass und Tint-Grids funktionieren identisch — **dafür gilt weiterhin Skill
`weltgen-v2`**. Dieser Skill beschreibt nur, was V3 anders macht. V2 bleibt unverändert als
bit-stabiler Regressionsanker.

## Kernidee: Parameter blenden, nie Höhen

Biome sind keine Schwellwert-Regionen mehr, sondern **Zielpunkte im 5D-Klimaraum**
(Temperatur, Feuchte, Kontinentalität, Erosion + neues Feld **Variante**, Seed-Offset 26 —
trennt Biome mit gleichem Klima, z.B. Wüste vs. Canyon). Pro Spalte:

1. `BiomeWeights.blend(smooth)` berechnet die Kernel-Gewichte aller `BiomeTerrainProfile`s
   und mittelt deren **Terrain-PARAMETER** (baseOffset, detailMul, mountainAmp, plateauMul,
   shapeAmpMax, cliffHeight, terraceStrength) zu einem `TerrainParams`-Record.
2. Die geblendeten Parameter werden in die **eine geteilte Noise-Basis** eingesetzt (gleiche
   Seed-Offsets/Frequenzen wie V2). Nie eigene Noises pro Biom, nie k Höhenfunktionen mitteln —
   das wäre 3-4× teurer und würde den riverCarrier-Vertrag zerstören.
3. `BiomeWeights.pick(warped)` bestimmt das **Label** (argmax derselben Gewichte, aber am
   domain-gewarpten Klima) → Material, Vegetation, Tints. Terrain und Biomkarte können sich
   so nie großflächig widersprechen.

Kernel: `weight = weightScale * (1 - d²/r²)²` mit achsen-skalierter Distanz (`dist2`,
Achsen-Skala 0 = Achse ignorieren) — C1-stetig am Radius, kein k-nearest-Cutoff (das würde
beim Rangwechsel „poppen"). Außerhalb aller Radien fällt `blend`/`pick` auf das
radius-normiert nächste Profil zurück (`nearest`). Ozean/Strand haben KEIN Profil — sie
bleiben reine Kontinentalitäts-Schwellen (`Biomes.C_OCEAN`/`C_BEACH`, Karibikstrand per T/H).

## Die zwei Pfade — WICHTIGSTE Regel dieses Generators

- **Label-Pfad** (`climate.sample(x,z)` = domain-gewarpt → `BiomeWeights.pick`): `biomeAt`,
  Material-Biom in generate()/sampleSurface/debugSurfaceTop, Tint-Grids, LOD-Tints,
  Fenstertitel. Warp: WARP_X/Z (Seed 27/28), FBm 2 Oktaven, Freq 0.006, ±24 Blöcke —
  Grenzlinien werden kohärent wellig statt Speckle (ersetzt das alte additive Dither).
- **Blend-/Höhen-Pfad** (`climate.sampleSmooth(x,z)` → `BiomeWeights.blend`): columnFor,
  rawHeight, Seespiegel, Fluss-Hooks, slopeAt.

Nie mischen: Label am Smooth-Punkt erzeugt Speckle-Grenzen zurück; Höhen am Warp-Punkt
verändern das Terrain (Hash-Bruch). In generate() werden pro Spalte bewusst BEIDE Samples
gezogen (zweite volle Feld-Auswertung — der Hauptteil der V3-Mehrkosten).

**Gelernte Lektion (Canyon-Bug):** der argmax gewinnt schon bei ~35-50 % Gewichtsanteil, die
„Drama-Optik" (Terrassen/Mesas/Strata) entsteht aber erst bei hohem Anteil — das Label trug
Canyon-Material weit in Randzonen, deren Terrainform noch von Nachbarn dominiert wurde.
Fix: **`minShare`-Gate in `pick()`** — Profile mit `minShare > 0` (Canyon 0.5) beanspruchen
das Label nur ab diesem Gewichtsanteil (best/total), sonst gewinnt das zweitbeste Profil
(breite Klimabiome haben minShare 0, es gibt immer einen gültigen Zweiten). `blend()` und
`dominance()` sind davon unberührt — **Höhen ändern sich durch minShare nie**.
Regel für jedes neue „Drama-Biom": entweder minShare setzen ODER den Look über den Anteil
abstufen — und die Label-Look-Kopplung **in-game am Biomrand** prüfen, nicht nur im Kern.

## Profile (`BiomeWeights.PROFILES`) — Tuning-Wissen

- **Küsten-Aversion:** ALLE Inland-Klimabiome haben c=0.35/sC=0.8. Ohne sie verdünnen ihre
  breiten Kernel die Küsten-Spezialisten (Fjord-Klippenhöhe halbierte sich!). Tief im
  Binnenland ist der Term vernachlässigbar.
- **EXTREME_HILLS**: nur C/E zählen (sT=sH=0, wie V2s mountainWeight), weightScale 2.2,
  mountainAmp 200 — hoher Prior, damit breite Klimabiome das Massiv nicht verdünnen.
- **FJORD_HIGHLANDS**: enges C-Band um die Küstenlinie (c=-0.02, sC=2.0), weightScale 3.0,
  cliffHeight 120 (effektiv ~60-105), shapeAmpMax 16 (Felswände), mountainAmp 120.
- **CANYON**: heiß-trocken wie DESERT, per VARIANT getrennt (v=0.5 vs. -0.35), minShare 0.5,
  baseOffset 14, mountainAmp 70, plateauMul 2.2 (Mesa-Deckel statt Grate),
  terraceStrength 0.85, KEINE Klippe.
- `SHAPE_AMP_CEIL` (16) **muss ≥ max. shapeAmpMax aller Profile sein** — er dimensioniert
  die Solid-/Top-Margins der 3D-Dichte in generate()/surfaceSolidHeight.

## Intra-Biom-Shaper (abrupte Formen NIE an Biomgrenzen)

Steile Übergänge entstehen **innerhalb** eines Bioms über stetigen Feldern; das Biom-Gewicht
blendet sie nur ein/aus:

- **Fjord-Klippe** (`cliffLift`): `cliffHeight * smoothstep((c - CLIFF_START) / CLIFF_WIDTH)`
  mit CLIFF_START = C_BEACH − 0.012, CLIFF_WIDTH = **0.008**. Das Band MUSS so schmal sein:
  der lokale c-Gradient ist nur ~0.0002-0.001/Block, breitere Bänder verschmieren an flachen
  Küsten zur Rampe. Ortssteuerung ausschließlich über geblendetes cliffHeight — anderswo
  entstehen keine Klippen. Das Strandband schrumpft vor der Wand auf einen schmalen Saum.
- **Canyon-Terrassen** (`terrace`): Höhe in TERRACE_STEP-11-Stufen, doppeltes Smootherstep
  (C1, flache Tritte, steile Kanten), Einblendung `terraceStrength * inlandGate`.
- **Canyon-Strata** (`canyonStratum(y + strataShift)`): gewellte Terracotta-Bandfolge nach
  absoluter Höhe (floorMod 26, Versatz ±7 aus sedimentNoise, nur oberhalb STRATA_MIN_Y 45),
  nur in Canyon-gelabelten Spalten, im Stein-Bereich UNTER den 1-4 Filler-Schichten →
  **sichtbar erst an freigelegten Wänden ≥ ~6 Blöcke**, nicht auf sanften Hängen (dort sieht
  man nur Deckel+Filler — das ist kein Bug). Bewusst eine METHODE statt statischem Array:
  ein Array würde `Blocks.*`-IDs beim Klassen-Init einfangen (Init-Falle).
- **mesaness** (Mesa-Formanteil 0..1): in `rawHeight` aus dem Ridged-Bergterm berechnet
  (`shape * min(1, m/40)` — kleine Rand-Amplituden gedämpft, sonst bekämen 10-Block-Hügel
  Fels-Deckel), durchgereicht als `Raw` → `ColumnSample` → `surfaceTop`. Canyon:
  mesaness < 0.30 → **SAND-Boden** zwischen den Mesas (Bryce-Look), sonst RED_SANDSTONE
  (Mesa-Fels). Kein red_sand-Block möglich — Textur fehlt in den Ressourcen.
- **Canyon-Sonderregeln:** keine Stein-/Schneekappe (`biome != CANYON`-Guard in surfaceTop —
  die Mesas SIND Fels); Kakteen auf `(DESERT || CANYON) && top == SAND`; tote Büsche auch
  auf dem Sandboden (`canyonFloor`-Ausnahme in placePlants, da surfaceBlock der Mesa-Fels
  ist); Seen per Feuchte-Gate (h < −0.1) ausgeschlossen.
- **lineLift** = Uplift + cliffLift + baseLift: verschiebt die Stein-/Schneegrenze mit dem
  regionalen Grundniveau — sonst wären Fjord-Plateaus und Canyon-Mesas pauschal Fels/Schnee.

## Fluss-Hooks (RiverTerrain) — Lifts vollständig halten!

`riverBase` (gemeinsame glatte Basis von riverGuide/riverCarrier) **MUSS alle Nicht-Noise-
Anhebungen enthalten** (cliffLift, baseOffset·inlandGate): fehlt einer, liegt der Träger im
Hochland pauschal darunter und jeder Fluss carvt dort einen Canyon. riverGuide addiert die
geblendete Berg-Amplitude als weiche Penalty (Traces umlaufen Massive). Der Carrier hat
bewusst KEINE Terrassen — das laufende Minimum des Profils schneidet Stufen von oben, die
Rest-Abweichung (±TERRACE_STEP/2) fängt der Uferdamm.

## Init-Falle (verschärft gegenüber V2)

`Biomes` UND `BiomeWeights` (Profile referenzieren Biome-Konstanten) fangen `Blocks.*`-IDs
beim Klassen-Init ein — **nie vor `Blocks.bootstrap` berühren, nie aus dem
Generator-Konstruktor** (World entsteht vor dem Bootstrap; im V3-Konstruktor steht der
Warnkommentar). Compile-time-Konstanten wie `Biomes.C_BEACH` sind sicher (werden geinlined) —
deshalb darf `CLIFF_START` sie in einem static final nutzen.

## Verifikation & Performance

- `GeneratorMapExporter` schreibt `v3_height/v3_biomes/v3_surface/v3_water/v3_section` +
  **`v3_blend`** (Dominanz-Karte: hell = Biom-Kern, dunkel = Blend-Zone; nutzt
  `BiomeWeights.dominance`, nur Debug — nicht im Hot-Path verwenden).
- **Hash-Regression:** `v3_height.png` mit identischen Args vorher/nachher hashen, wenn eine
  Änderung nur Label/Material betreffen soll (minShare, surfaceTop, Vegetation dürfen die
  Höhen nie ändern). Für geteilte Klassen zusätzlich `v2_height.png` (s. Skill weltgen-v2).
- Sonden per jshell gegen `build/classes/java/main` (+ GSON-Jar), Java 25 explizit:
  `C:\Users\alec_\.jdks\ms-25.0.3\bin\jshell.exe`; zuerst `Blocks.bootstrap(...)`, dann
  Generator/Sampler bauen. Muster: Gewichte/Dominanz/Label/Höhe an Einzelpunkten ausgeben.
- Fenstertitel zeigt „Biom: …" an der Spielerposition (SkyEngine, 1×/Sekunde) — schnellste
  In-Game-Prüfung der Label-Look-Kopplung.
- Performance: V3 ~3,2 ms/Chunk (Fjord-Region ~3,5; V2: 2,5). Haupttreiber ist die zweite
  Feld-Auswertung des Warp-Pfads. Bekannter, bewusst NICHT gebauter Hebel: Dominanz-Shortcut
  (pick nur nahe Grenzen voll auswerten) — erst ziehen, wenn das Budget reißt.

## Stand & offene Punkte (2026-07)

Umgesetzt: Fundament + 2 Leuchtturm-Biome (FJORD_HIGHLANDS id 9, CANYON id 10), VARIANT-Feld,
Domain-Warp (beide Sampler), minShare-Gate, mesaness-Sandboden, Spawn via
`surfaceSolidHeight(0,0)+2`. Offen (geplant, nicht anfangen ohne Auftrag): restliche ~16
Biome (Klimatabelle mit dem User abstimmen), Bach-Ebene im RiverNetwork, Sumpf-Hydrologie,
See-Parameter pro Biom, red_sand-Block (wenn der User eine Textur liefert).
