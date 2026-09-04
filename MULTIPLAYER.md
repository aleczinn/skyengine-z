# SkyEngine Multiplayer Architecture

Der Multiplayer-Umbau verwendet eine serverautoritative, transportneutrale Architektur. LOD ist
nicht Teil dieses Pfads; repliziert werden ausschließlich normale 32×512×32-L0-Chunkspalten mit
bis zu 16 nichtleeren 32³-Sections.

## Module und Abhängigkeiten

```text
skyengine-shared
    ↑             ↑
skyengine-server  │
    ↑             │
skyengine-client ─┘

skyengine-tools → shared/server
```

- `shared/` enthält ausschließlich Protokoll-, Registry-, Snapshot- und Gameplay-Datentypen.
  Es kennt weder OpenGL/GLFW noch Audio oder GUI.
- `server/` enthält Tick-Ownership, Sessions, Netty-TCP, den LocalTransport-Anschluss,
  Interest Management, Events und den headless Entrypoint.
- `client/` enthält Client-Session, Prediction/Reconciliation, Remote-Interpolation sowie
  replizierte Chunk-, Entity- und Inventarcaches. Während der schrittweisen Migration kompiliert
  dieses Modul zusätzlich den bisherigen Quellbaum unter `src/main/java`.
- `tools/` ist für headless Protocol-/Bot-/Skalierungswerkzeuge vorgesehen.

Der Server hat keine LWJGL-Abhängigkeit und kann über `gradlew serverRun` beziehungsweise als
Fat-Jar über `gradlew serverJar` gebaut werden. Die Distribution liegt anschließend unter
`server/build/libs/skyengine-server-all-*.jar`. `verifyHeadlessServerJar` ist Teil von `check` und
weist den Build ab, sobald Client-, Grafik-, Audio- oder LWJGL-Klassen in dieses Artefakt gelangen.

## Client starten und verbinden

Dedicated Server und Client werden in zwei getrennten Terminals beziehungsweise IntelliJ-
Konfigurationen gestartet:

```powershell
.\gradlew.bat serverRun
.\gradlew.bat :skyengine-client:run
```

Im Hauptmenü ist **Mehrspieler** aktiv. Der Screen bietet gespeicherte Server, Bearbeiten/Löschen,
Doppelklick zum Beitreten und eine Direktverbindung; ohne Port wird `25565` verwendet. Favoriten
liegen clientseitig in `config/servers.json` unter dem Spielordner. DNS- und TCP-Verbindungsaufbau
laufen auf einem virtuellen Thread, während Handshake, Login, Pack-/Registry-Konfiguration und
Packetverarbeitung weiterhin vom Client-Owner-Thread ausgeführt werden. Fehler und strukturierte
Disconnectgründe landen auf einem eigenen Screen; Abbrechen gibt Socket und Netty-Eventloop frei.

Jeder Eintrag wird parallel über einen begrenzten SkyEngine-Protokoll-Ping geprüft. Die Liste zeigt
Online/Offline, aktuelle/maximale Spielerzahl und Round-Trip-Latenz; ein erreichbarer Dienst mit
abweichender Protokollversion erscheint gesondert als inkompatibel und nicht fälschlich als
spielbarer Server. Der Ping führt weder Login noch World-Join aus und kann manuell aktualisiert
werden. Als rein lesender Netty-Fast-Path wartet er nicht auf den 20-TPS-Welttick; die gemeldete
Zeit beginnt erst nach dem TCP-Aufbau und endet am Empfang im Netty-Thread. Sie entspricht damit
der Paket-RTT statt Verbindungssetup, Tick-Scheduling oder Polling-Verzögerung; localhost wird bei
einem Roundtrip unter einer Millisekunde als `0 ms` angezeigt.

Der Verbindungsweg erreicht den Zustand `PLAY` und erstellt anschließend eine replizierte
`RemoteWorldView`. Sie verwendet den normalen L0-Renderer und zeigt den Verbindungsbildschirm so
lange, bis der erste vollständige 3×3-Nachbarschaftsring meshingbereit ist. Der headless Server
liefert dafür bereits deterministisch erzeugte, renderbare L0-Spalten. Nach dem ersten Ring wird
die normale Spielansicht geöffnet; weitere Chunks werden anhand des serverseitigen Interests
priorisiert nachgeladen. Die Ansicht enthält außer Terrain auch replizierte BlockEntities,
Spieler, Weltentities, Partikel, Sounds, HUD, Hand, Inventare und Container.

## Verbindungsablauf

```text
HANDSHAKE
  Handshake → HandshakeAccepted
  CompressionSelect → CompressionEnabled (Barriere)
LOGIN
  LoginStart → LoginSuccess
CONFIGURATION
  PackManifest → PackStatus
  RegistryData* → RegistryFingerprint → ConfigurationAck
JOINING
  JoinGame → ClientReady
PLAY
```

