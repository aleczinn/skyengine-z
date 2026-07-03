# SkyEngine-Z

Java-Voxel-Engine auf Basis von **LWJGL** — eine eigenständige Version eines Minecraft-Clones.
Der Fokus liegt aktuell auf der **Technik/Engine**, nicht auf einem eigenen Look. Es werden
bewusst viele Original-Minecraft-Texturen verwendet (Platzhalter, bis ein eigener Stil kommt) —
**das ist Absicht, kein Fehler.** Ändere Texturen oder Look nicht ungefragt.

---

## Arbeitsanweisungen (verbindlich, gelten immer)

1. **Fragen statt annehmen.** Wenn etwas unklar ist, frage nach, bevor du auch nur eine Zeile
   schreibst. Gehe niemals stillschweigend von bestimmten Absichten, einer bestimmten Architektur
   oder bestimmten Anforderungen aus.
2. **Zuerst die einfachste Lösung.** Implementiere immer die einfachste Lösung, die funktionieren
   könnte. Füge keine Abstraktionen, Konfigurierbarkeit oder Flexibilität hinzu, die nicht
   ausdrücklich gefordert wurden (kein "für später" vorbauen).
3. **Fasse nicht zusammenhängenden Code nicht an.** Wenn eine Datei oder Funktion nicht direkt Teil
   der aktuellen Aufgabe ist, ändere sie nicht — auch wenn du sie verbessern könntest. Kein
   Drive-by-Refactoring, kein Umformatieren, keine Importsortierung in fremden Dateien.
4. **Unsicherheiten ausdrücklich benennen.** Wenn du dir bei einem Ansatz oder technischen Detail
   nicht sicher bist, sage das, bevor du fortfährst. Selbstsicherheit ohne Gewissheit richtet mehr
   Schaden an als das Eingestehen einer Wissenslücke.
5. Achte bei der Entwicklung stets auf Wartbarkeit und Performance, sodass neue Anforderungen nicht unnötig komplex implementiert werden

Zusätzlich:
- **Sprache:** Code-Kommentare, Logs und Commit-Nachrichten sind auf **Deutsch** (siehe bestehende
  Dateien). Halte dich an diesen Stil.
- **Scope klein halten:** Lieber ein kleiner, korrekter Diff als ein großer "Verbesserungs"-Diff.
- Bei mehrdeutigen Aufgaben lieber kurz rückfragen, als die teurere Annahme zu treffen.

---

## Tech-Stack & Build

- **Java 25** (Gradle Toolchain), Build-Tool **Gradle** (Kotlin DSL, Wrapper vorhanden).
- **LWJGL 3.4.1** (GLFW, OpenGL, OpenAL, STB) — Natives: `natives-windows` (Projekt ist
  Windows-zentriert).
- **JOML 1.10.9** (Mathe: Vektoren/Matrizen), **GSON 2.14** (JSON-Laden von Blöcken/Modellen).
- Einstiegspunkt: `de.skyengine.DesktopLauncher` → konfiguriert `EngineConfig` → `new SkyEngine(config).launch()`.

**Befehle** (aus dem Projekt-Root):

```bash
./gradlew run     # Engine starten
./gradlew build   # Bauen
./gradlew compileJava   # Nur kompilieren (schneller Check)
```

> Es gibt aktuell **keine Tests** und **keine Test-Infrastruktur**. "Funktioniert" heißt: kompiliert
> sauber und läuft. Vieles (besonders Rendering) ist nur **visuell** verifizierbar — wenn etwas nicht
> ohne laufendes Fenster prüfbar ist, sag das offen, statt es als verifiziert auszugeben.

---

## Laufzeit-Architektur (wichtig vor Render-/Input-Änderungen)

Die Engine ist **NICHT single-threaded**. `SkyEngine.launch()` startet zwei Threads:

- **"Render Thread"** (`gameLoop`): treibt `input.update()` → `onUpdate` (Tick) → `onRender`. Hier
  läuft der OpenGL-Kontext und die gesamte Spiel-/Render-Logik.
- **"Window-Processing Thread"** (Main): `runWindowProcessLoop` mit `glfwWaitEvents()` und
  abgearbeiteter `mainThreadTasks`-Queue.

