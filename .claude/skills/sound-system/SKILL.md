---
name: sound-system
description: OpenAL-Audio — SoundManager (Source-Pool, Effekt-Preload), MusicPlayer (stb_vorbis-Streaming), BlockSoundGroup-Ableitung, GameContainer-Hooks (Schritte/Hit/Break/Place, Hurt/Fall, Essen), Asset-Extraktion aus Minecraft. Lesen bevor Sounds, Musik, OpenAL, Sound-Gruppen oder Lautstärke-Settings angefasst werden.
---

# Sound-System (OpenAL)

## Architektur (Paket `de.skyengine.audio`)

- **`SoundManager`** — ALC-Device/Context, alle Effekt-Sounds beim Init als AL-Buffer
  vorgeladen (Varianten `<base>1..8.ogg`), 12er-Source-Pool im Round-Robin (**kein
  Stehlen** — Pool voll ⇒ Sound wird verworfen). Fehlt der Sounds-Ordner oder das
  Audio-Gerät: `enabled=false`, alle play-Methoden sind No-Ops + eine Warnung (Muster
  Font-System, nie Crash).
- **`OggLoader`** — `stb_vorbis_decode_filename` → PCM → `alBufferData`. Der PCM-Pointer
  kommt aus malloc ⇒ mit `LibCStdlib.free` freigeben, nicht `MemoryUtil.memFree`.
- **`MusicPlayer`** — Streaming über `stb_vorbis_open_filename` + 3 rotierende AL-Buffer,
  Refill pro Frame via `AL_BUFFERS_PROCESSED`, Loop via `stb_vorbis_seek_start`,
  Underrun-Neustart, `AL_SOURCE_RELATIVE` (Musik klebt am Listener).
- **`BlockSoundGroup`** — Enum (STONE/WOOD/GRAVEL/GRASS/SAND/SNOW/CLOTH/GLASS) mit
  Datei-Basisnamen für `step/`, `dig/` und dem Platzieren (`placeName`, zeigt auf eine
  dig-Basis — es gibt keine place-Assets, Default = digName). Sonderfall wie MC: `GLASS`
  steppt auf „stone", bricht als „glass", **platziert als „stone"** (placeBuffers teilen
  die dig-Arrays laut placeName, nichts wird doppelt geladen). Gruppen mit gleichem
  Basisnamen **teilen sich die AL-Buffer** (Dedup beim Preload; dispose dedupliziert
  über Identität).
- **Zuordnung Block→Gruppe** (`BlockSoundGroup.resolve`): explizites `"sound"`-Feld in der
  Block-JSON gewinnt (unbekannter Wert → Warnung + STONE); sonst Ableitung AXE→WOOD,
  SHOVEL→GRAVEL, Archetyp cross/tall_cross→GRASS, Fallback STONE. Verdrahtung:
  `BlockDefinition.sound` → `ArchetypeBlockFactory` → `BlockConfig.soundGroup` →
  `Block.getSoundGroup()` (Muster wie `hardness`).
- **Lose Spieler-/UI-Sounds** (ohne BlockSoundGroup, via `loadVariants(folder, base)` —
  nummerierte Varianten `<base>1..N.ogg`, sonst Einzeldatei `<base>.ogg`; fehlt beides:
  Warnung + null = stumm): `ui/click`, `damage/hit1-3` (Hurt), `damage/fallsmall`/`fallbig`
  (Aufprall), `eat/eat1-3` (Kauen), `eat/burp`. Play-Methoden `playHurt()`/`playFall(big)`/
  `playEat()`/`playBurp()` — alle Kanal `PLAYER`, nicht-positional, ±10 % Pitch.
