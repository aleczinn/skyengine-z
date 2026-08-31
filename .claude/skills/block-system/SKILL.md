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
  cullSame, ticksRandomly, hasBlockEntity, leaves; dazu Licht-Opazität in Bits
  10-13 und Luminanz in Bits 14-17 — s. Skill `licht-system`) und Modelle
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

## Zwei Blöcke, die zusammengehören (Doppeltruhe)

Es gibt **drei** Muster, und sie lösen verschiedene Probleme — das falsche zu greifen ist der
häufigste Fehler hier:

- **`parts` / `PartsBehavior`** = EIN Block belegt mehrere Zellen (Tür, hohe Pflanze). Er verlangt
  in `canPlace`, dass die Geschwisterzellen **Luft** sind, **setzt** sie in `onPlaced` selbst und
  **löscht sich ersatzlos**, sobald ein Teil fehlt.
- **`MultiblockPattern`** = rein lesende Musterprüfung über FREMDE Blöcke, setzt nur ein
  `formed`-Flag am Controller. Kennt keine Rollen und keine Richtungsvarianten.
- **Doppeltruhe** = zwei **eigenständige** Blöcke mit je eigenem BlockEntity und Inventar, die sich
  nur über `facing` + `type` (single/left/right) finden. Genau deshalb passt keines der ersten
  beiden: die Nachbarzelle existiert ja schon (mit Inhalt!), und beim Trennen muss die andere
  Hälfte **stehen bleiben**.

Der Doppeltruhen-Mechanismus (`ChestBehavior`, nach MCs `ChestBlock`): `onPlace` wählt die eigene
Rolle, `onNeighborUpdate` rechnet sie neu — Partner weg → `SINGLE`, und eine Einzeltruhe wird zum
Gegenstück, sobald ein Nachbar mit passendem `facing` **auf sie zeigt**. Das ist der Weg, auf dem
die ZUERST gesetzte Truhe von der neuen erfährt. Verbunden wird nur mit `type == SINGLE`-Nachbarn,
sonst entstünden Dreierketten. Dass ein Verschmelzen das Inventar nicht anfasst, liegt an
`manageBlockEntity` (nächster Abschnitt): reiner State-Wechsel behält die BlockEntity.

`PlacementContext.sneaking` ist MCs „secondary use" und hat **zwei** Rollen (beide am Spiel
geprüft): beim normalen Platzieren neben einer Truhe **verhindert** es das Verschmelzen — das ist
der einzige Weg, zwei Einzeltruhen nebeneinander zu stellen. Ein sneakender Klick auf die **Seite**
einer Truhe verbindet dagegen **trotzdem** und übernimmt deren Ausrichtung. Damit man überhaupt an
die Seite bauen kann, überspringt der `GameContainer` beim Sneaken mit einem Block in der Hand die
Rechtsklick-Interaktion — sonst öffnete der Klick nur das GUI.

Die Quelle für „sneakt gerade" ist `EntityPlayer.isSecondaryUseActive()`, **nicht** `isSneaking()`:
Letzteres ist `!flying && sneakActive` und im Kreativflug immer false — Platzierungsregeln wären
dort sonst unerreichbar.

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
- Materialwerte je Block (nie ins Preset — `preset/cube` bedient stone, dirt UND wool): `hardness`,
  `tool`, `harvest_tier`, `resistance` (Explosion; ohne Feld gilt `hardness`, dadurch erbt Bedrock
  seine −1 und bleibt Strahlenstopper) sowie `friction`/`speed_factor`/`jump_factor` (Bewegung,
  Defaults 0.6/1.0/1.0) und `bounciness`/`fall_damage_factor` (Landung, Defaults 0/1.0). Die
  Auflösung von `resistance` passiert in `ArchetypeBlockFactory`, NICHT im `BlockConfig`-Default —
  sonst ginge der Bedrock-Fallback verloren.
- Entity↔Block-Werte werden **nie** über `Block.getBehavior(Class)` gelesen: das ist eine lineare
  Schleife mit `Class.isInstance` und damit die falsche Ebene für einen Pro-Tick-Pro-Entity-Pfad.
  Muster ist `Blocks.getState(id).getBlock().getX()` (Array-Index + Feld-Read) — so machen es die
  Strömung (`Entity.pushOutOfFluids`), die Reibung und der Abpraller (`Entity.move`).

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
