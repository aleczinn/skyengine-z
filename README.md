# SkyEngine

[![Java](https://img.shields.io/badge/Java-25-ED8B00?style=flat-square\&logo=openjdk)](https://openjdk.org)
[![LWJGL](https://img.shields.io/badge/LWJGL-3-FFFFFF?style=flat-square)](https://www.lwjgl.org)
[![OpenGL](https://img.shields.io/badge/OpenGL-4.6-5586A4?style=flat-square\&logo=opengl)](https://www.opengl.org)
[![Gradle](https://img.shields.io/badge/Gradle-9-02303A?style=flat-square\&logo=gradle)](https://gradle.org)

Eine moderne Voxel-Engine in Java, entwickelt mit LWJGL 3 und OpenGL. Das Ziel des Projekts ist die Entwicklung einer performanten und modularen Engine für voxelbasierte Welten mit Chunk-System, Mesh-Generierung und Echtzeit-Rendering.

Diese Engine ist dabei eine Migration aus meinen verschiedensten Engines, welche ich bisher entwickelt habe.

Used texture pack: C-tetra by canna (under CC BY-NC 4.0 licence); downloaded from https://www.planetminecraft.com/texture_pack/16x-c-tetra-1-13/

## Features

- 🌍 Chunk-System inkl. Welt Rendering (Chunks sind Säulenförmig a 32x512x32m jedoch in Sektionen mit 32³ Blöcken verteilt)
- 📷 Kamera inkl. Inverse Depth für bessere Tiefenaufteilung + Frustum Culling
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
- 📜 Einfache GUI wie Hotbar des Spielers oder Truheninventar mit Item-Verschiebung via Maus
- 🎲 Verschiedene Gamemode's wie Survival, Kreativ oder Zuschauer (Fliegen + NoClip)
- 🔝 Optimizations & Features
  - Frustum Culling
  - Vertex Komprimierung zu 16 bits
  - Nutzen von MultiDrawIndirect & BufferStorage -> Reduziert Draw-Calls von 12.288 auf 3 (16 Chunk Renderdistanz; 3 weil einen für OPAQUE, CUTOUT & TRANSLUCENT)
  - Ambient Occlusion
  - Level of Detail
- 🏔️ Welt-Generator
  - Biome
  - Strukturen wie Bäume, Palmen

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

## Screenshots

v0.0.7 | Distanz-Nebel (auch bei LOD)

![Ingame Screenshot mit Nebel-Effekt](project/0.0.7_fog.png?raw=true)

v0.0.7 | Level of Detail V2 (LOD nun in eigenen Vertex Arenen, Ambient Occlusion, supportet nun Transparenz für Wasser)

![Ingame Screenshot mit verbessertem Level of Detail](project/0.0.7_level-of-detail-v2.png?raw=true)

v0.0.6 | Welt-Generator V2.1 verbessertes Terrain Shaping + Fluss Netzwerk, bei dem Terrain um Flüsse gebaut wird + Vegetations-Fix

![Ingame Screenshot mit neuer Welt-Generierung inkl. Biomes](project/0.0.6_world-generator-with-river-network.png?raw=true)

v0.0.5 | Welt-Generator V2 (Bioms, Structure-System für Bäume)

![Ingame Screenshot mit neuer Welt-Generierung inkl. Biomes](project/0.0.5_world-generator-with-bioms+tinting.png?raw=true)

v0.0.4 | Level of Detail (128 Render Distanz in Chunks bei >1000 FPS -> Entspricht in Minecraft einer Renderdistanz von 256)

![Ingame Screenshot mit einer Renderdistanz von 16 L0 Chunks und ingesamt 128](./project/0.0.4_level-of-detail.png?raw=true)

v0.0.3 | Ambient Occlusion + Greedy Meshing + MultiDrawIndirect

![Ingame Screenshot mit Ambient Occlusion, Greedy Meshing und MultiDrawIndirect](./project/0.0.3_greedy-meshing+multi-draw-indirect+ambient-occlusion.png?raw=true)

v0.0.2 | Fluid-System (Screenshot stammt 0.0.5)

![Fluid-System mit Wasser und Lava inkl. Cobble-Stone-Generator Funktion](./project/0.0.2_fluid-system+reaction-system.png?raw=true)

v0.0.1 | Blöcke-System: Grass, Stein; Kreuzblöcke für kurzes Gras, Tulpen; Stufen, Zäune, Custom Modelle wie Zaubertisch 

![Ingame Screenshot mit Hotbar und Block Rendering](./project/0.0.0_1.png?raw=true)

![Ingame Screenshot mit hoher Sichtweite im Spectator Modus](./project/0.0.1_1.png?raw=true)

## License

Dieses Repository ist ausschließlich zur Ansicht veröffentlicht. Eine Nutzung, Vervielfältigung oder Weiterverwendung des Codes ist ohne ausdrückliche Genehmigung nicht gestattet.