- **Hooks im `GameContainer`:** `soundManager.init()` vor `applySettings()`, danach
  `playMusic("music/minecraft.ogg", true)`; pro Frame in `renderWorld` nach
  `camera.update`: `updateListener(camera)` + `update()` (Musik-Refill); pro Tick
  `updateStepSounds()` + `updateHurtSounds()` (pollt `EntityPlayer.consumeHurt()`/
  `consumeFallDamage()` — der Schaden entsteht tief in der Physik, EntityPlayer kennt
  keinen SoundManager; Fall-„big" ab 4 Schaden) sowie Ess-Sounds in `updateEating`
  (Kauen alle 4 Ticks, Burp beim Abschluss); Hit alle 250 ms in `updateMining`, Break in
  `breakTargetBlock`, Place nach `world.placeBlock` UND in `tryMergeSlab`; `dispose()` am Ende.

## Threading

OpenAL läuft **komplett auf dem Render-Thread** — es hat (anders als GLFW) keine
Main-Thread-Bindung. Nichts auf den Window-Thread verschieben, keinen eigenen Audio-Thread
bauen: Init, play-Aufrufe, Frame-Updates und dispose teilen sich so einen Thread und
brauchen keinerlei Synchronisation.

## Fallstricke

- **Mono-Zwang für 3D-Effekte:** OpenAL schwächt Stereo-Buffer NICHT positional ab —
  ein Stereo-Effekt klingt überall gleich laut (stiller Bug). `OggLoader.load(file, true)`
  mixt deshalb L/R in-place auf Mono. Musik bleibt bewusst Stereo (source-relative).
- **Lautstärke/Pitch-Konventionen** (MC-Werte, Konstanten im SoundManager): Step 0.15/1.0
  (nicht-positional am Listener), Hit 0.25/0.5 (**nutzt die step-Dateien**, nicht dig!),
  Dig/Place 1.0/0.8; überall ±10 % Zufalls-Pitch. Positional: `AL_REFERENCE_DISTANCE=4`,
  `AL_MAX_DISTANCE=32`.
- **Step-Kadenz:** Distanz-Akkumulator (`STEP_INTERVAL=1.6` Blöcke) statt Timer — Sprint
  ergibt automatisch schnellere Schritte. Die Distanz akkumuliert AUCH in der Luft
  (MC: `walkDist`) — nur die Sound-Auslösung verlangt `onGround`; so gibt jeder
  Sprint-Sprung bei der Landung einen Schritt (Springen auf der Stelle bleibt stumm).
  Guards (kein Akkumulieren + kein Sound): Fliegen, Sneaken (lautlos wie MC),
  `isTouchingFluid`. Dafür existiert
  `EntityPlayer.isTouchingFluid(World)` als public Wrapper — `isInFluid` bleibt protected.
  Bodenblock via `floor(y - 0.2)` (trifft Slabs), bei AIR/Fluid eine Zelle tiefer probieren.
- **Asset-Extraktion:** `scripts/extract-mc-sounds.ps1` zieht die OGGs aus
  `%APPDATA%\.minecraft\assets` (Klarname→Hash über `indexes/<n>.json`). **Rein numerische
  Index-Namen (z.B. `32.json`) sind die neuesten** — nicht per Ziffernstrip vergleichen
  („1.19"→119-Falle). Moderne Musik heißt nach Songs (`minecraft.ogg`/`sweden.ogg`), nicht
  `calm1.ogg`; die step/dig-Pfade sind unverändert. Die Sounds sind MC-Platzhalter wie die
  Texturen.
- **Settings:** `masterVolume` = Listener-Gain (wirkt global inkl. Musik), `musicVolume` =
  Musik-Source-Gain (effektiv master × music, wie MC). Beide 0..100 in `options.json`,
  sanitize-Clamp in GameSettings, Anwendung in `GameContainer.applySettings` (/100F).

## Verifikation

Nur hörbar im laufenden Fenster (`./gradlew run`): Laufen auf verschiedenen Untergründen
(Gras/Sand/Holz/Wolle), Block abbauen (Hit-Takt + Bruch), platzieren, Musik im Hintergrund.
Minimal-Check ohne Lautsprecher: Log-Zeilen „Audio initialisiert: N Effekt-Sounds geladen"
und „Musik gestartet: …". Ohne extrahierte Sounds startet die Engine stumm mit Warnung —
das ist der beabsichtigte Zustand für Checkouts ohne MC-Installation.
