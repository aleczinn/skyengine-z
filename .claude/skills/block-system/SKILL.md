---
name: block-system
description: Datengetriebenes Block-System — JSON-Definitionen, Archetypen, Behavior-Komposition, BlockState-Kartesisches-Produkt, Registry-Bake mit Runtime-IDs und StateFlags, Platzierungs-Vertrag. Lesen vor dem Anlegen/Ändern von Blöcken, Properties, Behaviors oder Registry-Code.
---

# Block-System

## Aufbau: Daten + Komposition, KEINE Vererbungshierarchie

Ein Block entsteht aus `src/main/resources/game/blocks/<id>.json` → `BlockLoader` →
Archetyp (`Registries.BLOCK_ARCHETYPE`, z.B. cube/slab/stairs/fence/pane/door/pillar/cross/fluid/
custom) → `ArchetypeBlockFactory` füllt einen `BlockConfig` (Properties, Behaviors, ShapeProvider,
ModelGenerator, FluidInfo, Tint, Härte/Tool, ConnectionGroup, BlockEntityType) → generischer
`Block`. Unbekannter Typ fällt auf `JsonBlock` zurück. Verhalten kommt aus `behavior/BlockBehavior`-
Listen (Hooks: onPlace/canPlace/onPlaced/onNeighborUpdate/onUse/onBreak/scheduledTick/randomTick),
die den State transformieren. **Kein Rewrite, keine Subklassen-Kaskaden** — neue Features sind
additive Primitive (neues Behavior, neuer Archetyp, neues Property).

## States: kartesisches Produkt + Runtime-IDs

`Block.createStates()` bildet das kartesische Produkt ALLER Property-Werte — jedes zusätzliche
Property multipliziert die State-Zahl (LEVEL hat 16 Werte × FALLING 2 = 32 States pro Fluid).
Property-Konstanten leben zentral in `state/Properties` und werden **per Identität** verglichen —
niemals ein Property doppelt definieren.

`BlockRegistry.bake()` vergibt sequenzielle Runtime-IDs über alle States:
- **Luft MUSS zuerst registriert werden** (State-ID 0 = leer, Chunks sind default 0) — bake() wirft sonst.
- `BlockLoader` sortiert die JSON-Dateien nach Namen → **stabile IDs nur innerhalb einer Version**.
  IDs NIE persistieren (Kommentar an `BlockState.id`); dafür gibt es `BlockStateCodec`.
- Beim Bake werden Hot-Path-`StateFlags` gepackt (opaqueCube, solid, layer, fluid, randomOffset,
  cullSame, ticksRandomly, hasBlockEntity) und Modelle gebacken. Nach dem Bake ist die Registry
  eingefroren (`register` wirft).
- `statesById` ist volatile: gebaut auf dem Render-Thread, gelesen von Worker-Threads.

`Blocks.bootstrap(...)` ist die einzige korrekte Init-Reihenfolge: Luft → BlockEntities →
ContentSources → BlockLoader → ModelLoader → BlockStateModels → `bake()` → Items → Crack-Texturen.
Die `Blocks.*`-int-Konstanten sind **Default-State-IDs** (nicht Block-IDs).

## Platzierungs-Vertrag (Ordering ist entscheidend)

`World.placeBlock(x,y,z,state)`: 1) Block setzen OHNE Nachbar-Kaskade, 2) `onPlaced` (Mehrteil-
Logik, z.B. obere Türhälfte), 3) ERST DANN `updateNeighbors`. Wer die Reihenfolge ändert, lässt
z.B. die untere Türhälfte sich selbst entfernen, bevor die obere existiert.
`getPlacementState` berechnet erst den State durch alle Behaviors (`onPlace`), DANN das Veto
(`canPlace`) — null = Platzierung abgelehnt. `updateNeighbors` aktualisiert genau einen Ring
(4 horizontal + oben/unten für Pipes/Cables), keine Kaskade; Folge-Updates laufen mit
`updateNeighbors=false`.

## Ticking & BlockEntities

- Geplante Ticks: `World.scheduleTick` (Dedup pro Position) / `scheduleTickEarlier` (zieht späteren
  Tick vor — für Lava+Wasser-Reaktionen). Außerhalb der Simulations-Distanz werden fällige Ticks
  re-scheduled (+20) statt ausgeführt — Flüsse frieren ein und laufen beim Zurückkommen weiter.
- Random-Ticks: 3 Positionen pro nicht-leerer Section pro Tick, nur States mit `ticksRandomly()`;
  der ganze Pass entfällt, wenn kein Block das Flag hat (`hasRandomTickBlocks`).
- BlockEntities verwaltet `World.manageBlockEntity` beim setBlock: nur bei **Typwechsel** wird
  angelegt/entfernt — reine State-Änderungen (Verbindungen, Treppen-Ecken) behalten die BlockEntity.
- Jeder Nicht-Luft-, Nicht-Fluid-Block bekommt automatisch ein `BlockItem` (`Items.bootstrap`).
  Mining: MC-Harvest-Regel in `GameContainer.isHarvestable` (ToolType + Mindest-Tier), Härte < 0 =
  unzerstörbar (Bedrock), Härte 0 = instant.

## Fallstricke für schwächere Modelle

- Die `textures`-Map in der Block-JSON wird bei Archetyp-Blöcken **nicht** fürs Rendern gelesen —
  Texturen gehören ins Modell (siehe Skill block-modelle-und-texturen).
- `state.with(prop, value)` wirft bei ungültigem Wert (Lookup über die vorgebauten Kombinationen).
- `Biomes` fängt `Blocks.*`-IDs beim Klassen-Init ein → `Biomes` darf erst NACH
  `Blocks.bootstrap` berührt werden (nie aus einem Generator-Konstruktor; World wird vor dem
  Bootstrap erzeugt!).

## Verifikation

- `./gradlew compileJava`, dann `./gradlew run`: Log zeigt „N Block-Definitionen geladen" und
  „BlockRegistry gebaked: X Blöcke, Y States" — Y-Sprünge nach Property-Änderungen plausibilisieren.
- Neuer Block unsichtbar? Fast immer fehlt `game/models/block/<id>.json` (Cube braucht ein Modell).
- Warnung „Block nicht gefunden, Fallback auf Luft" beim Start = Blocks.*-Konstante referenziert
  eine nicht (mehr) existierende JSON.
- Platzierbarkeit/Interaktion nur im laufenden Spiel prüfbar; Startinventar füllt
  `GameContainer.fillStartInventory` (dort Testblöcke eintragen).