TCP und der In-Process-`LocalTransport` verwenden dieselben Packettypen und Zustände. Der
LocalTransport überspringt nur Encoding und Socketkopien. TCP verwendet einen begrenzten
VarInt-Frame, optional ausgehandeltes Zstd und logische Queues für `CONTROL`, `MOVEMENT`,
`GAMEPLAY`, `ENTITY`, `CHAT` und `CHUNK_DATA`. Kompression wird erst nach dem beidseitig sichtbaren
`CompressionEnabled`-Paket aktiviert, sodass keine State-/Frame-Race entstehen kann.

Chunk-Batches werden für TCP pro Verbindung in einer seriellen Worker-Queue kodiert und optional
Zstd-komprimiert. Die Tick-Thread-Reihenfolge `ChunkBatchStart -> ChunkColumnData -> ChunkBatchEnd`
bleibt dabei erhalten; der Tick-Thread wartet jedoch weder auf Encoding noch auf Kompression. Die
fertigen Frames landen in begrenzten Channel-Queues und werden mit einem konfigurierbaren
`chunk-bytes-per-second`-Budget geflusht. Der Tick vergibt dabei nur Bandbreitenkredit; Netty
leert diesen unabhängig von 20 TPS bei jeder erneuten Socket-Schreibbarkeit weiter. Lokale
TCP-Verbindungen werden unkomprimiert mit 128 MiB/s bedient. `LocalTransport` übernimmt dieselben
atomaren Batches direkt und ohne den Encode-/Decode-Umweg.

Pakete werden auf dem Network-I/O-Thread nur als begrenzte Frames angenommen. Decoding und jede
State-Transition passieren beim Polling auf dem Client- beziehungsweise Server-Owner-Thread. Somit
können mehrere direkt aufeinanderfolgende Zustandsphasen nicht untereinander vertauscht werden und
Network-I/O mutiert niemals autoritative Weltobjekte.

Der Servertick ist dabei explizit in drei Eigentumsphasen geteilt:

```text
Inbound Decode/Validation -> autoritativer World-Tick -> Chunk-/Entity-Replikation + Flush
```

Dadurch veröffentlicht die Replikation immer den Zustand des gerade abgeschlossenen World-Ticks
und nicht versehentlich den Stand des vorherigen Ticks.

## Authority und Replikation

- Der Client sendet `PlayerInputFrame` mit monotoner Sequenz, nicht seine Position. Der
  Server-World-Adapter liefert `PlayerStateSnapshot`; der Client entfernt bestätigte Inputs und
  simuliert ausschließlich noch unbestätigte Inputs erneut.
- Block-, Inventar- und Interaktionspakete sind Requests. Reichweite, Dimension, Rate und
  Transaktion werden serverseitig geprüft. Der Server sendet Resultat und autoritative Korrektur.
- Entity-IDs sind separate Network-IDs. Der Serverindex ist nach Dimension/Chunk gebucketet und
  führt einen Reverse-Index von Entity zu beobachtenden Sessions.
- Chunkinteressen werden als Differenz zweier begrenzter View-Bereiche gebildet. Snapshots werden
  priorisiert (Nähe, danach Bewegungsrichtung), asynchron angefordert und nur auf dem Tick-Thread
  veröffentlicht. Um den sichtbaren Kreis liegt seine exakte Chebyshev-Dilatation um eine Spalte:
  damit besitzt auch jeder Randchunk alle acht realen Meshing-Nachbarn. Veraltete, nach einem
  Interest-Wechsel fertiggestellte Jobs werden server- und clientseitig über Interest-/Receive-
  Generationen verworfen; ein alter Async-Decode kann keinen entladenen Chunk wieder einsetzen.
- Der Integrated Server verwendet einen gemeinsamen adaptiven CPU-Pool mit
  `availableProcessors - 4` Workern. Worldgen, Licht, Snapshot-Aufbau, Client-Decode und Meshing
  greifen auf dieses eine priorisierte Budget zu; es gibt keine feste Server-/Client-Aufteilung.
  Dedicated Server und Remote-Clients behalten dagegen jeweils ihren eigenen Pool.
- Leere Sections werden nicht übertragen. Paletten, BitStorage-Wörter, Biome-IDs, 33×33-Tintgrids,
  Heightmap und uniform/nibble-gepacktes Sky-/Blocklicht bleiben explizite Snapshotbestandteile.

