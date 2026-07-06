---
name: fluid-system
description: Wasser/Lava — FluidBehavior (Scheduled-Tick-Fluss, LEVEL-Konvention INVERS zu Vanilla, Wasser+Lava-Reaktionen mit Druck-Regel) und FluidGeometry (dynamische Eckhöhen-Meshes). Lesen vor JEDER Änderung an Fluid-Verhalten, -Rendering, Eimern oder Schwimm-/Strömungsphysik.
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

Reihenfolge im Tick: 1) Wasser+Lava-Reaktion, 2) Hohlraum-Regel, 3) eigenen Stand aus Nachbarn
ableiten (Quelle bleibt; ohne Stütze/außer Reichweite → Luft; unendliche Wasserquelle bei ≥2
Quell-Nachbarn + kein Fall nach unten), 4) Abfluss nach unten hat Vorrang (Ausnahme: ≥3
Quell-Nachbarn breiten sich trotzdem seitwärts aus), 5) horizontale Ausbreitung mit
**Gefälle-Suche** (minSlope: kürzeste Distanz zu einem „Loch" = nicht-solider Block darunter;
Suchtiefe Wasser 4 / Lava 2; nur Richtungen mit minimalem Wert fließen).

Nicht-offensichtliche Invarianten:
- Eigenes **fließendes** Fluid zählt in der Gefälle-Suche als passierbar UND als Loch — hält den
  Fluss auf der etablierten Richtung; Quellen blockieren.
- Eine fallende Säule „stützt" Nachbarn nur dort, wo sie auf festem Boden aufkommt — sonst wird
  der Wasserfall in der Luft breiter.
- `canFluidReplace`: Luft immer; sonst nur nicht-solide Blöcke ohne Kollisionsform (Pflanzen —
  droppen ihr Item). Andere Fluids NIE ersetzen.

## Wasser+Lava (Absicht, mehrfach abgestimmt — nicht ändern)

- **Kontakt synchron** (`onNeighborUpdate`): Lava neben/unter Wasser → Quelle→Obsidian,
  fließend→Cobblestone; Lava fließt/fällt in Wasser → Stein an der Wasserposition.
- **Ausbreitung tickt IMMER im eigenen Takt** (`fluid_tick`) — kein beschleunigtes Ticken neben
  dem Gegen-Fluid, sonst rast Wasser über ein Lavafeld und konvertiert alles instant statt
  sichtbar Ring für Ring.
- **Hohlraum-Regel mit Druck-Bedingung:** Luftzelle horizontal zwischen Wasser und Lava wird (im
  Lava-Takt) zu Cobblestone, aber NUR wenn mindestens ein Fluid sie reichweitenmäßig noch
  erreichen könnte (`hasPressure`: effLevel + dropOff ≤ spread) — unabhängig davon, wohin minSlope
  real lenkt. Enden BEIDE Fluids mit maximaler Reichweite an der Lücke → kein Cobble.
  Weder die Druck-Bedingung entfernen noch Reaktionen an bloße Nachbarschaft koppeln.
- Fluids fließen selbst nie in **Misch-Zellen** (Zellen, die horizontal ans Gegen-Fluid grenzen) —
  dort erzeugt die Hohlraum-Regel den Cobble.

## Geometrie (`chunk/FluidGeometry`) & Physik

Fluid-Geometrie wird beim Meshen dynamisch gebaut (kein gebackenes Modell): Eckhöhen als
gewichtetes Mittel der Spalten (Quelle/fallend ≥ 0.8 zählt 10×, Luft zieht auf 0, solide zählen
nicht; Diagonale nur, wenn eine Kardinale Fluid ist). **Dafür braucht der Mesher die
Diagonal-Chunks** — sonst klaffen die vier Zellen einer Chunk-Ecke auseinander. Top-Face bekommt
die Flow-Textur entlang der Fließrichtung rotiert (Vanilla `getFlow`-Formel — NICHT aus Eckhöhen
ableiten, die kippen neben Wänden ins Diagonale). Dieselbe Formel existiert bewusst dupliziert
Welt-basiert in `FluidBehavior.flowVector` (Entity-Strömung, `Entity`-Push) — beide synchron halten.
Wasser wird per `WATER_TINT 0x4076E6` eingefärbt (Texturen sind grau), Lava neutral.
Eimer: `BucketItem` + `GameContainer.handleBucket` (leerer Eimer nutzt einen fluid-bewussten
Raycast; der normale Raycast ignoriert Fluids). Unterwasser-Overlay: `renderFluidOverlay`.

## Verifikation

Fluid-Verhalten ist **nur visuell** verifizierbar (Engine-Fenster nötig). Prüfszenarien nach
Änderungen: 1) Quelle auf Ebene → symmetrische 7-Block-Ausbreitung, 2) Wasserfall → Säule bleibt
schmal, Pfütze am Fuß, 3) Wasser trifft Lavasee → Ring-für-Ring-Konvertierung im Lava-Takt (nicht
instant), 4) Cobble-Generator (Lücke zwischen Quellen) funktioniert, aber zwei Fluids am
Reichweiten-Ende erzeugen KEINEN Cobble, 5) Eimer nimmt nur Quellen (LEVEL 0, nicht fallend).
Ohne Fenster: als ungetestet kennzeichnen.
