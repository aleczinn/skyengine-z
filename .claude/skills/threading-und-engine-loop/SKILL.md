---
name: threading-und-engine-loop
description: Threading-Modell der Engine (Render-Thread vs. Window-Processing-Thread vs. Chunk-Worker), GLFW-Main-Thread-Regel, 20-TPS-Game-Loop, Reversed-Z. Lesen vor JEDER Änderung an Input, Fenster, Game-Loop, GL-State oder allem, was Threads berührt.
---

# Threading & Engine-Loop

## Das Modell (nicht raten — es ist ungewöhnlich)

`SkyEngine.launch()` (`core/SkyEngine.java`) startet **zwei** Threads, plus einen Worker-Pool:

1. **"Render Thread"** (`gameLoop()`): Hier laufen `input.update()` → `onUpdate()` (Tick, fest 20 TPS
   mit Akkumulator, max. 10 Nachhol-Ticks) → `onRender(partialTick)`. Der **OpenGL-Kontext lebt hier**.
   Auch die gesamte Spiel-Logik (World.update, Block-Edits, GUI) läuft auf diesem Thread —
   Tick und Render sind NICHT getrennt.
2. **"Window-Processing Thread"** (der eigentliche Main-Thread): `runWindowProcessLoop()` mit
   `glfwWaitEvents()` und Abarbeitung der `mainThreadTasks`-Queue.
3. **"Chunk Worker"-Pool** (`ChunkManager`): `max(2, cores-2)` Daemon-Threads mit
   `PriorityBlockingQueue` — Generierung, Dekoration, Meshing, LOD. **Kein GL hier, nur Daten.**

Ein schwächeres Modell nimmt typisch an, der Main-Thread sei der Render-Thread. Falsch herum:
Der Main-Thread macht NUR Fenster-Events.

## Die zwei harten Regeln

1. **GLFW-Fenster-/Cursor-Funktionen NUR auf dem Main-Thread.** `glfwSetInputMode`,
   Fenstermodus-Wechsel etc. müssen über `SkyEngine.get().addTaskToMainThread(...)` laufen
   (weckt per `glfwPostEmptyEvent`). Muster: `Input.disableCursor()`, Fullscreen-Toggle in
   `GameContainer.handleGlobalHotkeys` (F11). **Ausnahme:** `glfwSwapInterval` (VSync) gehört auf den
   Render-Thread, weil es den GL-Kontext braucht — siehe Kommentar in `GameContainer.applySettings`.
2. **GL-Aufrufe NUR auf dem Render-Thread.** Init-Arbeit vor Fenster-Anzeige läuft über die
   `renderTasks`-Queue (`DelayedRunnable`, siehe `launch()`).

## Reversed-Z (Falle bei allem mit Depth)

Bei `Window.getProperties().isUseInverseDepth()`: `glClipControl(ZERO_TO_ONE)` +
`glDepthFunc(GL_GREATER)` + `glClearDepth(0.0)` (nah ≈ 1, fern ≈ 0), gesetzt pro Frame in
`SkyEngine.onRender`. Wer Depth-Funcs anfasst, muss BEIDE Modi behandeln — Muster:
`ChunkRenderer.renderSolid` mappt für den CUTOUT-Pass `GREATER→GEQUAL` bzw. `LESS→LEQUAL`
("or-equal", für koplanare Gras-Overlays) und stellt den vorherigen Func danach wieder her.
Niemals blind `GL_LESS`/`GL_LEQUAL` hartkodieren.

## Weitere Nicht-Offensichtlichkeiten

- Während eines aktiven Fenster-Resizes wird NICHT gerendert/geswappt (`window.isResizing()` →
  Sleep 8 ms), sonst flackert der ganze Desktop (modale Win32-Resize-Schleife). Ticks laufen weiter.
- `partialTick = accumulatedTime / TICK_TIME_NANOS` ist die Interpolationsbasis für Kamera/Entities
  (`Camera.follow(player, partialTick)`). Wer neue bewegte Objekte rendert, interpoliert
  prev→current mit partialTick, sonst ruckelt es bei >20 FPS.
- Screenshots (F2): nur Flag setzen (`GameContainer.screenshotRequested`); der Pixel-Read passiert
  in `SkyEngine.onRender` NACH `blitToScreen()` und VOR `glfwSwapBuffers` → Ordner `screenshots/`.

## Verifikation

- Kompiliert: `./gradlew compileJava`. Verhalten (Cursor, Fullscreen, Flackern) ist **nur mit
  laufendem Fenster** prüfbar (`./gradlew run`) — sonst ehrlich als „ungetestet" ausweisen.
- Bei jeder neuen GLFW-Fensterfunktion fragen: „Läuft dieser Aufruf über
  `addTaskToMainThread`?" Bei jedem neuen GL-Call: „Bin ich auf dem Render-Thread?"
- Bei Depth-Änderungen: beide Zweige (`isUseInverseDepth()` true/false) durchdenken und den
  vorherigen GL-State wiederherstellen.
