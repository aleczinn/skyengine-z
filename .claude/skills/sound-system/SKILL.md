---
name: sound-system
description: OpenAL-Audio — SoundManager (Source-Pool, Effekt-Preload, Musik-Playlist), MusicPlayer (Streaming von .ogg/.wav), BlockSoundGroup-Ableitung, GameContainer-Hooks (Schritte/Hit/Break/Place, Hurt/Fall, Essen), Asset-Extraktion aus Minecraft. Lesen bevor Sounds, Musik, OpenAL, Sound-Gruppen oder Lautstärke-Settings angefasst werden.
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
- **`MusicPlayer`** — Streaming in 3 rotierende AL-Buffer, Refill pro Frame via
  `AL_BUFFERS_PROCESSED`, Underrun-Neustart, `AL_SOURCE_RELATIVE` (Musik klebt am Listener).
  Das Dekodieren steckt hinter **`MusicStream`** (package-private): `VorbisMusicStream`
  (`stb_vorbis_open_filename`, Loop via `stb_vorbis_seek_start`) und `WavMusicStream`
  (`javax.sound.sampled`, JDK-Bordmittel — **keine** Fremdbibliothek; setzt auf PCM signed
  16 Bit LE um, Samplerate bleibt, weil OpenAL jede akzeptiert; `seekStart` = neu öffnen).
  `play(File, boolean)` liefert **`boolean`**: unbekannte Endung, defekte Datei oder „kein
  einziger Buffer gefüllt" ⇒ Warnung + `false`, damit die Playlist die Datei überspringen
  kann statt hängen zu bleiben. **`WavMusicStream.read` schreibt absolut (`put(index, …)`)** —
  der Vorbis-Pfad lässt die Buffer-Position ebenfalls auf 0 stehen und `fillAndQueue` setzt
  danach nur noch das Limit; eine verschobene Position ergäbe einen leeren AL-Buffer.
  MP3 geht bewusst nicht: dafür hat weder das JDK noch LWJGL einen Decoder.
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
- **`BlockOpenSound`** — Auf-/Zu-Sounds (WOOD_DOOR/IRON_DOOR/CHEST), **bewusst ein eigenes
  Konzept neben `BlockSoundGroup`**, wie MCs `BlockSetType` neben dem `SoundType`: Eichenbretter
  und Eichentür klingen beim Abbauen gleich (beide WOOD), aber nur die Tür geht auf. Jeder Satz
  trägt seinen Ordner **plus Gain und Pitch-Jitter** (Vanilla: Tür 1.0/fest, Truhe 0.5/variabel).
  Zuordnung `BlockOpenSound.resolve`: JSON-Feld `"open_sound"` gewinnt (unbekannt → Warnung +
  **null = stumm**, nicht Fallback!), sonst Archetyp `door` → WOOD_DOOR, sonst null. Verdrahtung
  wie bei `sound`: `BlockDefinition.open_sound` → `ArchetypeBlockFactory` → `BlockConfig.openSound`
  → `Block.getOpenSound()`. **Regel: ein eigener Ordner je Satz** (`door/wood`, `door/iron`,
  `chest`) — die Extraktion behält die MC-Dateinamen, `wooden_door/open1.ogg` und
  `iron_door/open1.ogg` würden sich sonst überschreiben. Eine neue Türart ist damit: Assets,
  eine Enum-Konstante, eine JSON-Zeile. IRON_DOOR ist vorgeladen, hat aber noch keinen Block.
- **Lose Spieler-/UI-Sounds** (ohne BlockSoundGroup, via `loadVariants(folder, base)` —
  nummerierte Varianten `<base>1..N.ogg`, sonst Einzeldatei `<base>.ogg`; fehlt beides:
  Warnung + null = stumm): `ui/click`, `damage/hit1-3` (Hurt), `damage/fallsmall`/`fallbig`
  (Aufprall), `eat/eat1-3` (Kauen), `eat/burp`. Play-Methoden `playHurt()`/`playFall(big)`/
  `playEat()`/`playBurp()` — alle Kanal `PLAYER`, nicht-positional, ±10 % Pitch.
