# SkyEngine-Z — Architektur-Überblick & Einstieg

Java-25-Voxel-Engine (LWJGL 3.4.1, OpenGL 4.6, JOML, GSON) — ein eigenständiger Minecraft-Clone
mit Fokus auf **Engine-Technik**. Minecraft-Texturen sind bewusste Platzhalter — nicht ändern.
**Die verbindlichen Arbeitsregeln stehen in `.claude/CLAUDE.md` und gelten immer** (fragen statt
annehmen, einfachste Lösung, kein Drive-by-Refactoring, Unsicherheiten benennen, Deutsch in
Kommentaren/Logs/Commits).

## Build & Verifikation

```bash
./gradlew compileJava   # schneller Pflicht-Check
./gradlew run           # Engine starten (einzige Verifikation für alles Sichtbare)
./gradlew build
```

Es gibt **keine Tests**. „Funktioniert" = kompiliert UND (bei sichtbarem Verhalten) im laufenden
Fenster geprüft — sonst als „visuell ungetestet" ausweisen. Weltgen lässt sich ohne Fenster über
`GeneratorMapExporter` (PNG-Karten nach `debug-maps/`) prüfen. Details + Debug-Hotkeys:
Skill `visuelle-verifikation`.

## Architektur in einem Absatz

`DesktopLauncher` → `SkyEngine.launch()` startet zwei Threads: den **Render Thread** (GL-Kontext,
20-TPS-Tick UND Rendering, `GameContainer` → `World`) und den **Main/Window-Thread** (nur
GLFW-Events + `mainThreadTasks`). Chunks (32×512×32, 16 Sections à 32³, Palette-komprimiert)
laufen durch die Status-Pipeline NEW→…→READY auf einem **Chunk-Worker-Pool** (Priority-Queue:
Edit-Remesh > Load > LOD). Worker meshen Sections (Greedy + AO, gepacktes 16-Byte-Vertex-Format)
und legen Batches in Upload-Queues; der `ChunkRenderer` zeichnet alles per **MultiDrawIndirect**
aus je einer `VertexArena` pro RenderLayer (OPAQUE/CUTOUT/TRANSLUCENT), plus Heightmap-**LOD**-
Regionen jenseits der Render-Distanz. Blöcke sind **datengetrieben** (JSON + Archetypen +
Behavior-Komposition, Registry-Bake vergibt Runtime-State-IDs). Die Welt kommt aus
`AlphaWorldGeneratorV2`: Klima-Felder → Höhe UND Biome, Worley-Seen, explizites
Quelle→Mündung-Flussnetz, Feature-Pass im Scheiben-Modell.

## Paketstruktur (Kurzfassung)

- `core/` — SkyEngine (Threads/Loop), Window, Input, EngineConfig, Files, settings/
- `game/world/chunk/` — Chunk, ChunkSection, palette/, ChunkManager, ChunkMesher, FluidGeometry
- `game/world/block/` — Block-System: archetype/, behavior/, state/, model/, json/, entity/
  (BlockEntities + Capabilities), connection/, shape/, registry/, multiblock/, network/
- `game/world/generator/` — WorldGenerator, generators/ (V2 + RiverNetwork), climate/, biome/,
  feature/ (ChunkDecorator, FeaturePlacer, trees/), debug/ (GeneratorMapExporter)
- `game/world/lod/` — LodManager, LodConfig, LodMesher, LodDataSource(+World/Generator-Impl)
- `game/world/item/`, `game/entity/`, `game/physics/`, `game/GameContainer` (Verdrahtung,
  Interaktion, Mining, Inventar)
- `graphics/` — world/ (ChunkRenderer, VertexArena, SectionMesh, LodMesh, MappedRing,
  SelectionBox-/CrackRenderer), blockentity/, entity/, gui/, shader/, texture/ (TextureArray,
  SpriteAnimations), camera/, framebuffer/
- `utils/` — Logging, FastNoiseLite/FBM, Profiler

**Shader sind Inline-GLSL-Strings** in den Renderer-Klassen, keine .glsl-Dateien.
Ressourcen: `game/blocks/*.json` (Definition + variants/inventory_model/icon),
`game/models/block/*.json` (Geometrie/Texturen), `game/textures/`.

