---
name: fluid-system
description: Wasser/Lava — FluidBehavior (Scheduled-Tick-Fluss, LEVEL-Konvention INVERS zu Vanilla, kontaktbasierte Wasser+Lava-Reaktionen) und FluidGeometry (dynamische Eckhöhen-Meshes). Lesen vor JEDER Änderung an Fluid-Verhalten, -Rendering, Eimern oder Schwimm-/Strömungsphysik.
---

# Fluid-System (Wasser/Lava)

## Konventionen (INVERS zu Vanilla — nicht „korrigieren"!)

- `Properties.LEVEL`: **0 = Quelle (stärkstes Fluid)**, höhere Werte = schwächer. Pro horizontalem
  Block kommt `dropOff` dazu; `LEVEL > spread` trocknet aus. `FALLING = true` = fallende Säule,
  zählt effektiv als Level 0. Wasser: spread 7, dropOff 1 → 7 fließende Blöcke; Lava: spread 7,
  dropOff 2 → 3 Blöcke (Level 2, 4, 6).
- Parameter kommen aus `FluidInfo` (JSON: `fluid_spread`, `drop_off`, `fluid_tick` in
  `game/blocks/water.json`/`lava.json`; water.json setzt kein `drop_off` → Default 1;
  Takt: Wasser 5, Lava 30 Ticks).
- Sichthöhe: `(8 − level) / 9` (`FluidGeometry.ownHeight`); Quelle = 8/9 (`SOURCE_HEIGHT` —
  das LOD setzt Fluid-Zellen exakt darauf → koplanar, kein Z-Fighting).

## Fluss-Logik (`behavior/FluidBehavior.scheduledTick`)

Reihenfolge im Tick: 1) Wasser+Lava-Reaktion, 2) eigenen Stand aus Nachbarn
ableiten (Quelle bleibt; ohne Stütze/außer Reichweite → Luft; unendliche Wasserquelle bei ≥2
Quell-Nachbarn + kein Fall nach unten), 3) Abfluss nach unten hat Vorrang (Ausnahme: ≥3
Quell-Nachbarn breiten sich trotzdem seitwärts aus), 4) horizontale Ausbreitung mit
**Gefälle-Suche** (minSlope: kürzeste Distanz zu einer tatsächlich abfließbaren Zelle darunter;
Suchtiefe Wasser 4 / Lava 2; nur Richtungen mit minimalem Wert fließen).

Nicht-offensichtliche Invarianten:
- Eigenes **fließendes** Fluid zählt in der Gefälle-Suche als passierbar UND als Loch — hält den
  Fluss auf der etablierten Richtung; Quellen blockieren.
- Nicht-READY Chunks und nicht editierbare Positionen blockieren die Gefälle-Suche; die aktuelle
  Fluidzelle wird erneut geplant, damit sie nach dem Chunk-Load weiterfließen kann.
- Eine fallende Säule „stützt" Nachbarn nur dort, wo sie auf festem Boden aufkommt — sonst wird
  der Wasserfall in der Luft breiter. Fallende Zustände sind immer `LEVEL 0, FALLING true`.
- `canFluidReplace`: Luft immer; sonst nur nicht-solide Blöcke ohne Kollisionsform (Pflanzen —
  droppen ihr Item). Andere Fluids NIE ersetzen.
- Wird horizontale Lava schwächer, hat ihr Folgetick wie in Vanilla mit 75 % Wahrscheinlichkeit
  den vierfachen Basistakt (120 statt 30 Ticks).

## Wasser+Lava

- **Kontakt synchron** (`onNeighborUpdate`): Lava neben/unter Wasser → Quelle→Obsidian,
  fließend→Cobblestone; Lava fließt/fällt in Wasser → Stein an der Wasserposition. Jede
  erfolgreiche Konvertierung spielt genau einmal den positionsgebundenen Extinguish-/Fizz-Sound.
- **Ausbreitung tickt IMMER im eigenen Takt** (`fluid_tick`) — kein beschleunigtes Ticken neben
  dem Gegen-Fluid, sonst rast Wasser über ein Lavafeld und konvertiert alles instant statt
  sichtbar Ring für Ring.
