# SkyEngine

[![Java](https://img.shields.io/badge/Java-25-ED8B00?style=flat-square\&logo=openjdk)](https://openjdk.org)
[![LWJGL](https://img.shields.io/badge/LWJGL-3-FFFFFF?style=flat-square)](https://www.lwjgl.org)
[![OpenGL](https://img.shields.io/badge/OpenGL-4.6-5586A4?style=flat-square\&logo=opengl)](https://www.opengl.org)
[![Gradle](https://img.shields.io/badge/Gradle-9-02303A?style=flat-square\&logo=gradle)](https://gradle.org)

Eine moderne Voxel-Engine in Java, entwickelt mit LWJGL 3 und OpenGL. Das Ziel des Projekts ist die Entwicklung einer performanten und modularen Engine für voxelbasierte Welten mit Chunk-System, Mesh-Generierung und Echtzeit-Rendering.

Diese Engine ist dabei eine Migration aus meinen verschiedensten Engines, welche ich bisher entwickelt habe. Als Unterstützen kommt hier Claude Code von Anthropic zum Einsatz.

Als Texturen wurden die offiziellen Minecraft Texturen aus der 1.21 genutzt. Zusätzlich wurde das Texture-Pack genutzt: C-tetra by canna (under CC BY-NC 4.0 licence); downloaded from https://www.planetminecraft.com/texture_pack/16x-c-tetra-1-13/

## Features

- 🌍 Chunk-System inkl. Welt Rendering (Chunks sind Säulenförmig a 32x512x32m jedoch in Sektionen mit 32³ Blöcken verteilt)
- 📷 Kamera
  - Inverse Depth für bessere Tiefenaufteilung
  - First-/Third-/Frontal Perspektive
- 💥 AABB Sweep Kollision + Spieler Physik wie Springen, Sneaken, Strafe, Fliegen etc.
- 🫲🏼 Block Platzieren/Abbauen
- 🟩 Vollwertiges Block-System: 
  - Volle Blöcke wie Stein, Grass oder Erde
  - Stufen und halbe Blöcke
  - Kreuz-Blöcke wie Kurzes Grass oder Blumen mit zufälliger Block-Verschiebung im Rendering
  - Transparente Blöcke wie Glass oder Laub
  - Spezielle Blöcke wie Zäune, Glassscheiben, Eisenstäbe mit Verbindungs-Logik
  - Animierte Blöcke mit Entity Verhalten wie Truhen mit Inventar und Aufklapp-Animation oder einen Zaubertisch, bei dem das Buch obendrauf den Spieler verfolgt und aufgeht
  - Multipart Blöcke wie Türen, welche mit Spielerklick auf und zu gehen inkl. Platzierungslogik für Türanschlag
  - Flüssigkeiten wie Wasser und Lava mit Reaktionsverhalten für Obsidian, Cobblestone und Stein
- 📜 GUI-System
  - Texturen / Formen zeichnen 
  - Font Rendering (TTF -> Texture Atlas -> Zeichnen)
  - HUD mit Hotbar
  - GuiScreens darunter Boot Splash Screen mit Ladebalken, Hauptmenü, Pause Menü, Optionen + Gui Komponenten wie Button, Slider etc.
  - Kreatives Inventar mit Tabs für Block/Items
- 🎶 Audio System
  - Unterstützt bis 5.1 Surround Sound
  - Soundeffekte
  - Musik
- 🔆 Licht-System
  - Skylight
  - Einfaches Blocklicht für Fackel, Lava und Pilze
- 🔝 Optimizations & Features
  - Frustum Culling
  - Vertex Komprimierung zu 20 bits (x, y, w, h, u, v, skylight + block light)
  - Nutzen von MultiDrawIndirect & BufferStorage -> Reduziert Draw-Calls von 12.288 auf 3 (16 Chunk Renderdistanz; 3 weil einen für OPAQUE, CUTOUT & TRANSLUCENT)
  - Ambient Occlusion
  - Level of Detail
  - GPU Acceleration (Frustum-/Occlusion Culling)
- 🏔️ Welt-Generator
  - Biome
  - Strukturen wie Bäume, Palmen
- 🖼️ Post Processing Pipeline
  - Color Grading
  - Antialiasing: None, FXAA, TAA, FXAA + TAA, MSAA
- 🎲 Spiel
  - Verschiedene Gamemode's wie Survival, Kreativ oder Zuschauer (Fliegen + NoClip)
  - Spieler + Animation für Laufen/Essen
  - Redstone mit Pistons, Observer, Verstärker, Schleimblöcke und und und

## Tools

Ab der 0.0.9 kann man Minecraft Welten in das SkyEngine Format konvertieren. Im Spiel geht das über
Einzelspieler → „Importieren" (oben rechts): dort stehen die Welten aus `%APPDATA%\.minecraft\saves`
zur Auswahl, der Import läuft im Hintergrund und zeigt Fortschritt + Log an.
Ansonsten kann man es mit diesem Befehl nutzen:

```bash
./gradlew mcimport --args="'<path_for_minecraft_world>' '<world_name>'"

Beispiel:
./gradlew mcImport --args="'C:/Users/useerrr/AppData/Roaming/.minecraft/saves/MeineWelt' 'MeineWelt'"
```

## Development

### Prerequisites

- Java 25
- Gradle 9+

### Installation

```bash
git clone https://github.com/aleczinn/skyengine-z.git
cd skyengine-z
```

### Build

```bash
gradlew build
```

### Run

```bash
gradlew run
```

## Goals

Die Engine dient als Lern- und Entwicklungsprojekt für moderne Rendering-Techniken, Engine-Architekturen und die Optimierung voxelbasierter Welten.

Der Fokus liegt auf:

- Performance
- Sauberer Architektur
- Erweiterbarkeit
- Moderne OpenGL-Techniken

## Screenshots / Changelog

v0.0.12 | Redstone
- Diverse Redstone Blöcke wie Redstone, Verstärker, Observer, Komparator, (Klebriger) Piston, Redstone-Fackel und und und
- Möglich: Schleim-Flugmaschinen, TNT-Kanonen

![Redstone Test's](project/0.0.12_redstone-1.png?raw=true)

v0.0.11 | Verbesserungen + Bugfixes
- Block Physik für z. B. Friction, Jump-/Speed Faktor -> Neue Blöcke Slime block, Soulsand, Eis etc.
- Inventar im Kreativmodus mit Tabs für Blöcke/Items
- Maus/Tastatur Shortcuts zum besseren Nutzen des Inventares (Inspiriert am Minecraft Mod Inventory Tweaks)
- Item Rendering bei gedroppten Blöcken/Items inkl. Stacking bei gleichem Typ
- Items können nun vom Spieler gedroppt werden
- Diverse kleiner Fehler (Rendering, Alpha in HeldItemRenderer)

![Gedroppte Items rendern](project/0.0.11_dropped-item-rendering.png?raw=true)

v0.0.10 | Licht-System
- Skylight
- Einfaches Blocklicht für Fackeln/Lava

![Skylight](project/0.0.10_skylight.png?raw=true)

![Blocklicht](project/0.0.10_blocklight.png?raw=true)

v0.0.9 | Persistenz der Welt
- Speichern der Welt
- Generische Scheduled-Tick System für Blöcke wie Wasser/Öfen/Maschinen
- Tool zum Importieren von Minecraft Welten ab 1.18 

v0.0.8
- Gui-System (GuiScreens, Komponenten wie Button, Slider, CycleButton inkl. Persistenz; Ressourcenpacks sind WIP)
- Gui Inventare sind ebenfalls möglich (z. B. Inventar im Survival mit Spielermodell)
- I18n für Übersetzungen im Menü, Hotbar oder Itemnamen
- Spieler wird basierend auf Minecraft Skins gerendert (eigener Skin in appdata/.skyengine/skin.png)
- Hand-Item-Rendering
- Spieleranimationen für Laufen/Essen/Block Interaktion

![Gui-System](project/0.0.8_gui-system.png?raw=true)

![Spielerinventar](project/0.0.8_inventar_mit_spielermodell.png?raw=true)

![Spieler mit Frontalperspektive](project/0.0.8_spieler_frontal_person.png?raw=true)

v0.0.7
- Distanz-Nebel (auch bei LOD)
- Level of Detail Verbesserungen (LOD nun in eigenen Vertex Arenen, Ambient Occlusion, supportet nun Transparenz für Wasser) 

![Ingame Screenshot mit Nebel-Effekt](project/0.0.7_fog.png?raw=true)

![Ingame Screenshot mit verbessertem Level of Detail](project/0.0.7_level-of-detail-v2.png?raw=true)

v0.0.6 
- verbesserung des Welt-Generator V2.1 
- verbessertes Terrain Shaping + Fluss Netzwerk, bei dem Terrain um Flüsse gebaut wird 
- Bessere Verteilung von Gräsern/Blumen

![Ingame Screenshot mit neuer Welt-Generierung inkl. Biomes](project/0.0.6_world-generator-with-river-network.png?raw=true)

v0.0.5 | Welt-Generator V2 (Bioms, Structure-System für Bäume)

![Ingame Screenshot mit neuer Welt-Generierung inkl. Biomes](project/0.0.5_world-generator-with-bioms+tinting.png?raw=true)

v0.0.4 | Level of Detail (128 Render Distanz in Chunks bei >1000 FPS -> Entspricht in Minecraft einer Renderdistanz von 256)

![Ingame Screenshot mit einer Renderdistanz von 16 L0 Chunks und ingesamt 128](./project/0.0.4_level-of-detail.png?raw=true)

v0.0.3 
- Ambient Occlusion 
- Greedy Meshing
- Chunk Rendering mithilfe von MultiDrawIndirect

![Ingame Screenshot mit Ambient Occlusion, Greedy Meshing und MultiDrawIndirect](./project/0.0.3_greedy-meshing+multi-draw-indirect+ambient-occlusion.png?raw=true)

v0.0.2 | Fluid-System (Screenshot stammt 0.0.5)

![Fluid-System mit Wasser und Lava inkl. Cobble-Stone-Generator Funktion](./project/0.0.2_fluid-system+reaction-system.png?raw=true)

v0.0.1
- Basis
- Blöcke-System: Grass, Stein; Kreuzblöcke für kurzes Gras, Tulpen; Stufen, Zäune, Custom Modelle wie Zaubertisch
- Simples Welt-Rendering

![Ingame Screenshot mit Hotbar und Block Rendering](./project/0.0.1_2.png?raw=true)

![Ingame Screenshot mit hoher Sichtweite im Spectator Modus](./project/0.0.1_1.png?raw=true)

## License

Dieses Repository ist ausschließlich zur Ansicht veröffentlicht. Eine Nutzung, Vervielfältigung oder Weiterverwendung des Codes ist ohne ausdrückliche Genehmigung nicht gestattet.