## Skills — wann welche greift (`.claude/skills/`)

| Skill | Lesen wenn… |
|---|---|
| `threading-und-engine-loop` | …irgendetwas Threads, GLFW, GL-State, Game-Loop oder Depth/Reversed-Z berührt |
| `chunk-pipeline` | …ChunkManager/Status/setBlock/Remesh/Dekoration geändert wird |
| `chunk-meshing` | …ChunkMesher, Greedy, AO oder das Vertex-Format angefasst wird |
| `mdi-rendering` | …ChunkRenderer, VertexArena, Ringe/Fences, EBO oder Pass-Reihenfolge |
| `block-system` | …Blöcke/Properties/Behaviors/Registry angelegt oder geändert werden |
| `block-modelle-und-texturen` | …Modelle, Blockstates, Texturen, Icons, RenderLayer |
| `fluid-system` | …Wasser/Lava-Verhalten, -Geometrie, Eimer, Strömung |
| `lod-system` | …LodManager/LodMesher/LodConfig oder LOD-Anbindung im Renderer |
| `weltgen-v2` | …Weltgenerierung, Biome, Seen, Flüsse, Features, Noise-Seeds |
| `vegetation-tint` | …Gras-/Laubfärbung, Tint-Grids, Grasblock-Overlay |
| `visuelle-verifikation` | …IMMER bevor eine Änderung als „fertig" gemeldet wird |

## Aktueller Stand

**Fertig und stabil:**
- Chunk-Pipeline mit Nachbar-Gating, Prioritäts-Workern, Edit-Priority-Uploads
- Meshing: Greedy + Minecraft-AO (inkl. Flip + Shader-Clamp), 16-Byte-Vertices, Index-Buffer
- Rendering: MDI + VertexArena + Frame-Fences, TextureArray mit animierten Sprites,
  Translucent-Sortierung, BlockEntity-Renderer (Chest, EnchantingTable), Reversed-Z
- Block-System (Architektur gilt als **reif — kein Rewrite**): ~171 JSON-Blöcke, Archetypen,
  Behaviors, Verbindungen (Zaun/Pane/Cable), Türen, BlockEntities + Capabilities (Item/Energie)
- Fluids komplett (Fluss, Reaktionen, Eimer, Schwimmen/Strömung, Unterwasser-Overlay)
- Weltgen V2: Klima→Höhe/Biome, 3D-Dichte/Höhlen, Worley-Seen, Fluss-Netz Quelle→Mündung
  (~2,5 ms/Chunk), Feature-Bäume (Scheiben-Modell), Debug-Karten-Exporter
- LOD: formelbasierte Clipmap-Ringe, Chunk-Masken-Clipping, Skirts, Epoche/Hysterese
- Vegetations-Tint biome-abhängig (Eck-Grids + bilinear), koplanare Grasblock-Overlays
- Mining: MC-Harvest-Regel, 28 Tools (7 Tiers × 4 Typen), Durability, Crack-Overlay,
  Bedrock unzerstörbar; Gamemodes Survival/Creative/Spectator; Item-Entities + Aufsammeln

**Offen / geplant (bekannt, nicht angefangen):**
- Occlusion Culling (nächster Rendering-Schritt laut Roadmap)
- Welt-/Inventar-Persistenz (Speichern/Laden) und Crafting
- Controller-Support: `Input.isControllerButton*`/`getControllerAxis` sind TODO-Stubs
- TEMP-Testblöcke in `GameContainer.fillStartInventory` (als solche markiert)