`ChunkSnapshotEncoder` bildet den bereits existierenden `Chunk` unter dessen Read-Lock ohne
Weltobjekt-Leak auf genau dieses unveränderliche Netzwerkformat ab. Palette und BitStorage werden
nicht neu interpretiert; Worker dürfen den fertigen Snapshot anschließend gefahrlos komprimieren.
Die inverse `LegacyChunkSnapshotDecoder`-Brücke installiert Palette, gepackte Indizes, Heightmap,
33×33-Tints sowie uniformes oder nibble-gepacktes Licht direkt in die vorhandene L0-Repräsentation.
Protokollversion 11 kodiert Biome als VarInt, Tints als RGB24 und Heightmap-Werte als unsigned
short; übliche Spalten bleiben durch 512-KiB-Fragmente in einem Datenframe. Beim Remote-Decode
werden Registry-IDs bereits während des Parsens gemappt, statt den vollständigen Snapshot danach
noch einmal zu kopieren. Decode und Validierung laufen auf dem Worker-Pool; nur die fertige
Installation erfolgt auf dem Client-Owner-Thread. Der Batch wird erst danach bestätigt. Jede
Interest-Belegung einer Chunkkoordinate besitzt eine monotone Lease-ID. Dadurch kann ein
`UnloadChunk` veraltete, bereits kodierende TCP-Batches überholen, ohne dass deren spätes Ergebnis
den Chunk wieder einsetzt. Noch nicht kodierende Batches werden beim Verlassen des Interests
abgebrochen; zugleich begrenzt ein kleines ACK-Fenster den nicht mehr abbrechbaren Vorlauf.

Ein replizierter Chunk verwendet den unveränderten `ChunkMesher` ausschließlich mit einem
vollständigen realen 3×3-Nachbarschaftsring. Fertig hochgeladene Chunks treten in eine stabile,
zusammenhängende Präsentationsmenge ein und bleiben bis zum autoritativen Unload sichtbar. Ein
noch fehlender Chunk am neuen Spieleranker kann deshalb niemals den bereits dargestellten Radius
auf null zusammenklappen. Bei sehr schneller Reise darf der fertig vorbereitete neue Anker eine
zweite Komponente beginnen, an die weitere Chunks schrittweise anschließen. Es gibt keinen zweiten
Multiplayer-Terrainmesher und keinen späteren Voll-Remesh nur wegen nachgeladener Nachbarn.

Die Remote-Ansicht sendet pro Clienttick `PlayerInputFrame` mit Achsen, Tasten, Blickrichtung und
monotoner Sequenz. Bei geöffnetem GUI werden neutrale Eingaben gesendet, damit Keepalive und
Serverzustand weiterlaufen. Der Client simuliert ausstehende Inputs lokal mit derselben
deterministischen Bewegungsfunktion, verwirft bestätigte Inputs beim autoritativen Snapshot und
spielt nur den Rest erneut ab. Zwischen vorhergesagten Ticks wird interpoliert; kleine
Reconciliation-Korrekturen laufen weich aus, große Korrekturen und Dimensionswechsel springen
sofort auf die Serverwahrheit.

GameMode und Flugzustand sind Teil des autoritativen Player-Snapshots. `G` fordert den nächsten
Modus an, Doppel-Leertaste schaltet den Creative-Flug; Survival, Creative und Spectator werden vom
Server validiert und zurückgesendet. Alternativ setzt die Serverkonsole den Modus mit
`gamemode <survival|creative|spectator> <player>`. Im Offline-Testbetrieb erzeugt jeder gestartete
Client genau eine neue zufällige UUID, die über mehrere Verbindungen desselben Prozesses stabil
bleibt. Dadurch können mehrere Clients mit demselben lokalen Benutzernamen gleichzeitig testen.
Seit Protokollversion 5 wird die Movement-Buttonmaske als VarInt übertragen; damit bleiben auch
Toggle-Eingaben oberhalb von Bit 7 erhalten. Die per Mausrad einstellbare Spectator-Geschwindigkeit
ist ebenfalls serverautoritativ und Bestandteil von Prediction und Reconciliation.

## Sicherheit und Diagnose

- feste Grenzwerte für Frames, dekomprimierte Daten, Strings, Collections, Packs, Registries,
  Entity-Metadaten und Item-Komponenten;
- striktes UTF-8, begrenzte VarInts und vollständiger Payload-Verbrauch;
- erlaubte Packet-ID wird immer gegen Richtung und Connection State geprüft;
- Token-Buckets für Movement, Gameplay, Inventar und Chat sowie begrenzte In-/Outboundqueues;
- Keepalive unabhängig von Movement, RTT, Timeout und strukturierte Disconnectgründe;
- die Dedicated-Server-Konsole meldet Weltpfad/Seed sowie Login, Join, Leave und Kick mit
  Spielername, Identität und Disconnectgrund;
