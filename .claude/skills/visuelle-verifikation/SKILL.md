---
name: visuelle-verifikation
description: Wie man Änderungen an dieser Engine ehrlich verifiziert — was ohne Fenster prüfbar ist (compileJava, GeneratorMapExporter), was das laufende Spiel braucht, welche Debug-Hotkeys existieren, F2-Screenshots. IMMER lesen, bevor man eine Änderung als „fertig/verifiziert" meldet.
---

# Visuelle Verifikation — was „funktioniert" hier bedeutet

## Es gibt keine Tests. Diese Stufen existieren stattdessen:

1. **`./gradlew compileJava`** — schneller Pflicht-Check. Beweist NUR Kompilierbarkeit.
2. **`./gradlew mapExport`** (`GeneratorMapExporter`, eigene `main`, **kein GL/Engine-Start**):
   Falschfarben-PNGs (Klima, Biome, Höhen, Oberfläche, `section` = echte generate()-Chunks)
   nach `debug-maps/`. Der richtige Weg für Weltgen-Änderungen — Sekunden statt Flug.
   **Bit-Identitäts-Beweis:** Karten vor/nach der Änderung per Hash vergleichen.
   Für eigene Args (`<step> <centerX> <centerZ>`): `--args="4 0 0"`.
3. **`./gradlew saveTest`** (`SaveRoundTripTest`, ebenfalls ohne GL): bootstrappt die **komplette
   Block-/Item-Registry**, generiert einen Chunk, serialisiert und vergleicht ihn. Deckt alles
   ab, was mit dem Laden von Blöcken, Modellen, Items, Properties und Persistenz zu tun hat —
   also fast jede JSON-Änderung. Die **Log-Zähler sind das schärfste billige Signal**: Anzahl
   Block-Definitionen/Modelle/„aus Block-Definitionen erzeugt"/Blockstates/Items, und die
   Warnungen „Modell fehlt"/„Variante ... fehlt"/„Modell-Datei ueberdeckt"/„Unaufgeloeste
   Platzhalter" müssen **null** Treffer haben.
4. **`./gradlew lightTest`** (`LightProbe`, ohne GL): Himmels- UND Blocklicht fensterlos —
   Heightmap, Flood, Chunk-Naht, Emitter setzen/abbauen, bis in den gepackten Licht-Int des
   Vertex-Puffers. Der richtige Prüfstand für alles im Skill `licht-system`.
5. **`./gradlew meshTest`** (`MesherCensus`, ohne GL): deterministischer Mesher-Zensus —
   3×3 Generator-Chunks (Seed 123), Quad-Zähler je Layer + FNV-Hash über alle Vertex-Daten.
   Identische `MESH <hash>`-Zeile vor/nach einer Mesher-Änderung = bit-identische Geometrie;
   explodierende Quad-Zähler = stiller Greedy-Regress (Merge bricht weg).
6. **`./gradlew run`** — alles Sichtbare (Meshing, Rendering, Fluids, LOD, GUI, Tints) ist NUR so
   prüfbar. Konsole zeigt FPS/TPS jede Sekunde; der Fenstertitel (im Debug-Modus) Sections
   sichtbar/total, Chunk-Zahl, Spielerposition.

**Für JSON-Massenänderungen** (Presets, Modell-Migration) reichen Zähler nicht: dort einen
Vorher/Nachher-Vergleich der *effektiv aufgelösten* Daten gegen `git show HEAD:<datei>` fahren
(gemergte Block-Definition je Block, aufgelöste Texturmap je Modellname). Genau das hat bei der
Preset-Migration zwei stille Regressionen gefunden, die alle Zähler passiert hatten.

**Ehrlichkeitsregel (verbindlich, aus CLAUDE.md):** Wenn das Fenster nicht lief, die Änderung als
„kompiliert, visuell ungetestet" ausweisen — niemals als verifiziert. Ein schwächeres Modell
neigt dazu, „kompiliert" mit „funktioniert" zu verwechseln; bei Renderern mit Fences,
Race-Fenstern und GL-State ist das die teuerste Verwechslung im Projekt.

## Hotkeys & Debug-Schalter (Stand 2026-07-29)

Verdrahtete Tasten (KeyBindings bzw. GameContainer):

| Taste | Wirkung |
|---|---|
| F2 | Screenshot nach `%APPDATA%\.skyengine\screenshots\` (aus dem fertigen Default-Framebuffer inkl. GUI, vor dem Present) |
| F3 | Debug-Overlay; **F3+H** Hitboxen, **F3+G** Chunk-Grenzen |
| F5 | Perspektive (Ego/hinten/vorne) |
| G | Gamemode durchschalten (Survival/Creative/Spectator) |
| E / Q | Inventar / Item droppen |
| F11 | Fullscreen |
| ESC | Pausenmenü (löst auch einen Save aus) |

**Alle übrigen Debug-Schalter liegen im `GuiDebugScreen` (Optionsmenü), NICHT auf Tasten:**
Wireframe, GpuCull an/aus + Occlusion-Debug-Tint, LOD-Gras-Overlay, Chunk-Loading einfrieren
(Edit-Remeshes laufen weiter), alle Chunks neu laden (Determinismus-Check; LOD-Neuaufbau geht
über den LOD-Toggle in den Grafik-Optionen). AO/LOD/Render-Distanz/Fog/AA schalten die
Grafik-Optionen (`GuiVideoSettings`, Live-Apply). Die früheren Hotkeys F6/F7/F8/P/N/V/−/=/[/]
existieren nicht mehr.

Startinventar für Test-Blöcke: `GameContainer.fillStartInventory` (dort neue Blöcke zum
visuellen Testen eintragen; greift nur bei frisch erstellten Welten).

## Automatisiertes Prüfen mit laufendem Fenster

Screenshots lassen sich per F2 auslösen und aus `%APPDATA%\.skyengine\screenshots\` lesen; Fenster-Screenshots/
Input-Injection von außen (PowerShell) müssen das Fenster **case-sensitiv** über den Titel
`SkyEngine v*` matchen — ein laxer Match trifft sonst das IntelliJ-Fenster mit dem Projektnamen.
Wichtig: Der User übernimmt das Engine-Fenster manchmal selbst, auch mitten in einem
Screenshot-Durchlauf — bei Anzeichen (unerwartete Kamerabewegung/Inputs) den automatisierten
Durchlauf sofort abbrechen statt weiterzusteuern.

## Typische Symptome → Verdachtsort

- Löcher/falsche Faces an Chunk-Grenzen → Nachbar-Gating/Dirty-Masken (chunk-pipeline).
- Helle Funkel-Striche auf Augenhöhe → AO-Extrapolation, Clamp im Fragment-Shader fehlt (chunk-meshing).
- Sporadischer Geometrie-Müll → Arena-Free ohne Fence-Schutz (mdi-rendering).
- Block unsichtbar → weder `model`/`models` in der Block-JSON noch eine gleichnamige
  `models/block/<id>.json` (block-modelle-und-texturen).
- Textur-Änderung im Block wirkt nicht → eine gleichnamige Modell-Datei überdeckt die
  Block-Definition (Warnung im Log) oder ein Preset-Feld wird vom Kind überschrieben.
- Falsche Textur auf anderem Block → `layerOf` nach TextureArray-Bau aufgerufen.
- Schlitze am LOD/L0-Übergang → fehlende Skirts an Masken-Kanten (lod-system).
- Naht in der Welt nach „Chunks neu laden" → Generator-Purity verletzt (weltgen-v2).