**Bewusst nicht vorhanden (nicht „vergessen" — nicht ungefragt bauen):**
- **Kein Sky-Rendering** (keine Atmosphäre/Wolken/God-Rays — Clear-Color ist der Himmel)
- **Kein Lichtsystem** (keine Block-/Himmelslicht-Propagation; Helligkeit = Face-Brightness × AO)
- Kein Sound (OpenAL nur als Dependency), keine Mobs, kein Multiplayer, keine Test-Infrastruktur

## Wiederkehrende Fallen (Kurzliste — Details in den Skills)

- GLFW-Fenster/Cursor → nur via `addTaskToMainThread`; GL nur auf dem Render-Thread
- Chunks sind 32er: `ChunkSection.SHIFT/MASK` statt `>>4`/`&15`
- Chunks unter DECORATED nie lesen (Worker schreiben lock-frei) — Guards in World/processRemeshes
- Worker-Jobs: `execute` mit `PrioTask`, nie `submit`
- `BlockTextures.layerOf` nach dem TextureArray-Bau = kaputter Layer-Index
- Cube-Block ohne `models/block/<id>.json` = unsichtbar; Block-JSON-`textures` ist bei
  Archetyp-Blöcken wirkungslos
- Fluid-LEVEL ist invers zu Vanilla (0 = Quelle); Ausbreitung immer im eigenen Takt
- Seen dürfen nie von Flüssen abhängen (Cache-Rekursion); Generator-Funktionen müssen pur sein
- Reversed-Z: Depth-Funcs nie hartkodieren, or-equal-Mapping wie im ChunkRenderer
- Arena-Regionen/GL-Buffer nie sofort freigeben/löschen — Fence-geschützt bzw. neu-vor-alt

## Handoff-Hinweise

**Die 5 häufigsten Fehlerquellen in diesem Projekt (subsystemübergreifend):**

1. **„Kompiliert" mit „funktioniert" verwechseln.** Fast alles Sichtbare (Meshing, Rendering,
   Fluids, LOD, Tints) ist NUR im laufenden Fenster prüfbar. Ohne Fenster: ehrlich als
   „visuell ungetestet" ausweisen (Skill `visuelle-verifikation`).
2. **Minecraft-Wissen ungeprüft übertragen.** Dieses Projekt weicht bewusst ab: 32er-Chunks
   (nie `>>4`/`&15`), Fluid-LEVEL invers (0 = Quelle), eigene Entity-UV-Konvention, kein
   Licht-/Sky-System. Vor jeder „das ist wie in Minecraft"-Annahme den passenden Skill lesen.
3. **Thread-Kontext ignorieren.** GL nur auf dem Render-Thread, GLFW-Fenster/Cursor nur auf dem
   Main-Thread, Worker arbeiten lock-frei nach festen Regeln (DECORATED-Lese-Grenze, Fences,
   deferred frees). Verstöße crashen nicht, sondern erzeugen sporadische, nicht reproduzierbare
   Fehler — die teuerste Bug-Klasse hier.
4. **Schutzmechanismen als „überflüssig" entfernen.** AO-Shader-Clamp, `withWater=false`,
   Fluid-Druck-Bedingung, `seq`-Tiebreaker, neu-vor-alt bei GL-Buffern, or-equal-Depth,
   koplanare Overlays: alles Fixes für real beobachtete, teuer gefundene Bugs. Sieht etwas
   redundant aus → zuständigen Skill lesen, dann fragen — nie einfach löschen.
5. **An der wirkungslosen Stelle editieren.** `textures` in der Block-JSON (Archetyp-Blöcke),
   geratene Textur-Layer, fehlende Modell-Datei, hartkodierte Depth-Funcs. Symptom „meine
   Änderung bewirkt nichts" heißt fast immer: falsche Stelle — nicht mehr Code nachschieben.

**Eskalationsregel — stoppen und den User fragen, wenn eine Änderung:**

- etwas anfassen müsste, das ein Skill mit „nie/NICHT/Absicht" markiert;
- die Threading-/Locking-Struktur, Status-Übergänge oder binäre Layouts ÄNDERN müsste
  (Vertex-Format, Arena/EBO, Palette, Seed-Offsets);
- die generierte Welt bestehender Seeds verändern würde (Weltgen bit-stabil halten —
  Beweis: `GeneratorMapExporter`-Karten vorher/nachher hashen);
- scheinbar toten Code löschen soll (Beispiel: `GeneratorLodDataSource` ist absichtlich da);
- auf einen Widerspruch zwischen Code und Skill-Doku stößt — dann ist eines von beiden
  falsch: melden, nicht stillschweigend „reparieren".

Kein Nachfragen nötig für additive Standard-Arbeit nach Skill-Muster (neuer Block/Behavior/
Feature, Bugfix mit klarer Ursache) — dort gelten die Arbeitsregeln aus `.claude/CLAUDE.md`.
