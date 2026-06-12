# SkyEngine

[![Java](https://img.shields.io/badge/Java-25-ED8B00?style=flat-square\&logo=openjdk)](https://openjdk.org)
[![LWJGL](https://img.shields.io/badge/LWJGL-3-FFFFFF?style=flat-square)](https://www.lwjgl.org)
[![OpenGL](https://img.shields.io/badge/OpenGL-4.6-5586A4?style=flat-square\&logo=opengl)](https://www.opengl.org)
[![Gradle](https://img.shields.io/badge/Gradle-8-02303A?style=flat-square\&logo=gradle)](https://gradle.org)

Eine moderne Voxel-Engine in Java, entwickelt mit LWJGL 3 und OpenGL. Das Ziel des Projekts ist die Entwicklung einer performanten und modularen Engine für voxelbasierte Welten mit Chunk-System, Mesh-Generierung und Echtzeit-Rendering.

Diese Engine ist dabei eine Migration aus meinen verschiedensten Engines, welche ich bisher entwickelt habe.


## Features

- 🌍 Chunk-System inkl. Welt Rendering (Chunks sind Säulenförmig a 32x512x32m jedoch in Sektionen mit 32³ Blöcken verteilt)
- 📷 Kamera inkl. Inverse Depth für bessere Tiefenaufteilung + Frustum Culling
- 🫲🏼 Block Platzieren/Abbauen
- 💥 AABB Sweep Kollision + Spieler Physik wie Springen, Sneaken, Strafe, Fliegen etc.

## Development

### Prerequisites

- Java 25
- Gradle 8+

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

## Screenshots

Comming soon.

## Goals

Die Engine dient als Lern- und Entwicklungsprojekt für moderne Rendering-Techniken, Engine-Architekturen und die Optimierung voxelbasierter Welten.

Der Fokus liegt auf:

- Performance
- Sauberer Architektur
- Erweiterbarkeit
- Moderne OpenGL-Techniken

## License

Dieses Repository ist ausschließlich zur Ansicht veröffentlicht.
Eine Nutzung, Vervielfältigung oder Weiterverwendung des Codes
ist ohne ausdrückliche Genehmigung nicht gestattet.
