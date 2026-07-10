---
name: visuelle-verifikation
description: Wie man Änderungen an dieser Engine ehrlich verifiziert — was ohne Fenster prüfbar ist (compileJava, GeneratorMapExporter), was das laufende Spiel braucht, welche Debug-Hotkeys existieren, F2-Screenshots. IMMER lesen, bevor man eine Änderung als „fertig/verifiziert" meldet.
---

# Visuelle Verifikation — was „funktioniert" hier bedeutet

## Es gibt keine Tests. Diese Stufen existieren stattdessen:

1. **`./gradlew compileJava`** — schneller Pflicht-Check. Beweist NUR Kompilierbarkeit.
2. **`GeneratorMapExporter`** (`generator/debug/`, eigene `main`, **kein GL/Engine-Start**):
   Falschfarben-PNGs (Klima, Biome, Höhen, Oberfläche, `section` = echte generate()-Chunks)
   nach `debug-maps/`; Args `<step> <centerX> <centerZ>`. Der richtige Weg für
   Weltgen-Änderungen — Sekunden statt Flug. **Bit-Identitäts-Beweis:** Karten vor/nach der
   Änderung per Hash vergleichen. Aufruf (PATH-Java ist zu alt, Projekt braucht JDK 25;
   Pfade ggf. prüfen):
   ```powershell
   & "$env:USERPROFILE\.jdks\ms-25.0.3\bin\java.exe" -cp "build\classes\java\main;<gson-jar aus ~\.gradle\caches>" `
     de.skyengine.game.world.generator.debug.GeneratorMapExporter 4 0 0
   ```
3. **`./gradlew run`** — alles Sichtbare (Meshing, Rendering, Fluids, LOD, GUI, Tints) ist NUR so
   prüfbar. Konsole zeigt FPS/TPS jede Sekunde; der Fenstertitel (im Debug-Modus) Sections
   sichtbar/total, Chunk-Zahl, Spielerposition.

**Ehrlichkeitsregel (verbindlich, aus CLAUDE.md):** Wenn das Fenster nicht lief, die Änderung als
„kompiliert, visuell ungetestet" ausweisen — niemals als verifiziert. Ein schwächeres Modell
neigt dazu, „kompiliert" mit „funktioniert" zu verwechseln; bei Renderern mit Fences,
Race-Fenstern und GL-State ist das die teuerste Verwechslung im Projekt.

## Debug-Hotkeys (in GameContainer verdrahtet)

| Taste | Wirkung |
|---|---|
| F2 | Screenshot nach `screenshots/` (nach fertigem Frame gelesen) |
| F6 / F7 | Wireframe / Chunk-Bounding-Box |
| F8 | Alle Chunks verwerfen und neu laden (Determinismus-Check!) |
| P | Chunk-Loading/-Unload einfrieren (Edit-Remeshes laufen weiter; friert auch LOD-Desired ein) |
| G | Gamemode durchschalten (Survival/Creative/Spectator) |
| N | Distanz-Fog an/aus |
| V | NoClip (nur im Flugmodus wirksam); Doppel-Leertaste = Fliegen togglen |
| − / = | Render-Distanz; [ / ] GUI-Scale; F11 Fullscreen |
| KeyBindings.AMBIENT_OCCLUSION / .LOD | AO-Toggle (remeshAll) / LOD-Toggle (Epoche) |

Startinventar für Test-Blöcke: `GameContainer.fillStartInventory` (dort neue Blöcke zum
visuellen Testen eintragen; der TEMP-Block dort ist genau dieses Muster).

## Automatisiertes Prüfen mit laufendem Fenster

Screenshots lassen sich per F2 auslösen und aus `screenshots/` lesen; Fenster-Screenshots/
Input-Injection von außen (PowerShell) müssen das Fenster **case-sensitiv** über den Titel
`SkyEngine v*` matchen — ein laxer Match trifft sonst das IntelliJ-Fenster mit dem Projektnamen.
Wichtig: Der User übernimmt das Engine-Fenster manchmal selbst, auch mitten in einem
Screenshot-Durchlauf — bei Anzeichen (unerwartete Kamerabewegung/Inputs) den automatisierten
Durchlauf sofort abbrechen statt weiterzusteuern.

## Typische Symptome → Verdachtsort

- Löcher/falsche Faces an Chunk-Grenzen → Nachbar-Gating/Dirty-Masken (chunk-pipeline).
- Helle Funkel-Striche auf Augenhöhe → AO-Extrapolation, Clamp im Fragment-Shader fehlt (chunk-meshing).
- Sporadischer Geometrie-Müll → Arena-Free ohne Fence-Schutz (mdi-rendering).
- Block unsichtbar → fehlende `models/block/<id>.json` (block-modelle-und-texturen).
- Falsche Textur auf anderem Block → `layerOf` nach TextureArray-Bau aufgerufen.
- Schlitze am LOD/L0-Übergang → fehlende Skirts an Masken-Kanten (lod-system).
- Naht in der Welt nach F8 → Generator-Purity verletzt (weltgen-v2).