- `net` in der Serverkonsole zeigt Paket-/Bytezähler, Queues, RTT, getrackte Chunks sowie
  aktive/gesamte World-Worker und deren Warteschlange;
- die Kommandos `list`, `tps`/`perf`, `net`, `kick` und `stop` verwenden für Konsole und
  Netzwerkclients denselben Dispatcher; privilegierte Kommandos bleiben der Konsole vorbehalten;
- der Netzwerk-Snapshot enthält zusätzlich Anzahl, Pakete und CPU-Zeit der asynchron kodierten
  Chunk-Batches;
- `SimulatedTransportConnection` erzeugt reproduzierbar Latenz, Jitter, Bandbreitenlimit sowie
  für unzuverlässige Klassen Loss, Reordering und Duplikate.

## Tests

```powershell
$env:JAVA_HOME='C:\Users\alec_\.jdks\ms-25.0.4'
.\gradlew.bat :skyengine-shared:test :skyengine-server:test :skyengine-client:test
.\gradlew.bat check
.\gradlew.bat multiplayerLoadTest -Pplayers=8 -Pseconds=10
.\gradlew.bat multiplayerLoadTest -Pplayers=100 -Pseconds=30
```

Die Tests decken unter anderem binäre Roundtrips, ungültige VarInts/UTF-8/Größen, Zustände,
Timeout, LocalTransport, einen echten TCP-Join mit aktivem Zstd, Chunk-Batches und Revisionen,
Interest-Diffs, den gegenseitigen Join-Abgleich mehrerer Spieler, Prediction-Replay, Events und
den räumlichen Entity-Index ab. Der aktuelle Stand umfasst 551 erfolgreiche automatisierte Tests
in 152 Suites; zusätzlich bleiben Save-, Lighting- und deterministische L0-Mesher-Abnahme grün.

Der letzte Befehl startet rein headless bis zu 100 (konfigurierbar auch mehr) lokale Protokollbots,
führt für jeden den vollständigen Handshake/Login/Configuration/Join aus, sendet anschließend mit
20 Hz Movement-Inputs und meldet Tick-Median/p95/Maximum, Paketmengen und Queuefüllstände. Damit
lassen sich spätere Netzwerkänderungen reproduzierbar gegen 8/16/32/64/100 Sessions prüfen.

Referenzlauf vom 1. September 2026 auf der Entwicklungsmaschine (fünf Sekunden Simulationszeit):

```text
8 Spieler:   8/8 PLAY,   Tick median/p95/max 0,109/1,188/32,895 ms, Queues 0/0
100 Spieler: 100/100 PLAY, Tick median/p95/max 0,362/2,028/104,535 ms, Queues 0/0
```

Das Maximum des 100-Spieler-Laufs enthält die einmalige Join-Welle und ist kein Steady-State-Wert.

## Gameplay-Parität

`ServerWorldRuntime` ist die einzige Grenze zur autoritativen Welt. Der Dedicated Server verwendet
dahinter inzwischen dieselben `World`-, `Dimension`-, Generator-, Feature-, Struktur-,
`EntityPlayer`-, BlockShape-, Chunk-Simulations-, Registry- und Persistenzsysteme wie der bisherige
Singleplayer. Es existiert kein vereinfachter Multiplayer-Generator und keine zweite
Terrainkollision mehr.

Pro Dimension existiert serverseitig genau ein gemeinsamer autoritativer `ChunkManager` mit einer
gemeinsamen Map geladener Chunks. Spieler besitzen keine eigenen Chunkkopien auf dem Server;
`ChunkInterestManager` und `ChunkReplicationService` halten pro Session nur Koordinatenmengen,
Prioritäten und Übertragungszustände. Erst auf jedem Client liegt zusätzlich ein nicht
autoritativer replizierter Chunkcache für Rendering, Prediction, Kollision und Raycasting.

Serverautoritativ repliziert werden insbesondere normale L0-Chunks samt Biomen und Licht,
Blockänderungen und BlockEntities, Spielerzustand und Inventar, Container/Crafting, Items,
Minecarts, ItemFrames, fallende Blöcke, TNT, Weltgeräusche, Tod/Respawn sowie Dimensions- und
Portalwechsel. SIMPLE- und Nether-Portale verwenden dieselben sicheren Ankunftsregeln,
persistenten Portal-IDs und Links wie der Singleplayer; während Zielchunks laden, bleibt der
Spieler serverseitig eingefroren.

Der grafische Client führt nur Prediction und Präsentation aus. Endgültige Bewegung, Kollision,
Blockinteraktion, Inventare, Entityzustände, Portale und Weltpersistenz bleiben ausschließlich
Serverwahrheit.
