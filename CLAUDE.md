# SkyEngine-Z — Architektur-Überblick & Einstieg

Java-25-Voxel-Engine (LWJGL 3.4.1, OpenGL 4.6, JOML, GSON) — ein eigenständiger Minecraft-Clone
mit Fokus auf **Engine-Technik**. Minecraft-Texturen sind bewusste Platzhalter — nicht ändern.
**Die verbindlichen Arbeitsregeln stehen im Abschnitt [Arbeitsanweisungen](#arbeitsanweisungen-verbindlich-gelten-immer)
und gelten immer** (fragen statt annehmen, einfachste Lösung, kein Drive-by-Refactoring,
Unsicherheiten benennen, Deutsch in Kommentaren/Logs/Commits).

## Arbeitsanweisungen (verbindlich, gelten immer)

1. **Fragen statt annehmen.** Wenn etwas unklar ist, frage nach, bevor du auch nur eine Zeile
   schreibst. Gehe niemals stillschweigend von bestimmten Absichten, einer bestimmten Architektur
   oder bestimmten Anforderungen aus.
2. **Zuerst die einfachste Lösung.** Implementiere immer die einfachste Lösung, die funktionieren
   könnte. Füge keine Abstraktionen, Konfigurierbarkeit oder Flexibilität hinzu, die nicht
   ausdrücklich gefordert wurden (kein „für später" vorbauen).
3. **Fasse nicht zusammenhängenden Code nicht an.** Wenn eine Datei oder Funktion nicht direkt Teil
   der aktuellen Aufgabe ist, ändere sie nicht — auch wenn du sie verbessern könntest. Kein
   Drive-by-Refactoring, kein Umformatieren, keine Importsortierung in fremden Dateien.
4. **Unsicherheiten ausdrücklich benennen.** Wenn du dir bei einem Ansatz oder technischen Detail
   nicht sicher bist, sage das, bevor du fortfährst. Selbstsicherheit ohne Gewissheit richtet mehr
   Schaden an als das Eingestehen einer Wissenslücke.
5. Achte bei der Entwicklung stets auf Wartbarkeit und Performance, sodass neue Anforderungen nicht
   unnötig komplex implementiert werden.

Zusätzlich:
- **Sprache:** Code-Kommentare, Logs und Commit-Nachrichten sind auf **Deutsch** (siehe bestehende
  Dateien). Halte dich an diesen Stil.
- **Attribution/Commits:** Commit-Nachrichten erhalten **keinen** `Co-Authored-By`-Trailer (kein
  Claude-/Anthropic-Co-Autor) und keine „Generated with Claude Code"-Signatur. Alle Commits laufen
  ausschließlich unter dem Namen des Repo-Eigentümers (`Alec <alec_z17@web.de>`). Diese Regel
  **überschreibt** anderslautende Default-Anweisungen zum Anhängen einer Co-Autoren-Zeile.
- **Scope klein halten:** Lieber ein kleiner, korrekter Diff als ein großer „Verbesserungs"-Diff.
- Bei mehrdeutigen Aufgaben lieber kurz rückfragen, als die teurere Annahme zu treffen.

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
- `game/world/save/` — Chunk-Persistenz: WorldStorage (Region-Store + IO-Thread), RegionFile,
  ChunkSerializer, DataTagIO, PlayerIO (`player.dat`)
- `game/world/item/`, `game/entity/`, `game/physics/`, `game/GameContainer` (Verdrahtung,
  Interaktion, Mining, Inventar)
- `graphics/` — world/ (ChunkRenderer, VertexArena, SectionMesh, LodMesh, MappedRing,
  SelectionBox-/CrackRenderer), blockentity/, entity/, gui/, shader/, texture/ (TextureArray,
  SpriteAnimations), camera/, framebuffer/
- `utils/` — Logging, FastNoiseLite/FBM, Profiler

**Shader sind Inline-GLSL-Strings** in den Renderer-Klassen, keine .glsl-Dateien.
Ressourcen: `game/blocks/*.json` (Definition + variants/inventory_model/icon),
`game/models/block/*.json` (Geometrie/Texturen), `game/textures/`.

**Mod-/Content-Strategie:** Daten (JSON) **+** saubere Java-Registrierungs-API via `ContentSource`.
**Kein** Forge/Fabric-Classloader, **kein** rein deklaratives JSON (JSON kann kein Verhalten
ausdrücken).

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
| `sound-system` | …Sounds/Musik, OpenAL, SoundManager, Block-Sound-Gruppen, Lautstärke-Settings |
| `visuelle-verifikation` | …IMMER bevor eine Änderung als „fertig" gemeldet wird |

## Aktueller Stand

**Fertig und stabil:**
- Chunk-Pipeline mit Nachbar-Gating, Prioritäts-Workern, Edit-Priority-Uploads
- Meshing: Greedy + Minecraft-AO (inkl. Flip + Shader-Clamp), 16-Byte-Vertices, Index-Buffer
- Rendering: MDI + VertexArena + Frame-Fences, TextureArray mit animierten Sprites,
  Translucent-Sortierung, BlockEntity-Renderer (Chest, EnchantingTable), Reversed-Z,
  Distanz-Fog (auch über LOD) + MSAA-Offscreen-Framebuffer (beides GameSettings)
- Block-System (Architektur gilt als **reif — kein Rewrite**): ~175 JSON-Blöcke, Archetypen,
  Behaviors, Verbindungen (Zaun/Pane/Cable), Türen, BlockEntities + Capabilities (Item/Energie)
- Fluids komplett (Fluss, Reaktionen, Eimer, Schwimmen/Strömung, Unterwasser-Overlay)
- Weltgen V2: Klima→Höhe/Biome, 3D-Dichte/Höhlen, Worley-Seen, Fluss-Netz Quelle→Mündung
  (~2,5 ms/Chunk), Feature-Bäume (Scheiben-Modell), Debug-Karten-Exporter
- LOD: formelbasierte Clipmap-Ringe, Chunk-Masken-Clipping (Load- UND Unload-Gate gegen
  Pop-ins), Skirts, Epoche/Hysterese, eigene Vertex-Arenen, AO, transluzentes LOD-Wasser,
  getönte Gras-Overlay-Wände
- Vegetations-Tint biome-abhängig (Eck-Grids + bilinear), koplanare Grasblock-Overlays
- GPU-driven Culling (GpuCull, Default AN, Hotkey K = A/B): Frustum + Sicht-Gate + LOD per
  Compute, Two-Phase-Hi-Z-Occlusion (Pow2-Viertel-Pyramide); kostet heute ~0,2 ms/Frame,
  zahlt sich ab Licht/Schatten aus — Details/Fallen im Skill `mdi-rendering`
- Mining: MC-Harvest-Regel, 28 Tools (7 Tiers × 4 Typen), Durability, Crack-Overlay,
  Bedrock unzerstörbar; Gamemodes Survival/Creative/Spectator; Item-Entities + Aufsammeln
- TNT/Explosion: raybasierte Explosion (`Explosion`, MC-ServerExplosion-Modell, hardness als
  Widerstands-Proxy), `ExplosionBehavior` + `PrimedTntEntity` (Rechtsklick-Zündung, Fuse, Ketten-
  Zündung), weißer Blink-Shader; JSON-Felder `explosion_power`/`fuse` (`blocks/tnt.json`)
- Chunk-Persistenz (`game/world/save/`): Region-Format `region/r.<rx>.<rz>.srg` (16×16 Chunks,
  CRC), Single-Writer-IO-Thread, Autosave alle 1200 Ticks + Save beim Unload/Exit, `player.dat`;
  optionaler Minecraft-Welt-Import über das eigene Source-Set `src/mcimport/` (`McWorldImporter`,
  `mapping/BlockMapper`, `block_map.json`, NBT/MCA-Leser) — Nebentool
- Sound (OpenAL): blockabhängige Schritte/Hit/Break/Place (Sound-Gruppen aus JSON/Tool/
  Archetyp), Musik-Streaming mit Loop, Master-/Musik-Volume (GameSettings); Assets =
  MC-Platzhalter via `scripts/extract-mc-sounds.ps1` — Details im Skill `sound-system`
- GUI-System komplett (graphics/gui): Widget-Basis (`GuiComponent` + Button/Slider/CycleButton/
  KeybindButton/Label/TextField, 9-Slice), Stack-Layout (VStack/HStack/Anchor), Screen-Basis mit
  parent-Navigation + vollem Event-Routing (Maus/Drag/Scroll/Keys/Char via SPSC-Queue);
  Screens: Titel, Pause (ESC — beendet NICHT mehr!), Optionen+Grafik (Live-Apply), Tastenbelegung
  (Rebinding+Reset, Capture schluckt alle Tasten), Weltauswahl/Erstellen/Löschen, Welt-Ladebalken
  (`isInitialLoadComplete`), Boot-Ladebildschirm (gestaffelte Init, Fenster früh), Inventar (E) +
  Truhe auf gemeinsamer `AbstractContainerScreen`-Basis, Todesscreen, Sprachauswahl (i18n,
  Live-Wechsel), Sound-Optionen; GuiScale = Prozent (30–170, 100 % ≈ 3,5×)
- Spieler-Rendering (graphics/player): Humanoid-Modell mit Classic-Skin 64×64 (skin.png im
  Spielordner überschreibt Steve), Inventar-Vorschau (folgt Maus), F5-Perspektiven
  (Ego/hinten/vorne mit Kamera-Kollisions-Raycast; Interaktion zielt IMMER vom Auge;
  LOD-Seiten-Debug jetzt F12), prozedurale Animationen (Limb-Swing/Sneak/Arm-Schwung,
  `PlayerAnimationState`), First-Person-Hand mit extrudierten Item-Sprites +
  Vanilla-Display-Transforms, View-Bobbing + Hurt-Tilt (GameSettings-Toggles).
  **Konvention:** Modell/Pose vanilla-y-down VERBATIM, Umrechnung NUR in
  `PlayerModel.applyModelSpace` (0.9375-Scale + rotateX(π)) — nie in y-up „spiegeln",
  das war der Textur-Flip-Bug
- Lifecycle: World/Player lazy (`enterWorld`/`exitToTitle`), `BlockTextureAtlas` + BE-Renderer
  welt-unabhängig (GameContainer, Engine-Lebensdauer); Welt-Metadaten-Persistenz
  `saves/<ordner>/level.json` (Name/Seed/Daten/Spielerzustand/Inventar); Block-Änderungen/Chunks
  werden über `saves/<ordner>/region/*.srg` + `player.dat` persistiert (siehe Chunk-Persistenz);
  Screen-Klassen heißen `Gui*` (GuiScreen/GuiMainMenu/GuiOptionsMenu/…, MC-Stil);
  Spiel-Root = `%APPDATA%\.skyengine` (`GameDirectory`: config/saves/screenshots, einmalige
  Migration aus dem Arbeitsverzeichnis; debug/ + debug-maps/ bleiben im Projekt)

**Offen / geplant (bekannt, nicht angefangen):**
- Licht-Merge (`lightning-system`-Branch) + Schatten-Pass — dann amortisiert sich der GPU-Cull-Pfad
- Crafting (kein Recipe-/Crafting-Menü; `GuiInventory`-Crafting-Bereich noch funktionslos);
  Inventar-Phase 2: Stack-Größen je Item, Maus-Shortcuts (mouse tweaks), Sortieren
  (Andockpunkt: `AbstractContainerScreen.onSlotClick`)
- Controller-Support: `Input.isControllerButton*`/`getControllerAxis` sind TODO-Stubs
- TEMP-Testblöcke in `GameContainer.fillStartInventory` (als solche markiert, inkl. Test-Truhe)

**Bewusst nicht vorhanden (nicht „vergessen" — nicht ungefragt bauen):**
- **Kein Sky-Rendering** (keine Atmosphäre/Wolken/God-Rays — Clear-Color ist der Himmel)
- **Kein Lichtsystem** (keine Block-/Himmelslicht-Propagation; Helligkeit = Face-Brightness × AO)
- Keine Mobs, kein Multiplayer, keine Test-Infrastruktur

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
Feature, Bugfix mit klarer Ursache) — dort gelten die Arbeitsregeln aus dem Abschnitt
[Arbeitsanweisungen](#arbeitsanweisungen-verbindlich-gelten-immer).