- Eine leere Zelle zwischen Wasser und Lava reagiert nicht vorab. Das Fluid, das sie durch die
  normale Tick- und Gefälle-Logik zuerst erreicht, belegt sie; erst der dadurch entstandene echte
  Kontakt löst die passende Reaktion aus. So bleibt der Ausgang von Tick-Reihenfolge und
  Fließrichtung abhängig wie in Minecraft.

## Geometrie (`chunk/FluidGeometry`) & Physik

Fluid-Geometrie wird beim Meshen dynamisch gebaut (kein gebackenes Modell): Eckhöhen als
gewichtetes Mittel der Spalten (Quelle/fallend ≥ 0.8 zählt 10×, Luft zieht auf 0, solide zählen
nicht; Diagonale nur, wenn eine Kardinale Fluid ist). **Dafür braucht der Mesher die
Diagonal-Chunks** — sonst klaffen die vier Zellen einer Chunk-Ecke auseinander (Nachbar-Auflösung
über den geteilten `chunk/NeighborSampler`, siehe chunk-meshing). Top-Face bekommt
die Flow-Textur entlang der Fließrichtung rotiert (Vanilla `getFlow`-Formel — NICHT aus Eckhöhen
ableiten, die kippen neben Wänden ins Diagonale). Dieselbe Formel existiert bewusst dupliziert
Welt-basiert in `FluidBehavior.flowVector` (Entity-Strömung, `Entity`-Push) — beide synchron halten.
Die offiziellen Flow-Sprites bleiben 32×32-Animationsframes. Minecraft zeigt pro Block nur einen
effektiven 16×16-Ausschnitt: Top-UVs rotieren mit Radius 0,25 um (0,5/0,5), Seiten verwenden
U/V 0..0,5. Nicht wieder den ganzen Frame über einen Block spannen oder in 16px-Frames zerlegen.
Wasser wird per `WATER_TINT 0x4076E6` eingefärbt (Texturen sind grau), Lava neutral.
**Greedy-Kopplung:** flach-stille Quell-Tops merged der ChunkMesher in einem eigenen Pass 1.5
greedy zu großen TRANSLUCENT-Quads (`FluidGeometry.isMergeableFlatStillTop` + Markierung in
`mergedWaterTop`, damit `FluidGeometry.build` das Top auslässt — s. chunk-meshing); im LOD
meshen Fluid-Flächen vollständig greedy und rendern transluzent (s. lod-system).
Eimer: `BucketItem` + `GameContainer.handleBucket` (leerer Eimer nutzt einen fluid-bewussten
Raycast; der normale Raycast ignoriert Fluids). Unterwasser-Overlay: `renderFluidOverlay`.
Oberwelt-Audio läuft über den kosmetischen `BlockBehavior.animateTick`: nur fließendes,
nicht-fallendes Wasser gluckert; Lava spielt bei freier Oberfläche selten Ambient und Pop. Der
separate Zufallsgenerator darf die Simulation nicht beeinflussen. Assets: `liquid/water.ogg`,
`liquid/lava.ogg`, `liquid/lavapop.ogg`; Reaktionen verwenden `random/fizz.ogg`.

## Verifikation

Automatisierte Kernfälle stehen in `FluidBehaviorSimulationTest`: Reichweite, Kontakt-Reaktionen,
Quellbildung, Gefälle durch Quellen/Chunk-Grenzen, Fallzustand und Lava-Verzögerung. Zusätzlich
visuell prüfen: 1) Quelle auf Ebene → symmetrische 7-Block-Ausbreitung, 2) Wasserfall → Säule bleibt
schmal, Pfütze am Fuß, 3) Wasser trifft Lavasee → sichtbare schrittweise Konvertierung, 4)
Cobble-Generator reagiert erst bei tatsächlichem Kontakt, 5) Eimer nimmt nur Quellen (LEVEL 0,
nicht fallend), 6) Wasser-/Lava-Ambience und genau ein Fizz pro Reaktion sind räumlich hörbar.
Ohne Fenster den visuellen und akustischen Teil als ungetestet kennzeichnen.
