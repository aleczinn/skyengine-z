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

./gradlew saveTest      # fensterlos: Block-Registry bootstrappen + Chunk-Round-Trip
./gradlew lightTest     # fensterlos: Himmelslicht-Ausbreitung (Heightmap, Flood, Naht, Vertex)
./gradlew meshTest      # fensterlos: deterministischer Mesher-Zensus (Quad-Zähler + Byte-Hash)
./gradlew mapExport     # fensterlos: Weltgen-Karten nach debug-maps/
```

Es gibt **keine Tests**. „Funktioniert" = kompiliert UND (bei sichtbarem Verhalten) im laufenden
Fenster geprüft — sonst als „visuell ungetestet" ausweisen. Details + Debug-Hotkeys:
Skill `visuelle-verifikation`.

Ohne Fenster prüfbar sind immerhin: Weltgen über `mapExport` (`GeneratorMapExporter`) und
**alles rund ums Laden von Blöcken/Modellen/Items** über `saveTest` (`SaveRoundTripTest`
bootstrappt die komplette Registry ohne GL). Die **Log-Zähler dieses Laufs sind das schärfste
billige Signal** — Anzahl Block-Definitionen/Modelle/Blockstates/Items, und `Modell fehlt`
bzw. `Variante ... fehlt` müssen **null** Treffer haben.

## Architektur in einem Absatz

`DesktopLauncher` → `SkyEngine.launch()` startet zwei Threads: den **Render Thread** (GL-Kontext,
20-TPS-Tick UND Rendering, `GameContainer` → `World`) und den **Main/Window-Thread** (nur
GLFW-Events + `mainThreadTasks`). Chunks (32×512×32, 16 Sections à 32³, Palette-komprimiert)
laufen durch die Status-Pipeline NEW→…→READY auf einem **Chunk-Worker-Pool** (Priority-Queue:
Edit-Remesh > Load > LOD). Worker meshen Sections (Greedy + AO, gepacktes 20-Byte-Vertex-Format)
und legen Batches in Upload-Queues; der `ChunkRenderer` zeichnet alles per **MultiDrawIndirect**
aus je einer `VertexArena` pro RenderLayer (OPAQUE/CUTOUT/TRANSLUCENT), plus Heightmap-**LOD**-
Regionen jenseits der Render-Distanz. Blöcke sind **datengetrieben** (JSON + Archetypen +
Behavior-Komposition, Registry-Bake vergibt Runtime-State-IDs). Die Welt kommt aus
`AlphaWorldGeneratorV2`: Klima-Felder → Höhe UND Biome, Worley-Seen, explizites
Quelle→Mündung-Flussnetz, Feature-Pass im Scheiben-Modell.

## Paketstruktur (Kurzfassung)

- `core/` — SkyEngine (Threads/Loop), Window, Input, EngineConfig, file/ (Files, GameDirectory),
  settings/, i18n/
- `game/world/chunk/` — Chunk, ChunkSection, palette/, ChunkManager, ChunkMesher, FluidGeometry
- `game/world/light/` — LightEngine, LightStorage (Himmels- + Blocklicht)
- `game/world/tick/` — ScheduledTickQueue (geplante Ticks, Fluid-Fluss)
- `game/world/block/` — Block-System: archetype/, behavior/, state/, model/, json/, entity/
  (BlockEntities + Capabilities), connection/, shape/, registry/, multiblock/, network/
- `game/world/generator/` — WorldGenerator, generators/ (V2 + RiverNetwork), climate/, biome/,
  feature/ (ChunkDecorator, FeaturePlacer, trees/), debug/ (GeneratorMapExporter)
- `game/world/lod/` — LodManager, LodConfig, LodMesher, LodDataSource(+World/Generator-Impl)
- `game/world/save/` — Chunk-Persistenz: WorldStorage (Region-Store + IO-Thread), RegionFile,
  ChunkSerializer, DataTagIO, PlayerIO (`player.dat`)
- `game/world/item/` (+ `json/` = ItemLoader/ItemDefinition), `game/entity/`, `game/physics/`,
  `game/GameContainer` (Verdrahtung, Interaktion, Mining, Inventar)
- `graphics/` — world/ (ChunkRenderer, VertexArena, SectionMesh, LodMesh, MappedRing, GpuCull,
  SelectionBox-/CrackRenderer), blockentity/, entity/, player/, gui/ (+font/, text/), shader/,
  texture/ (TextureArray, SpriteAnimations), camera/, framebuffer/, post/ (PostProcessor,
  Grading/AA-Pässe), color/
- `mcimport/` — Minecraft-Welt-Importer (liegt im Haupt-SourceSet, s. Chunk-Persistenz)
- `utils/` — Logging, FastNoiseLite/FBM, Profiler

**Shader sind Inline-GLSL-Strings** in den Renderer-Klassen, keine .glsl-Dateien.
Ressourcen: `game/blocks/*.json` (Definition + variants/inventory_model/icon **+ Texturen**),
`game/items/*.json` (Material-Items), `game/models/block/*.json` (**nur noch Geometrie**),
`game/textures/`, `game/lang/*.json`. Presets liegen jeweils im Unterordner `preset/` und
werden nicht registriert.

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
| `licht-system` | …Himmelslicht, Licht-Opazität, ChunkStatus, der 5. Vertex-Int, Chunk-Shader |
| `weltgen-v2` | …Weltgenerierung, Biome, Seen, Flüsse, Features, Noise-Seeds |
| `vegetation-tint` | …Gras-/Laubfärbung, Tint-Grids, Grasblock-Overlay |
| `sound-system` | …Sounds/Musik, OpenAL, SoundManager, Block-Sound-Gruppen, Lautstärke-Settings |
| `visuelle-verifikation` | …IMMER bevor eine Änderung als „fertig" gemeldet wird |

## Aktueller Stand

**Fertig und stabil:**
- Chunk-Pipeline mit Nachbar-Gating, Prioritäts-Workern, Edit-Priority-Uploads
- Meshing: Greedy + Minecraft-AO (inkl. Flip + Shader-Clamp), 20-Byte-Vertices, Index-Buffer
- Licht, zwei Ebenen (monochrom 0..15, kein RGB): Himmelslicht + **Blocklicht** (Leuchtblöcke).
  Je Ebene ein `LightStorage` am Chunk (Nibble je Zelle, lazy + Uniform pro Section), gemeinsame
  `LightEngine` (Heightmap, Emitter-Seeding mit Paletten-Vorfilter, Flood-BFS, Randaustausch,
  Edit-Updates), Pipeline-Stufen LIGHTING/LIT, Smooth Lighting im Mesher (gepackt: Himmel Bits
  0-3, Block 4-7 im 5. Vertex-Int), im Shader `max(sky, block)` → MC-Lichtkurve +
  Helligkeits-Slider (AUS = Fullbright). Block-JSON `light_level` (torch 14, enchanting_table 7,
  lava 15, brown_mushroom 1); `light_color` wird gelesen und abgelegt, wirkt aber noch NICHT.
  Objekte ohne Vertex-Licht (Truhe, Drops, Hand, Spieler) hängen über `ChunkRenderer.lightFactor`
  am selben Licht. Licht wird nicht persistiert. Prüfstand `gradlew lightTest`
- Rendering: MDI + VertexArena + Frame-Fences, TextureArray mit animierten Sprites,
  Translucent-Sortierung, BlockEntity-Renderer (Chest, EnchantingTable), Reversed-Z,
  Distanz-Fog (auch über LOD); Szene rendert in ein HDR-Offscreen-Target (RGBA16F)
- Post-Processing (`graphics/post/`): `PostProcessor`-Kette Color-Grading (Exposure/Tonemap/
  Lift/Gain, eigenes JSON `PostProcessingSettings`) → Anti-Aliasing mit Modi
  NONE/FXAA/TAA/TAA_FXAA/MSAA (MSAA = Multisample-Framebuffer wie früher, alle anderen Modi
  ohne MSAA mit sample-barer Depth-Textur), dazu MenuBlurPass; läuft in `SkyEngine.onRender`
  zwischen `resolve()` und GUI
- Grafik-/Spiel-Settings (GameSettings): renderDistance, simulationDistance,
  vegetationDistance, `LeavesQuality` (LOW/MID/HIGH inkl. Laub-an-Laub-Culling im Mesher),
  GraphicsMode, anisotropicFiltering, msaaSamples, fog, Helligkeit, GuiScale,
  sneakToggle/sprintToggle, `soundVolumes`-Mischpult + audioDevice
- Block-System (Architektur gilt als **reif — kein Rewrite**): ~203 JSON-Blöcke, Archetypen,
  Behaviors, Verbindungen (Zaun/Pane/Cable), BlockEntities + Capabilities (Item/Energie).
  **Türen in allen 8 Holzsorten + Eisen** über `blocks/preset/door.json`: die 32 Varianten
  (facing × half × hinge × open) stehen dort EINMAL, die Rümpfe `models/block/door_*.json`
  ziehen ihre Textur per `#bottom`/`#top` aus der Block-JSON. Ein Kind ist damit zwei Zeilen.
  Die Eisentür setzt `"hand_openable": false` (JSON-Feld → `DoorArchetype` → `DoorBehavior`):
  Rechtsklick tut nichts, sie braucht ein Signal — das es noch **nicht** gibt, sie ist also
  bewusst vorerst ohne Funktion. **Falltüren** analog über `preset/trapdoor.json` +
  Archetyp `trapdoor` (FACING/HALF/OPEN, EINTEILIG — kein `parts`, kein HINGE, 16 States),
  `Shapes.trapdoor()`, `TrapdoorBehavior`; Geometrie und alle 16 Varianten verbatim aus MC
  (geschlossen wird NICHT gedreht, nur die offene Variante). Auch hier eine Eisenvariante
  ohne Handbedienung
- Block-Materialwerte in der JSON, alle vanilla-getreu: `hardness` (Abbau, negativ = unzerstörbar),
  `tool`/`harvest_tier` (Drop-Regel), **`resistance`** (Explosions-Widerstand; fehlt das Feld,
  gilt `hardness` — es steht deshalb nur bei den ~80 Blöcken, wo MC beide Werte auseinanderzieht:
  Stein 1.5/6, Obsidian 50/1200, End-Stone 3/9) sowie die Bewegungs-Faktoren **`friction`**
  (Default 0.6, Eis 0.98, Blaueis 0.989), **`speed_factor`** (Seelensand/Honig 0.4) und
  **`jump_factor`** (Honig 0.5), ausgewertet in `EntityPlayer.travelWalking` nach der
  MC-Formel (Beschleunigung skaliert mit `0.6³/friction³`, sonst wäre Eis schnell statt glatt).
  Dazu die Landungs-Werte **`bounciness`** (Slime 1.0) und **`fall_damage_factor`** (Slime 0,
  Honig 0.2): der Abpraller sitzt in `Entity.move` an genau der Stelle, an der sonst `motionY`
  genullt würde — MCs `Block.updateEntityAfterFallOn` — und gilt damit auch für Drops und
  gezündetes TNT (gedämpft mit 0.8, `Entity.bounceDamping`; der Spieler federt voll). Sneaken
  unterdrückt nur den Abpraller, nicht die Schadens-Immunität. Der Fallschaden-Faktor greift in
  `EntityPlayer.updateFallDamage` NACH der 3-Block-Schwelle (wie MCs `calculateFallDamage`).
  Dazu die zehn Blöcke, die diese Werte erst sichtbar machen: ice, packed_ice, blue_ice,
  soul_sand, soul_soil, slime_block, honey_block, end_stone, netherrack, magma_block
  (Texturen via `scripts/extract-mc-blocks.ps1`); Slime/Honig haben das Vanilla-Innenwürfel-Modell
  (`models/block/cube_inner_all`/`_bottom_top`, Innenwürfel **zuerst** — der Translucent-Pass
  schreibt Tiefe und die Sortierung ist gedrosselt) und eigene Sound-Gruppen SLIME/HONEY.
  Kollisionshöhen vanilla-getreu (Honig 15/16 + 1 px eingerückt, Seelensand 14/16) bei vollem
  Modell. **Kein** Netz-Bremsen und **kein** Honig-Wandrutschen
- Doppeltruhen mit den MC-Platzierungsregeln: Property `type` (single/left/right) + `ChestBehavior`
  (Verschmelzen beim Platzieren, Sneaken verhindert es, sneakender Seitenklick verbindet trotzdem;
  Auftrennen per Nachbar-Update),
  54-Slot-GUI aus denselben zwei Inventaren, Vanilla-Hälften im `ChestRenderer`
  (`normal_left/right.png`). Truheninhalt fällt jetzt beim Abbauen heraus (`ChestBehavior.onBreak`)
- JSON-Vererbung für Blöcke UND Items: `parent` + Deep-Merge + `${var}`-Platzhalter
  (`BlockJson`/`ItemLoader` über `utils/json/JsonMerge`), Presets in `blocks/preset/` bzw.
  `items/preset/`. Die Auflösung passiert EINMAL je Quelle; dieselbe Map geht an beide Leser
  (DTO + Render-Sektion), damit sie nicht auseinanderlaufen können. `oak_stairs.json`: 63 → 9 Zeilen
- Modell-Konsolidierung: Block-JSON deklariert `model`/`models` (Suffix → Rumpf),
  `ModelLoader.registerBlockModels` erzeugt daraus **virtuelle** Modelle `block/<id><suffix>`.
  `models/` enthält seither nur noch Geometrie + geteilte Rümpfe (264 → 41 Dateien; die sechs
  `bars_*` sind der Rumpf-Satz der Eisengitter, inkl. der Vanilla-`_alt`-Varianten für Süd/West)
- Deklarierbare Properties je Block (`"properties": {"lit": {"values":[…], "default":…}}`,
  `JsonProperties` mit Interning); Element-Rotation mit beliebigem Winkel (MC
  `rotation: {origin, axis, angle, rescale}`); Archetyp `attached` für hängende Blöcke
  (Fackel floor/wall — Hebel/Knopf/Leiter wären damit reines JSON)
- Mehrteilige Blöcke deklarativ: `parts` (Teil-Property + Offsets, genau ein Ursprung `[0,0,0]`,
  optional `relative_to: "facing"` für facing-relative Offsets) → `PartsBehavior`. Tür und
  tall_grass laufen darüber; `DoorBehavior`/`TallPlantBehavior` enthalten nur noch das
  Blockspezifische (Hinge/Öffnen bzw. Stützregel). `World.updateStateAt` ruft beim
  Selbst-Entfernen jetzt `onBreak` — heute ein No-op, schließt aber die Lücke für Teile mit
  BlockEntity
- Item-System datengetrieben (`game/items/*.json`, `ItemLoader`, `SimpleItem`) neben den weiterhin
  in Java registrierten 28 Tools/Eimern/Foods; `display`-Sektion im Modell-JSON (erbt über
  `parent` bis `block/block`) versorgt die Hand-Transforms der Block-Items
  — **Fackel, Material-Items und die Modell-Migration sind visuell noch ungetestet**
- Fluids komplett (Fluss, Reaktionen, Eimer, Schwimmen/Strömung, Unterwasser-Overlay)
- Weltgen V2: Klima→Höhe/Biome, 3D-Dichte/Höhlen, Worley-Seen, Fluss-Netz Quelle→Mündung
  (~2,5 ms/Chunk), Feature-Bäume (Scheiben-Modell), Debug-Karten-Exporter
- LOD: formelbasierte Clipmap-Ringe, Chunk-Masken-Clipping (Load- UND Unload-Gate gegen
  Pop-ins), Skirts, Epoche/Hysterese, eigene Vertex-Arenen, AO, transluzentes LOD-Wasser,
  getönte Gras-Overlay-Wände
- Vegetations-Tint biome-abhängig (Eck-Grids + bilinear), koplanare Grasblock-Overlays
- GPU-driven Culling (GpuCull, Default **AUS**, Umschalten im GuiDebugScreen): Frustum +
  Sicht-Gate + LOD per Compute, Two-Phase-Hi-Z-Occlusion (Pow2-Viertel-Pyramide).
  **Gemessen 2026-07-30:** das Compute-Frustum kostet nur +35 µs/Frame gegenüber dem CPU-Cull,
  die Hi-Z-Occlusion obendrauf +134 µs — und spart nichts: die Rasterarbeit (`solid`+`cut`)
  bleibt in allen Konfigurationen bei 156 µs, weil Early-Z verdeckte Fragmente ohnehin
  verwirft. 156 µs sind zugleich die Obergrenze des möglichen Nutzens, Hi-Z kostet 85 µs.
  Deshalb `FRUSTUM_ONLY` Default AN (Hi-Z aus), beides getrennt schaltbar; Hi-Z wieder an,
  sobald Fragmente teuer werden (Licht/Schatten heben die Decke). Messstand: `./gradlew run
  -Dskyengine.cullbench=<Weltordner> [-Dskyengine.window=BxH]` (`CullBench`, feste Pose +
  eingefrorenes Laden, sonst sind Läufe nicht vergleichbar). Details/Fallen im Skill
  `mdi-rendering`
- Mining: MC-Harvest-Regel, 28 Tools (7 Tiers × 4 Typen), Durability, Crack-Overlay,
  Bedrock unzerstörbar; Gamemodes Survival/Creative/Spectator; Item-Entities + Aufsammeln
- TNT/Explosion: raybasierte Explosion (`Explosion`, MC-ServerExplosion-Modell), Widerstand aus
  dem Block-Feld `resistance` (s.u.), `ExplosionBehavior` + `PrimedTntEntity` (Rechtsklick-Zündung,
  Fuse, Ketten-Zündung), weißer Blink-Shader; JSON-Felder `explosion_power`/`explosion_fuse`
  (`blocks/tnt.json`, power **4** wie MC-TNT). Die Zerstörung läuft als Batch:
  `World.breakBlocksBatch` (ein Lock/Dirty/Licht-Update je Chunk,
  `LightEngine.onBlocksChanged` = EINE Flutung, Äquivalenz-Beweis im `lightTest`),
  Raycast mit Chunk-Memo, Priority-Uploads gedeckelt. Danach EIN Nachbar-Update-Pass über die
  Krater-**Schale** (`World.updateBlastShell`) — erst dadurch fallen hängende Blöcke (Fackel,
  hohe Pflanze, Türhälfte), rieselt Sand nach und fließt Wasser in den Krater
  (`FluidBehavior.onNeighborUpdate` ist der einzige Weg zu einem Fluid-Tick). Drops wie in MC
  mit Chance **1/power**, ohne Werkzeug-Regel (Vanilla-Explosionsloot kennt kein Tool);
  gezündetes Ketten-TNT droppt nicht. Nur BlockEntity-Blöcke laufen durch `onBreak` (Truheninhalt)
- Dispenser/Dropper: 9-Slot-BlockEntities mit GUI und Persistenz, 4-Tick-Redstone-Flanke inklusive
  Quasi-Connectivity, gleichverteilte Vanilla-Slotwahl und gerichteter Item-Auswurf. Der Dropper
  füllt einen Container vor seiner Front, ohne bei vollem Ziel auszuwerfen; der Dispenser besitzt
  Sonderverhalten für die vorhandenen TNT-, Eimer-, Feuerzeug- und Item-Frame-Mechaniken.
- Chunk-Persistenz (`game/world/save/`): Region-Format `region/r.<rx>.<rz>.srg` (16×16 Chunks,
  CRC), Single-Writer-IO-Thread, `player.dat`; **vollständiger** Autosave (Chunks + level.json +
  player.dat) alle 1200 Ticks, zusätzlich beim Öffnen des Pausenmenüs (ESC) und beim Unload/Exit.
  Quittung „Spiel gespeichert" unten rechts (`graphics/gui/SaveToast`), ausgelöst erst, wenn
  `World.hasPendingSaves()` abgeräumt ist — beim Autosave nur, wenn Chunks dabei waren;
  optionaler Minecraft-Welt-Import über das Paket `de.skyengine.mcimport` im Haupt-SourceSet
  (`McWorldImporter` als API + `GuiImportWorld`, `mapping/BlockMapper`, `block_map.json`,
  NBT/MCA-Leser; Gradle-Tasks `mcAnalyze`/`mcMapReport`/`mcImport`) — Nebentool
- Sound (OpenAL): blockabhängige Schritte/Hit/Break/Place (Sound-Gruppen aus JSON/Tool/
  Archetyp), Musik als **Playlist**: alles, was in `game/sounds/music` liegt (`.ogg` **und**
  `.wav` — WAV über `javax.sound.sampled`, kein MP3), wird gemischt und ohne Pause
  nacheinander gespielt (Shuffle-Bag, Fortschalten in `SoundManager.update()`);
  Lautstärke als **Mischpult** je `SoundCategory`
  (MUSIC/BLOCKS/PLAYER/… — `GameSettings.soundVolumes`-Map statt eines einzelnen
  musicVolume) + Master-Volume + Audiogeräte-Auswahl (`audioDevice`); Assets =
  MC-Platzhalter via `scripts/extract-mc-sounds.ps1` — Details im Skill `sound-system`
- GUI-System komplett (graphics/gui): **Schriftgrößen zentral in `GuiText`** (TINY 7 / SMALL 8 /
  COMPACT 9 / NORMAL 10 / MEDIUM 12 / TITLE 14 / LARGE 20 / HERO 32) — die einzige Stellschraube,
  21 Dateien hängen daran. Die acht Stufen sind die gewachsene Abstufung der GUI und **bewusst
  nicht zusammengelegt**: ein Einebnen auf wenige Größen zerstört die Hierarchie. Achtung: der
  Font ist **monospace** (Breite ≈ 0,5 × Größe × Zeichen — seit `FontRenderer.SPACE_ADVANCE`
  eine Obergrenze, weil das Leerzeichen schmaler gezeichnet wird), Hochdrehen kostet proportional
  Platz und es gibt **kein Clipping** — der 200-px-Standard-Button verträgt höchstens
  `NORMAL = 11` („Speichern und zurück zum Hauptmenü" = 34 Zeichen); feste Widget-Breiten und die
  hartkodierten Textzeilen-Offsets in `GuiSelectWorld`/`GuiImportWorld` müssten sonst mitwachsen.
  **Wortlücken** stellt `FontRenderer.SPACE_ADVANCE` ein (0.65 = MC-Verhältnis): Monocraft ist
  echt monospace, das Leerzeichen wäre sonst so breit wie ein „M". Mess- und Zeichenpfad teilen
  sich dafür `advanceOf` — der Zeichenpfad führt den Stift seither selbst, statt sich auf die
  `xpos`-Rückschreibung von `stbtt_GetPackedQuad` zu verlassen.
  Widget-Basis (`GuiComponent` + Button/Slider/CycleButton/
  KeybindButton/Label/TextField, 9-Slice; `TextField.borderless()` für Felder über einem schon
  gemalten Kasten), Stack-Layout (VStack/HStack/Anchor), Screen-Basis mit
  parent-Navigation + vollem Event-Routing (Maus/Drag/Scroll/Keys/Char via SPSC-Queue);
  Screens: Titel, Pause (ESC — beendet NICHT mehr!), Optionen+Grafik (Live-Apply), Tastenbelegung
  (Rebinding+Reset, Capture schluckt alle Tasten), Weltauswahl/Erstellen/Löschen, Welt-Ladebalken
  (`isInitialLoadComplete`), Boot-Ladebildschirm (gestaffelte Init, Fenster früh), Inventar (E) +
  Truhe auf gemeinsamer `GuiContainer`-Basis, **Creative-Inventar** (`GuiCreativeInventory`,
  im Creative statt `GuiInventory`: zwei Reiter-Reihen über und unter dem Fenster mit
  Item-Icons + Tooltips (Spalten 0-4 linksbündig, 5-6 rechtsbündig wie MC — dadurch schließen
  erster und letzter Reiter bündig mit den Fensterkanten ab; Suche oben rechts, Survival-
  Inventar unten rechts angeheftet), Seiten-Blättern,
  9×5-Liste mit Scroller, Such-Reiter, Survival-Reiter mit Lösch-Slot; Reiter stehen in
  `game/creative_tabs.json`, die Zuordnung im Feld `creative_tab` der Block-/Item-JSONs —
  vererbbar über die Presets, ungetaggte Items landen sichtbar im Sammel-Reiter `misc`.
  Die **Reihenfolge INNERHALB eines Reiters** steht ebenfalls in `creative_tabs.json`
  (Feld `items` je Reiter, Namespace optional) — kuratiert wie MCs `CreativeModeTabs`,
  Familie-zuerst (erst die Eichen-Familie komplett, dann Fichte …) bzw. Typ-zuerst bei den
  farbigen Blöcken (16× Wolle, dann 16× Terrakotta, Farbfolge weiß→hellgrau→grau→schwarz→
  braun→rot→…→rosa). `CreativeTabs.build()` sortiert nur diese **Anzeigeliste**; die
  Registry-Reihenfolge (alphabetisch nach Dateiname) bleibt unangetastet, weil an ihr die
  Runtime-State-IDs und damit die Weltspeicher hängen — Blöcke umzubenennen wäre der falsche
  Weg. Für die regelmäßigen Familien gibt es **Achsen-Expansion** (`axes`: `wood`/`color`/`tier`):
  `"{color}_wool"` expandiert die Achse als INNERE Schleife (16 Wollen am Stück),
  `{"for":"wood","items":[…]}` als ÄUSSERE (erst alles für Eiche, dann Fichte). Expansionen
  ohne Block werden still übersprungen (es gibt keinen `stripped_oak_log`); Unregelmäßiges
  (`bricks`, `terracotta`, `glass_pane`, `iron_bars`, `smooth_basalt`) bleibt bewusst explizit.
  Nicht gelistete Items hängen stabil hinten an; drei Warnungen decken den Rest ab (Item
  ungelistet / wörtliche ID ohne Item / Muster ohne EINEN Treffer = Tippfehler),
  Prüfstand `gradlew saveTest`. Der Item-Tooltip im Creative zeigt unter dem Namen die Reiter
  in `Colors.BLUE` (`CreativeTabs.tabsOf`, Override von `tooltipLines` nur im Creative-Screen —
  im Survival-Inventar und in der Truhe soll die Zeile NICHT stehen)),
  Todesscreen, Sprachauswahl (i18n,
  Live-Wechsel), Sound-Optionen, Grafik-Optionen (`GuiVideoSettings`), MC-Welt-Import
  (`GuiImportWorld`), Bestätigungsdialog (`GuiConfirm`), Ressourcenpakete-Platzhalter
  (`GuiResourcePacks`) und der **GuiDebugScreen** (Optionsmenü) mit allen Debug-Schaltern
  (Wireframe, GpuCull + Tint, LOD-Overlay, Loading einfrieren, Chunks neu laden, **GUI-Slot-
  Flächen** u.a. — die früheren F-Hotkeys dafür sind weg); **GuiScale = ganzzahliger Faktor**
  (`GameSettings.guiScaleLevel`, 0 = automatisch, sonst 1..6), garantierte virtuelle
  Mindestfläche 340×240 (deckt das höchste Fenster ab — die Doppeltruhe mit 222 px).
  Zwischenstufen kann es NICHT geben: nur bei ganzzahligem Faktor ist 1 virtueller Pixel = N
  ganze Gerätepixel. Bei 3,85 wurde eine 1-Texel-Linie mal 3, mal 4 px breit und 16-px-Sprites
  ragten in ihre Slotrahmen (gemessen) — deshalb ist die frühere Prozent-Einstellung weg.
  Auflösung Wunsch+Fenstergröße → Faktor an EINER Stelle: `GuiManager.resolveScale`
  (auch von `BootProgress` genutzt), Obergrenze `maxScaleFor` = abgerundet, sonst fiele die
  Mindestfläche. Das Optionsmenü bietet nur Faktoren an, die ins Fenster passen.
  Abnahmetest: Lauflängen-Histogramm der Linienbreiten im Screenshot muss genau EINE Breite
  zeigen. Deshalb müssen GUI-Positionen auf **ganze virtuelle Pixel** gerundet werden
  (`Hud`, `GuiCreativeInventory.init`, Container-Slots).
  Slot-Trefferflächen sind um `GuiContainer.HIT_PAD = 1` erweitert, damit bei Raster 18 /
  Größe 16 keine tote Zone zwischen zwei Slots bleibt (MC macht das in `isHovering` genauso);
  der Hover-Kasten bleibt 16×16. Icon-Größe im Slot: `ItemIconRenderer.ICON_SCALE = 0.625`
  = MCs `gui`-Display-Scale — der Iso-Würfel ist projiziert `1.5731 ×` seiner Kante hoch,
  mehr als 0.635 ragt oben/unten aus dem Slot
- Spieler-Rendering (graphics/player): Humanoid-Modell mit Classic-/Slim-Skin 64×64; Legacy
  64×32 wird nach Vanillas UV-Spiegelung intern auf 64×64 konvertiert (skin.png im Spielordner
  überschreibt Steve), Inventar-Vorschau (folgt Maus), F5-Perspektiven
  (Ego/hinten/vorne mit Kamera-Kollisions-Raycast; Interaktion zielt IMMER vom Auge;
  LOD-Gras-Overlay-Debug liegt im GuiDebugScreen), prozedurale Animationen (Limb-Swing/Sneak/Arm-Schwung,
  `PlayerAnimationState`), First-Person-Hand mit extrudierten Item-Sprites +
  Vanilla-Display-Transforms (bei Block-Items aus der `display`-Sektion des Modells, bei flachen
  Items weiterhin hartkodiert; der Iso-Würfel im `ItemIconRenderer` bleibt bewusst außen vor —
  er weicht absichtlich von Vanilla ab und hängt an den `inventory_y`-Kompensationen),
  View-Bobbing + Hurt-Tilt (GameSettings-Toggles).
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
- Farbiges (RGB) Blocklicht — `light_color` steht schon in der Block-JSON und liegt in
  `BlockConfig` bereit, wirkt aber noch nicht; dafür sind die freien Bits 8-31 des Licht-Ints und
  weitere `LightStorage`-Ebenen da. Danach Tag-Nacht-Zyklus und Schatten-Pass
  (`lightning-system`-Branch als Vorlage) — dann amortisiert sich der GPU-Cull-Pfad
- Crafting (kein Recipe-/Crafting-Menü; `GuiInventory`-Crafting-Bereich noch funktionslos);
  Inventar-Phase 2: Stack-Größen je Item, Maus-Shortcuts (mouse tweaks), Sortieren
  (Andockpunkt: `GuiContainer.onSlotClick`)
- Controller-Support: `Input.isControllerButton*`/`getControllerAxis` sind TODO-Stubs
- Creative-Inventar-Feinschliff: Hotbar-Speicher (MCs C + 1-9) fehlt bewusst; der reservierte
  Reiter-Platz (Spalte 5 beider Reihen) ist dafür schon frei gehalten. Klick-Verhalten der
  Item-Liste ist bewusst NICHT ganz MC: Linksklick gibt **1** Item und stapelt bei weiteren
  Klicks hoch, Rechtsklick zählt herunter, ein fremdes Item leert die Hand (die Liste ist der
  Mülleimer); Bulk gibt es über Shift-Klick und die Tasten 1-9. Aufgemacht wird **immer** der
  Such-Reiter mit fokussiertem Feld — deshalb schließt **ESC**, nicht E (das „e" läuft ins
  Suchfeld, wie in MC)
- Bett/Reaktor: die Mechanik steht (`parts`, s.o.), es fehlen nur noch die Blöcke selbst —
  ein Bett braucht ein eigenes 3D-Modell + Texturen. `multiblock/MultiblockPattern` bleibt
  ungenutzte Infrastruktur für Controller-Strukturen (validiert eine Struktur aus FREMDEN
  Blöcken — ein anderes Problem als ein Block, der mehrere Zellen belegt)

**Bewusst nicht vorhanden (nicht „vergessen" — nicht ungefragt bauen):**
- **Kein Sky-Rendering** (keine Atmosphäre/Wolken/God-Rays — Clear-Color ist der Himmel)
- **Kein farbiges Licht** (Blocklicht ist monochrom wie in MC), kein Tag-Nacht-Zyklus, keine
  Schatten; Entities emittieren kein Licht (kein „Dynamic Lights") — s. Skill `licht-system`
- Keine Mobs, kein Multiplayer, keine Test-Infrastruktur

## Wiederkehrende Fallen (Kurzliste — Details in den Skills)

- GLFW-Fenster/Cursor → nur via `addTaskToMainThread`; GL nur auf dem Render-Thread
- Chunks sind 32er: `ChunkSection.SHIFT/MASK` statt `>>4`/`&15`
- Chunks unter DECORATED nie lesen (Worker schreiben lock-frei) — Guards in World/processRemeshes
- Worker-Jobs: `execute` mit `PrioTask`, nie `submit`
- `BlockTextures.layerOf` nach dem TextureArray-Bau = kaputter Layer-Index (gilt auch für
  jede neue Item-Textur: `ItemLoader` meldet sie synchron bei der Registrierung an, nie lazy)
- Texturen stehen in der **Block-JSON** (`textures`), Geometrie im Modell. Ein Block braucht
  entweder `model`/`models` ODER eine gleichnamige Datei `models/block/<id>.json` — fehlt
  beides, ist er unsichtbar. Existiert eine Datei UND deklariert der Block `model`, **gewinnt
  die Datei** (Warnung „Modell-Datei ueberdeckt die Block-Definition")
- `ModelLoader.registerBlockModels` MUSS nach `ModelLoader.load` (das leert MODELS *und*
  CACHE) und vor dem ersten `bake` laufen — Reihenfolge steht in `Blocks.bootstrap`
- Preset-Felder gelten für ALLE Kinder: ein Feld ins Preset zu ziehen, das nur ein Teil der
  Blöcke hatte, ändert die anderen still mit (`no_lod_surface` bei Säulen war genau der Fall)
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
5. **An der wirkungslosen Stelle editieren.** Ein Preset-Feld, das das Kind überschreibt;
   eine Modelldatei, die die Block-Definition überdeckt; geratene Textur-Layer; hartkodierte
   Depth-Funcs. Symptom „meine Änderung bewirkt nichts" heißt fast immer: falsche Stelle —
   nicht mehr Code nachschieben, sondern `gradlew saveTest` laufen lassen und die Warnungen lesen.

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