- **Hooks im `GameContainer`:** `soundManager.init()` vor `applySettings()`, danach
  `startMusicPlaylist()`; pro Frame in `renderWorld` nach
  `camera.update`: `updateListener(camera)` + `update()` (Musik-Refill); pro Tick
  `updateStepSounds()` + `updateHurtSounds()` (pollt `EntityPlayer.consumeHurt()`/
  `consumeFallDamage()` — der Schaden entsteht tief in der Physik, EntityPlayer kennt
  keinen SoundManager; Fall-„big" ab 4 Schaden) sowie Ess-Sounds in `updateEating`
  (Kauen alle 4 Ticks, Burp beim Abschluss); Hit alle 250 ms in `updateMining`, Break in
  `breakTargetBlock`, Place nach `world.placeBlock` UND in `tryMergeSlab`; `dispose()` am Ende.
- **Auf/Zu-Hooks liegen NICHT im GameContainer:** Tür in `DoorBehavior.onUse` (hat `World` ⇒
  `world.getSoundManager()`, nullbar wie beim TNT-Fuse), Truhe in `ChestBlockEntity.setOpen` —
  und dort mit Absicht statt in `GuiChest.onClose`: `GuiScreen.onClose()` bekommt keinen
  `GuiManager`, der GUI-Weg bräuchte also eine Signaturänderung quer durch die GUI-Basis und
  deckte trotzdem weniger ab. Über `setOpen` laufen **alle** Schließwege (ESC, Inventar-Taste,
  Todesscreen, Welt verlassen), und der Sound kann nicht von der Deckel-Animation abdriften.
  Die Wächter `if (open == this.open) return;` sorgt dafür, dass nur echte Wechsel klingen.

## Musik-Playlist

Der **Ordner ist die Playlist**: `SoundManager.startMusicPlaylist()` scannt beim Boot
`game/sounds/music` auf `.ogg`/`.wav` (`listFiles`, wie `ItemLoader`/`I18n` — `soundsDir` liegt
im Dateisystem unter `Files.RESOURCES_PATH`, nicht im Classpath). Ein Lied dazu heißt: Datei
hineinkopieren, sonst nichts. Es gibt **keine** Index-/Konfigurationsdatei und keinen Re-Scan
zur Laufzeit.

- **Shuffle-Bag**, kein Würfeln je Lied: die Liste wird gemischt und einmal komplett
  durchgespielt, dann neu gemischt (`reshuffle`). Fiele das neue erste Lied auf das eben
  gespielte, wird es weggetauscht — sonst hört man an der Bag-Grenze doch eine Wiederholung.
- **Fortschalten** in `SoundManager.update()` (läuft jeden Frame, auch im Hauptmenü):
  `if (playlistActive && !music.isPlaying()) playNextTrack();`. Das Ende erkennt der
  `MusicPlayer` selbst (`exhausted && AL_BUFFERS_QUEUED == 0` → `stop()` → `playing = false`),
  das nächste Lied startet ohne Pause im selben Frame.
- **`isPlaying()` ist absichtlich true, solange pausiert ist** (`pause()` setzt nur `paused`) —
  im Pausenmenü darf die Playlist nicht weiterrücken.
- Liefert `music.play` `false`, fliegt die Datei aus der Liste und der nächste Kandidat wird
  probiert; die Schleife ist auf die Listenlänge gedeckelt (sonst Endlos-Loop im Frame). Bleibt
  nichts übrig: `playlistActive = false` + Warnung, nie ein Crash.
- `stopMusic()` beendet **auch** die Playlist (sonst startete sofort das nächste Lied).
- Ein einziges Lied im Ordner verhält sich automatisch wie der frühere Loop. Ein
  `playMusic(relPath, loop)` gibt es nicht mehr.

## Pause (Pausenmenü)

`SoundManager.pauseAll()`/`resumeAll()`, angestoßen an der Flanke in
`GameContainer.updatePaused` (nicht pro Tick). Die **Musik** pausiert dabei nur, wenn das
Setting `GameSettings.pauseMusicInMenus` gesetzt ist (Default an = altes Verhalten) — sonst
laufen im Menü nur die Geräusche still, die Musik streamt weiter und schaltet am Liedende
auch weiter. Gebündelt in `SoundManager.applyMusicPause(boolean gamePaused)`; `GuiSoundOptions`
ruft dieselbe Methode mit `GuiManager.pausesGame()`, weil der Screen aus dem Pausenmenü
erreichbar ist und der Schalter dort **sofort** greifen muss.

Drei Dinge, die man dabei falsch machen kann:

- **Nur `AL_PLAYING` pausieren, nur `AL_PAUSED` fortsetzen.** Ein pauschales `alSourcePlayv` über
  den Pool würde gestoppte Sources **von vorn** neu starten.
- **`acquireSource` darf pausierte Sources nicht als frei sehen.** „Frei" ist deshalb
  `AL_STOPPED || AL_INITIAL`, nicht `!= AL_PLAYING` — sonst wird eine pausierte Source neu
  vergeben, ihre Wiedergabe ist weg und `alSourcei(src, AL_BUFFER, …)` liefert laut Spec
  `AL_INVALID_OPERATION`. Neue Sounds bleiben in der Pause absichtlich möglich: die
  Menü-Button-Klicks sollen klingen (wie in MC).
- **`MusicPlayer` braucht ein `paused`-Flag mit Early-Return in `update()`.** Dessen
  Underrun-Schutz prüft `SOURCE_STATE != AL_PLAYING` — `AL_PAUSED` erfüllt das, und da
  `soundManager.update()` unbedingt jeden Frame läuft, würde die Musik sich im nächsten Frame
  selbst entpausieren (Symptom: Log-Spam „Musik-Underrun"). `stop()` taugt nicht als Pause, es
  schließt den vorbis-Handle.

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
- **Settings:** `masterVolume` = Listener-Gain (wirkt global) plus ein **Mischpult je
  `SoundCategory`** (MUSIC/WEATHER/BLOCKS/HOSTILE/FRIENDLY/PLAYER/AMBIENT/UI):
  `GameSettings.soundVolumes` (Map, 0..100 in `options.json`) → `SoundManager.categoryGains`
  (effektiv master × category, wie MC). Dazu `audioDevice` (Ausgabegeräte-Auswahl,
  `GuiSoundOptions`). Ein einzelnes `musicVolume` existiert nicht mehr.
- **TNT:** `playExplosion()` / `playFuse()` sind eigene lose Effekt-Sounds (Explosion/Lunte).

## Verifikation

Nur hörbar im laufenden Fenster (`./gradlew run`): Laufen auf verschiedenen Untergründen
(Gras/Sand/Holz/Wolle), Block abbauen (Hit-Takt + Bruch), platzieren, Musik im Hintergrund.
Minimal-Check ohne Lautsprecher: Log-Zeilen „Audio initialisiert: N Effekt-Sounds geladen",
„Musik-Playlist: N Lied(er) gefunden." und „Musik gestartet: …" — Letztere muss über mehrere
Starts hinweg **wechselnde** Dateinamen zeigen und am Liedende erneut erscheinen. Ohne extrahierte Sounds startet die Engine stumm mit Warnung —
das ist der beabsichtigte Zustand für Checkouts ohne MC-Installation.