**Regel (häufige Falle):** GLFW-Cursor-/Fenster-Funktionen (z.B. `glfwSetInputMode`,
Fenstermodus-Wechsel) MÜSSEN auf dem **Main-Thread** laufen. Verschiebe solche Aufrufe über
`SkyEngine.get().addTaskToMainThread(...)` (weckt via `glfwPostEmptyEvent`). `Input.disableCursor()`/
`showCursor()`/`centerMouse()` und der Fullscreen-Toggle tun das bereits — dem Muster folgen.

**Game-Loop:** feste **20 TPS** (`onUpdate`), entkoppeltes Rendering mit `partialTick`-Interpolation.

**Reversed-Z (Falle):** Bei `isUseInverseDepth()` nutzt die Engine `glClipControl(ZERO_TO_ONE)` +
`glDepthFunc(GL_GREATER)` + clearDepth 0 (nah ≈ 1, fern ≈ 0). Tiefen-/Depth-Func-Logik muss diesen
Modus berücksichtigen — nicht blind `GL_LESS`/`GL_LEQUAL` annehmen.

---

## Paketstruktur (`src/main/java/de/skyengine/`)

- `core/` — Engine-Kern: `SkyEngine`, `Window`, `EngineConfig`, `Input`, `Files`, `settings/GameSettings`.
- `game/` — Spiel-Logik:
  - `game/world/` — `World`, `chunk/` (Chunk, ChunkSection, `palette/` PalettedContainer/BitStorage), Generator, `tick/`.
  - `game/world/block/` — **Block-System** (siehe unten): `archetype/`, `behavior/`, `state/`,
    `model/`, `json/`, `entity/` (BlockEntities + Capabilities), `connection/`, `shape/`, `registry/`.
  - `game/world/item/` — `Item`, `BlockItem`, `ItemStack`, `Items`.
  - `game/entity/` — `Entity`, `EntityPlayer`. `game/physics/` — `AABB`.
  - `game/GameContainer` — verdrahtet Welt, Spieler, Kamera, Input-Handling, GUI, Block-Interaktion.
- `graphics/` — Rendering: `world/` (ChunkRenderer, SelectionBoxRenderer), `gui/` (GuiManager, Hud,
  Screen/ChestScreen, SpriteRenderer, ItemIconRenderer), `blockentity/` (BER + ChestRenderer),
  `shader/`, `texture/` (inkl. TextureArray, animierte Sprites), `camera/`, `framebuffer/`, `color/`.
- `utils/` — Logging (`Log`/`LogManager`/`Logger`), Mathe (`FastNoiseLite`, FBM), Profiler.

> **Shader** liegen **nicht** als `.glsl`-Dateien vor, sondern als **Inline-GLSL-Strings** in den
> jeweiligen Renderer-Klassen (`ChunkRenderer`, `SpriteRenderer`, `ItemIconRenderer`,
> `ChestRenderer`, `SelectionBoxRenderer`).

---

## Block-System (datengetrieben)

Die Architektur gilt als reif — **kein Rewrite**, neue Features sind additive Primitive.

- **Definition:** Jeder Block ist eine JSON-Datei in `src/main/resources/game/blocks/<id>.json`
  (`id`, `type`/`archetype`, `layer`, optional `textures`, `variants`, `gravity`, `facing`,
  `inventory_model`/`icon_flat` …). Geladen von `BlockLoader.load(...)` (deterministisch sortiert →
  stabile Runtime-IDs).
- **Archetypen** (`archetype/Archetypes`): `cube`, `slab`, `stairs`, `fence`, `pane`, `door`,
  `pillar`, `cross`, `fluid`, `custom` … = datengetriebene Block-Fabriken. Unbekannter Typ fällt auf
  `JsonBlock` zurück.
- **Verhalten** über Komposition: `behavior/BlockBehavior` (Hooks `canPlace`/`onPlaced`/`onBreak`/
  `onUse`/`scheduledTick`/`randomTick`). Beispiele: `GravityBehavior` (fallender Sand via
  `"gravity": true`), `HorizontalFacingBehavior` (`"facing": true`), Door/Slab/Stairs-Behaviors.
- **BlockEntities & Capabilities** (`entity/`): `BlockEntity`, `Capability`, `ItemStorage`/
  `EnergyStorage` (z.B. `ChestBlockEntity`, `CableBlockEntity` für Energie-Netz).
