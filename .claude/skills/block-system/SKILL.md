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
  cullSame, ticksRandomly, hasBlockEntity, noLodSurface — Block-JSON `"no_lod_surface": true`
  schließt einen Block als LOD-Terrain-Oberfläche aus, gesetzt auf den Weltgen-Logs) und Modelle
  gebacken. Nach dem Bake ist die Registry eingefroren (`register` wirft).
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

- Die `textures`-Map in der Block-JSON IST die Texturquelle; die Block-JSON nennt mit
  `model`/`models` nur den Geometrie-Rumpf (seit 2026-07-26 — ältere Notizen behaupten das
  Gegenteil). Details und die Vorrang-Regel „Datei schlägt Block-Definition" im Skill
  block-modelle-und-texturen.
- Presets (`blocks/preset/*.json`, via `parent`) gelten für ALLE Kinder — ein Feld dorthin zu
  ziehen, das nur ein Teil der Blöcke hatte, ändert die anderen still mit.
- Eigene Properties aus der JSON (`"properties": {...}`) laufen über `JsonProperties`: Werte sind
  Strings, Namen werden **interniert** (Property vergleicht per Identität) und Namen, die
  `Properties` schon belegt (`facing`, `type`, `half`, `axis`, …), werden abgelehnt — sonst
  griffe `BlockStateCodec` beim Weltladen auf das falsche Property-Objekt zu.
- `state.with(prop, value)` wirft bei ungültigem Wert (Lookup über die vorgebauten Kombinationen).
- `Biomes` fängt `Blocks.*`-IDs beim Klassen-Init ein → `Biomes` darf erst NACH
  `Blocks.bootstrap` berührt werden (nie aus einem Generator-Konstruktor; World wird vor dem
  Bootstrap erzeugt!).

## Verifikation

- `./gradlew compileJava`, dann `./gradlew saveTest` (fensterlos, bootstrappt die Registry ohne
  GL): Log zeigt „N Block-Definitionen geladen" und „BlockRegistry gebaked: X Blöcke, Y States" —
  Y-Sprünge nach Property-Änderungen plausibilisieren. Der Round-Trip prüft zusätzlich, dass
  Properties die Persistenz überleben (`BlockStateCodec`).
- Neuer Block unsichtbar? Es fehlt `model`/`models` in der Block-JSON *und* eine gleichnamige
  Datei `game/models/block/<id>.json` — eines von beidem muss die Geometrie liefern.
- Warnung „Block nicht gefunden, Fallback auf Luft" beim Start = Blocks.*-Konstante referenziert
  eine nicht (mehr) existierende JSON.
- Platzierbarkeit/Interaktion nur im laufenden Spiel prüfbar; Startinventar füllt
  `GameContainer.fillStartInventory` (dort Testblöcke eintragen).