- **State-Storage:** `state/BlockState` + `palette/PalettedContainer`. Globales 65536-State-Limit in
  `BlockRegistry.bake` (heute kein Problem; `getState` maskiert mit `& 0xFFFF`).

### Modell-/Textur-Auflösung — bekannte Stolpersteine

- Ein **Cube-/Archetyp-Block braucht eine `models/block/<id>.json`**. Fehlt sie, ist der Block
  **unsichtbar** (keine Geometrie). Texturen kommen aus der **Modell-Datei** (Minecraft-Stil:
  `parent` + `#ref`-Auflösung über `ModelLoader`).
- Die `textures`-Map in der **Block-JSON** (`game/blocks/<id>.json`) wird für **Archetyp-Blöcke
  NICHT** fürs Rendern gelesen — nur der `JsonBlock`-Fallback nutzt sie. Ein Edit dort bei einem
  Cube-Block bewirkt **nichts** (häufige Verwirrungsquelle).
- `BlockTextures.layerOf` ist `computeIfAbsent` → fügt unbekannte Pfade an (out-of-range-Gefahr).
  **Layer nie raten** — immer aus dem real gebackenen Block nehmen.

### Mod-/Content-Strategie

Daten (JSON) **+** saubere Java-Registrierungs-API via `ContentSource`. **Kein** Forge/Fabric-
Classloader, **kein** rein deklaratives JSON (JSON kann kein Verhalten ausdrücken).

---

## Fluid-System (Wasser/Lava)

Logik in `behavior/FluidBehavior` (Scheduled-Tick-basiert), Parameter aus `FluidInfo`
(JSON-Felder `fluid_spread`, `drop_off`, `fluid_tick` in `game/blocks/water.json`/`lava.json`).

- **LEVEL-Konvention ist INVERS zu Vanilla:** `LEVEL 0` = Quelle (stärkstes Fluid), höhere Werte =
  schwächer; pro horizontalem Block kommt `dropOff` dazu, `LEVEL > spread` trocknet aus.
  `FALLING = true` = fallende Säule (zählt effektiv als Level 0). Wasser: dropOff 1 → 7 Blöcke
  Reichweite; Lava: dropOff 2 → 3 Blöcke.
- **Wasser+Lava-Reaktion:** Bei direktem Kontakt (Nachbar-Update) Lavaquelle→Obsidian, fließende
  Lava→Cobblestone, Lava fließt/fällt in Wasser→Stein. Zusätzlich die **Hohlraum-Regel mit
  Druck-Bedingung**: Eine Luftzelle horizontal zwischen Wasser und Lava wird (im Lava-Takt) zu
  Cobblestone, aber **nur**, wenn mindestens eines der beiden Fluids sie reichweitenmäßig noch
  erreichen könnte (`effLevel + dropOff <= spread`, „Druck") — unabhängig davon, wohin die
  Gefälle-Suche (minSlope) den Fluss real umlenkt. Enden **beide** Fluids mit maximaler
  Reichweite an der Lücke → **kein** Cobble (Vanilla-„Druck"-Regel). Beides ist Absicht — weder
  die Druck-Bedingung entfernen noch Reaktionen an bloße Nachbarschaft ohne Druck koppeln.
  Fluids fließen selbst nie in Misch-Zellen (Zellen, die ans Gegen-Fluid grenzen).
- Fluid-Verhalten ist **nur visuell** verifizierbar (Engine-Fenster nötig).

---

## Ressourcen (`src/main/resources/`)

- `game/blocks/` — Block-Definitionen (JSON).
- `game/models/block/` — Block-Modelle (MC-Stil, mit `parent`).
- `game/textures/` — Texturen (großteils Minecraft-Assets: `block/`, `gui/`, `entity/` …).
- `engine/logo/` — Fenster-Icons.

---

## Gotchas-Checkliste (vor dem Loslegen kurz durchgehen)

- GLFW-Cursor/Fenster-Calls → **Main-Thread-Queue**, nie direkt aus dem Render-Thread.
- Reversed-Z beachten, wenn du an Depth-Test/Outline/Bias arbeitest.
- Neuer sichtbarer Block → **`models/block/<id>.json` nicht vergessen**.
- Archetyp-Block-Texturen gehören ins **Modell**, nicht in die Block-JSON.
- Rendering-Änderungen sind **nur visuell** verifizierbar — als ungetestet kennzeichnen, wenn das Fenster nicht lief.
